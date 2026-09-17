# フェーズ0 結論（2026-09-18 時点）

対象: com.discord 345.9 Stable / arm64-v8a / 非root / Android(targetSdk36) / Xiaomi(MIUI)

## 確定した注入点（静的解析＋システム音声ログで確定）

| 項目 | 値 | 根拠 |
| --- | --- | --- |
| ライブラリ | `libkrisp_wrapper.so`（独立 .so、クリーンな C API） | 静的: エクスポート表 |
| 注入関数(第一候補) | `krispAudioNcCleanAmbientNoiseInt16` | 静的 + 下記フォーマット一致 |
| 〃(対抗) | `krispAudioNcWithStatsCleanAmbientNoiseInt16` | Discord は noise 統計を出すため |
| 引数(int16版) | x0=session, x1=in, x2=cnt, **x3=out(post-Krisp)**, x4=outcnt | Krisp SDK 標準 + 静的サイズ |
| フォーマット | int16 / 48000Hz / mono | `AudioRecord: fmt 1, sr 48000, ch 1`（logcat 実測） |
| フレーム長 | 480 サンプル = 10ms → **約100Hz** | `OpenSLESRecorder: frames per 10ms buffer: 480`（logcat 実測） |
| 静的 vaddr | Int16=0x8c064 / WithStatsInt16=0x8c630 / Float=0x8ca5c | 静的解析 |

int16 版が本命（Discord のキャプチャが PCM16 のため）。float 版はフォールバック。
opus は libdiscord.so に静的リンク・strip 済みで、Krisp 経路が圧倒的に有利（確定）。

## 非root 動的解析の到達点

- ✅ frida-gadget を LSPatch で Discord に埋込 → 非root で `Frida: Listening on 127.0.0.1:27042` 成功。
- ✅ frida 接続・libc 等のシンボル解決・モジュール監視は動作。
- ✅ アイドル時 attach は安全。VC 参加で libkrisp_wrapper.so が dlopen される（module observer で捕捉）。

## 判明した障害と対策（重要・フェーズ1へ引き継ぐ）

### 1. 音声中の frida attach はクラッシュ（SIGSEGV）
- 症状: VC 接続（AudioRecord 稼働）中に新規 attach するとネイティブクラッシュ。
- 対策: **アイドル時に attach し、module observer で krisp ロードを検知して in-process でフック**。
  重い列挙(`enumerateExports/Symbols`)は避ける（これもクラッシュ要因）。フック設置は
  ロード直後を避け 2〜2.5秒遅延させると安定。

### 3.【決定的】frida-gadget は隔離名前空間にロードされ dlsym が届かない
- LSPatch は gadget を `isolated ns clns-7` にロードする（logcat: `Load .../libgadget.so using isolated ns`）。
- そのため gadget の `dlopen("libkrisp_wrapper.so", RTLD_NOLOAD)` は常に NULL
  （Discord 本体の名前空間にある lib を隔離名前空間から解決できない）。
- module observer は krisp ロードを検知できるが、アドレス解決手段が全滅:
  findExportByName / getGlobalExportByName / enumerateExports(クラッシュ) / base+offset(幻address) / dlsym(名前空間) / maps(XOM匿名でコード領域が追えず)。
- **結論: この端末(Android XOM + APK埋込lib + 隔離ns)では frida-gadget 経由のライブ注入は不可。**
  ただしフェーズ1の本番方式（ShadowHook を Discord 自身の名前空間で動かす in-process 実装）は
  アプリのリンカで解決・inline hook するため、この問題は発生しない見込み。

### 2. APK 埋込 XOM ライブラリで frida の module.base が誤検出（幻のアドレス）
- 症状: `libkrisp_wrapper.so` は APK 内非圧縮(offset 0x1b98000)から直接 mmap されるが、
  frida の `module.base` が /proc/self/maps に存在しないアドレスを返し、
  `findExportByName`/`getGlobalExportByName`/`enumerateExports` が全滅、base+offset も access violation。
- **対策（確定した正攻法）: プロセス自身の `dlopen(soname, RTLD_NOLOAD)` + `dlsym(handle, name)` で
  実行時アドレスを取得する**（リンカの正規リゾルバを使うため frida のパーサ不具合を回避）。
  代替: `adb shell cat /proc/<pid>/maps` は読める → APK offset から実アドレスを算出可能。
- フェーズ1の実装（ShadowHook）は**プロセス内**でリンカ経由の解決を行うため、この frida 外部
  オブザーバ特有の問題は発生しない見込み。

## 残タスク（ライブ確認）

- [ ] dlsym 経由で実アドレスを取得しフック → 実際に呼ばれる関数（素/WithStats）を確定
- [ ] out=x3 にサイン波を加算 → **別端末で可聴か**（注入点の最終確定）
  - 実施には「アイドル attach を保持したまま実機で VC 参加」の同期が必要。
    UI 自動タップはフレッシュ起動時に不安定なため、手動参加と同時実行が確実。

## 補足

- LSPatch パッチ済み Discord は署名変更のみで正常動作（ログイン・VC 接続可）。MIUI の
  「USB経由でインストール」有効化が必要だった。
- gadget config は listen + on_load:resume（起動をブロックしない）。
