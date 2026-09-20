package dev.uta.voicecordcompanion;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

// フェーズ3 コンパニオン。
//   基本: PIN ペアリングで token を受け取り、sound_id 再生 / 停止 / 音量。
//   拡張: (1) SAF で端末内ファイルを選び一時読み取り権限付きで再生 (2) 状態確認(PING) (3) 履歴。
//
// 設計上の約束:
//   - すべてのブロードキャストは setPackage("com.discord") で voicecordmod にだけ届ける。
//   - PIN confirm / PING は sendOrderedBroadcast で送り、結果データで受け取る(送信元にのみ戻る)。
//   - ファイル再生は content:// を FLAG_GRANT_READ_URI_PERMISSION 付きで渡す(選んだ1ファイルの
//     一時読み取りのみ。常時権限や /sdcard 直パスは使わない)。
//   - d8 8.2.2 対策で匿名クラス/ラムダは使わず、リスナは implements、結果レシーバは名前付き
//     static クラス。履歴ボタンは setTag(sound_id) で識別する。
public class MainActivity extends Activity implements View.OnClickListener {

    // voicecordmod と一致させる定数。
    static final String TARGET_PKG = "com.discord";
    static final String ACTION_PAIR = "dev.uta.voicecord.PAIR";
    static final String ACTION_PLAY_SB = "dev.uta.voicecord.PLAY_SB";
    static final String ACTION_PLAY_URI = "dev.uta.voicecord.PLAY_URI";
    static final String ACTION_STOP = "dev.uta.voicecord.STOP";
    static final String ACTION_PING = "dev.uta.voicecord.PING";

    private static final String PREFS = "vc_companion";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_FILE_URI = "file_uri";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 8;
    private static final int REQ_PICK = 1001;

    private SharedPreferences prefs;
    private String token;        // ペアリング済みなら非 null。
    private Uri pickedUri;       // SAF で選んだファイル(永続化された読み取り権限を持つ)。
    private String pickedName;
    private final List<String> history = new ArrayList<>();
    private LocalFileServer server;  // ファイル再生用の 127.0.0.1 待受(遅延起動)。

    private EditText pinField;
    private Button pairBtn;
    private Button confirmBtn;
    private TextView statusLine;   // 接続状態
    private TextView stateLine;    // native 状態(PING 結果)
    private EditText soundIdField;
    private EditText gainField;
    private Button playBtn;
    private Button stopBtn;
    private Button pingBtn;
    private TextView fileLabel;
    private Button pickBtn;
    private Button playFileBtn;
    private LinearLayout historyContainer;
    private Button forgetBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        token = prefs.getString(KEY_TOKEN, null);
        server = new LocalFileServer(this);
        loadPickedUri();
        loadHistory();
        setContentView(buildUi());
        renderHistory();
        refreshState();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        statusLine = new TextView(this);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        root.addView(statusLine);
        stateLine = new TextView(this);
        stateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        root.addView(stateLine);
        addSpace(root, 12);

        // --- ペアリング ---
        addHeader(root, "ペアリング");
        addLabel(root, "1) 「ペアリング開始」を押すと Discord 側に PIN 通知が出ます");
        pairBtn = addButton(root, "ペアリング開始");
        addSpace(root, 8);
        addLabel(root, "2) 通知の PIN を入力して「接続」");
        pinField = addField(root, "PIN(6桁)", InputType.TYPE_CLASS_NUMBER);
        confirmBtn = addButton(root, "接続");
        addSpace(root, 20);

        // --- 再生(sound_id) ---
        addHeader(root, "再生(サウンドボード)");
        soundIdField = addField(root, "sound_id(数字)", InputType.TYPE_CLASS_NUMBER);
        gainField = addField(root, "音量 gain(既定 1.0)",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        gainField.setText("1.0");
        playBtn = addButton(root, "再生");
        stopBtn = addButton(root, "停止");
        addSpace(root, 20);

        // --- ファイル再生(SAF) ---
        addHeader(root, "ファイル再生(端末内音源)");
        fileLabel = new TextView(this);
        fileLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        root.addView(fileLabel);
        pickBtn = addButton(root, "ファイルを選択");
        playFileBtn = addButton(root, "選択ファイルを再生");
        addSpace(root, 20);

        // --- 状態 ---
        addHeader(root, "状態");
        pingBtn = addButton(root, "状態を確認");
        addSpace(root, 20);

        // --- 履歴 ---
        addHeader(root, "履歴(タップで再生)");
        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(historyContainer, matchWidth());
        addSpace(root, 20);

        forgetBtn = addButton(root, "ペアリング解除(token 破棄)");

        return scroll;
    }

    private void refreshState() {
        boolean paired = token != null;
        statusLine.setText(paired
                ? "状態: 接続済み（token 保存済み）"
                : "状態: 未接続（まずペアリングしてください）");
        soundIdField.setEnabled(paired);
        gainField.setEnabled(paired);
        playBtn.setEnabled(paired);
        stopBtn.setEnabled(paired);
        pingBtn.setEnabled(paired);
        // ファイル選択自体は未接続でも可。再生は token 必須。
        playFileBtn.setEnabled(paired && pickedUri != null);
        forgetBtn.setEnabled(paired);
        fileLabel.setText(pickedUri == null
                ? "選択ファイル: なし"
                : "選択ファイル: " + (pickedName != null ? pickedName : pickedUri.getLastPathSegment()));
        setHistoryEnabled(paired);
    }

    @Override
    public void onClick(View v) {
        // 履歴ボタンは tag に sound_id を持たせて識別する(匿名クラス回避)。
        Object tag = v.getTag();
        if (tag instanceof String) {
            playSoundId((String) tag);
            return;
        }
        if (v == pairBtn) {
            sendPairRequest();
        } else if (v == confirmBtn) {
            sendPairConfirm();
        } else if (v == playBtn) {
            String id = soundIdField.getText().toString().trim();
            if (id.isEmpty()) { toast("sound_id を入れてください"); return; }
            playSoundId(id);
        } else if (v == stopBtn) {
            sendStop();
        } else if (v == pickBtn) {
            startFilePicker();
        } else if (v == playFileBtn) {
            sendPlayUri();
        } else if (v == pingBtn) {
            sendPing();
        } else if (v == forgetBtn) {
            forgetToken();
        }
    }

    // --- ペアリング ---

    private void sendPairRequest() {
        Intent i = new Intent(ACTION_PAIR);
        i.setPackage(TARGET_PKG);
        i.putExtra("step", "request");
        i.putExtra("reply_pkg", getPackageName());
        sendBroadcast(i);
        toast("PIN を発行しました。Discord の通知を確認してください");
    }

    private void sendPairConfirm() {
        String pin = pinField.getText().toString().trim();
        if (pin.length() != 6) { toast("PIN は 6 桁です"); return; }
        Intent i = new Intent(ACTION_PAIR);
        i.setPackage(TARGET_PKG);
        i.putExtra("step", "confirm");
        i.putExtra("reply_pkg", getPackageName());
        i.putExtra("pin", pin);
        sendOrderedBroadcast(i, null, new TokenResultReceiver(this), null,
                Activity.RESULT_OK, null, null);
    }

    void onTokenReceived(String received) {
        if (received == null || received.isEmpty()) {
            toast("接続に失敗しました（PIN 不一致/失効の可能性）");
            return;
        }
        token = received;
        prefs.edit().putString(KEY_TOKEN, received).apply();
        pinField.setText("");
        refreshState();
        toast("接続しました");
    }

    // --- 再生(sound_id) ---

    private void playSoundId(String soundId) {
        if (token == null) { toast("未接続です"); return; }
        Intent i = new Intent(ACTION_PLAY_SB);
        i.setPackage(TARGET_PKG);
        i.putExtra("token", token);
        i.putExtra("sound_id", soundId);
        i.putExtra("gain", parseGain());
        sendBroadcast(i);
        soundIdField.setText(soundId);
        addHistory(soundId);
        toast("再生: " + soundId);
    }

    private void sendStop() {
        if (token == null) return;
        Intent i = new Intent(ACTION_STOP);
        i.setPackage(TARGET_PKG);
        i.putExtra("token", token);
        sendBroadcast(i);
        toast("停止");
    }

    // --- ファイル再生(SAF) ---

    private void startFilePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable e) {
            toast("ファイル選択を開けません");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        // 再起動後も同じファイルを再生できるよう読み取り権限を永続化する。
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignore) {}
        pickedUri = uri;
        pickedName = queryDisplayName(uri);
        prefs.edit().putString(KEY_FILE_URI, uri.toString()).apply();
        refreshState();
        toast("選択: " + (pickedName != null ? pickedName : uri.getLastPathSegment()));
    }

    private void sendPlayUri() {
        if (token == null) { toast("未接続です"); return; }
        if (pickedUri == null) { toast("ファイルを選択してください"); return; }
        // SAF/ContentProvider は package visibility で Discord から読めないため、127.0.0.1 の
        // 待受を立て、Discord に http://127.0.0.1:PORT/<token> を取得させる(token 一致時のみ配信)。
        int port = server.ensureStarted();
        if (port <= 0) { toast("ローカル待受の起動に失敗しました"); return; }
        String url = "http://127.0.0.1:" + port + "/" + token;
        Intent i = new Intent(ACTION_PLAY_URI);
        i.setPackage(TARGET_PKG);
        i.putExtra("uri", url);
        i.putExtra("token", token);
        i.putExtra("gain", parseGain());
        sendBroadcast(i);
        toast("ファイル再生");
    }

    // localhost 待受(LocalFileServer)が配信時に参照する(別スレッドから読まれる)。
    String currentToken() { return token; }
    Uri currentUri() { return pickedUri; }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignore) {}
        }
        return null;
    }

    private void loadPickedUri() {
        String s = prefs.getString(KEY_FILE_URI, null);
        if (s == null) return;
        try {
            Uri uri = Uri.parse(s);
            // 永続権限が残っているものだけ有効扱いにする。
            boolean held = false;
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(uri) && p.isReadPermission()) { held = true; break; }
            }
            if (held) {
                pickedUri = uri;
                pickedName = queryDisplayName(uri);
            } else {
                prefs.edit().remove(KEY_FILE_URI).apply();
            }
        } catch (Throwable ignore) {}
    }

    // --- 状態(PING) ---

    private void sendPing() {
        if (token == null) { toast("未接続です"); return; }
        Intent i = new Intent(ACTION_PING);
        i.setPackage(TARGET_PKG);
        i.putExtra("token", token);
        sendOrderedBroadcast(i, null, new StateResultReceiver(this), null,
                Activity.RESULT_OK, null, null);
    }

    void onStateReceived(String data) {
        if (data == null) {
            stateLine.setText("native: 応答なし");
            return;
        }
        try {
            int s = Integer.parseInt(data.trim());
            boolean hooked = (s & 1) != 0;
            boolean playing = (s & 4) != 0;
            stateLine.setText("native: " + (hooked ? "hook有効" : "hook無効")
                    + " / " + (playing ? "再生中" : "停止"));
        } catch (Throwable e) {
            stateLine.setText("native: 不明(" + data + ")");
        }
    }

    // --- 履歴 ---

    private void loadHistory() {
        history.clear();
        String s = prefs.getString(KEY_HISTORY, "");
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) history.add(id);
        }
    }

    private void addHistory(String soundId) {
        history.remove(soundId);
        history.add(0, soundId);
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(history.get(i));
        }
        prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
        renderHistory();
    }

    // 履歴を毎回作り直す(件数が少ないので単純に removeAllViews→再生成)。
    private void renderHistory() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();
        if (history.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("履歴なし");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            historyContainer.addView(t);
            return;
        }
        boolean paired = token != null;
        for (String id : history) {
            Button b = new Button(this);
            b.setText("▶ " + id);
            b.setTag(id);              // onClick で sound_id を識別
            b.setEnabled(paired);
            b.setOnClickListener(this);
            historyContainer.addView(b, matchWidth());
        }
    }

    private void setHistoryEnabled(boolean paired) {
        if (historyContainer == null) return;
        for (int i = 0; i < historyContainer.getChildCount(); i++) {
            View c = historyContainer.getChildAt(i);
            if (c instanceof Button) c.setEnabled(paired);
        }
    }

    // --- 共通 ---

    private float parseGain() {
        try {
            float g = Float.parseFloat(gainField.getText().toString().trim());
            if (g < 0f) g = 0f;
            if (g > 4f) g = 4f;  // 過大入力での破綻を避ける安全上限
            return g;
        } catch (Throwable e) {
            return 1.0f;
        }
    }

    private void forgetToken() {
        token = null;
        prefs.edit().remove(KEY_TOKEN).apply();
        refreshState();
        renderHistory();
        toast("token を破棄しました");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // --- UI 部品(コード生成の補助) ---

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void addHeader(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        t.setPadding(0, dp(4), 0, dp(6));
        parent.addView(t);
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        parent.addView(t);
    }

    private EditText addField(LinearLayout parent, String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        parent.addView(e, matchWidth());
        return e;
    }

    private Button addButton(LinearLayout parent, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(this);
        parent.addView(b, matchWidth());
        return b;
    }

    private void addSpace(LinearLayout parent, int h) {
        View s = new View(this);
        parent.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(h)));
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // 順序付きブロードキャストの結果 token を受ける名前付きレシーバ(匿名クラス回避)。
    static final class TokenResultReceiver extends BroadcastReceiver {
        private final MainActivity activity;
        TokenResultReceiver(MainActivity activity) { this.activity = activity; }
        @Override
        public void onReceive(Context context, Intent intent) {
            activity.onTokenReceived(getResultData());
        }
    }

    // PING の結果(native 状態)を受ける名前付きレシーバ。
    static final class StateResultReceiver extends BroadcastReceiver {
        private final MainActivity activity;
        StateResultReceiver(MainActivity activity) { this.activity = activity; }
        @Override
        public void onReceive(Context context, Intent intent) {
            activity.onStateReceived(getResultData());
        }
    }
}
