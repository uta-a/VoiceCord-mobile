// PairingManager(PIN ペアリングの状態機械)の host 単体テスト。実機不要。
// 認可の要である「PIN 一致・reply_pkg 束縛・試行回数失効・使い切り・スロットル」を検証する。
// 時刻依存(60 秒失効)は host では待たずに済むケースのみを対象にする(失効は実機/コメントで担保)。
//
// コンパイル & 実行(SoundboardFetcherTest と同じ要領。Xposed スタブと android.jar を classpath に):
//   JC="/c/Program Files/Java/jdk-21.0.12/bin/javac"
//   AJ="/c/Users/utaaa/Android/sdk/platforms/android-34/android.jar"
//   CP="$(cygpath -m -a "$AJ");$(cygpath -m -a tools/dynamic/voicecordmod/shadowhook-classes.jar)"
//   "$JC" --release 8 -cp "$CP" -d /tmp/pmtest \
//     tools/dynamic/voicecordmod/src/de/robv/android/xposed/XposedBridge.java \
//     tools/dynamic/voicecordmod/src/dev/uta/voicecord/PairingManager.java \
//     tools/dynamic/voicecordmod/test/PairingManagerTest.java
//   OUT="$(cygpath -m -a /tmp/pmtest)"
//   "/c/Program Files/Java/jdk-21.0.12/bin/java" -cp "$OUT;$CP" PairingManagerTest
import dev.uta.voicecord.PairingManager;

public class PairingManagerTest {
    static int fail = 0;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) fail++;
    }

    static final String PKG_A = "dev.uta.voicecordcompanion";
    static final String PKG_B = "com.evil.other";

    // 発行された PIN と必ず異なる 6 桁を返す(誤 PIN テスト用)。
    static String wrongOf(String pin) {
        return "000000".equals(pin) ? "111111" : "000000";
    }

    public static void main(String[] args) {
        // 発行: 正常な pkg は 6 桁 PIN を返す。
        PairingManager m1 = new PairingManager();
        String pin1 = m1.createPending(PKG_A);
        check(pin1 != null && pin1.length() == 6 && pin1.matches("\\d{6}"), "createPending: 6桁PIN発行");

        // 発行拒否: null/空 pkg。
        check(new PairingManager().createPending(null) == null, "createPending: null pkg 拒否");
        check(new PairingManager().createPending("") == null, "createPending: 空 pkg 拒否");

        // 正常 confirm: 一致で true。
        check(m1.onConfirm(pin1, PKG_A), "confirm: 正PIN+正pkg で成功");
        // 使い切り: 成功後は pending 消費済みで再度 true にならない。
        check(!m1.onConfirm(pin1, PKG_A), "confirm: 成功後は使い切りで再成功しない");

        // reply_pkg 束縛: 別 pkg からの confirm は拒否し、pending は消費しない(その後 正pkg で成功)。
        PairingManager m2 = new PairingManager();
        String pin2 = m2.createPending(PKG_A);
        check(!m2.onConfirm(pin2, PKG_B), "confirm: 別pkg は拒否");
        check(m2.onConfirm(pin2, PKG_A), "confirm: 別pkg 拒否では pending を消費しない(正pkgで成功)");

        // 誤 PIN → 正 PIN(試行回数内): 誤りで false、続けて正で true。
        PairingManager m3 = new PairingManager();
        String pin3 = m3.createPending(PKG_A);
        check(!m3.onConfirm(wrongOf(pin3), PKG_A), "confirm: 誤PIN は false");
        check(m3.onConfirm(pin3, PKG_A), "confirm: 誤PIN後も試行内なら正PINで成功");

        // 試行回数失効: 誤 PIN を MAX_ATTEMPTS 回で pending 失効し、以後 正PINでも成功しない。
        PairingManager m4 = new PairingManager();
        String pin4 = m4.createPending(PKG_A);
        String bad = wrongOf(pin4);
        for (int i = 0; i < PairingManager.MAX_ATTEMPTS; i++) {
            m4.onConfirm(bad, PKG_A);
        }
        check(!m4.onConfirm(pin4, PKG_A), "confirm: 誤り MAX_ATTEMPTS 回で失効し正PINでも失敗");

        // pending 無しでの confirm は false。
        check(!new PairingManager().onConfirm("123456", PKG_A), "confirm: pending 無しは false");

        // スロットル: 同一インスタンスで即時 2 回目の createPending は発行しない(通知スパム抑止)。
        PairingManager m5 = new PairingManager();
        check(m5.createPending(PKG_A) != null, "throttle: 1回目は発行");
        check(m5.createPending(PKG_A) == null, "throttle: 直後の2回目は抑止");

        System.out.println(fail == 0 ? "ALL PASS" : (fail + " FAILED"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
