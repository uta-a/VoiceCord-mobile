package dev.uta.gadgetloader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// フェーズ0専用: com.discord に frida-gadget を仕込むだけの最小 Xposed モジュール。
// Context/hook を使わず handleLoadPackage 内で完結させる(匿名クラスを避け d8 の不具合も回避)。
// libgadget.so / libgadget.config.so はモジュール APK の assets に同梱し、
// アプリの cache へ展開してから System.load する(設計書の .so 絶対パス方針)。
public class Entry implements IXposedHookLoadPackage {

    private static final String TARGET = "com.discord";
    private static final String MODULE = "dev.uta.gadgetloader";
    private static boolean loaded = false;

    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        if (loaded) return;
        loaded = true;
        try {
            String base = "/data/data/" + TARGET + "/cache";
            new File(base).mkdirs();

            String apkPath = findOwnApk();
            if (apkPath == null) {
                XposedBridge.log("[gadgetloader] モジュール APK パスを特定できない");
                return;
            }
            XposedBridge.log("[gadgetloader] module apk = " + apkPath);

            File so = new File(base, "libgadget.so");
            File cfg = new File(base, "libgadget.config.so");
            extract(apkPath, "assets/libgadget.so", so);
            extract(apkPath, "assets/libgadget.config.so", cfg);

            System.load(so.getAbsolutePath());
            XposedBridge.log("[gadgetloader] loaded " + so.getAbsolutePath());
        } catch (Throwable t) {
            XposedBridge.log("[gadgetloader] 失敗:");
            XposedBridge.log(t);
        }
    }

    // LSPatch はモジュール APK を cache/lspatch/<module>/ に展開する。そこを直接走査する。
    private String findOwnApk() {
        String[] roots = {
            "/data/user/0/" + TARGET + "/cache/lspatch/" + MODULE,
            "/data/data/" + TARGET + "/cache/lspatch/" + MODULE,
        };
        for (String r : roots) {
            File[] fs = new File(r).listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.getName().endsWith(".apk")) return f.getAbsolutePath();
            }
        }
        return null;
    }

    private void extract(String apkPath, String entryName, File out) throws Throwable {
        ZipFile zf = new ZipFile(apkPath);
        try {
            ZipEntry ze = zf.getEntry(entryName);
            if (ze == null) { XposedBridge.log("[gadgetloader] 見つからない: " + entryName); return; }
            InputStream is = zf.getInputStream(ze);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            is.close();
        } finally {
            zf.close();
        }
    }
}
