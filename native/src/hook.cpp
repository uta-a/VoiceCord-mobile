// libvoicecord.so フェーズ2: 送信 Opus エンコーダ入口の PCM に、デコードした音声ファイルの
// PCM を混ぜて送信する。フェーズ1の固定サイン波→リングバッファ経由の実ファイル再生へ。
//
// 注入点(Krisp 非依存): libdiscord.so 内の WebRtcOpus_Encode（static/stripped, RVA でフック）。
//   int WebRtcOpus_Encode(void* inst, const int16_t* audio_in, size_t samples,
//                         size_t max_bytes, uint8_t* encoded);
//   x1=audio_in が送信 PCM(int16/mono/48kHz), x2=samples(10ms=480 / 20ms=960)。
//
// 手法: shadowhook_intercept_func_addr（pre 割り込み）で cpu_context の x1 を書き換える。
//   hook+CALL_PREV(トランポリンで元プロローグ実行)は signal 7(Bus error)になったため。
//   audio_in は read-only なので、コピー(g_buf)にミックスして x1 を差し替える。
//
// データ経路: Java(CommandReceiver+MediaCodec)→ nativeWrite → リングバッファ(SPSC)
//            → pre_encode(オーディオスレッド)で g_buf に加算 → x1 差し替え。
//
// 実行時 base: エクスポート JNI(RVA 0x9d39ec)の実アドレス − 0x9d39ec。
//   ※ RVA は Discord バージョン依存(345.9)。更新時は signatures/ で版別管理が必要。
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <pthread.h>
#include <unistd.h>
#include "shadowhook.h"

#define TAG "VoiceCord"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static const uintptr_t kAnchorRva = 0x9d39ec;  // nativeCreateBuiltinAudioEncoderFactory
static const char *kAnchorSym = "Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory";
static const uintptr_t kWebRtcOpusEncodeRva = 0x56b758;

static std::atomic<bool> g_inject{true};        // リングのミックスを行うか
static std::atomic<int> g_calls{0};
static void *g_stub = nullptr;
static std::atomic<float> g_gain{1.0f};         // sfx(注入音)の音量
static std::atomic<float> g_duck{1.0f};         // mic(元音声)の音量

// --- SPSC リングバッファ(int16, 48kHz mono, 約10秒) ---
// 書き手=デコードスレッド(nativeWrite)、読み手=オーディオスレッド(pre_encode)の 1:1。
// index は単調増加カウンタ。実体アクセスは % で折り返す。stop は第三スレッドから
// head を tail に合わせて捨てる(SPSC厳密ではないが index は常に範囲内でメモリ安全)。
static const size_t kRingCap = 480000;
static int16_t g_ring[kRingCap];
static std::atomic<uint64_t> g_head{0};    // 読み(consumer=オーディオスレッドのみが書く)
static std::atomic<uint64_t> g_tail{0};    // 書き(producer=デコードスレッド)
static std::atomic<uint64_t> g_drainTo{0}; // stop 要求。consumer が g_head を進める目標

// 差し替え用の書込可能バッファ。前提(フェーズ1と同じ): 送信オーディオスレッドは単一、
// 元 WebRtcOpus_Encode は pre 復帰後 g_buf を同期的に読み切って return する(Opus同期)。
// TODO(フェーズ4): 複数エンコードスレッド対策で thread_local 化 + 版ズレガード + uninstall。
static int16_t g_buf[8192];

static inline int16_t clamp16(int v) {
  if (v > 32767) return 32767;
  if (v < -32768) return -32768;
  return (int16_t)v;
}

// producer: リングへ書き込む。書けたサンプル数を返す。
static size_t ring_write(const int16_t *pcm, size_t n) {
  uint64_t head = g_head.load(std::memory_order_acquire);
  uint64_t tail = g_tail.load(std::memory_order_relaxed);
  size_t space = kRingCap - (size_t)(tail - head);
  if (n > space) n = space;
  for (size_t i = 0; i < n; i++) g_ring[(tail + i) % kRingCap] = pcm[i];
  g_tail.store(tail + n, std::memory_order_release);
  return n;
}

// consumer: リングから最大 n サンプル読み、dst に gain/duck で加算合成する。
// g_head を書くのはこの関数(オーディオスレッド)のみ。stop 要求は g_drainTo で受け取る。
static void ring_mix(int16_t *dst, size_t n, float gain, float duck) {
  uint64_t head = g_head.load(std::memory_order_relaxed);
  uint64_t tail = g_tail.load(std::memory_order_acquire);
  uint64_t drainTo = g_drainTo.load(std::memory_order_relaxed);
  if (drainTo > head) {  // stop 要求: 消費済み扱いにして捨てる(head は consumer 単独更新)
    head = (drainTo < tail) ? drainTo : tail;
    g_head.store(head, std::memory_order_release);
  }
  size_t avail = (size_t)(tail - head);
  size_t m = (n < avail) ? n : avail;
  for (size_t i = 0; i < m; i++) {
    int mic = (int)lround(dst[i] * duck);
    int sfx = (int)lround(g_ring[(head + i) % kRingCap] * gain);
    dst[i] = clamp16(mic + sfx);
  }
  // duck を掛けた残り(sfx が尽きた分)も duck 適用しておく(duck<1 のとき自然)。
  if (duck != 1.0f) {
    for (size_t i = m; i < n; i++) dst[i] = clamp16((int)lround(dst[i] * duck));
  }
  if (m > 0) g_head.store(head + m, std::memory_order_release);
}

static inline bool ring_has_data() {
  return g_tail.load(std::memory_order_acquire) != g_head.load(std::memory_order_acquire);
}

// WebRtcOpus_Encode 入口の pre 割り込み。x1=audio_in, x2=samples。
static void pre_encode(shadowhook_cpu_context_t *ctx, void * /*data*/) {
  const int16_t *audio_in = (const int16_t *)ctx->regs[1];
  size_t samples = (size_t)ctx->regs[2];
  if (g_calls.load(std::memory_order_relaxed) < 3) {
    LOG("WebRtcOpus_Encode call#%d samples=%zu",
        g_calls.fetch_add(1, std::memory_order_relaxed), samples);
  }
  if (!g_inject.load(std::memory_order_relaxed)) return;
  if (audio_in == nullptr || samples == 0 || samples > 8192) return;
  if (!ring_has_data()) return;  // 再生中でなければ素通し(x1 差し替えなし)
  memcpy(g_buf, audio_in, samples * sizeof(int16_t));  // audio_in は読み取り可
  ring_mix(g_buf, samples, g_gain.load(std::memory_order_relaxed),
           g_duck.load(std::memory_order_relaxed));
  ctx->regs[1] = (uint64_t)(uintptr_t)g_buf;  // x1 を書込可能バッファへ差し替え
}

// libdiscord.so のロードを待って base を解決し、WebRtcOpus_Encode を intercept する。
static void *install_thread(void *) {
  for (int tries = 0; tries < 120; tries++) {  // 最大 ~60s
    void *h = shadowhook_dlopen("libdiscord.so");
    if (h != nullptr) {
      void *anchor = shadowhook_dlsym(h, kAnchorSym);
      shadowhook_dlclose(h);
      if (anchor != nullptr) {
        uintptr_t base = (uintptr_t)anchor - kAnchorRva;
        void *target = (void *)(base + kWebRtcOpusEncodeRva);
        LOG("libdiscord base=%p target(WebRtcOpus_Encode)=%p", (void *)base, target);
        g_stub = shadowhook_intercept_func_addr(target, pre_encode, nullptr,
                                                SHADOWHOOK_INTERCEPT_DEFAULT);
        int e = shadowhook_get_errno();
        LOG("intercept WebRtcOpus_Encode stub=%p errno=%d (%s)", g_stub, e, shadowhook_to_errmsg(e));
        return nullptr;
      }
      LOG("anchor sym 未解決。リトライ");
    }
    usleep(500 * 1000);
  }
  LOG("libdiscord.so を解決できず(タイムアウト)");
  return nullptr;
}

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

// デコードスレッドから 48kHz/int16/mono の PCM を投入する。書けたサンプル数を返す。
extern "C" JNIEXPORT jint JNICALL
Java_dev_uta_voicecord_NativeBridge_nativeWrite(JNIEnv *env, jclass, jshortArray pcm, jint len) {
  if (pcm == nullptr || len <= 0) return 0;
  jsize alen = env->GetArrayLength(pcm);
  if (len > alen) len = alen;  // 申告 len が配列長を超えても範囲外読みしない
  jshort *p = env->GetShortArrayElements(pcm, nullptr);
  if (p == nullptr) return 0;
  size_t w = ring_write((const int16_t *)p, (size_t)len);
  env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);  // 変更しないので破棄
  return (jint)w;
}

// 再生停止: 現在の tail までを drain 要求する(g_head は consumer 単独更新のため直接触らない)。
extern "C" JNIEXPORT void JNICALL
Java_dev_uta_voicecord_NativeBridge_nativeStop(JNIEnv *, jclass) {
  g_drainTo.store(g_tail.load(std::memory_order_acquire), std::memory_order_relaxed);
  LOG("stop");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_uta_voicecord_NativeBridge_nativeSetParams(JNIEnv *, jclass, jfloat gain, jfloat duck) {
  g_gain.store(gain, std::memory_order_relaxed);
  g_duck.store(duck, std::memory_order_relaxed);
  LOG("params gain=%.2f duck=%.2f", gain, duck);
}

// bit0=hooked, bit2=playing(リングにデータあり)。
extern "C" JNIEXPORT jint JNICALL
Java_dev_uta_voicecord_NativeBridge_nativeState(JNIEnv *, jclass) {
  int s = 0;
  if (g_stub != nullptr) s |= 1;
  if (ring_has_data()) s |= 4;
  return s;
}
