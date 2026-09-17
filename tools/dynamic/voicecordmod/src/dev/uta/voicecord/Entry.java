package dev.uta.voicecord;

import com.bytedance.shadowhook.ShadowHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

// フェーズ1: com.discord に libvoicecord.so を仕込み、ShadowHook で post-Krisp 出力に
// 固定テスト音を注入する最小 Xposed モジュール。
// .so はモジュール APK の lib/arm64-v8a に非圧縮同梱し、LSPatch が設定する
// nativeLibraryDir 経由で System.loadLibrary / dlopen(by name) で解決させる。
// (ShadowHook は libshadowhook_nothing.so を dlopen で必要とするため lib/ 同梱が必須)
public class Entry implements IXposedHookLoadPackage {

    private static final String TARGET = "com.discord";
    private static boolean loaded = false;

    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        if (loaded) return;
        loaded = true;
        try {
            // ShadowHook 初期化(default loader が System.loadLibrary("shadowhook"))。
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
        } catch (Throwable t) {
            XposedBridge.log("[voicecord] 失敗:");
            XposedBridge.log(t);
        }
    }
}
