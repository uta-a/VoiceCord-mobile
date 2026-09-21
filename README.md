# VoiceCord-mobile

非 root で **Android 版 Discord の「送信音声」に任意の音源をミックス**し、同じ VC の相手に
聞かせるツール一式。LSPatch 埋め込みモジュール（ShadowHook で送信 Opus 入口をフック）＋
操作用コンパニオンアプリで構成する。デスクトップ版 VoiceCord の Android 移植。

> ⚠️ **利用範囲**: 自分が操作権限を持つアカウント・自分のサーバー・**同意のある相手**との検証にのみ
> 使うこと。同意なく他人の VC に音声を流すのは迷惑行為になり得る。クライアント改変は Discord
> 利用規約上推奨されない。**サブアカウント前提・自己責任**。無保証。

## しくみ（要点）

- 注入点: `libdiscord.so` の `WebRtcOpus_Encode`（送信 Opus 入口）を ShadowHook の
  **intercept(pre)** でフックし、第2引数の送信 PCM をデコード音と合成したコピーへ差し替える。
- 無音時は Discord 独自 VAD が送信を止めるため、再生中は `Connection` の送信デッドラインを
  上書きして送信を強制する。
- コンパニオンは別アプリ。**PIN ペアリング**で操作 token を受け取り、ブロードキャストで
  再生を指示する。端末内ファイルは **127.0.0.1 のループバック配信**で Discord 側へ渡す。

対応 Discord: **345.9（versionCode 345009）**。非対応版では native 側の版ズレガードが働き
**フックを張らない**（安全側＝無音動作）。別版対応は「新バージョン対応」を参照。

## 必要なもの

| 物 | 入手先 |
| --- | --- |
| `voicecordmod.apk`（埋め込みモジュール） | 本リポジトリの [Releases](https://github.com/uta-a/VoiceCord-mobile/releases) |
| `voicecordcompanion.apk`（操作アプリ） | 同上 Releases |
| **LSPatch**（JingMatrix fork） | https://github.com/JingMatrix/LSPatch/releases |
| 対象バージョンの Discord APK 一式 | 自分の端末から抽出（下記）／正規の入手元 |
| PC 側 | `adb`、JDK（`lspatch.jar` 実行用） |

> パッチ済み Discord 本体は配布しない（再配布不可）。各自が自分の Discord をパッチする。

## 導入手順（LSPatch 入手 → パッチ → 起動）

### 1. LSPatch を入手

- **CLI（推奨・再現性が高い）**: JingMatrix fork の Releases から `lspatch.jar` をダウンロード。
- **アプリ版（GUI）**: 同 Releases の `manager` APK を端末に入れてもよい（後述の代替手順）。

### 2. 対象 Discord の APK 一式を用意

自分の端末に入っている Discord（345.9）から抽出するのが確実:

```bash
adb shell pm path com.discord
# 出力の各 package:/... を pull する
adb pull /data/app/.../base.apk               ./apk/
adb pull /data/app/.../split_config.arm64_v8a.apk ./apk/
adb pull /data/app/.../split_config.ja.apk    ./apk/
adb pull /data/app/.../split_config.xxhdpi.apk ./apk/
```

### 3. 公式 Discord をアンインストール

LSPatch は再署名するため公式署名版と共存できない。

```bash
adb uninstall com.discord
```

### 4. LSPatch でモジュールを埋め込みパッチ（CLI）

`-l 2`（署名バイパス）で、`-m` に `voicecordmod.apk`、Discord の分割 APK をまとめて渡す:

```bash
java -jar lspatch.jar \
  apk/base.apk apk/split_config.arm64_v8a.apk apk/split_config.ja.apk apk/split_config.xxhdpi.apk \
  -m voicecordmod.apk -l 2 -d -f -o out_vc
```

- `-l 2`: 署名バイパスモード（埋め込み） / `-d`: debuggable / `-f`: 上書き / `-o`: 出力先。
- 生成物は `out_vc/*.apk`。

### 5. パッチ済み Discord とコンパニオンを導入

```bash
adb install-multiple -r out_vc/*.apk
adb install -r voicecordcompanion.apk
```

- **MIUI/HyperOS 注意**: 新規アプリの USB インストールが弾かれる場合
  （`INSTALL_FAILED_USER_RESTRICTED`）は、開発者オプションの「USB 経由でのインストール」を
  許可するか、端末のファイルマネージャで APK を開いて手動インストールする。

### 6. ペアリングして使う

1. Discord を起動（モジュールが読み込まれる）。
2. コンパニオンで「**ペアリング開始**」→ Discord 側に **PIN 通知**（60 秒で自動失効）。
3. PIN を入力して「**接続**」。成功で通知が消え、接続済みになる（token は端末内で受け渡し。
   他アプリには漏れない）。接続後はペアリング操作は無効化。失効時は「ペアリング解除」→再接続。
4. 使い方:
   - **音源リスト**: 「＋ 音源を追加」で端末内音源を選択（複数選択可・同一内容は自動重複除外）。
     項目を**タップで即再生**、**長押しで削除**。
   - **サウンドボード**: `sound_id`（数字）を入れて再生（CDN 取得）。
   - VC に参加中に再生すると、同じ VC の相手に聞こえる。

### 代替: LSPatch アプリ版（GUI）でパッチ

CLI が使えない場合は端末上の LSPatch Manager でも可:

1. LSPatch Manager を起動 →「+」→ Discord（345.9）を選択。
2. **署名バイパスモード**を選び、**モジュールに `voicecordmod.apk` を追加**してパッチ。
3. 出力されたパッチ済み APK をインストール（公式版は事前にアンインストール）。
4. 以降は上記 5〜6 と同じ。

## 新しい Discord バージョンへの対応（RVA 再導出）

版ズレガードにより非対応版ではフックしない。新版対応:

1. Ghidra で対象版の `libdiscord.so`(arm64) を解析。
2. アンカー `Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory`
   基準（ImageBase 0x100000）で各関数 RVA を再導出。
3. `signatures/rva.json` と `native/src/hook.cpp` の RVA 定数・`kExpectedVersionCode` を更新して再ビルド。
4. 詳細は `docs/FINDINGS.md`。

## ビルド（開発者向け）

前提: NDK + CMake + build-tools 34 + JDK。外部取得物（`.gitignore` 済み）:
`tools/dynamic/lspatch.jar`、`tools/dynamic/voicecordmod/lib/arm64-v8a/` の `libshadowhook.so` 等。

```bash
bash native/build_native.sh
cp native/build/libvoicecord.so tools/dynamic/voicecordmod/lib/arm64-v8a/
cd tools/dynamic && bash build_voicecordmod.sh   # → voicecordmod.apk
cd ../../app && bash build_companion.sh           # → app/voicecordcompanion.apk
```

host 単体テスト（実機不要）: SPSC リング / サウンドボード ID 関門 / PIN ペアリング /
localhost 取得ガードなど（各テストファイル冒頭のコマンド参照）。

## リポジトリ構成

```
native/            libvoicecord.so 本体(NDK/CMake) — hook.cpp / ring.h
app/               コンパニオン(別APK, res非依存のコード生成 + Material You)
tools/dynamic/     非root 導入ツールチェーン
  voicecordmod/    Xposed モジュール(Entry / CommandReceiver / PairingManager / *Fetcher)
re/                フェーズ0 の静的/動的解析ツール
signatures/rva.json  RVA/オフセットのバージョン別レジストリ
docs/              INSTALL.md（導入ガイド）/ FINDINGS.md（解析知見）
```

詳しい導入は `docs/INSTALL.md`、解析・設計知見は `docs/FINDINGS.md` を参照。
