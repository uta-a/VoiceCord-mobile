#!/usr/bin/env bash
# 端末にインストール済みの Discord APK 一式(base + split)を取り出す。
# 移植元の考え方: ../VoiceCord は Windows の modules フォルダから .node を直接読んでいたが、
# Android では端末から pull する。バージョンは記録用に必ず控えること。
set -euo pipefail

PKG="${1:-com.discord}"
OUT="${2:-apk}"
mkdir -p "$OUT"

echo "[*] $PKG のバージョン:"
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | head -2 || true

echo "[*] APK パスを取得:"
paths=$(adb shell pm path "$PKG" | sed 's/package://' | tr -d '\r')
if [ -z "$paths" ]; then
  echo "[!] $PKG が見つからない。パッケージ名を確認すること。" >&2
  exit 1
fi

for p in $paths; do
  name=$(basename "$p")
  echo "  pull $p -> $OUT/$name"
  adb pull "$p" "$OUT/$name"
done
echo "[+] 完了。次は tools/extract_libs.sh で lib/arm64-v8a/*.so を取り出す。"
