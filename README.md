# VoiceCord-mobile

Android 版 Discord（非root / LSPatch 方式）の**送信音声に任意の音声ファイルをミックス**するツール。
デスクトップ版 VoiceCord の Android 移植（LSPatch 方式）。

> ⚠️ クライアント改変は Discord 利用規約違反。**サブアカウント・検証用サーバーでのみ**使用すること。

## 現状（2026-09-18）

| フェーズ | 内容 | 状態 |
| --- | --- | --- |
| 0 | フック位置の調査 | ✅ 完了 |
| 1 | 最小モジュールで送信音声にテスト音を注入 | ✅ 完了（実機で別端末可聴確認） |
| 2 | 命令プロトコル＋デコードで任意音声を注入 | ✅ 実装完了（声出し時に実ファイル可聴。無音時のVAD対策は残） |
| 3 | コンパニオンUI（ライブラリ/フローティング/通知/タイル） | ⬜ 未着手 |
| 4 | 堅牢化（RVA版ズレ検出・thread_local化・埋め込みモード等） | ⬜ 未着手 |

## 到達点と要点

- **注入点**: `libdiscord.so` の `WebRtcOpus_Encode`（送信 Opus 入口、RVA 0x56b758 @345.9）。
  Krisp/Play アセット配信に依存しないため再パック版でも動く（設計書の優先1）。
- **手法**: ShadowHook の **intercept(pre)** で第2引数(送信PCM)を、デコード音を合成した
  書込可能コピーへ差し替える。hook+CALL_PREV はトランポリンで SIGBUS のため不可。
- **base 解決**: エクスポート JNI シンボル基準で実行時 base を逆算（`signatures/rva.json`）。
- **既知の残課題**:
  - 無音時は Discord 独自 VAD が送信をゲート（声を出すと全区間届く）。→ キャプチャキープアライブで対応予定。
  - 送信元検証はフェーズ2は token のみ。FileProvider/署名/authority はフェーズ3。
  - RVA は Discord バージョン依存。版ズレ検出・thread_local 化・uninstall はフェーズ4。

詳細な解析ログ・結論は `docs/ANALYSIS-android.md` / `docs/FINDINGS.md`。

## リポジトリ構成

```
native/            libvoicecord.so 本体(NDK/CMake, Gradle不要)
  src/hook.cpp     ShadowHook intercept + 送信PCMへの合成
  src/ring.h       SPSC リングバッファ(Ringクラス, host単体テスト可)
  test/ring_test.cpp  リングの host 単体テスト
tools/dynamic/     非root 導入ツールチェーン
  voicecordmod/    Xposedモジュール(Entry, NativeBridge, CommandReceiver)
  build_voicecordmod.sh / build_module.sh  raw build-tools ビルド
  shadowhook/      ShadowHook ヘッダ(.so/.aar は各自取得)
re/                フェーズ0 の静的/動的解析ツール(frida)
signatures/        RVA/バイトパターンのバージョン別レジストリ
docs/              解析ログ・結論
```

## ビルドと導入（非root）

前提: NDK 27 + CMake + build-tools 34 + JDK。外部取得物（.gitignore 済み）:
`tools/dynamic/lspatch.jar`（LSPatch）、`libshadowhook.so`（ShadowHook AAR から）、
`tools/dynamic/voicecordmod/lib/arm64-v8a/` に libshadowhook.so 等。

```bash
# 1. native ビルド
bash native/build_native.sh
cp native/build/libvoicecord.so tools/dynamic/voicecordmod/lib/arm64-v8a/

# 2. モジュール(APK)ビルド
bash tools/dynamic/build_voicecordmod.sh

# 3. Discord にパッチして導入(公式版はアンインストールが必要)
cd tools/dynamic
java -jar lspatch.jar ../../apk/*.apk -m voicecordmod.apk -l 2 -d -f -o out_vc
adb install-multiple -r out_vc/*.apk
```

## テスト（フェーズ2, 開発用）

```bash
# 起動後 token を取得
adb shell run-as com.discord cat /data/data/com.discord/cache/vc_token
# VC 参加後、音声ファイルを再生(送信音声にミックスされる)
adb shell am broadcast -a dev.uta.voicecord.PLAY -p com.discord \
  --es path /data/data/com.discord/cache/<file> --es token <token>
# 停止
adb shell am broadcast -a dev.uta.voicecord.STOP -p com.discord --es token <token>
```

## host 単体テスト（実機不要）

```bash
g++ -std=c++17 -O2 -pthread native/test/ring_test.cpp -I native/src -o ring_test && ./ring_test
```
