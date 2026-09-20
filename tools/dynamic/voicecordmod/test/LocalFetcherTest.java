// LocalFetcher.isLocalUrl(取得先を loopback+http に固定する関門)の host 単体テスト。実機不要。
// ファイル再生は 127.0.0.1/localhost の http だけに繋ぐ(端末外へ出さない)ことを厳密に確かめる。
//
// コンパイル & 実行(SoundboardFetcherTest と同じ要領):
//   JC="/c/Program Files/Java/jdk-21.0.12/bin/javac"
//   AJ="/c/Users/utaaa/Android/sdk/platforms/android-34/android.jar"
//   CP="$(cygpath -m -a "$AJ");$(cygpath -m -a tools/dynamic/voicecordmod/shadowhook-classes.jar)"
//   "$JC" --release 8 -cp "$CP" -d /tmp/lftest \
//     tools/dynamic/voicecordmod/src/de/robv/android/xposed/XposedBridge.java \
//     tools/dynamic/voicecordmod/src/dev/uta/voicecord/LocalFetcher.java \
//     tools/dynamic/voicecordmod/test/LocalFetcherTest.java
//   OUT="$(cygpath -m -a /tmp/lftest)"
//   "/c/Program Files/Java/jdk-21.0.12/bin/java" -cp "$OUT;$CP" LocalFetcherTest
import dev.uta.voicecord.LocalFetcher;

public class LocalFetcherTest {
    static int fail = 0;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) fail++;
    }

    public static void main(String[] args) {
        // 許可: 127.0.0.1 / localhost + http + ポート + パス。
        check(LocalFetcher.isLocalUrl("http://127.0.0.1:34193/abc123"), "127.0.0.1 ok");
        check(LocalFetcher.isLocalUrl("http://localhost:8080/token"), "localhost ok");
        check(LocalFetcher.isLocalUrl("http://127.0.0.1:1/x"), "最小ポート ok");

        // 拒否: https(生ソケット http 専用)。
        check(!LocalFetcher.isLocalUrl("https://127.0.0.1:443/x"), "https 拒否");
        // 拒否: loopback 以外のホスト(端末外/LAN へ出させない)。
        check(!LocalFetcher.isLocalUrl("http://192.168.1.5:8080/x"), "LAN IP 拒否");
        check(!LocalFetcher.isLocalUrl("http://evil.com:80/x"), "外部ホスト 拒否");
        check(!LocalFetcher.isLocalUrl("http://10.0.0.1:80/x"), "私設IP 拒否");
        // 拒否: ポート無し。
        check(!LocalFetcher.isLocalUrl("http://127.0.0.1/x"), "ポート無し 拒否");
        // 拒否: ポートが数字でない/桁数超。
        check(!LocalFetcher.isLocalUrl("http://127.0.0.1:abc/x"), "非数字ポート 拒否");
        check(!LocalFetcher.isLocalUrl("http://127.0.0.1:123456/x"), "6桁ポート 拒否");
        // 拒否: スキーム違い・null・紛らわしいホスト。
        check(!LocalFetcher.isLocalUrl("ftp://127.0.0.1:21/x"), "ftp 拒否");
        check(!LocalFetcher.isLocalUrl("http://127.0.0.1.evil.com:80/x"), "紛らわしいホスト 拒否");
        check(!LocalFetcher.isLocalUrl(null), "null 拒否");
        check(!LocalFetcher.isLocalUrl("http://127.0.0.1:80"), "パス無し 拒否");

        System.out.println(fail == 0 ? "ALL PASS" : (fail + " FAILED"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
