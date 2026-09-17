// libvoicecord.so フェーズ1: post-Krisp 出力(x3)に固定テスト音(サイン波)を注入する。
// フック対象: libkrisp_wrapper.so の NC clean 系(int16)。ShadowHook で
// 未ロード lib への予約フックを使い、VC 参加で krisp がロードされた瞬間に有効化する。
//
// Krisp SDK signature:
//   int krispAudioNcCleanAmbientNoiseInt16(session, const int16* in, uint cnt,
//                                          int16* out, uint outcnt);
//   out(x3) が post-Krisp 出力。cnt(x2) が有効サンプル数(48k/10ms=480)。
//
// proxy はオーディオスレッド上でフレーム毎(~100Hz)に呼ばれるホットパス。
// clean / clean_ws は別関数だが両方フックするため、共有状態は atomic 化し
// 位相は proxy ごとに分離する(実際に呼ばれるのは通常どちらか一方)。
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include "shadowhook.h"

#define TAG "VoiceCord"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef int (*krisp_clean_int16_t)(void *, const int16_t *, uint32_t, int16_t *, uint32_t);

static const double kFreq = 440.0, kSr = 48000.0, kAmp = 0.25;
static const double kTwoPi = 6.283185307179586;

static std::atomic<bool> g_inject{true};
static std::atomic<int> g_calls{0};

static void *g_orig_clean = nullptr;
static void *g_orig_clean_ws = nullptr;

// out バッファ(post-Krisp)にサイン波を加算する。位相は呼び出し元(proxy)が保持する。
static void mix_sine(int16_t *out, uint32_t n, double *phase) {
  if (out == nullptr || n == 0 || n > 8192) return;
  double p = *phase;
  for (uint32_t i = 0; i < n; i++) {
    double s = kAmp * sin(p);
    p += kTwoPi * kFreq / kSr;
    if (p > kTwoPi) p -= kTwoPi;
    int v = (int)out[i] + (int)lround(s * 32767.0);
    if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
    out[i] = (int16_t)v;
  }
  *phase = p;
}

static int proxy_clean(void *session, const int16_t *in, uint32_t cnt, int16_t *out, uint32_t outcnt) {
  static double phase = 0.0;  // このスレッド/関数専用の位相
  int ret = SHADOWHOOK_CALL_PREV(proxy_clean, session, in, cnt, out, outcnt);
  if (g_calls.load(std::memory_order_relaxed) < 3) {
    LOG("clean call#%d cnt=%u outcnt=%u out=%p", g_calls.fetch_add(1, std::memory_order_relaxed), cnt, outcnt, out);
  }
  if (g_inject.load(std::memory_order_relaxed) && cnt > 0 && cnt <= outcnt) mix_sine(out, cnt, &phase);
  return ret;
}

static int proxy_clean_ws(void *session, const int16_t *in, uint32_t cnt, int16_t *out, uint32_t outcnt) {
  static double phase = 0.0;
  int ret = SHADOWHOOK_CALL_PREV(proxy_clean_ws, session, in, cnt, out, outcnt);
  if (g_calls.load(std::memory_order_relaxed) < 3) {
    LOG("clean_ws call#%d cnt=%u outcnt=%u out=%p", g_calls.fetch_add(1, std::memory_order_relaxed), cnt, outcnt, out);
  }
  if (g_inject.load(std::memory_order_relaxed) && cnt > 0 && cnt <= outcnt) mix_sine(out, cnt, &phase);
  return ret;
}

// 予約フックを設置する。成功(stub 非 null)なら true。
// 未ロードでも PENDING(errno=1)で stub が返るため、それも成功として扱う。
static bool hook_one(const char *name, void *proxy, void **orig) {
  void *stub = shadowhook_hook_sym_name("libkrisp_wrapper.so", name, proxy, orig);
  int e = shadowhook_get_errno();
  LOG("hook %s stub=%p errno=%d (%s)", name, stub, e, shadowhook_to_errmsg(e));
  return stub != nullptr;
}

// ShadowHook の init は Java 側(ShadowHook.init)で済ませる前提。ここではフックのみ設置。
// 戻り値: 0=OK, 負値=フック失敗コード(NativeBridge の契約に合わせる)。
extern "C" JNIEXPORT jint JNICALL
Java_dev_uta_voicecord_NativeBridge_nativeInit(JNIEnv *, jclass) {
  LOG("nativeInit: shadowhook version=%s (init は Java 側)", shadowhook_get_version());
  bool ok1 = hook_one("krispAudioNcCleanAmbientNoiseInt16", (void *)proxy_clean, &g_orig_clean);
  bool ok2 = hook_one("krispAudioNcWithStatsCleanAmbientNoiseInt16", (void *)proxy_clean_ws, &g_orig_clean_ws);
  if (!ok1 && !ok2) return -1;  // どちらも設置できなければ失敗
  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_uta_voicecord_NativeBridge_nativeSetInject(JNIEnv *, jclass, jboolean on) {
  g_inject.store(on, std::memory_order_relaxed);
  LOG("inject=%d", (int)on);
}
