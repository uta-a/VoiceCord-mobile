package dev.uta.voicecord;

import de.robv.android.xposed.XposedBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

// フェーズ2(案B): Discord サウンドボード音源を sound_id から端末内で CDN 取得し、
// キャッシュへ保存して既存デコード経路(DecodeTask)へ渡すための取得器。
//
// 外(CDN)から取ったものを書き、それを DecodeTask に読ませる経路なので、関門を固定する
// (移植元 VoiceCord-module/src/patcher/soundboardCache.ts の関門を Java へ移植):
//   - 取りに行くのは cdn.discordapp.com の決まったパスだけ。ID は数字だけ(1..20 桁)。
//     ID は URL とファイル名にそのまま入るので、数字以外を一切通さない(パストラバーサル不可)。
//   - リダイレクトは追わない(行き先を CDN の外へ変えられない)。3xx はエラー扱い。
//   - 200 以外は失敗。
//   - 形式は Content-Type で判別。audio/mpeg→.mp3 / audio/ogg→.ogg。それ以外(text/html 等)は失敗。
//   - 大きさは 8MB まで。Content-Length があれば事前チェックし、実受信バイトでも累積チェックして超過で中断・破棄。
//   - 空/途中切れは保存しない。別名(.tmp)で書き切ってから renameTo で原子的に確定する。
//
// このクラス自体はブロッキングでよい(呼び出し側がワーカースレッドで呼ぶ前提)。
// 例外は握って null 返却し、XposedBridge.log に残す。
//
// セキュリティ位置づけ: 本取得器は「CDN 固定ホスト+数字 ID 限定+リダイレクト非追従+
// Content-Type/サイズ検証+原子的 rename+パストラバーサル不可」で、既存 ACTION_PLAY の
// 任意ローカルパス読み出しギャップ(docs/FINDINGS.md)を新たに悪化させない(むしろ制約が強い)。
// exported レシーバ+token のみという既存の認可の弱さは本変更のスコープ外(フェーズ3で対応)。
public final class SoundboardFetcher {

    private SoundboardFetcher() {}

    // ID は URL とファイル名にそのまま入るので、数字以外は一切通さない(1..20 桁)。
    static final Pattern SOUND_ID_RE = Pattern.compile("^\\d{1,20}$");

    private static final String CDN_HOST = "cdn.discordapp.com";
    private static final int MAX_BYTES = 8 * 1024 * 1024;   // 8MB 上限
    private static final int TIMEOUT_MS = 10_000;           // connect/read 各 10 秒
    // キャッシュ eviction 上限。取得成功のたびに vc_sb_* を古い順で上限内へ収める。
    // (host テストが default パッケージから参照するため public。)
    public static final int MAX_CACHE_FILES = 32;                  // vc_sb_* の最大件数
    public static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;  // vc_sb_* の総バイト上限(64MB)
    // 同一 sound_id を並行取得しても .tmp が衝突しないよう呼び出しごとに一意な連番を付ける。
    private static final AtomicInteger TMP_SEQ = new AtomicInteger();

    /** 数字 ID の形かどうか(URL/ファイル名に入るので数字以外を一切通さない)。 */
    public static boolean isSoundId(String id) {
        return id != null && SOUND_ID_RE.matcher(id).matches();
    }

    /**
     * sound_id を CDN から取得し、cacheDir 直下に vc_sb_<id>.<ext> として保存して File を返す。
     * 失敗時は null。ブロッキングするのでワーカースレッドから呼ぶこと。
     */
    public static File fetch(File cacheDir, String id) {
        if (cacheDir == null) {
            XposedBridge.log("[voicecord] SB: cacheDir 無し");
            return null;
        }
        // ID 検証(数字 1..20 桁のみ)。以降 URL・ファイル名に使う値はこの検証済み ID だけ。
        if (!isSoundId(id)) {
            XposedBridge.log("[voicecord] SB: sound_id の形が不正");
            return null;
        }

        HttpsURLConnection conn = null;
        InputStream in = null;
        File tmp = null;
        try {
            // ホスト・スキーム・パスを固定生成(ユーザ入力で URL を組ませない)。
            URL url = new URL("https://" + CDN_HOST + "/soundboard-sounds/" + id);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);  // 3xx は追わない(CDN 外へ飛ばさない)
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.connect();

            int status = conn.getResponseCode();
            if (status != 200) {
                // 3xx(リダイレクト)も 200 以外としてまとめて失敗扱い。
                XposedBridge.log("[voicecord] SB: HTTP " + status + " で失敗");
                return null;
            }

            // Content-Type で形式判別。それ以外(text/html 等)は保存しない。
            String ext = extForContentType(conn.getContentType());
            if (ext == null) {
                XposedBridge.log("[voicecord] SB: 音声ではない Content-Type=" + conn.getContentType());
                return null;
            }

            // Content-Length があれば事前チェック(偽れるので後段の実受信チェックも行う)。
            long declared = conn.getContentLengthLong();
            if (declared > MAX_BYTES) {
                XposedBridge.log("[voicecord] SB: 大きすぎ(宣言 " + declared + " バイト)");
                return null;
            }

            // 書きかけを読ませないよう .tmp に書き切ってから rename で確定する。
            // final 名は id 単位(同一 id は上書き)だが、.tmp は連番付きで並行取得の衝突を避ける。
            File finalFile = new File(cacheDir, "vc_sb_" + id + "." + ext);
            tmp = new File(cacheDir, "vc_sb_" + id + "." + ext + "." + TMP_SEQ.incrementAndGet() + ".tmp");

            in = conn.getInputStream();
            long received = 0;
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    received += n;
                    // Content-Length は偽れるので、受信した実量でも打ち切る。
                    if (received > MAX_BYTES) {
                        XposedBridge.log("[voicecord] SB: 大きすぎ(受信 " + received + " バイトで上限 " + MAX_BYTES + " 超)");
                        fos.close();
                        deleteQuietly(tmp);
                        return null;
                    }
                    fos.write(buf, 0, n);
                }
                fos.flush();
            } finally {
                try { fos.close(); } catch (Throwable ignore) {}
            }

            // 空/途中切れ(0 バイト等)は保存しない。
            if (received == 0) {
                XposedBridge.log("[voicecord] SB: 空の応答");
                deleteQuietly(tmp);
                return null;
            }
            // 接続が途中で切れても read は正常終了しうる。欠けた音声をキャッシュに残さない。
            if (declared >= 0 && declared != received) {
                XposedBridge.log("[voicecord] SB: 受信が途中で切れた(" + received + "/" + declared + " バイト)");
                deleteQuietly(tmp);
                return null;
            }

            // 原子的確定。失敗したら残骸を消して null。
            deleteQuietly(finalFile);  // 既存の古いキャッシュがあれば置き換える
            if (!tmp.renameTo(finalFile)) {
                XposedBridge.log("[voicecord] SB: rename 失敗");
                deleteQuietly(tmp);
                return null;
            }
            XposedBridge.log("[voicecord] SB: 取得完了 id=" + id + " ext=" + ext + " bytes=" + received);
            evictCache(cacheDir);  // 上限超過分の古いキャッシュを整理(finalFile は最新なので残る)
            return finalFile;
        } catch (Throwable e) {
            XposedBridge.log("[voicecord] SB: 取得失敗:");
            XposedBridge.log(e);
            if (tmp != null) deleteQuietly(tmp);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignore) {}
        }
    }

    // Content-Type(パラメータ付きうる, 例 "audio/mpeg; charset=...")から拡張子を判別。
    private static String extForContentType(String contentType) {
        if (contentType == null) return null;
        String type = contentType;
        int semi = type.indexOf(';');
        if (semi >= 0) type = type.substring(0, semi);
        type = type.trim().toLowerCase();
        if ("audio/mpeg".equals(type)) return "mp3";
        if ("audio/ogg".equals(type)) return "ogg";
        return null;
    }

    /**
     * cacheDir 内の vc_sb_* を列挙し、件数上限(MAX_CACHE_FILES)または総バイト上限
     * (MAX_CACHE_BYTES)を超えていたら lastModified 昇順(古い順=LRU 近似)に削除して
     * 上限内へ収める。vc_sb_ で始まるファイルのみ対象(vc_token 等 他は触らない)。
     * 直前に確定した finalFile は最新のため最後まで残る。例外は握って無視(eviction 失敗は致命的でない)。
     * (host テストが default パッケージから参照するため public。)
     */
    public static void evictCache(File cacheDir) {
        try {
            File[] files = cacheDir.listFiles();
            if (files == null) return;
            // vc_sb_ で始まる確定ファイルのみ対象(vc_token 等は触らない)。
            // 取得中/残骸の .tmp は除外(並行取得中の .tmp を消すと renameTo が失敗するため)。
            // Comparator/List.sort は d8 8.2.2 でクラッシュするため、File[] + 手動の最古選択で回す
            // (対象は上限+数件と小さいので単純な線形選択で十分)。
            File[] sb = new File[files.length];
            int count = 0;
            long total = 0;
            for (File f : files) {
                if (f.isFile() && f.getName().startsWith("vc_sb_") && !f.getName().endsWith(".tmp")) {
                    sb[count++] = f;
                    total += f.length();
                }
            }
            // 上限超過の間、最古(lastModified 最小)を1件ずつ削除。上限内で止めるので最新の finalFile は残る。
            while (count > MAX_CACHE_FILES || total > MAX_CACHE_BYTES) {
                int oldest = -1;
                long oldestMtime = Long.MAX_VALUE;
                for (int i = 0; i < sb.length; i++) {
                    if (sb[i] == null) continue;
                    long m = sb[i].lastModified();
                    if (m < oldestMtime) { oldestMtime = m; oldest = i; }
                }
                if (oldest < 0) break;  // 対象が尽きた(安全弁)
                long len = sb[oldest].length();
                if (sb[oldest].delete()) { total -= len; count--; }
                sb[oldest] = null;  // 試行済みは外す(削除失敗でも無限ループにしない)
            }
        } catch (Throwable ignore) {
            // eviction 失敗は致命的でない(握って無視)。
        }
    }

    private static void deleteQuietly(File f) {
        try { if (f != null) f.delete(); } catch (Throwable ignore) {}
    }
}
