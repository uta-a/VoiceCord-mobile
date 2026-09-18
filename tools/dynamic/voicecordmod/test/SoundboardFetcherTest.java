// SoundboardFetcher.isSoundId(数字 ID の関門)の host 単体テスト。実機不要。
// URL とファイル名にそのまま入る値なので、数字以外・境界長を厳密に弾くことを確かめる。
//
// コンパイル & 実行(ring_test.cpp と同じく手動。Xposed スタブと android.jar を classpath に置く):
//   JC="/c/Program Files/Java/jdk-21.0.12/bin/javac"
//   AJ="/c/Users/utaaa/Android/sdk/platforms/android-34/android.jar"
//   CP="$(cygpath -m -a "$AJ");$(cygpath -m -a tools/dynamic/voicecordmod/shadowhook-classes.jar)"
//   "$JC" --release 8 -cp "$CP" -d /tmp/sbtest \
//     tools/dynamic/voicecordmod/src/de/robv/android/xposed/XposedBridge.java \
//     tools/dynamic/voicecordmod/src/dev/uta/voicecord/SoundboardFetcher.java \
//     tools/dynamic/voicecordmod/test/SoundboardFetcherTest.java
//   "/c/Program Files/Java/jdk-21.0.12/bin/java" -cp "/tmp/sbtest;$CP" SoundboardFetcherTest
import dev.uta.voicecord.SoundboardFetcher;

public class SoundboardFetcherTest {
    static int fail = 0;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) fail++;
    }

    public static void main(String[] args) {
        // 正常: 1..20 桁の数字。
        check(SoundboardFetcher.isSoundId("1"), "1 digit ok");
        check(SoundboardFetcher.isSoundId("1234567890123456789"), "19 digits ok");
        check(SoundboardFetcher.isSoundId("12345678901234567890"), "20 digits ok");

        // 拒否: 21 桁(上限超)。
        check(!SoundboardFetcher.isSoundId("123456789012345678901"), "21 digits rejected");
        // 拒否: 空・null。
        check(!SoundboardFetcher.isSoundId(""), "empty rejected");
        check(!SoundboardFetcher.isSoundId(null), "null rejected");
        // 拒否: 数字以外(パストラバーサル/URL 差し替えの芽を摘む)。
        check(!SoundboardFetcher.isSoundId("../123"), "path traversal rejected");
        check(!SoundboardFetcher.isSoundId("123/456"), "slash rejected");
        check(!SoundboardFetcher.isSoundId("12 3"), "space rejected");
        check(!SoundboardFetcher.isSoundId("12a3"), "letter rejected");
        check(!SoundboardFetcher.isSoundId("123\n456"), "newline rejected");
        check(!SoundboardFetcher.isSoundId("+123"), "sign rejected");
        check(!SoundboardFetcher.isSoundId("１２３"), "fullwidth digits rejected");

        System.out.println(fail == 0 ? "ALL PASS" : (fail + " FAILED"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
