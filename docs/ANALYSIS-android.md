# Android 版 discord ネイティブ RE — 解析ログ

デスクトップ版 `../VoiceCord/ANALYSIS.md` の Android 版。フェーズ0の調査結果を
ここに追記していく。**根拠と未確認範囲を必ず残すこと**（推測と実測を分ける）。

## 対象

- パッケージ: `com.discord`（arm64-v8a、非root）
- バージョン: __（`tools/pull_apk.sh` 実行時に記録）__
- 取得日: __

## デスクトップ版からの引き継ぎ（確定事項・再調査不要）

- 送信音声はネイティブ完結（マイク→Krisp→Opus→RTP）。上位レイヤに PCM は出ない。
- 他人へ任意音声を届ける唯一の経路は送信 RTP ストリーム。
- サウンドボード送信はサーバ側 API。クライアントに送信注入 API は非公開。
- ローカル再生系（`StartSamplesLocalPlayback` 相当）は押下者にしか聞こえず送信されない。
- デスクトップの確定注入点: `KrispNCProcessFloat`(float32) / `KrispNCProcess`(int16) の
  第4引数=out=post-Krisp、第3引数=cnt=480、48kHz mono、約100Hz 連続。

## Android で未確認（フェーズ0で潰す）

- [ ] Krisp は独立 `.so` か、`libdiscord_voice`/WebRTC 等に内蔵か。エクスポート名は残るか。
- [ ] 送信用 Opus エンコーダはどの `.so` にあるか。`opus_encode` シンボルは残るか。
- [ ] 音声は別プロセス（`:voice` 等）か同一プロセスか（frida のアタッチ先）。
- [ ] 呼び出し頻度（20ms=50/s か 10ms=100/s か）とフレーム長・チャンネル数。
- [ ] ミュート／VAD 無音時にフック関数が呼ばれ続けるか。
- [ ] 注入したサイン波が別端末で聞こえるか。opus / krisp どちらが成立するか。
- [ ] 非root で frida-gadget によるネイティブフックが成立するか。

## 静的解析ログ

（`elf_triage.py` / `so_exports.py` / `strings_grep.py` の結果を貼る）

## 動的解析ログ

（`frida_find.py` / `frida_cadence.py` / `frida_probe.py` の結果を貼る）

## 注入テスト結果

（`frida_inject.py` の結果と、別端末での可聴判定を貼る）
