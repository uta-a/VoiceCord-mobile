package dev.uta.voicecord;

import de.robv.android.xposed.XposedBridge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// フェーズ3拡張(ファイル再生): コンパニオンが 127.0.0.1 で立てた待受から、選択ファイルを
// 「生ソケットの HTTP/1.0 GET」で取得しキャッシュへ保存する。
//
// なぜ生ソケットか:
//   SAF/ContentProvider 経路は Android 11+ の package visibility と DocumentsProvider の
//   再共有制限で Discord からアクセスできない(docs/FINDINGS.md)。そこでサウンドボードと同じ
//   「Discord がネットワークで取得する」形にする。ただし HttpURLConnection/MediaExtractor 経由の
//   http はホストの cleartext ポリシーで弾かれうるため、生の Socket で読む(このポリシーの対象外)。
//
// 関門(取りに行く先を厳しく固定する):
//   - スキームは http のみ。ホストは 127.0.0.1 / localhost のみ(ループバック限定=端末外へ出さない)。
//   - ポートは 1..65535。パスはコンパニオンが埋める token 付き(モジュール側は素通しで渡す)。
//   - 応答は 200 のみ。本文は上限 MAX_BYTES まで。空/超過は破棄。
//   - 一時ファイルへ書き切ってから rename で確定(書きかけを読ませない)。
public final class LocalFetcher {

    private LocalFetcher() {}

    private static final int MAX_BYTES = 32 * 1024 * 1024;  // 32MB 上限
    private static final int TIMEOUT_MS = 10_000;
    // http://127.0.0.1:PORT/... or http://localhost:PORT/...
    private static final Pattern URL_RE =
            Pattern.compile("^http://(127\\.0\\.0\\.1|localhost):(\\d{1,5})(/.*)$");

    /** localhost の URL かどうか(モジュール側の一次判定に使う)。 */
    public static boolean isLocalUrl(String url) {
        return url != null && URL_RE.matcher(url).matches();
    }

    /**
     * localhost の待受から本文を取得し cacheDir/vc_local.dat として返す。失敗時 null。
     * ブロッキングするのでワーカースレッドから呼ぶこと。
     */
    public static File fetch(File cacheDir, String url) {
        if (cacheDir == null) return null;
        Matcher m = URL_RE.matcher(url == null ? "" : url);
        if (!m.matches()) {
            XposedBridge.log("[voicecord] LOCAL: URL の形が不正");
            return null;
        }
        String host = m.group(1);
        int port;
        try {
            port = Integer.parseInt(m.group(2));
        } catch (Throwable e) {
            return null;
        }
        if (port < 1 || port > 65535) return null;
        String path = m.group(3);

        Socket sock = null;
        File tmp = null;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            sock.setSoTimeout(TIMEOUT_MS);

            OutputStream os = sock.getOutputStream();
            String req = "GET " + path + " HTTP/1.0\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(req.getBytes("US-ASCII"));
            os.flush();

            InputStream is = sock.getInputStream();
            // ヘッダとボディの境界(\r\n\r\n)まで読み、ステータス行を検査してから本文を書く。
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int boundary = -1;
            byte[] one = new byte[1];
            // まずヘッダ部分を 1 バイトずつ読み境界を探す(ヘッダは小さい)。
            int headCap = 16 * 1024;
            while (headBuf.size() < headCap) {
                int r = is.read(one);
                if (r < 0) break;
                headBuf.write(one[0]);
                byte[] h = headBuf.toByteArray();
                if (h.length >= 4 && h[h.length - 4] == '\r' && h[h.length - 3] == '\n'
                        && h[h.length - 2] == '\r' && h[h.length - 1] == '\n') {
                    boundary = h.length;
                    break;
                }
            }
            if (boundary < 0) {
                XposedBridge.log("[voicecord] LOCAL: ヘッダ境界が見つからない");
                return null;
            }
            String header = new String(headBuf.toByteArray(), 0, boundary, "US-ASCII");
            String statusLine = header.substring(0, header.indexOf('\r') < 0 ? header.length() : header.indexOf('\r'));
            if (!statusLine.contains(" 200")) {
                XposedBridge.log("[voicecord] LOCAL: HTTP 応答が 200 でない: " + statusLine);
                return null;
            }

            // 本文を一時ファイルへ書く(境界以降は既に読んでいないので、ここから EOF まで)。
            File finalFile = new File(cacheDir, "vc_local.dat");
            tmp = new File(cacheDir, "vc_local.dat.tmp");
            long received = 0;
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = is.read(buf)) != -1) {
                    received += n;
                    if (received > MAX_BYTES) {
                        XposedBridge.log("[voicecord] LOCAL: 大きすぎ(" + received + " バイト)");
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
            if (received == 0) {
                XposedBridge.log("[voicecord] LOCAL: 空の応答");
                deleteQuietly(tmp);
                return null;
            }
            deleteQuietly(finalFile);
            if (!tmp.renameTo(finalFile)) {
                XposedBridge.log("[voicecord] LOCAL: rename 失敗");
                deleteQuietly(tmp);
                return null;
            }
            XposedBridge.log("[voicecord] LOCAL: 取得完了 bytes=" + received);
            return finalFile;
        } catch (Throwable e) {
            XposedBridge.log("[voicecord] LOCAL: 取得失敗:");
            XposedBridge.log(e);
            if (tmp != null) deleteQuietly(tmp);
            return null;
        } finally {
            try { if (sock != null) sock.close(); } catch (Throwable ignore) {}
        }
    }

    private static void deleteQuietly(File f) {
        try { if (f != null) f.delete(); } catch (Throwable ignore) {}
    }
}
