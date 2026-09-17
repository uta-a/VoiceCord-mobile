# 動的解析ツールチェーン（非root / frida-gadget 埋め込み）

root なし端末で Discord に frida を注入するため、frida-gadget をロードする最小 Xposed
モジュールを作り、LSPatch で Discord に埋め込む。これはフェーズ1の本番モジュール骨格も兼ねる。

## 構成

- `gadgetloader/` … frida-gadget ローダー Xposed モジュールのソース
  - `src/dev/uta/gadgetloader/Entry.java` … `handleLoadPackage` で gadget を cache へ展開し `System.load`
  - `src/de/robv/...` … Xposed API スタブ（コンパイル専用。dex には含めない）
  - `assets/libgadget.config.so` … gadget 設定（listen + on_load:resume で起動をブロックしない）
  - `assets/xposed_init` … エントリクラス登録
- `build_module.sh` … Gradle 不要のビルド（javac → d8 → aapt2 → zipalign → apksigner）
- `patch.sh` … Discord を LSPatch でパッチしてインストールするラッパー

## 前提（自動取得しないもの）

以下は各自で取得して `tools/dynamic/` に置く（.gitignore 済み・大容量/外部）:
- `lspatch.jar` … https://github.com/JingMatrix/LSPatch/releases
- `libgadget.so` … https://github.com/frida/frida/releases の
  `frida-gadget-<ver>-android-arm64.so.xz` を解凍。host の frida とバージョンを合わせる。

ツールパス（環境変数で上書き可）: JDK21 `JAVA_HOME`、build-tools `BT`、`ANDROID_JAR`。

## 手順

```bash
cp libgadget.so gadgetloader/assets/libgadget.so   # 取得した gadget を配置
bash build_module.sh                                # gadgetloader.apk を生成
bash patch.sh                                       # Discord をパッチ+インストール
# Discord を起動 → logcat に "Frida : Listening on 127.0.0.1 TCP port 27042"
adb forward tcp:27042 tcp:27042
python ../../re/frida_find.py --mode remote --host 127.0.0.1:27042
```

## 注意

- libkrisp_wrapper.so は VC 参加時に dlopen される遅延ロード。**通話に入るまで Krisp
  シンボルは現れない**。cadence/probe/inject は VC 参加中に実行すること。
- gadget は on_load:resume なので起動をブロックしない。listen が要らなくなったら
  パッチ前の公式 APK（`apk/`）を入れ直せば元に戻る。
- 検証はサブアカウント・検証サーバーのみ（利用規約）。
