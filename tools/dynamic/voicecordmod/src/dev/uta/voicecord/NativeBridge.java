package dev.uta.voicecord;

// libvoicecord.so への JNI ブリッジ。実体は native/src/hook.cpp。
public class NativeBridge {
    public static native int nativeInit();          // 0=OK, 負値=失敗
    public static native void nativeSetInject(boolean on);
}
