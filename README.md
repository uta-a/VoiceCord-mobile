# VoiceCord-mobile

Android 版 Discord の送信音声に音声ファイルをミックスするツール（LSPatch方式）。

## 現在のフェーズ: フェーズ0（調査）

プロジェクト最大の不確実要素は **ネイティブのフック位置**。これを実機で確定するまで
UI・モジュール本体には着手しない。フェーズ0の調査ツールは `re/` にある。

- 手順とツールの説明: **[re/README.md](re/README.md)**
- 解析ログ: `docs/ANALYSIS-android.md`
- 結論（記入待ち）: `docs/FINDINGS.md`

## セットアップ

```bash
python -m venv .venv
. .venv/Scripts/activate      # Windows は .venv\Scripts\activate
pip install -r requirements.txt
```

## 構成

```
re/                調査ツール（../VoiceCord/re を Android 向けに移植）
tools/             APK 取得・lib 展開スクリプト
signatures/        Discord バージョン別バイトパターン
docs/              解析ログと結論
```

## 注意

クライアント改変は Discord 利用規約違反。**サブアカウント・検証用サーバーでのみ**行うこと。
ログ・スクリーンショット等の一時生成物は調査後に削除する。
