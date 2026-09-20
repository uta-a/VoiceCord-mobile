package dev.uta.voicecord;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import de.robv.android.xposed.XposedBridge;

import java.security.SecureRandom;

// フェーズ3: コンパニオン(別APK)へ操作トークンを安全に渡すための PIN ペアリングを担う。
//
// 位置づけ(trust boundary):
//   フェーズ2の平文 vc_token ファイル(cache 直書き)を廃止し、代わりに以下の受け渡しにする。
//     1. コンパニオンが PAIR(request) を投げる → ここで 6 桁 PIN を生成し「通知」で画面に出す。
//        通知は Discord プロセス側に出るので、端末画面を見ている本人だけが PIN を読める
//        (= 物理的に端末を握っている人=正規利用者、という人間側の確認点)。
//     2. 本人がコンパニオンへ PIN を入力 → PAIR(confirm, pin, reply_pkg) を「順序付き」で投げる。
//     3. ここで PIN 一致・未失効・pending の reply_pkg 一致を検証し、成功時のみ
//        CommandReceiver 側が「順序付きブロードキャストの結果データ」で token を返す
//        (結果は送信元コンパニオンにしか戻らない=他アプリ不可視、exported レシーバ不要)。
//
// このクラスは pending PIN の生成・検証・失効のみを持つ(通知の送出も担う)。
// トークン本体(Entry.TOKEN)や PAIRED 返信の組み立ては CommandReceiver 側。
//
// 残存リスク(MVP として受容、docs/FINDINGS.md に記載):
//   - PAIR は token 不要のブートストラップなので、悪意アプリが request を連投して通知を
//     出させる DoS はありうる → 直近リクエストを一定間隔でスロットルして緩和。
//   - PIN ブルートフォース → 6 桁 + 60 秒有効 + 5 回失敗で失効 + 同時 1 pending のみ で緩和。
//   - 攻撃者が reply_pkg に自分を指定して token を奪う経路 → PIN は画面通知にしか出ないため、
//     PIN を知らない限り成立しない。加えて「身に覚えのないペアリング通知」が人間側の検知点になる。
// (状態機械を default パッケージの host テストから検証するため public。モジュール APK 内部専用で
//  外部 API ではない。)
public final class PairingManager {

    // pending PIN の有効期間と、confirm 失敗の許容回数、request のスロットル間隔。
    // (host テストが default パッケージから参照するため public。)
    public static final long PIN_TTL_MS = 60_000L;      // PIN は 60 秒だけ有効
    public static final int MAX_ATTEMPTS = 5;           // confirm 失敗 5 回で失効
    public static final long REQUEST_THROTTLE_MS = 2_000L; // request 連投の通知スパム抑止

    private static final String CHANNEL_ID = "voicecord_pairing";
    private static final int NOTIF_ID = 0x7C0DE;         // 固定 ID(上書き更新)

    private final SecureRandom rng = new SecureRandom();

    // pending 状態(request で作られ、confirm 成否/失効/新 request で消える)。単一 pending のみ。
    private String pendingPin;
    private String pendingReplyPkg;
    private long pendingExpiryMs;
    private int attempts;
    // 直近 request 受理時刻(スロットル用)。
    private long lastRequestMs;

    /**
     * PAIR(request) を処理する。reply_pkg 宛に新しい PIN を発行し通知で表示する。
     * スロットル間隔内の連投は無視(通知スパム抑止)。呼び出しは CommandReceiver のメインスレッド。
     */
    synchronized void onRequest(Context context, String replyPkg) {
        String pin = createPending(replyPkg);
        if (pin == null) return;  // reply_pkg 不正 or スロットルで発行せず
        showPinNotification(context, pin, replyPkg);
        // PIN 全文はログに出さない(logcat/adb 経由で漏れるため)。発行の事実のみ。
        XposedBridge.log("[voicecord] PAIR: PIN 発行 reply_pkg=" + replyPkg + " (通知表示, 60秒有効)");
    }

    /**
     * pending PIN を生成して内部状態にセットし、生成した PIN を返す(通知は行わない)。
     * reply_pkg 不正・スロットル中は発行せず null。Context 非依存にして状態機械を host テスト可能にする。
     * (default パッケージの host テストから参照するため package-private。)
     */
    public synchronized String createPending(String replyPkg) {
        if (replyPkg == null || replyPkg.isEmpty()) {
            XposedBridge.log("[voicecord] PAIR: reply_pkg 無しの request を無視");
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < REQUEST_THROTTLE_MS) {
            XposedBridge.log("[voicecord] PAIR: request が短時間に連投されたため無視(スロットル)");
            return null;
        }
        lastRequestMs = now;

        // 6 桁 PIN(000000..999999)。先頭 0 も許容し常に 6 桁表示。
        int n = rng.nextInt(1_000_000);
        pendingPin = String.format("%06d", n);
        pendingReplyPkg = replyPkg;
        pendingExpiryMs = now + PIN_TTL_MS;
        attempts = 0;
        return pendingPin;
    }

    /**
     * PAIR(confirm) を検証する。一致すれば true(=呼び出し側が PAIRED を返す)。
     * 一致時 pending は消費される。失敗回数超過/失効でも pending を消す。
     */
    public synchronized boolean onConfirm(String pin, String replyPkg) {
        if (pendingPin == null) {
            XposedBridge.log("[voicecord] PAIR: pending 無しで confirm(先に request が必要)");
            return false;
        }
        if (System.currentTimeMillis() > pendingExpiryMs) {
            XposedBridge.log("[voicecord] PAIR: PIN 失効");
            clear();
            return false;
        }
        // confirm 元が request 時の reply_pkg と一致することを要求(別アプリが横取りできない)。
        if (replyPkg == null || !replyPkg.equals(pendingReplyPkg)) {
            XposedBridge.log("[voicecord] PAIR: reply_pkg 不一致で拒否");
            return false;
        }
        attempts++;
        if (pin == null || !constantTimeEquals(pin, pendingPin)) {
            XposedBridge.log("[voicecord] PAIR: PIN 不一致(" + attempts + "/" + MAX_ATTEMPTS + ")");
            if (attempts >= MAX_ATTEMPTS) {
                XposedBridge.log("[voicecord] PAIR: 失敗回数超過で PIN 失効");
                clear();
            }
            return false;
        }
        XposedBridge.log("[voicecord] PAIR: 成功 reply_pkg=" + replyPkg);
        clear();  // 使い切り
        return true;
    }

    private void clear() {
        pendingPin = null;
        pendingReplyPkg = null;
        pendingExpiryMs = 0L;
        attempts = 0;
    }

    // PIN 長は固定 6 桁だが、桁数差でも早期 return しない比較(タイミング差の芽を摘む)。
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        int diff = a.length() ^ b.length();
        for (int i = 0; i < a.length() && i < b.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // 接続成功時などに PIN 通知を消す。CommandReceiver の confirm 成功で呼ぶ。
    void dismissNotification(Context context) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Throwable ignore) {}
    }

    // PIN を通知で表示する。Discord プロセス側の通知権限で出る(API33+ の POST_NOTIFICATIONS は
    // ホスト Discord に付与済みの想定)。チャンネルは API26+ で必要。
    private void showPinNotification(Context context, String pin, String replyPkg) {
        try {
            NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                XposedBridge.log("[voicecord] PAIR: NotificationManager 取得失敗");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "VoiceCord ペアリング", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("コンパニオン接続用の PIN を表示します");
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            // アイコンは host にある汎用 android アイコンを流用(モジュールに drawable を持たないため)。
            // 長い BigTextStyle は接続後も残ると邪魔なので廃し、1 行の短い表示にする。
            b.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle("VoiceCord PIN: " + pin)
                    .setContentText("コンパニオンに入力（60秒で失効）")
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true);
            if (Build.VERSION.SDK_INT >= 26) {
                // 60 秒(PIN_TTL_MS)で自動消滅。接続しなくても残さない。
                b.setTimeoutAfter(PIN_TTL_MS);
            }
            if (Build.VERSION.SDK_INT >= 21) {
                b.setVisibility(Notification.VISIBILITY_SECRET);  // ロック画面には出さない
            }
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable e) {
            XposedBridge.log("[voicecord] PAIR: 通知表示失敗:");
            XposedBridge.log(e);
        }
    }
}
