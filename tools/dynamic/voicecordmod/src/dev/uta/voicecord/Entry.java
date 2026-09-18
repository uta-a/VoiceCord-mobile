package dev.uta.voicecord;

import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

import com.bytedance.shadowhook.ShadowHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.security.SecureRandom;

// フェーズ2: com.discord に libvoicecord.so を仕込み、ShadowHook で送信 Opus 入口に
// デコード済み PCM を注入する。CommandReceiver を動的登録し adb ブロードキャストで操作。
public class Entry implements IXposedHookLoadPackage {

    private static final String TARGET = "com.discord";
    private static boolean loaded = false;

    // 送信元検証用トークン(フェーズ2最小限)。同一プロセス/classloader の CommandReceiver が参照。
    public static volatile String TOKEN = null;

    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        if (loaded) return;
        loaded = true;
        try {
            ShadowHook.Config cfg = new ShadowHook.ConfigBuilder()
                    .setMode(ShadowHook.Mode.UNIQUE)
                    .setDebuggable(true)
                    .build();
            int initErr = ShadowHook.init(cfg);
            XposedBridge.log("[voicecord] ShadowHook.init=" + initErr
                    + " errno=" + ShadowHook.getInitErrno()
                    + " (" + ShadowHook.toErrmsg(ShadowHook.getInitErrno()) + ")");

            System.loadLibrary("voicecord");
            int r = NativeBridge.nativeInit();
            XposedBridge.log("[voicecord] nativeInit=" + r);

            TOKEN = String.format("%016x", new SecureRandom().nextLong());
            // token 全文は logcat に出さない(READ_LOGS/adb で漏洩するため)。先頭のみ。
            XposedBridge.log("[voicecord] token generated prefix=" + TOKEN.substring(0, 4) + "…");

            // Context 取得 → CommandReceiver 登録は Application 生成後に別スレッドで行う。
            Thread t = new Thread(new RegisterTask(), "voicecord-register");
            t.start();
        } catch (Throwable t) {
            XposedBridge.log("[voicecord] 失敗:");
            XposedBridge.log(t);
        }
    }

    // ActivityThread.currentApplication() を待って CommandReceiver を動的登録する。
    static final class RegisterTask implements Runnable {
        @Override
        public void run() {
            Context ctx = null;
            for (int i = 0; i < 100; i++) {  // 最大 ~30s
                ctx = currentApplication();
                if (ctx != null) break;
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
            if (ctx == null) {
                XposedBridge.log("[voicecord] Context 取得失敗、レシーバ未登録");
                return;
            }
            try {
                IntentFilter f = new IntentFilter();
                f.addAction(CommandReceiver.ACTION_PLAY);
                f.addAction(CommandReceiver.ACTION_PLAY_SB);
                f.addAction(CommandReceiver.ACTION_STOP);
                f.addAction(CommandReceiver.ACTION_SET);
                f.addAction(CommandReceiver.ACTION_PING);
                CommandReceiver rx = new CommandReceiver();
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
                } else {
                    ctx.registerReceiver(rx, f);
                }
                XposedBridge.log("[voicecord] CommandReceiver 登録完了");
                // token をファイルにも出力(テスト時の取得用)。
                // TODO(フェーズ3): これはテスト専用。コンパニオンとのペアリング(content:// call)に
                //   置き換え、この平文ファイル出力は削除する(唯一のアクセス制御の漏洩経路のため)。
                try {
                    File out = new File(ctx.getCacheDir(), "vc_token");
                    FileOutputStream fos = new FileOutputStream(out);
                    fos.write(TOKEN.getBytes());
                    fos.close();
                } catch (Throwable ignore) {}
            } catch (Throwable e) {
                XposedBridge.log("[voicecord] レシーバ登録失敗:");
                XposedBridge.log(e);
            }
        }

        private static Context currentApplication() {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Method m = at.getMethod("currentApplication");
                Object app = m.invoke(null);
                return (Context) app;
            } catch (Throwable e) {
                return null;
            }
        }
    }
}
