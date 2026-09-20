package dev.uta.voicecordcompanion;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// フェーズ3 コンパニオン(Material You / Material 3 デザイン)。
//   基本: PIN ペアリングで token を受け取り、sound_id 再生 / 停止 / 音量。
//   拡張: (1) SAF で端末内ファイルを選び localhost 経由で再生 (2) 状態確認(PING) (3) 履歴。
//
// デザイン方針:
//   - res を持たずコードで UI 生成する制約のまま Material 3 を実装する。
//   - 色は Android 12+ の「Material You ダイナミックカラー」(android.R.color.system_* トーナル
//     パレット=壁紙連動)を採用。API<31 と取得失敗時は M3 ベースライン(紫)にフォールバック。
//   - ライト/ダークは端末設定に追従(Theme.DeviceDefault.DayNight.NoActionBar + 夜間判定で配色切替)。
//   - 構成要素: トップアプリバー / 角丸カード(surfaceContainer) / Filled ボタン(primary) /
//     Tonal ボタン(secondaryContainer) / アウトライン入力欄 / Ripple。
//
// 実装上の約束(従来どおり):
//   - ブロードキャストは setPackage("com.discord")。PIN confirm / PING は順序付きで結果受領。
//   - d8 8.2.2 対策で匿名クラス/ラムダを使わず、リスナは implements、結果レシーバは名前付き static。
public class MainActivity extends Activity
        implements View.OnClickListener, View.OnLongClickListener {

    // voicecordmod と一致させる定数。
    static final String TARGET_PKG = "com.discord";
    static final String ACTION_PAIR = "dev.uta.voicecord.PAIR";
    static final String ACTION_PLAY_SB = "dev.uta.voicecord.PLAY_SB";
    static final String ACTION_PLAY_URI = "dev.uta.voicecord.PLAY_URI";
    static final String ACTION_STOP = "dev.uta.voicecord.STOP";
    static final String ACTION_PING = "dev.uta.voicecord.PING";

    private static final String PREFS = "vc_companion";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_FILES = "files";       // 音源リスト(SAF で追加したファイル群)
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 8;
    private static final int REQ_PICK = 1001;

    private SharedPreferences prefs;
    private String token;        // ペアリング済みなら非 null。
    private Uri pickedUri;       // 直近タップした音源(localhost 配信対象)。再生時にセット。
    private final List<String> history = new ArrayList<>();
    private final List<FileEntry> files = new ArrayList<>();  // 音源リスト(ワンタップ再生)
    private LocalFileServer server;  // ファイル再生用の 127.0.0.1 待受(遅延起動)。

    // 音源リストの1件(SAF の URI と表示名)。永続 URI 権限を持つ前提。
    static final class FileEntry {
        final Uri uri;
        final String name;
        FileEntry(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

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
    private LinearLayout filesContainer;
    private Button addFileBtn;
    private LinearLayout historyContainer;
    private Button forgetBtn;

    // --- Material 3 パレット(onCreate で解決) ---
    private int cPrimary, cOnPrimary, cSecondaryContainer, cOnSecondaryContainer;
    private int cSurface, cOnSurface, cOnSurfaceVariant, cSurfaceContainer, cOutline;
    private int cErrorContainer, cOnErrorContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();  // M3 自作トップバーを使うため既定を隠す
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        token = prefs.getString(KEY_TOKEN, null);
        server = new LocalFileServer(this);
        resolvePalette();
        applySystemBars();
        loadFiles();
        loadHistory();
        setContentView(buildUi());
        renderFiles();
        renderHistory();
        refreshState();
    }

    // ============ Material You パレット ============

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void resolvePalette() {
        boolean night = isNight();
        boolean dynamic = false;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (night) {
                    cPrimary = getColor(android.R.color.system_accent1_200);
                    cOnPrimary = getColor(android.R.color.system_accent1_800);
                    cSecondaryContainer = getColor(android.R.color.system_accent2_700);
                    cOnSecondaryContainer = getColor(android.R.color.system_accent2_100);
                    cSurface = getColor(android.R.color.system_neutral1_900);
                    cOnSurface = getColor(android.R.color.system_neutral1_100);
                    cOnSurfaceVariant = getColor(android.R.color.system_neutral2_200);
                    cSurfaceContainer = getColor(android.R.color.system_neutral1_800);
                    cOutline = getColor(android.R.color.system_neutral2_400);
                } else {
                    cPrimary = getColor(android.R.color.system_accent1_600);
                    cOnPrimary = getColor(android.R.color.system_accent1_0);
                    cSecondaryContainer = getColor(android.R.color.system_accent2_100);
                    cOnSecondaryContainer = getColor(android.R.color.system_accent2_900);
                    cSurface = getColor(android.R.color.system_neutral1_10);
                    cOnSurface = getColor(android.R.color.system_neutral1_900);
                    cOnSurfaceVariant = getColor(android.R.color.system_neutral2_700);
                    cSurfaceContainer = getColor(android.R.color.system_neutral1_100);
                    cOutline = getColor(android.R.color.system_neutral2_500);
                }
                dynamic = true;
            } catch (Throwable e) {
                dynamic = false;
            }
        }
        if (!dynamic) {
            // M3 ベースライン(紫)。ダイナミックカラー非対応時のフォールバック。
            if (night) {
                cPrimary = 0xFFD0BCFF; cOnPrimary = 0xFF381E72;
                cSecondaryContainer = 0xFF4A4458; cOnSecondaryContainer = 0xFFE8DEF8;
                cSurface = 0xFF141218; cOnSurface = 0xFFE6E0E9; cOnSurfaceVariant = 0xFFCAC4D0;
                cSurfaceContainer = 0xFF211F26; cOutline = 0xFF938F99;
            } else {
                cPrimary = 0xFF6750A4; cOnPrimary = 0xFFFFFFFF;
                cSecondaryContainer = 0xFFE8DEF8; cOnSecondaryContainer = 0xFF1D192B;
                cSurface = 0xFFFEF7FF; cOnSurface = 0xFF1D1B20; cOnSurfaceVariant = 0xFF49454F;
                cSurfaceContainer = 0xFFF3EDF7; cOutline = 0xFF79747E;
            }
        }
        // エラー系はダイナミックパレットに無いので M3 ベースラインを使う。
        if (night) { cErrorContainer = 0xFF8C1D18; cOnErrorContainer = 0xFFF9DEDC; }
        else { cErrorContainer = 0xFFF9DEDC; cOnErrorContainer = 0xFF410E0B; }
    }

    // ステータスバーを surface 色に合わせ、明暗でアイコン色を切替。
    private void applySystemBars() {
        try {
            getWindow().setStatusBarColor(cSurface);
            getWindow().setNavigationBarColor(cSurface);
            View decor = getWindow().getDecorView();
            boolean lightBg = luminance(cSurface) > 0.5;
            int flags = decor.getSystemUiVisibility();
            if (lightBg) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignore) {}
    }

    private static double luminance(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }

    private static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    // ============ UI 構築 ============

    private View buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(cSurface);

        // トップアプリバー(M3)。
        TextView appBar = new TextView(this);
        appBar.setText("VoiceCord Companion");
        appBar.setTextColor(cOnSurface);
        appBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        appBar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        appBar.setPadding(dp(24), dp(20), dp(24), dp(16));
        outer.addView(appBar, mw());

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        outer.addView(scroll, slp);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(root);

        // 状態カード。
        LinearLayout statusCard = card(root, null);
        statusLine = new TextView(this);
        statusLine.setTextColor(cOnSurface);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        statusCard.addView(statusLine, mw());
        stateLine = new TextView(this);
        stateLine.setTextColor(cOnSurfaceVariant);
        stateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        addGap(statusCard, stateLine, 4);

        // ペアリング。
        LinearLayout pairCard = card(root, "ペアリング");
        addLabel(pairCard, "1) 「ペアリング開始」を押すと Discord 側に PIN 通知が出ます", 0);
        pairBtn = filledButton("ペアリング開始");
        addGap(pairCard, pairBtn, 10);
        addLabel(pairCard, "2) 通知の PIN を入力して「接続」", 16);
        pinField = field("PIN(6桁)", InputType.TYPE_CLASS_NUMBER);
        addGap(pairCard, pinField, 8);
        confirmBtn = filledButton("接続");
        addGap(pairCard, confirmBtn, 10);

        // 再生(サウンドボード)。
        LinearLayout sbCard = card(root, "再生(サウンドボード)");
        soundIdField = field("sound_id(数字)", InputType.TYPE_CLASS_NUMBER);
        addGap(sbCard, soundIdField, 0);
        gainField = field("音量 gain(既定 1.0)",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        gainField.setText("1.0");
        addGap(sbCard, gainField, 8);
        playBtn = filledButton("再生");
        addGap(sbCard, playBtn, 10);
        stopBtn = tonalButton("停止", cSecondaryContainer, cOnSecondaryContainer);
        addGap(sbCard, stopBtn, 8);

        // 音源リスト(端末内ファイル)。一覧からワンタップ再生、長押しで削除。
        LinearLayout fileCard = card(root, "音源リスト(タップで再生・長押しで削除)");
        filesContainer = new LinearLayout(this);
        filesContainer.setOrientation(LinearLayout.VERTICAL);
        fileCard.addView(filesContainer, mw());
        addFileBtn = tonalButton("＋ 音源を追加", cSecondaryContainer, cOnSecondaryContainer);
        addGap(fileCard, addFileBtn, 8);

        // 状態。
        LinearLayout stateCard = card(root, "状態");
        pingBtn = tonalButton("状態を確認", cSecondaryContainer, cOnSecondaryContainer);
        addGap(stateCard, pingBtn, 0);

        // 履歴。
        LinearLayout histCard = card(root, "履歴(タップで再生)");
        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        histCard.addView(historyContainer, mw());

        // ペアリング解除(エラートーン)。
        forgetBtn = tonalButton("ペアリング解除(token 破棄)", cErrorContainer, cOnErrorContainer);
        LinearLayout.LayoutParams flp = mw();
        flp.topMargin = dp(4);
        root.addView(forgetBtn, flp);

        return outer;
    }

    private void refreshState() {
        boolean paired = token != null;
        statusLine.setText(paired
                ? "接続済み（token 保存済み）"
                : "未接続（まずペアリングしてください）");
        setEnabledM3(soundIdField, paired);
        setEnabledM3(gainField, paired);
        setEnabledM3(playBtn, paired);
        setEnabledM3(stopBtn, paired);
        setEnabledM3(pingBtn, paired);
        setEnabledM3(forgetBtn, paired);
        // 音源の追加は未接続でも可。再生(リストのボタン)は token 必須。
        setFilesEnabled(paired);
        setHistoryEnabled(paired);
    }

    @Override
    public void onClick(View v) {
        // 音源リストのボタンは tag に FileEntry、履歴ボタンは tag に String(sound_id)を持たせて識別。
        Object tag = v.getTag();
        if (tag instanceof FileEntry) {
            playFile((FileEntry) tag);
            return;
        }
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
        } else if (v == addFileBtn) {
            startFilePicker();
        } else if (v == pingBtn) {
            sendPing();
        } else if (v == forgetBtn) {
            forgetToken();
        }
    }

    // 音源リストのボタンを長押しで削除する。
    @Override
    public boolean onLongClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof FileEntry) {
            removeFile((FileEntry) tag);
            return true;
        }
        return false;
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
        // 再起動後も同じ音源を再生できるよう読み取り権限を永続化する。
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignore) {}
        addFile(uri);
    }

    // 音源リストからワンタップ再生: 対象を配信対象にして localhost 経由で再生させる。
    private void playFile(FileEntry entry) {
        if (token == null) { toast("未接続です"); return; }
        pickedUri = entry.uri;   // LocalFileServer.currentUri() が配信に使う
        int port = server.ensureStarted();
        if (port <= 0) { toast("ローカル待受の起動に失敗しました"); return; }
        // SAF/ContentProvider は package visibility で Discord から読めないため、127.0.0.1 の
        // 待受を立て、Discord に http://127.0.0.1:PORT/<token> を取得させる(token 一致時のみ配信)。
        String url = "http://127.0.0.1:" + port + "/" + token;
        Intent i = new Intent(ACTION_PLAY_URI);
        i.setPackage(TARGET_PKG);
        i.putExtra("uri", url);
        i.putExtra("token", token);
        i.putExtra("gain", parseGain());
        sendBroadcast(i);
        toast("再生: " + entry.name);
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

    // --- 音源リスト ---

    private void loadFiles() {
        files.clear();
        String s = prefs.getString(KEY_FILES, null);
        if (s == null || s.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("uri", null);
                String n = o.optString("name", null);
                if (u == null) continue;
                Uri uri = Uri.parse(u);
                // 永続権限が残っているものだけ有効扱いにする(切れていたら一覧から落とす)。
                if (!hasPersistedRead(uri)) continue;
                if (n == null || n.isEmpty()) n = uri.getLastPathSegment();
                files.add(new FileEntry(uri, n));
            }
        } catch (Throwable ignore) {}
    }

    private void saveFiles() {
        JSONArray arr = new JSONArray();
        for (FileEntry e : files) {
            try {
                JSONObject o = new JSONObject();
                o.put("uri", e.uri.toString());
                o.put("name", e.name);
                arr.put(o);
            } catch (Throwable ignore) {}
        }
        prefs.edit().putString(KEY_FILES, arr.toString()).apply();
    }

    private boolean hasPersistedRead(Uri uri) {
        try {
            for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(uri) && p.isReadPermission()) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private void addFile(Uri uri) {
        // 既に同じ URI があれば重複追加しない。
        for (FileEntry e : files) {
            if (e.uri.equals(uri)) { toast("追加済みです"); return; }
        }
        String name = queryDisplayName(uri);
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        files.add(0, new FileEntry(uri, name));
        saveFiles();
        renderFiles();
        refreshState();
        toast("追加: " + name);
    }

    private void removeFile(FileEntry entry) {
        files.remove(entry);
        // 永続権限も解放しておく(端末側の付与残りを溜めない)。
        try {
            getContentResolver().releasePersistableUriPermission(
                    entry.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignore) {}
        saveFiles();
        renderFiles();
        refreshState();
        toast("削除: " + entry.name);
    }

    // 音源リストを作り直す(件数が少ないので removeAllViews→再生成)。
    private void renderFiles() {
        if (filesContainer == null) return;
        filesContainer.removeAllViews();
        if (files.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("音源なし（＋で追加）");
            t.setTextColor(cOnSurfaceVariant);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            filesContainer.addView(t, mw());
            return;
        }
        boolean paired = token != null;
        boolean first = true;
        for (FileEntry e : files) {
            Button b = tonalButton("▶ " + e.name, cSecondaryContainer, cOnSecondaryContainer);
            b.setTag(e);                 // onClick/onLongClick で識別
            b.setOnLongClickListener(this);
            addGap(filesContainer, b, first ? 0 : 8);
            setEnabledM3(b, paired);
            first = false;
        }
    }

    private void setFilesEnabled(boolean paired) {
        setEnabledM3(addFileBtn, true);  // 追加は常時可
        if (filesContainer == null) return;
        for (int i = 0; i < filesContainer.getChildCount(); i++) {
            View c = filesContainer.getChildAt(i);
            if (c instanceof Button) setEnabledM3(c, paired);
        }
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
            t.setTextColor(cOnSurfaceVariant);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            historyContainer.addView(t, mw());
            return;
        }
        boolean paired = token != null;
        boolean first = true;
        for (String id : history) {
            Button b = tonalButton("▶ " + id, cSecondaryContainer, cOnSecondaryContainer);
            b.setTag(id);              // onClick で sound_id を識別
            addGap(historyContainer, b, first ? 0 : 8);
            setEnabledM3(b, paired);
            first = false;
        }
    }

    private void setHistoryEnabled(boolean paired) {
        if (historyContainer == null) return;
        for (int i = 0; i < historyContainer.getChildCount(); i++) {
            View c = historyContainer.getChildAt(i);
            if (c instanceof Button) setEnabledM3(c, paired);
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

    // ============ Material 3 部品(コード生成) ============

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout.LayoutParams mw() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // 角丸カード(surfaceContainer)。title!=null なら見出しを付ける。
    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cSurfaceContainer);
        bg.setCornerRadius(dp(24));
        c.setBackground(bg);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = mw();
        lp.bottomMargin = dp(16);
        parent.addView(c, lp);
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(cOnSurface);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams tlp = mw();
            tlp.bottomMargin = dp(10);
            c.addView(t, tlp);
        }
        return c;
    }

    private void addLabel(LinearLayout parent, String text, int topGapDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(cOnSurfaceVariant);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        addGap(parent, t, topGapDp);
    }

    private void addGap(LinearLayout parent, View v, int topGapDp) {
        LinearLayout.LayoutParams lp = mw();
        lp.topMargin = dp(topGapDp);
        parent.addView(v, lp);
    }

    // M3 アウトライン入力欄。
    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextColor(cOnSurface);
        e.setHintTextColor(cOnSurfaceVariant);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), cOutline);
        e.setBackground(bg);
        e.setPadding(dp(16), dp(14), dp(16), dp(14));
        return e;
    }

    // M3 Filled ボタン(primary)。
    private Button filledButton(String label) {
        return styledButton(label, cPrimary, cOnPrimary);
    }

    // M3 Tonal ボタン(container/onContainer 指定)。
    private Button tonalButton(String label, int container, int onContainer) {
        return styledButton(label, container, onContainer);
    }

    private Button styledButton(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable content = new GradientDrawable();
        content.setColor(bg);
        content.setCornerRadius(dp(20));  // M3 の丸みの強いボタン
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(withAlpha(fg, 0x33)), content, null);
        b.setBackground(ripple);
        b.setStateListAnimator(null);     // 既定の影アニメを消してフラットに
        b.setElevation(0f);
        b.setMinHeight(dp(52));
        b.setPadding(dp(20), dp(12), dp(20), dp(12));
        b.setOnClickListener(this);
        return b;
    }

    // M3 の無効表示: 38% 透過(コンテナ/テキストとも)。
    private void setEnabledM3(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.38f);
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
