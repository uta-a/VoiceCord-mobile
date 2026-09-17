#!/usr/bin/env bash
# Discord を LSPatch でパッチし(gadgetloader 埋め込み)、端末にインストールする。
set -euo pipefail
cd "$(dirname "$0")"

APK_DIR="${APK_DIR:-../../apk}"
OUT="${OUT:-out_patched}"

[ -f lspatch.jar ] || { echo "lspatch.jar が無い。README 参照。" >&2; exit 1; }
[ -f gadgetloader.apk ] || { echo "gadgetloader.apk が無い。bash build_module.sh を先に実行。" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT"
echo "[*] LSPatch でパッチ(gadgetloader 埋め込み, -l 2 署名バイパス, -d debuggable)"
java -jar lspatch.jar \
  "$APK_DIR"/base.apk \
  "$APK_DIR"/split_config.arm64_v8a.apk \
  "$APK_DIR"/split_config.ja.apk \
  "$APK_DIR"/split_config.xxhdpi.apk \
  -m gadgetloader.apk -l 2 -d -f -o "$OUT"

echo "[*] インストール(-r 更新。署名変更で失敗する場合は先に adb uninstall com.discord)"
adb install-multiple -r "$OUT"/*.apk
echo "[+] 完了。Discord を起動し、logcat で gadget の Listening を確認する。"
