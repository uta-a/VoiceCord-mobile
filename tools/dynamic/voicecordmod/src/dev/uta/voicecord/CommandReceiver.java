package dev.uta.voicecord;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import de.robv.android.xposed.XposedBridge;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicInteger;

// フェーズ2: adb ブロードキャストから PLAY/STOP/SET/PING を受け、音声ファイルをデコードして
// 48kHz/int16/mono の PCM を native リングへ投入する。
//
// セキュリティ(フェーズ2は最小限): token 一致を必須にするのみ。
//   FileProvider/content:// URI 権限・送信元パッケージ署名検証・任意ファイル読み出しの
//   authority 制限は【フェーズ3のコンパニオンで実装】。現状は「トークンを知るローカルの
//   adb 実行者がローカルパスの音声を鳴らせる」だけの開発用。exported レシーバなので
//   本番ではトークンだけでは不十分(docs/FINDINGS.md にギャップとして記載)。
public class CommandReceiver extends BroadcastReceiver {

    public static final String ACTION_PLAY = "dev.uta.voicecord.PLAY";
    // 案B: sound_id から CDN 取得→キャッシュ→既存デコード経路で再生。任意パスではなく数字 ID のみ。
    public static final String ACTION_PLAY_SB = "dev.uta.voicecord.PLAY_SB";
    // フェーズ3拡張: コンパニオンが SAF で選んだ content:// を一時読み取り権限付きで受け再生。
    // Intent.getData() の URI を FLAG_GRANT_READ_URI_PERMISSION 付きで受け取り、権限が有効な
    // onReceive 内で MediaExtractor を開いてから(=fd 確保)デコードスレッドへ渡す。
    public static final String ACTION_PLAY_URI = "dev.uta.voicecord.PLAY_URI";
    public static final String ACTION_STOP = "dev.uta.voicecord.STOP";
    public static final String ACTION_SET = "dev.uta.voicecord.SET";
    public static final String ACTION_PING = "dev.uta.voicecord.PING";
    // フェーズ3: コンパニオンへ token を渡す PIN ペアリング。PAIR は token 不要のブートストラップ。
    // confirm は「順序付きブロードキャスト」で送られ、成功時に token を結果データで返す
    // (= 送信元コンパニオンにしか戻らない。exported レシーバ不要で token を世に出さない)。
    public static final String ACTION_PAIR = "dev.uta.voicecord.PAIR";

    // PIN ペアリングの状態管理(pending PIN の生成・検証・失効)。同一 classloader で 1 個。
    static final PairingManager PAIRING = new PairingManager();

    // 再生世代。PLAY/STOP のたびに +1 し、旧デコードスレッドは自分の世代と不一致になったら
    // 自発終了する。これで PLAY 連打/STOP 時の複数 producer 同時書き込み(SPSC 破綻)と
    // 満杯時の無限ループ張り付きを防ぐ。
    static final AtomicInteger GEN = new AtomicInteger(0);

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;

        // PAIR は token を渡すためのブートストラップなので token 検証の前に処理する
        // (PIN が実質の認証。PairingManager が生成・検証・失効を管理する)。
        if (ACTION_PAIR.equals(action)) {
            handlePair(context, intent);
            return;
        }

        // token 検証(PAIR 以外の全アクション必須)。
        String token = intent.getStringExtra("token");
        if (Entry.TOKEN == null || !Entry.TOKEN.equals(token)) {
            XposedBridge.log("[voicecord] token 不一致で拒否: action=" + action);
            return;
        }

        if (ACTION_STOP.equals(action)) {
            GEN.incrementAndGet();     // 走行中デコードスレッドを終了させる
            NativeBridge.nativeStop();
        } else if (ACTION_SET.equals(action)) {
            float gain = intent.getFloatExtra("gain", 1.0f);
            float duck = intent.getFloatExtra("duck", 1.0f);
            NativeBridge.nativeSetParams(gain, duck);
        } else if (ACTION_PING.equals(action)) {
            int state = NativeBridge.nativeState();
            XposedBridge.log("[voicecord] state=" + state);
            // コンパニオンが順序付きで PING したら state を結果データで返す(UI 表示用)。
            if (isOrderedBroadcast()) setResultData(String.valueOf(state));
        } else if (ACTION_PLAY.equals(action)) {
            String path = intent.getStringExtra("path");
            float gain = intent.getFloatExtra("gain", 1.0f);
            float duck = intent.getFloatExtra("duck", 1.0f);
            if (path == null) {
                XposedBridge.log("[voicecord] PLAY: path 無し");
                return;
            }
            NativeBridge.nativeSetParams(gain, duck);
            int myGen = GEN.incrementAndGet();  // 旧デコードスレッドを終了させる
            NativeBridge.nativeStop();          // 前の再生分をリングから捨てる
            Thread t = new Thread(new DecodeTask(path, myGen), "voicecord-decode");
            t.start();
        } else if (ACTION_PLAY_SB.equals(action)) {
            String soundId = intent.getStringExtra("sound_id");
            float gain = intent.getFloatExtra("gain", 1.0f);
            float duck = intent.getFloatExtra("duck", 1.0f);
            // receiver 側でも一次検証(fetcher と二重でよい。数字 ID 以外を早期拒否)。
            if (!SoundboardFetcher.isSoundId(soundId)) {
                XposedBridge.log("[voicecord] PLAY_SB: sound_id の形が不正");
                return;
            }
            NativeBridge.nativeSetParams(gain, duck);
            int myGen = GEN.incrementAndGet();  // 旧デコードスレッドを終了させる
            NativeBridge.nativeStop();          // 前の再生分をリングから捨てる
            // onReceive はメインスレッドなので CDN 取得(ブロッキング)は必ず別スレッドで行う。
            Thread t = new Thread(new FetchTask(context.getApplicationContext().getCacheDir(), soundId, myGen),
                    "voicecord-sb-fetch");
            t.start();
        } else if (ACTION_PLAY_URI.equals(action)) {
            // ファイル再生はコンパニオンが 127.0.0.1 に立てた待受から取得する
            // (SAF/ContentProvider は package visibility 等で Discord から読めない。docs/FINDINGS.md)。
            // uri は http://127.0.0.1:PORT/<token> 形式の文字列 extra。
            String uriStr = intent.getStringExtra("uri");
            float gain = intent.getFloatExtra("gain", 1.0f);
            float duck = intent.getFloatExtra("duck", 1.0f);
            if (!LocalFetcher.isLocalUrl(uriStr)) {
                XposedBridge.log("[voicecord] PLAY_URI: localhost URL でない(拒否)");
                return;
            }
            NativeBridge.nativeSetParams(gain, duck);
            int myGen = GEN.incrementAndGet();  // 旧デコードスレッドを終了させる
            NativeBridge.nativeStop();          // 前の再生分をリングから捨てる
            // onReceive はメインスレッドなので取得(ブロッキング)は必ず別スレッドで行う。
            Thread t = new Thread(
                    new LocalFetchTask(context.getApplicationContext().getCacheDir(), uriStr, myGen),
                    "voicecord-local-fetch");
            t.start();
        }
    }

    // localhost 待受から取得(ブロッキング)し、成功かつ世代一致なら DecodeTask を回す。
    // 匿名クラスは d8 8.2.2 でクラッシュするため名前付き Runnable にする。
    static final class LocalFetchTask implements Runnable {
        private final File cacheDir;
        private final String url;
        private final int gen;

        LocalFetchTask(File cacheDir, String url, int gen) {
            this.cacheDir = cacheDir;
            this.url = url;
            this.gen = gen;
        }

        @Override
        public void run() {
            File f = LocalFetcher.fetch(cacheDir, url);
            if (f == null) {
                XposedBridge.log("[voicecord] PLAY_URI: localhost 取得失敗");
                return;
            }
            if (GEN.get() != gen) {  // 取得中に STOP / 新 PLAY が来ていたら再生しない
                XposedBridge.log("[voicecord] PLAY_URI: gen 不一致で破棄");
                return;
            }
            new DecodeTask(f.getAbsolutePath(), gen).run();
        }
    }

    // PAIR(request/confirm) を処理する。token 不要のブートストラップ。
    //   request: reply_pkg 宛に PIN を発行し通知表示(PairingManager)。
    //   confirm: 順序付きブロードキャストで送られる。PIN 検証に成功したら結果データに token を
    //            セットして返す。結果は送信元コンパニオンにしか戻らないので、token を
    //            ワイルドカードなブロードキャストや exported レシーバに晒さずに渡せる。
    private void handlePair(Context context, Intent intent) {
        String step = intent.getStringExtra("step");
        String replyPkg = intent.getStringExtra("reply_pkg");
        if ("request".equals(step)) {
            PAIRING.onRequest(context.getApplicationContext(), replyPkg);
        } else if ("confirm".equals(step)) {
            String pin = intent.getStringExtra("pin");
            if (Entry.TOKEN == null) {
                XposedBridge.log("[voicecord] PAIR: token 未生成のため confirm 不可");
                return;
            }
            boolean ok = PAIRING.onConfirm(pin, replyPkg);
            // token は「順序付きブロードキャストの結果」でのみ返す(送信元にしか戻らない)。
            // 非順序で来た confirm は結果を返せないため成立させない(コンパニオンは必ず順序付きで送る)。
            if (ok && isOrderedBroadcast()) {
                setResultData(Entry.TOKEN);
                XposedBridge.log("[voicecord] PAIR: 結果データで token を返却");
            } else if (ok) {
                XposedBridge.log("[voicecord] PAIR: 非順序 confirm のため token を返せない(順序付きで送ること)");
            }
        } else {
            XposedBridge.log("[voicecord] PAIR: 不明な step=" + step);
        }
    }

    // sound_id を CDN 取得(ブロッキング)し、成功かつ世代一致なら DecodeTask を回す。
    // 匿名クラスは d8 8.2.2 でクラッシュするため名前付き Runnable にする。
    static final class FetchTask implements Runnable {
        private final File cacheDir;
        private final String soundId;
        private final int gen;  // 自分の再生世代。GEN と不一致になったら破棄する。

        FetchTask(File cacheDir, String soundId, int gen) {
            this.cacheDir = cacheDir;
            this.soundId = soundId;
            this.gen = gen;
        }

        @Override
        public void run() {
            File f = SoundboardFetcher.fetch(cacheDir, soundId);
            if (f == null) {
                XposedBridge.log("[voicecord] PLAY_SB: 取得失敗 id=" + soundId);
                return;
            }
            if (GEN.get() != gen) {  // 取得中に STOP / 新 PLAY が来ていたら再生しない
                XposedBridge.log("[voicecord] PLAY_SB: gen 不一致で破棄");
                return;
            }
            // 取得済みキャッシュを既存デコード経路へそのまま流す。
            new DecodeTask(f.getAbsolutePath(), gen).run();
        }
    }

    // 音声ファイルを 48kHz/int16/mono にデコードし、native リングへ背圧付きで投入する。
    // 匿名クラスは d8 8.2.2 でクラッシュするため名前付き Runnable にする。
    static final class DecodeTask implements Runnable {
        private final String path;
        private final int gen;  // 自分の再生世代。GEN と不一致になったら中断する。

        DecodeTask(String path, int gen) {
            this.path = path;
            this.gen = gen;
        }

        @Override
        public void run() {
            MediaExtractor ex = new MediaExtractor();
            MediaCodec codec = null;
            try {
                ex.setDataSource(path);
                int track = -1;
                String mime = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String m = f.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) {
                        track = i; mime = m; break;
                    }
                }
                if (track < 0) {
                    XposedBridge.log("[voicecord] audio track 無し: " + path);
                    return;
                }
                ex.selectTrack(track);
                MediaFormat fmt = ex.getTrackFormat(track);
                int srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                XposedBridge.log("[voicecord] decode start rate=" + srcRate + " ch=" + channels + " mime=" + mime);

                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(fmt, null, null, 0);
                codec.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean sawInputEOS = false;
                boolean sawOutputEOS = false;
                long total = 0;
                // リサンプル用の位相(前フレーム末尾を跨ぐ線形補間の残差)。
                double resamplePos = 0.0;
                short last = 0;
                boolean havePrev = false;

                while (!sawOutputEOS) {
                    if (GEN.get() != gen) {  // STOP / 新 PLAY で中断(finally で codec 解放)
                        XposedBridge.log("[voicecord] decode 中断(gen)");
                        return;
                    }
                    if (!sawInputEOS) {
                        int inIx = codec.dequeueInputBuffer(10000);
                        if (inIx >= 0) {
                            ByteBuffer ib = codec.getInputBuffer(inIx);
                            int sz = ex.readSampleData(ib, 0);
                            if (sz < 0) {
                                codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                sawInputEOS = true;
                            } else {
                                codec.queueInputBuffer(inIx, 0, sz, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int outIx = codec.dequeueOutputBuffer(info, 10000);
                    if (outIx >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true;
                        if (info.size > 0) {
                            ByteBuffer ob = codec.getOutputBuffer(outIx);
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ShortBuffer sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            int n = sb.remaining();
                            short[] pcm = new short[n];
                            sb.get(pcm);
                            // ステレオ→モノ
                            short[] mono;
                            if (channels >= 2) {
                                int frames = n / channels;
                                mono = new short[frames];
                                for (int i = 0; i < frames; i++) {
                                    int acc = 0;
                                    for (int c = 0; c < channels; c++) acc += pcm[i * channels + c];
                                    mono[i] = (short) (acc / channels);
                                }
                            } else {
                                mono = pcm;
                            }
                            // 48kHz へ線形リサンプル(srcRate==48000 ならそのまま)
                            short[] out;
                            if (srcRate == 48000) {
                                out = mono;
                            } else {
                                double ratio = 48000.0 / srcRate;
                                int outLen = (int) (mono.length * ratio);
                                out = new short[outLen];
                                for (int j = 0; j < outLen; j++) {
                                    double srcIdx = j / ratio;
                                    int i0 = (int) srcIdx;
                                    double frac = srcIdx - i0;
                                    short a = (i0 == 0 && havePrev) ? last : (i0 < mono.length ? mono[i0] : 0);
                                    short b = (i0 + 1 < mono.length) ? mono[i0 + 1] : a;
                                    out[j] = (short) (a + (b - a) * frac);
                                }
                                if (mono.length > 0) { last = mono[mono.length - 1]; havePrev = true; }
                            }
                            feedWithBackpressure(out);
                            total += out.length;
                        }
                        codec.releaseOutputBuffer(outIx, false);
                    }
                }
                XposedBridge.log("[voicecord] decode done total=" + total + " samples(48k)");
            } catch (Throwable e) {
                XposedBridge.log("[voicecord] decode 失敗:");
                XposedBridge.log(e);
            } finally {
                try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignore) {}
                try { ex.release(); } catch (Throwable ignore) {}
            }
        }

        // リングが満杯なら少し待って残りを投入する(送信が 48k/s で消費する)。
        private void feedWithBackpressure(short[] pcm) {
            int off = 0;
            while (off < pcm.length) {
                if (GEN.get() != gen) return;  // STOP/新PLAYで満杯待ちループを抜ける
                int len = pcm.length - off;
                short[] chunk;
                if (off == 0 && len == pcm.length) {
                    chunk = pcm;
                } else {
                    chunk = new short[len];
                    System.arraycopy(pcm, off, chunk, 0, len);
                }
                int w = NativeBridge.nativeWrite(chunk, len);
                off += w;
                if (w < len) {
                    try { Thread.sleep(20); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }
}
