# VoiceCord-mobile 導入ガイド

Android 版 Discord の「送信音声」に任意の音源をミックスして、同じ VC の相手に聞かせる
非 root ツール一式（LSPatch 埋め込みモジュール + コンパニオンアプリ）の導入手順。

## 重要（利用範囲・免責）

- **自分が操作権限を持つアカウント・自分のサーバー・同意のある相手**とのテストにのみ使うこと。
  同意のない相手の VC に音声を流す行為は迷惑行為・ハラスメントになり得る。
- Discord の利用規約上、クライアント改変は推奨されない。**サブアカウント前提**で、
  メインアカウントの利用は自己責任。
- 本リポジトリと配布物は無保証。使用によるいかなる不利益も利用者の責任。

## 構成物（Release 添付物）

| ファイル | 役割 |
| --- | --- |
| `voicecordmod.apk` | LSPatch 埋め込み用 Xposed モジュール（`libvoicecord.so` + ShadowHook 同梱） |
| `voicecordcompanion.apk` | 操作用コンパニオン（別アプリ。ペアリング・再生・音源リスト） |

※ **パッチ済み Discord 本体は配布しない**（再配布不可）。各自が自分の Discord APK を用意して
パッチする。

## 前提

- arm64-v8a の Android 端末（Android 12+ 推奨。ダイナミックカラー対応）。
- **対応 Discord バージョン: 345.9（versionCode 345009）**。
  それ以外の版では native 側の「版ズレガード」が働き**フックを張らない**（安全側に倒す＝無音動作）。
  別版で使うには RVA 再導出が必要（後述）。
- PC 側: `adb`、Java、Android SDK build-tools、LSPatch（JingMatrix fork の `lspatch.jar`）。

## 手順

### 1. 公式 Discord をアンインストール
LSPatch は再署名するため、公式署名版と共存できない。

```
adb uninstall com.discord
```

### 2. Discord APK 一式を用意
自分の端末/正規の入手元から、対象バージョン(345.9)の分割 APK を用意する:
`base.apk` / `split_config.arm64_v8a.apk` / `split_config.ja.apk` / `split_config.xxhdpi.apk`。

### 3. LSPatch でモジュールを埋め込みパッチ
`-l 2`（署名バイパス）、`-m` にモジュール、複数 split をまとめて渡す:

```
java -jar lspatch.jar \
  base.apk split_config.arm64_v8a.apk split_config.ja.apk split_config.xxhdpi.apk \
  -m voicecordmod.apk -l 2 -d -f -o out_vc
```

### 4. パッチ済み Discord を導入

```
adb install-multiple -r out_vc/*.apk
```

### 5. コンパニオンを導入

```
adb install -r voicecordcompanion.apk
```

- **MIUI/HyperOS の注意**: 新規アプリの USB インストールが弾かれる場合（`INSTALL_FAILED_USER_RESTRICTED`）は、
  開発者オプションの「USB 経由でのインストール」を許可するか、端末のファイルマネージャで
  APK を開いて手動インストールする。

### 6. ペアリング（token の受け渡し）
1. Discord を起動（モジュールが読み込まれる）。
2. コンパニオンで「**ペアリング開始**」→ Discord 側に **PIN 通知**（60 秒有効）。
3. PIN をコンパニオンに入力して「**接続**」。成功すると通知が消え、接続済みになる。
   - token は順序付きブロードキャストの結果でのみ返るため、他アプリには漏れない。
   - 接続後はペアリング操作は無効化される。token が失効したら「ペアリング解除」→ 再ペアリング。

### 7. 使い方
- **音源リスト**: 「＋ 音源を追加」で端末内の音源を選択（複数選択可）。同一内容は自動で重複除外。
  リストの項目を**タップで即再生**、**長押しで削除**。
- **サウンドボード**: `sound_id`（数字）を入れて再生。CDN から取得して注入する。
- VC に参加している状態で再生すると、同じ VC の相手に聞こえる。

## 新しい Discord バージョンへの対応（RVA 再導出）

版ズレガードにより非対応版ではフックしない。新版対応の手順:

1. Ghidra で対象版の `libdiscord.so`(arm64) を解析。
2. アンカー `Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory`
   基準（ImageBase 0x100000）で各関数の RVA を再導出。
3. `signatures/rva.json` を更新し、`native/src/hook.cpp` の RVA 定数と
   `kExpectedVersionCode` を対象版に合わせて更新 → 再ビルド。
4. 詳細は `docs/FINDINGS.md`（フック点・オフセット・調査知見）を参照。

## ビルド（開発者向け）

```
bash native/build_native.sh
cp native/build/libvoicecord.so tools/dynamic/voicecordmod/lib/arm64-v8a/
cd tools/dynamic && bash build_voicecordmod.sh          # → voicecordmod.apk
cd app && bash build_companion.sh                        # → app/voicecordcompanion.apk
```
