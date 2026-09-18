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
// 送信ゲート: Discord は毎フレーム ctrl=*(Connection*+0x10) の ctrl[0](送信デッドライン)で
//   送信可否を決める。ctrl[0]=LLONG_MAX で VAD/Krisp を無視し常時送信、0 で通常 VAD。
//   Connection* は Connection::GetStats(約1Hz)の x0 で捕捉し、DeliverRecordedData(約100Hz)を
//   タイマにして再生中だけ ctrl[0] を再アサートする。旧方式(録音バッファへノイズ加算=keepAmp)は廃止。
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
#include "ring.h"
#include "shadowhook.h"

#define TAG "VoiceCord"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static const uintptr_t kAnchorRva = 0x9d39ec;  // nativeCreateBuiltinAudioEncoderFactory
static const char *kAnchorSym = "Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory";
static const uintptr_t kWebRtcOpusEncodeRva = 0x56b758;
// AudioDeviceBuffer::DeliverRecordedData(録音funnel, ~100Hz)。録音バッファ加算はやめ、
// これを 100Hz タイマとして使い、捕捉済み Connection* の ctrl[0] を再アサートする。
static const uintptr_t kDeliverRecordedRva = 0x891618;  // AudioDeviceBuffer::DeliverRecordedData(~100Hz)
static const uintptr_t kGetStatsRva = 0x392b18;  // Connection::GetStats(x0=Connection*, 約1Hz)

static std::atomic<bool> g_inject{true};        // リングのミックスを行うか
static std::atomic<int> g_calls{0};
static std::atomic<int> g_scalls{0};
static void *g_stub = nullptr;
static void *g_stub_rec = nullptr;
static void *g_stub_stats = nullptr;
static std::atomic<uintptr_t> g_conn{0};        // 捕捉した Connection*
static std::atomic<int> g_conn_ttl{0};          // 再アサート残フレーム(100Hz基準)。GetStatsで補充
static std::atomic<float> g_gain{1.0f};         // sfx(注入音)の音量
static std::atomic<float> g_duck{1.0f};         // mic(元音声)の音量

// SPSC リングバッファ(約10秒 @48kHz mono)。実装は ring.h。host 単体テストあり。
static Ring g_ring(480000);

// 差し替え用の書込可能バッファ。前提(フェーズ1と同じ): 送信オーディオスレッドは単一、
// 元 WebRtcOpus_Encode は pre 復帰後 g_buf を同期的に読み切って return する(Opus同期)。
// TODO(フェーズ4): 複数エンコードスレッド対策で thread_local 化 + 版ズレガード + uninstall。
static int16_t g_buf[8192];

// Connection::GetStats(0x392b18, 約1Hz)の pre。x0=Connection* を捕捉して TTL を補充する。
static void pre_getstats(shadowhook_cpu_context_t *ctx, void * /*data*/) {
  uintptr_t conn = (uintptr_t)ctx->regs[0];
  if (conn == 0) return;
  if (g_scalls.load(std::memory_order_relaxed) < 3) {
    LOG("GetStats call#%d conn=%p",
        g_scalls.fetch_add(1, std::memory_order_relaxed), (void *)conn);
  }
  g_conn.store(conn, std::memory_order_relaxed);
  // GetStats は約1Hz発火なので TTL=100(~1s @100Hz)でも接続中は枯れない。UAF窓を小さく保つ。
  g_conn_ttl.store(100, std::memory_order_relaxed);
}

// 録音funnel AudioDeviceBuffer::DeliverRecordedData(0x891618, ~100Hz)の pre 割り込み。
// 約100Hz。捕捉済み Connection* の送信ゲート(ctrl[0])を再生中だけ強制ONにし、
// 停止時は「自分が立てた LLONG_MAX のときだけ」0(=通常VAD)へ戻す。
// TTL: GetStats(約1Hz)が補充。VC切断で GetStats が止まると TTL が枯れ、
//      解放済みかもしれない Connection* への書き込みを止める(安全弁)。
//      ただし GetStats 停止と Connection 解放は非同期なので、TTL 幅(~1s)の
//      UAF 窓は残る(切断直後の数フレームで解放済みポインタに触れる可能性)。
//      根絶するには切断シグナルの捕捉が必要(フェーズ4)。
// 前提: このフック(録音funnel)は単一スレッドから呼ばれる(pre_encode と同様)。
//      よって g_conn_ttl の load→store(-1) の非アトミック RMW でも取りこぼしなし。
// 既知の制限: PTTモードの端末では復帰時に PTT保持中の LLONG_MAX と区別できない
//      (このデバイスはボイスアクティビティ運用前提)。
static void pre_recorded(shadowhook_cpu_context_t * /*ctx*/, void * /*data*/) {
  uintptr_t conn = g_conn.load(std::memory_order_relaxed);
  int ttl = g_conn_ttl.load(std::memory_order_relaxed);
  if (conn == 0 || ttl <= 0) return;
  g_conn_ttl.store(ttl - 1, std::memory_order_relaxed);
  long ctrl = *(long *)(conn + 0x10);
  if (ctrl == 0) return;
  volatile int64_t *deadline = (int64_t *)(ctrl + 0x0);
  bool playing = g_inject.load(std::memory_order_relaxed) && g_ring.hasData();
  if (playing) {
    *deadline = 0x7fffffffffffffffLL;         // LLONG_MAX: 常時送信
  } else if (*deadline == 0x7fffffffffffffffLL) {
    *deadline = 0;                            // 自分が立てた分だけ通常VADへ復帰
  }
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
  if (!g_ring.hasData()) return;  // 再生中でなければ素通し(x1 差し替えなし)
  memcpy(g_buf, audio_in, samples * sizeof(int16_t));  // audio_in は読み取り可
  g_ring.mix(g_buf, samples, g_gain.load(std::memory_order_relaxed),
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

        void *rtarget = (void *)(base + kDeliverRecordedRva);
        g_stub_rec = shadowhook_intercept_func_addr(rtarget, pre_recorded, nullptr,
                                                    SHADOWHOOK_INTERCEPT_DEFAULT);
        LOG("intercept DeliverRecordedData stub=%p errno=%d (%s)", g_stub_rec,
            shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));

        void *starget = (void *)(base + kGetStatsRva);
        g_stub_stats = shadowhook_intercept_func_addr(starget, pre_getstats, nullptr,
                                                      SHADOWHOOK_INTERCEPT_DEFAULT);
        LOG("intercept GetStats stub=%p errno=%d (%s)", g_stub_stats,
            shadowhook_get_errno(), shadowhook_to_errmsg(shadowhook_get_errno()));
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
  size_t w = g_ring.write((const int16_t *)p, (size_t)len);
  env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);  // 変更しないので破棄
  return (jint)w;
}

// 再生停止: 現在の tail までを drain 要求する(g_head は consumer 単独更新のため直接触らない)。
extern "C" JNIEXPORT void JNICALL
Java_dev_uta_voicecord_NativeBridge_nativeStop(JNIEnv *, jclass) {
  g_ring.requestStop();
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
  if (g_ring.hasData()) s |= 4;
  return s;
}
