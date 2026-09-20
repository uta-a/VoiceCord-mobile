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

import java.io.File;
import java.io.FileOutputStream;

public class SoundboardFetcherTest {
    static int fail = 0;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) fail++;
    }

    // 指定バイト数の中身で vc_sb_<id>.mp3 を作り、lastModified を明示設定して返す。
    static File makeSb(File dir, String id, int bytes, long mtime) throws Exception {
        File f = new File(dir, "vc_sb_" + id + ".mp3");
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(new byte[bytes]);
        fos.close();
        f.setLastModified(mtime);
        return f;
    }

    static void deleteTree(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
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

        try {
            evictionTests();
        } catch (Exception e) {
            System.out.println("FAIL eviction test threw: " + e);
            fail++;
        }

        System.out.println(fail == 0 ? "ALL PASS" : (fail + " FAILED"));
        System.exit(fail == 0 ? 0 : 1);
    }

    // evictCache の host テスト: 上限超過分を一時ディレクトリで作り、上限内に収まり最古が消えること・
    // 最新と vc_sb_ 以外(vc_token)が残ることを検証する。
    static void evictionTests() throws Exception {
        // (1) 件数上限: MAX_CACHE_FILES + 4 件を古い順に作る。小さいので総バイトは超えない。
        File d1 = new File(System.getProperty("java.io.tmpdir"),
                "vc_sb_evict_files_" + System.nanoTime());
        d1.mkdirs();
        try {
            int over = SoundboardFetcher.MAX_CACHE_FILES + 4;
            File oldest = null, newest = null;
            for (int i = 0; i < over; i++) {
                // mtime を i で昇順に(i=0 が最古)。
                File f = makeSb(d1, String.valueOf(1000 + i), 16, 1_000_000L + i * 1000L);
                if (i == 0) oldest = f;
                if (i == over - 1) newest = f;
            }
            // vc_sb_ 以外(vc_token)は eviction 対象外であることの確認用。
            File token = new File(d1, "vc_token");
            FileOutputStream tf = new FileOutputStream(token);
            tf.write(new byte[8]);
            tf.close();

            SoundboardFetcher.evictCache(d1);

            int remain = 0;
            for (File f : d1.listFiles()) {
                if (f.getName().startsWith("vc_sb_")) remain++;
            }
            check(remain <= SoundboardFetcher.MAX_CACHE_FILES, "eviction: 件数が上限内に収まる");
            check(!oldest.exists(), "eviction: 最古の vc_sb_ が消える");
            check(newest.exists(), "eviction: 最新の vc_sb_ は残る");
            check(token.exists(), "eviction: vc_token(対象外)は残る");
        } finally {
            deleteTree(d1);
        }

        // (1b) 取得中/残骸の .tmp は最古であっても eviction 対象外(消すと renameTo が失敗するため)。
        File d3 = new File(System.getProperty("java.io.tmpdir"),
                "vc_sb_evict_tmp_" + System.nanoTime());
        d3.mkdirs();
        try {
            // 上限超の確定ファイル + 最古の .tmp を1つ。
            for (int i = 0; i < SoundboardFetcher.MAX_CACHE_FILES + 4; i++) {
                makeSb(d3, String.valueOf(3000 + i), 16, 3_000_000L + i * 1000L);
            }
            File tmp = new File(d3, "vc_sb_3999.mp3.7.tmp");
            FileOutputStream tt = new FileOutputStream(tmp);
            tt.write(new byte[16]);
            tt.close();
            tmp.setLastModified(1L);  // 最古に設定(対象なら真っ先に消される位置)

            SoundboardFetcher.evictCache(d3);

            check(tmp.exists(), "eviction: 最古でも .tmp は消さない");
        } finally {
            deleteTree(d3);
        }

        // (2) バイト上限: 1 ファイル約 8MB を上限(64MB)超過まで作る。
        File d2 = new File(System.getProperty("java.io.tmpdir"),
                "vc_sb_evict_bytes_" + System.nanoTime());
        d2.mkdirs();
        try {
            int each = 8 * 1024 * 1024;
            int n = (int) (SoundboardFetcher.MAX_CACHE_BYTES / each) + 3;  // 上限を確実に超える件数
            File oldest = null, newest = null;
            for (int i = 0; i < n; i++) {
                File f = makeSb(d2, String.valueOf(2000 + i), each, 2_000_000L + i * 1000L);
                if (i == 0) oldest = f;
                if (i == n - 1) newest = f;
            }
            SoundboardFetcher.evictCache(d2);

            long total = 0;
            for (File f : d2.listFiles()) {
                if (f.getName().startsWith("vc_sb_")) total += f.length();
            }
            check(total <= SoundboardFetcher.MAX_CACHE_BYTES, "eviction: 総バイトが上限内に収まる");
            check(!oldest.exists(), "eviction: バイト超過で最古が消える");
            check(newest.exists(), "eviction: バイト超過でも最新は残る");
        } finally {
            deleteTree(d2);
        }
    }
}
