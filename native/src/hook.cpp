// libvoicecord.so フェーズ1: 送信 Opus エンコーダ入口の PCM に固定テスト音を注入する。
//
// 注入点(Krisp 非依存): libdiscord.so 内の WebRtcOpus_Encode（static/stripped, RVA でフック）。
//   int WebRtcOpus_Encode(void* inst, const int16_t* audio_in, size_t samples,
//                         size_t max_bytes, uint8_t* encoded);
//   x1=audio_in が送信 PCM(int16/mono/48kHz), x2=samples(10ms=480 / 20ms=960)。
//   CALL_PREV の前に audio_in へサイン波を加算すれば、エンコード結果に混ざって送信される。
//   Krisp の有無・Play アセット配信に依存しないため再パック(LSPatch)版でも発火する。
//
// 実行時 base の算出: libdiscord.so のエクスポート JNI シンボル
//   Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory
//   (dynsym RVA 0x9d39ec) の実行時アドレスから base を逆算し、base + 0x56b758 をフックする。
//   ※ RVA は Discord バージョン依存(345.9)。更新時は signatures/ で版別管理が必要。
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <pthread.h>
#include <unistd.h>
#include "shadowhook.h"

#define TAG "VoiceCord"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Discord 345.9 arm64 libdiscord.so の RVA(Ghidra 導出)。
static const uintptr_t kAnchorRva = 0x9d39ec;      // nativeCreateBuiltinAudioEncoderFactory
static const char *kAnchorSym = "Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory";
static const uintptr_t kWebRtcOpusEncodeRva = 0x56b758;

typedef int (*webrtc_opus_encode_t)(void *, const int16_t *, size_t, size_t, uint8_t *);

static const double kFreq = 440.0, kSr = 48000.0, kAmp = 0.25;
static const double kTwoPi = 6.283185307179586;

static std::atomic<bool> g_inject{true};
static std::atomic<int> g_calls{0};
static void *g_orig_encode = nullptr;
static void *g_stub = nullptr;

// audio_in(送信 PCM)にサイン波を加算する。位相は連続させる。
static void mix_sine(int16_t *buf, size_t n) {
  static double phase = 0.0;
  if (buf == nullptr || n == 0 || n > 8192) return;
  double p = phase;
  for (size_t i = 0; i < n; i++) {
    double s = kAmp * sin(p);
    p += kTwoPi * kFreq / kSr;
    if (p > kTwoPi) p -= kTwoPi;
    int v = (int)buf[i] + (int)lround(s * 32767.0);
    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
    buf[i] = (int16_t)v;
  }
  phase = p;
}

static int proxy_encode(void *inst, const int16_t *audio_in, size_t samples,
                        size_t max_bytes, uint8_t *encoded) {
  if (g_calls.load(std::memory_order_relaxed) < 3) {
    LOG("WebRtcOpus_Encode call#%d samples=%zu audio_in=%p",
        g_calls.fetch_add(1, std::memory_order_relaxed), samples, audio_in);
  }
  // audio_in は const だが実体は書き換え可能な送信バッファ。エンコード前に加算する。
  if (g_inject.load(std::memory_order_relaxed) && audio_in != nullptr && samples > 0 && samples <= 8192) {
    mix_sine(const_cast<int16_t *>(audio_in), samples);
  }
  return SHADOWHOOK_CALL_PREV(proxy_encode, inst, audio_in, samples, max_bytes, encoded);
}

// libdiscord.so のロードを待って base を解決し、WebRtcOpus_Encode をフックする。
static void *install_thread(void *) {
  for (int tries = 0; tries < 120; tries++) {  // 最大 ~60s
    void *h = shadowhook_dlopen("libdiscord.so");
    if (h != nullptr) {
      void *anchor = shadowhook_dlsym(h, kAnchorSym);
      shadowhook_dlclose(h);
      if (anchor != nullptr) {
        uintptr_t base = (uintptr_t)anchor - kAnchorRva;
        void *target = (void *)(base + kWebRtcOpusEncodeRva);
        LOG("libdiscord base=%p anchor=%p target(WebRtcOpus_Encode)=%p", (void *)base, anchor, target);
        g_stub = shadowhook_hook_func_addr(target, (void *)proxy_encode, &g_orig_encode);
        int e = shadowhook_get_errno();
        LOG("hook WebRtcOpus_Encode stub=%p errno=%d (%s)", g_stub, e, shadowhook_to_errmsg(e));
        return nullptr;
      }
      LOG("anchor sym 未解決。リトライ");
    }
    usleep(500 * 1000);
  }
  LOG("libdiscord.so を解決できず(タイムアウト)");
  return nullptr;
}

// ShadowHook の init は Java 側(ShadowHook.init)で済ませる前提。
extern "C" JNIEXPORT jint JNICALL
Java_dev_uta_voicecord_NativeBridge_nativeInit(JNIEnv *, jclass) {
  LOG("nativeInit: shadowhook version=%s", shadowhook_get_version());
  pthread_t t;
  if (pthread_create(&t, nullptr, install_thread, nullptr) != 0) {
    LOG("pthread_create 失敗");
    return -1;
  }
  pthread_detach(t);
  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_uta_voicecord_NativeBridge_nativeSetInject(JNIEnv *, jclass, jboolean on) {
  g_inject.store(on, std::memory_order_relaxed);
  LOG("inject=%d", (int)on);
}
