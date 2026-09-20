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

---

# フェーズ1 実機テストで判明した重大事項（2026-09-18）

## post-Krisp 注入は再パック版 Discord では発火しない（Krisp が起動しない）

ShadowHook 基盤は完璧に動作（init=0、krispAudioNcCleanAmbientNoiseInt16 / WithStats へ予約フック登録、
VC 参加で krisp ロード検知）。**しかし clean 関数が一度も呼ばれない**。原因:

- logcat: `PlayCore AssetPackServiceImpl: startDownload([krisp])` → `Finsky: startDownload() for com.discord`
  → **`AssetPackServiceImpl: onError(-15)`**（-15 = **APP_NOT_OWNED**）→ `DiscordKrisp: Failed to load asset`
  → `noise_canceller.cpp: Failed to load Krisp` → libkrisp_wrapper.so が即アンロード。
- Krisp モデルは **Play Asset Delivery のオンデマンド配信**。LSPatch で再署名した Discord は
  「Play で取得したアプリ」でないため、Play がアセットパックの配信を拒否する。
- 手動配置も不可: Play Core の AssetPackManager は所有権＋自前セッション状態で可用性を判定するため、
  モデル file を置いても `getPackLocation()` が有効を返さない（調査で確認、確度高）。

→ **再パック(LSPatch)版では Krisp NC が構造的に起動しない。post-Krisp 注入は成立しない。**

## 方針転換: Opus エンコーダ入口へ注入（設計書 優先1、ユーザー承認済み）

送信経路は Krisp の有無に関わらず常に通る（capture→APM→**opus encode**→RTP）。Krisp 非依存の
`WebRtcOpus_Encode`（送信 PCM = 第2引数 int16）または `opus_encode` に注入点を移す。

- opus は libdiscord.so に static リンク・strip 済み → 名前解決不可。**RVA（base+offset）でフック**。
- 実行時 base: エクスポート済み JNI シンボル
  `Java_org_webrtc_BuiltinAudioEncoderFactoryFactory_nativeCreateBuiltinAudioEncoderFactory`
  （dynsym RVA 0x9d39ec）から算出 → `base = 実行時addr − 0x9d39ec`、以降 `base + <opus関数RVA>`。
- RVA は Ghidra の文字列 xref から導出し、Discord バージョン別に `signatures/` で管理。
- ShadowHook 基盤（voicecordmod / native/src/hook.cpp）はフック対象を差し替えるだけで流用可。

### この方式の利点
- 再パック版でも動く（Krisp/Play 非依存）。
- opus 入口は AGC 等も通過した最終段 → 注入音の音量が後処理で変わらない。

---

# フェーズ2 実装(2026-09-18): 命令プロトコル + デコード

adbブロードキャストで音声ファイルを指定→MediaCodecでデコード→48k/int16/mono化→
native SPSCリングバッファ→intercept pre で送信PCMに合成、を実装。実機で:
- `decode done total=191865 samples(48k)`(44.1k stereo mp3→48k mono、リサンプル+モノ化動作)
- token検証・CommandReceiver動的登録(RECEIVER_EXPORTED)・クラッシュなし・VC維持 を確認。
- 可聴確認(受信側で660Hz)は送信中(WebRtcOpus_Encoceが発火する=マイク送信中)に行う。
  リングは送信が起きて初めて消費される(state playing ビットが残る場合は送信していないだけ)。

## セキュリティギャップ(フェーズ3で解消予定・重要)
現状は **token 一致のみ** の最小検証で、CommandReceiver は exported。
- ローカルパスの音声を読む(任意ファイル読み出しの authority 制限なし)。
- 送信元パッケージの署名検証なし、content:// URI 権限委譲なし。
→ token を知る同一端末上のアプリ/adb 実行者が、Discord が読めるパスの音声を送信音声に
   混入できる。**フェーズ3のコンパニオンで FileProvider(content:// + grantUriPermission)、
   getSentFromPackage() + 署名一致、authority 検証を実装して閉じる。**

---

# サウンドボード / Nitro / OTA 調査(2026-09-18)

サウンドボード音源の取得可否と、Nitro ロック除去の可否を調査した。結論として、
**音源取得は無認証で可能・注入経路で再生可能(案B)**、一方で **Nitro ロック(UI)除去は
再パック非依存の手段が無く断念** した。

## 1. サウンドボード音源は CDN 無認証で取得可能

- URL: `https://cdn.discordapp.com/soundboard-sounds/<sound_id>`。形式は MP3 または Ogg
  (`Content-Type: audio/mpeg` / `audio/ogg`)。**認証不要(公開配信)**。
  - 出典: Discord 公式 Soundboard Docs
    (https://docs.discord.com/developers/resources/soundboard)。
  - 出典: 兄弟デスクトップ版 `VoiceCord-module/src/patcher/soundboardCache.ts` が
    無認証 https で取得成功しているコード。
- sound_id の入手元: REST `GET /guilds/{id}/soundboard-sounds`・
  `GET /soundboard-default-sounds`、Gateway `Voice Channel Effect Send` 等。
- 含意: 他サーバーのサウンドボード音でも sound_id さえ分かれば取得でき、**VoiceCord の
  注入経路で流せば Nitro ゲートを通さず再生できる**(`ACTION_PLAY_SB` として実装=案B)。
  MP3/Ogg(Opus/Vorbis)は現行 MediaExtractor+MediaCodec 経路でデコード可
  (Android 公式対応表)。

## 2. サウンドボードの Nitro ロックは Hermes(JS)層のみに存在

- ロック判定 `canUseSoundboardEverywhere` /
  `modules/soundboard/native/utils/useSoundboardSoundLock.tsx` / アップセル
  `PremiumUpsellActionSheet` は全て `assets/index.android.bundle`(Hermes バイトコード
  HBC v98)内の文字列として存在。
- `libdiscord.so`・その他 .so・dex・Java 層には
  soundboard/premiumType/canUseSoundboard/entitlement/nitro のゲートは**存在しない**
  (全 so/dex 走査で 0 ヒット)。
- 含意: **Xposed(Java フック)でも native フックでもロック除去は不可**。手段は HBC
  バイトコード改変 / Hermes VM フック / データ注入のいずれかで、いずれも高難度。

## 3. アプリは OTA バンドルを実行(APK 内バンドルではない)

- `BundleUpdater.xml` の `key_android_js_bundle` =
  `/data/user/0/com.discord/files/otas/<hash>/app/src/main/assets/index.android.bundle`
  (約 54.7MB, ota_version=345.9)を実行。
- 含意: **APK 内 index.android.bundle を改変しても無意味**(実行されない)。バンドル改変系は
  OTA バンドルを対象にし、かつ OTA 無効化・e_tag 整合が要り極めて脆い。

## 4. premiumType のクライアント側 override 単独では Nitro ロックは外れない(write-test 結果)

- `shared_prefs/CacheStore.xml` の Flux 永続ストア `OverridePremiumTypeStore`
  (`premiumTypeActual`)に `2`(Nitro)を書いて再起動する write-test を実施
  (force-stop→書換→再起動→UI 確認→null 復帰、可逆・実施済み)。
- 結果: **サウンドボード UI のロック(🔒バッジ・「Nitro を入手する」アップセル)は
  書換前後で変化なし**。`canUseSoundboardEverywhere` は `premiumTypeActual` を単独では
  参照していない。
- 未検証の残手段: `perksActual.activePerksBitmask` への perk 投入、Hermes 層
  `canUseSoundboardEverywhere` の直接改変。いずれも不確実 / 高難度(**未検証**)。
- 方針: **Nitro ロック除去は断念**。サウンドボード再生目的は案B(CDN 取得→注入)で
  達成済みのため、UI アンロックは不要と判断。

## 5. フェーズ3: コンパニオン(別APK)への token 受け渡し設計と認可モデル

- 目的: フェーズ2の平文 token(`cache/vc_token` 直書き)を廃止し、別APKコンパニオンへ
  操作 token を安全に渡す。`vc_token` ファイル出力は削除済み(唯一の漏洩点を除去)。
- 方式: **PIN ペアリング**。
  1. コンパニオン → `PAIR{step=request, reply_pkg=自身}` を `setPackage("com.discord")` で送信。
  2. モジュール(Discord プロセス)が 6 桁 PIN を生成し**通知**で表示(端末画面を見ている本人だけが
     読める=物理所持が人間側の認証点)。通知は channel `voicecord_pairing`・VISIBILITY_SECRET。
  3. 本人がコンパニオンへ PIN 入力 → `PAIR{step=confirm, pin, reply_pkg}` を **順序付き**
     ブロードキャストで送信。
  4. モジュールが PIN 検証(一致・未失効・reply_pkg 一致)に成功したら **順序付きの結果データ**で
     token を返す。→ token は送信元コンパニオンにしか戻らず、exported レシーバ不要で世に出ない。
  5. コンパニオンは token を自身の private `SharedPreferences` に保存。以降のコマンドに付与。
- 実機検証(pipa, 345.9): request→PIN 通知(例 791543)→誤 PIN は結果 data 無し(拒否)→
  正 PIN で `Broadcast completed: result=0, data="<token>"`。誤 token の PLAY_SB は
  「token 不一致で拒否」、ペアリング取得 token では PLAY_SB 受理→decode 完了。UI 経由でも
  起動→ペアリング→token 永続化→再生まで一連成立。
- ホストテスト(`PairingManagerTest`, 実機不要, 13 ケース ALL PASS): PIN 一致 / reply_pkg 束縛
  (別 pkg は拒否かつ pending 非消費) / 試行回数 `MAX_ATTEMPTS`(=5) 失効 / 成功後の使い切り /
  request スロットル。60 秒失効は時刻依存のため host では非対象(実機/設計で担保)。
- 残存リスク(MVP として受容):
  - `PAIR` は token 不要のブートストラップ。悪意アプリが request を連投して通知を出させる DoS →
    `REQUEST_THROTTLE_MS`(2s)で緩和。
  - PIN ブルートフォース → 6 桁 + 60 秒有効 + 5 回失敗で失効 + 同時 1 pending で緩和。
  - 攻撃者が reply_pkg に自分を指定し順序付き confirm で token を奪う経路 → PIN は画面通知に
    しか出ないため PIN 非知で不成立。加えて「身に覚えのないペアリング通知」が人間側の検知点。
  - コンパニオンの PLAY は端末内任意パス(`ACTION_PLAY`)も送れる(モジュール側の既存ギャップは
    不変)。sound_id 経路は数字 ID 限定で従来どおり。
