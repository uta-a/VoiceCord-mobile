# Android 版 discord ネイティブ RE — 解析ログ

デスクトップ版 `../VoiceCord/ANALYSIS.md` の Android 版。**根拠と未確認範囲を分けて残す**。

## 対象

- パッケージ: `com.discord`（arm64-v8a、非root）
- バージョン: **345.9 - Stable**（versionCode=345009、minSdk=24 / targetSdk=36）
- 取得日: 2026-09-17
- 端末: arm64-v8a、user ビルド、**非root**（su/Magisk なし、Shizuku は ADB モード）

## デスクトップ版からの引き継ぎ（確定・再調査不要）

- 送信音声はネイティブ完結（マイク→Krisp→Opus→RTP）。他人へ届く唯一の経路は送信 RTP。
- サウンドボード送信はサーバ側 API。クライアントに送信注入 API は非公開。
- デスクトップの確定注入点: `KrispNCProcessFloat`/`KrispNCProcess` の out 引数＝post-Krisp、
  48kHz mono、480サンプル/frame、約100Hz 連続。

## 静的解析の結果（実測）

### ライブラリ構成（`out/libs/`、43 個の .so）

- **`libkrisp_wrapper.so`**（Krisp が独立 .so。C++ シンボル 2898 個、`.dynsym` のみ）
- **`libdiscord.so`**（ボイスエンジン本体。757 シンボル、`.dynsym` のみ）
- `libreactnative.so` / `libhermesvm.so` ほか RN 系（音声とは無関係）

### 【確定】Krisp は独立 .so でクリーンな C API を持つ — `libkrisp_wrapper.so`

デスクトップ（`discord_krisp.node`）と同様、Krisp は独立していて、しかも
**非マングルの C API がそのままエクスポート**されている（strip されていない）。

注入点の第一候補（NC clean 系、int16/float 両方あり）:

| シンボル | 用途 |
| --- | --- |
| `krispAudioNcWithStatsCleanAmbientNoiseInt16` / `...Float` | NC + 統計。**本命**（Discord は noise 統計を表示するため） |
| `krispAudioNcCleanAmbientNoiseInt16` / `...Float` | NC のみ |
| `krispAudioNc(WithStats)CleanAmbientNoiseWithRingtone*` | 着信音考慮版 |
| `KrispNCProcess` / `KrispNCProcessFloat` | デスクトップ互換の高レベル thunk（`b 0x163400`） |

標準 Krisp SDK の signature（実測の引数位置と一致）:
```
int krispAudioNcCleanAmbientNoiseInt16(
    session,               // x0
    const short* pFrameIn, // x1
    unsigned  frameInSize, // x2 (= サンプル数, 48k/10ms なら 480)
    short*    pFrameOut,   // x3  ← post-Krisp 出力。ここに加算する
    unsigned  frameOutSize)// x4
```
→ **注入は out=args[3] へ加算**。デスクトップと同じ考え方で移植できる。

関連 C API: `krispAudioGlobalInit/Destroy`、`krispAudioNc(WithStats)CreateSession/CloseSession`、
`krispAudioNcWithStatsRetrieveStats`、VAD 系 `KrispVADProcess`/`krispAudioNoiseDbFrame*`、
`krispAudioGetFrameEnergy*`（VAD/エネルギー判定に使える）。

### 【確定】Opus/WebRTC は libdiscord.so に静的リンク・strip 済み

- 独立 `libopus.so` は無い。libdiscord.so に **opus シンボルは1つも無い**（webrtc ごと静的リンク）。
- ボイスは WebRTC 経由（`Java_org_webrtc_*` JNI 多数）＋ `com.discord.native.engine.NativeEngine`
  （`createVoiceConnection`、`setOnVoiceCallback`、`setVoiceProcessingErrorCallback`）。
- `opus_encode` を狙うにはパターンスキャンが必須で難度が高い。
  → **Android では Krisp 経路が圧倒的に有利**（クリーンなエクスポートあり）。

### libkrisp_wrapper.so の呼ばれ方

- どの .so も `libkrisp_wrapper.so` を DT_NEEDED に持たない
  → libdiscord.so が `dlopen`/`dlsym` で動的にロードして呼ぶ形と推定（要動的確認）。
  フックはエクスポート名で解決できるため問題にならない。

## 動的解析（未実施 — 非root のため frida-gadget 埋め込みが必要）

frida-server は root 前提で使えない。次段では frida-gadget を LSPatch で Discord に
同梱して観測する（Discord の再パック・再署名・再インストールを伴う）。

- [ ] Discord が実際に呼ぶ Krisp 関数はどれか（WithStats 版か素の版か、int16 か float か）
- [ ] 呼び出し頻度（480サンプル/10ms=100Hz を想定）とフレーム長・サンプルレート
- [ ] ミュート／VAD 無音時に呼ばれ続けるか
- [ ] out=args[3] にサイン波を加算 → 別端末で可聴か（注入点の最終確定）
