package dev.uta.voicecordcompanion;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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

// フェーズ3 コンパニオン(MVP: 最小)。
//   - PIN ペアリングで voicecordmod から操作 token を受け取り、端末内(private prefs)に保存する。
//   - sound_id を入れて再生 / 停止 / 音量(gain) を送る。
//
// 設計上の約束:
//   - すべてのブロードキャストは setPackage("com.discord") で「Discord プロセスの
//     voicecordmod にだけ」届ける(他アプリに命令や token を晒さない)。
//   - PIN confirm は sendOrderedBroadcast で送り、結果データで token を受け取る
//     (token は送信元=このアプリにしか戻らない。exported レシーバを持たない)。
//   - d8 8.2.2 は匿名クラス/ラムダでクラッシュするため、リスナは implements で受け、
//     結果レシーバは名前付き static クラスにする(res も持たず UI はコード生成)。
public class MainActivity extends Activity implements View.OnClickListener {

    // voicecordmod と一致させる定数(モジュール側 CommandReceiver と揃える)。
    static final String TARGET_PKG = "com.discord";
    static final String ACTION_PAIR = "dev.uta.voicecord.PAIR";
    static final String ACTION_PLAY_SB = "dev.uta.voicecord.PLAY_SB";
    static final String ACTION_STOP = "dev.uta.voicecord.STOP";
    static final String ACTION_SET = "dev.uta.voicecord.SET";

    private static final String PREFS = "vc_companion";
    private static final String KEY_TOKEN = "token";

    private SharedPreferences prefs;
    private String token;  // ペアリング済みなら非 null。

    private TextView status;
    private EditText pinField;
    private Button pairBtn;
    private Button confirmBtn;
    private EditText soundIdField;
    private EditText gainField;
    private Button playBtn;
    private Button stopBtn;
    private Button forgetBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        token = prefs.getString(KEY_TOKEN, null);
        setContentView(buildUi());
        refreshState();
    }

    // res を持たないため UI はコードで構築する。縦 LinearLayout を ScrollView に載せる。
    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        root.addView(status);
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

        // --- 再生 ---
        addHeader(root, "再生");
        soundIdField = addField(root, "sound_id(数字)", InputType.TYPE_CLASS_NUMBER);
        gainField = addField(root, "音量 gain(既定 1.0)",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        gainField.setText("1.0");
        playBtn = addButton(root, "再生");
        stopBtn = addButton(root, "停止");
        addSpace(root, 20);

        forgetBtn = addButton(root, "ペアリング解除(token 破棄)");

        return scroll;
    }

    // ペアリング済みかどうかで表示とボタンの有効/無効を切り替える。
    private void refreshState() {
        boolean paired = token != null;
        status.setText(paired
                ? "状態: 接続済み（token 保存済み）"
                : "状態: 未接続（まずペアリングしてください）");
        soundIdField.setEnabled(paired);
        gainField.setEnabled(paired);
        playBtn.setEnabled(paired);
        stopBtn.setEnabled(paired);
        forgetBtn.setEnabled(paired);
    }

    @Override
    public void onClick(View v) {
        if (v == pairBtn) {
            sendPairRequest();
        } else if (v == confirmBtn) {
            sendPairConfirm();
        } else if (v == playBtn) {
            sendPlay();
        } else if (v == stopBtn) {
            sendStop();
        } else if (v == forgetBtn) {
            forgetToken();
        }
    }

    // PAIR(request): Discord 側で PIN を発行・通知させる。
    private void sendPairRequest() {
        Intent i = new Intent(ACTION_PAIR);
        i.setPackage(TARGET_PKG);
        i.putExtra("step", "request");
        i.putExtra("reply_pkg", getPackageName());
        sendBroadcast(i);
        toast("PIN を発行しました。Discord の通知を確認してください");
    }

    // PAIR(confirm): 順序付きで送り、結果データで token を受け取る。
    private void sendPairConfirm() {
        String pin = pinField.getText().toString().trim();
        if (pin.length() != 6) {
            toast("PIN は 6 桁です");
            return;
        }
        Intent i = new Intent(ACTION_PAIR);
        i.setPackage(TARGET_PKG);
        i.putExtra("step", "confirm");
        i.putExtra("reply_pkg", getPackageName());
        i.putExtra("pin", pin);
        // 結果レシーバ(名前付き)で getResultData() から token を拾う。
        sendOrderedBroadcast(i, null, new TokenResultReceiver(this), null,
                Activity.RESULT_OK, null, null);
    }

    // 結果データで受けた token を保存する(UI スレッドから呼ばれる)。
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

    private void sendPlay() {
        if (token == null) return;
        String soundId = soundIdField.getText().toString().trim();
        if (soundId.isEmpty()) {
            toast("sound_id を入れてください");
            return;
        }
        Intent i = new Intent(ACTION_PLAY_SB);
        i.setPackage(TARGET_PKG);
        i.putExtra("token", token);
        i.putExtra("sound_id", soundId);
        i.putExtra("gain", parseGain());
        sendBroadcast(i);
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

        TokenResultReceiver(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            // resultReceiver は送信元(このアプリ)のメインスレッドで呼ばれる。
            activity.onTokenReceived(getResultData());
        }
    }
}
