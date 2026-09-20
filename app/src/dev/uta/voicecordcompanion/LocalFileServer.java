package dev.uta.voicecordcompanion;

import android.net.Uri;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

// フェーズ3拡張(ファイル再生): SAF で選んだ端末内音源を Discord プロセスへ渡すための
// 127.0.0.1 限定の極小 HTTP/1.0 待受。
//
// なぜ必要か:
//   SAF/ContentProvider 経路は Android 11+ の package visibility と DocumentsProvider の再共有
//   制限で Discord から読めない。そこで Discord が INTERNET 権限で「ネットワーク取得」する形にする。
//
// セキュリティ:
//   - bind はループバック(127.0.0.1)のみ。端末外からは到達不可。
//   - GET /<token> の token がペアリング済み token と一致した時だけ配信(それ以外は 403)。
//   - 配信するのは「今 UI で選択中の 1 ファイル」だけ。パスから materialを取らない(任意読み出し不可)。
//   - 生ソケットで最小限の HTTP を話す(依存なし)。1 接続ずつ順次処理(単一利用者前提)。
final class LocalFileServer {

    private final MainActivity app;
    private ServerSocket server;
    private volatile int port = -1;
    private volatile boolean running = false;

    LocalFileServer(MainActivity app) {
        this.app = app;
    }

    /** 待受を(必要なら)起動し、ポート番号を返す。失敗時 -1。 */
    synchronized int ensureStarted() {
        if (running && server != null && !server.isClosed() && port > 0) return port;
        try {
            server = new ServerSocket();
            // IPv4 の 127.0.0.1 に明示 bind する。getLoopbackAddress() は ::1(IPv6)を返す環境があり、
            // モジュール側が 127.0.0.1(IPv4)へ繋ぐと ECONNREFUSED になるため。ポートは OS 任せ(0)。
            InetAddress ipv4loop = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            server.bind(new InetSocketAddress(ipv4loop, 0), 4);
            port = server.getLocalPort();
            running = true;
            Thread t = new Thread(new AcceptLoop(this), "vc-httpd");
            t.setDaemon(true);
            t.start();
            return port;
        } catch (Throwable e) {
            running = false;
            port = -1;
            return -1;
        }
    }

    // 接続受理ループ。1 接続ずつ順次さばく。
    static final class AcceptLoop implements Runnable {
        private final LocalFileServer s;
        AcceptLoop(LocalFileServer s) { this.s = s; }

        @Override
        public void run() {
            while (s.running) {
                Socket sock = null;
                try {
                    sock = s.server.accept();
                    s.handle(sock);
                } catch (Throwable e) {
                    Log.e("voicecord-httpd", "handle失敗", e);
                    // accept 失敗(server close 含む)はループ継続 or 終了。
                    if (s.server == null || s.server.isClosed()) break;
                } finally {
                    if (sock != null) try { sock.close(); } catch (Throwable ignore) {}
                }
            }
        }
    }

    // 1 リクエストを処理する。GET /<token> のみ受け付け、選択中ファイルを返す。
    private void handle(Socket sock) throws Exception {
        sock.setSoTimeout(10_000);
        InputStream in = sock.getInputStream();
        OutputStream out = sock.getOutputStream();

        String requestLine = readLine(in);
        if (requestLine == null) { writeStatus(out, "400 Bad Request"); return; }
        // 残りのリクエストヘッダを空行まで読み捨てる。受信バッファにデータを残したまま close すると
        // カーネルが FIN でなく RST を返し、クライアント側が "Connection reset" になるため。
        String h;
        while ((h = readLine(in)) != null && h.length() > 0) { /* discard */ }
        // "GET /<token> HTTP/1.0"
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !"GET".equals(parts[0])) { writeStatus(out, "405 Method Not Allowed"); return; }
        String path = parts[1];
        String reqToken = path.startsWith("/") ? path.substring(1) : path;

        String token = app.currentToken();
        Uri uri = app.currentUri();
        if (token == null || uri == null || !token.equals(reqToken)) {
            writeStatus(out, "403 Forbidden");
            return;
        }

        InputStream fin = null;
        try {
            fin = app.getContentResolver().openInputStream(uri);
            if (fin == null) { writeStatus(out, "404 Not Found"); return; }
            // Content-Length は不明なので付けず、Connection: close で本文終端を伝える。
            out.write(("HTTP/1.0 200 OK\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Connection: close\r\n\r\n").getBytes("US-ASCII"));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = fin.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        } finally {
            if (fin != null) try { fin.close(); } catch (Throwable ignore) {}
        }
    }

    private static void writeStatus(OutputStream out, String status) {
        try {
            out.write(("HTTP/1.0 " + status + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            out.flush();
        } catch (Throwable ignore) {}
    }

    // 1 行(\n 終端)を ASCII で読む。ヘッダは小さい前提で 1 バイトずつ。
    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        int cap = 8 * 1024;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > cap) break;
        }
        if (sb.length() == 0 && c == -1) return null;
        return sb.toString();
    }
}
