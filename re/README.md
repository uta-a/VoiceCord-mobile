# フェーズ0 調査ツール

Android 版 Discord（arm64-v8a）の送信音声に任意 PCM を混ぜられるか、
どこにフックすればよいかを実機で確定するためのツール群。デスクトップ版
（`../VoiceCord`）の `re/` を Android/ELF 向けに移植したもの。

**着手前提**: クライアント改変は Discord 利用規約違反。必ずサブアカウント・
検証用サーバーで行う。ログ・生成物は調査後に削除する。

## 確定させたいこと

- 送信用エンコーダ（`opus_encode` 系）または Krisp NC がどの `.so` にあるか
- 呼び出し頻度（20ms=50/s か 10ms=100/s か）とフレーム長・チャンネル数
- どの引数が PCM か、int16 か float32 か
- ミュート中／VAD 無音時にフック関数が呼ばれ続けるか
- **注入したサイン波が別端末で聞こえるか（＝注入点として成立するか）**

## フック候補の優先順位

1. **`opus_encode` / `opus_encode_float`**（設計書 優先1）。Krisp も AGC も通過した
   後段なので、注入音の音量が後処理で変わらない。第一候補。
2. **`KrispNCProcess` / `KrispNCProcessFloat`**（デスクトップ版で実績あり）。第4引数が
   post-Krisp 出力。フォールバック。後段 AGC で音量が変わる懸念がある。

`--family opus` / `--family krisp` / `--family auto`（既定・両方試す）で切り替える。

## セットアップ

```bash
python -m venv .venv && . .venv/Scripts/activate   # Windows は .venv\Scripts\activate
pip install -r requirements.txt
```

## 手順

### 1. APK を取得して .so を展開（root 不要）

```bash
bash tools/pull_apk.sh com.discord apk        # base + split APK を pull
bash tools/extract_libs.sh apk out/libs       # lib/arm64-v8a/*.so を展開
```

### 2. 静的解析で候補を絞る（root 不要）

```bash
python re/elf_triage.py   out/libs             # 各 .so の構造・依存・シンボル数
python re/so_exports.py   out/libs             # opus_/krisp 系シンボルを一覧
python re/strings_grep.py out/libs             # シンボルが strip 済みでも文字列で絞る
```

シンボルが strip されていてバイトパターンが要る場合:

```bash
python re/so_exports.py out/libs --emit-pattern opus_encode >> signatures/patterns.json
# ↑ 出力を手で patterns.json の該当バージョンキー配下に貼る
```

### 3. frida-gadget を埋め込む（非root）

root 端末が無いので frida-server の代わりに **frida-gadget** を Discord APK に同梱する。
**第一候補は LSPatch**（本プロジェクトが本番でも使う前提ツールのため）。

- 手順 A（推奨・LSPatch）:
  1. frida-gadget（arm64）と listen 設定の gadget config を用意する。
  2. LSPatch で Discord にモジュールとして gadget を注入、または `-l 2`（署名バイパス）で
     パッチした APK に gadget の `.so` を同梱する。
  3. 公式版をアンインストール → パッチ版を `adb install-multiple`。
- 手順 B（代替・apktool）: apktool で APK を展開し `lib/arm64-v8a/` に `libgadget.so` を置き、
  いずれかの読み込み対象 `.so` に依存を足して再パック・再署名する。

> gadget の同梱方式は LSPatch のバージョンで変わりうる。`lspatch.jar --help` と
> frida-gadget のドキュメントで最新を確認すること。

### 4. 動的解析（gadget 接続）

```bash
adb forward tcp:27042 tcp:27042
python re/frida_find.py    --mode gadget --patterns signatures/patterns.json
python re/frida_cadence.py --mode gadget          # 発声/ミュートを切り替え calls/s を見る
python re/frida_probe.py   --mode gadget --show 4 # 引数レイアウト・型・サンプル数
```

root 端末が使える場合は `--mode server`（必要なら `--spawn`）に切り替えるだけ。

### 5. 注入して可否を判定（フェーズ0の核）

別端末（サブアカウント）で VC に参加した状態で:

```bash
python re/frida_inject.py --mode gadget --amp 0.25 --freq 440
```

**別端末で 440Hz が聞こえれば注入点として成立**。自分側では送信ストリームなので聞こえない。
`--family` を変えて opus / krisp のどちらが成立するか、音質・音量を比べる。

## ファイル一覧

| ファイル | 役割 | 移植元 |
| --- | --- | --- |
| `elf_triage.py` | ELF 構造の概観 | `VoiceCord/re/triage.py` |
| `so_exports.py` | シンボル走査・パターン生成 | `VoiceCord/re/krisp_exports.py` |
| `strings_grep.py` | 文字列からの絞り込み | `VoiceCord/re/strings_grep.py` |
| `frida_find.py` | 候補シンボルの解決確認 | `VoiceCord/re/frida_find.py` |
| `frida_cadence.py` | 呼び出し頻度計測 | `VoiceCord/re/frida_cadence.py` |
| `frida_probe.py` | 引数・バッファ観察 | `VoiceCord/re/frida_krisp.py` |
| `frida_inject.py` | サイン波注入 | `VoiceCord/re/frida_inject.py` |
| `_common.py` / `_resolve.js` | 接続・シンボル解決の共通部 | (新規) |
