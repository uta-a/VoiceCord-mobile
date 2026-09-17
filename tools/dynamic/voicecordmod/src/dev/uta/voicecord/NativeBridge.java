package dev.uta.voicecord;

// libvoicecord.so への JNI ブリッジ。実体は native/src/hook.cpp。
public class NativeBridge {
    public static native int nativeInit();               // 0=OK, 負値=失敗
    public static native void nativeSetInject(boolean on);

    // フェーズ2: 48kHz/int16/mono の PCM をリングへ投入(書けたサンプル数)。
    public static native int nativeWrite(short[] pcm, int len);
    public static native void nativeStop();              // リングを空にする
    public static native void nativeSetParams(float gain, float duck);
    public static native int nativeState();              // bit0=hooked, bit2=playing
}
