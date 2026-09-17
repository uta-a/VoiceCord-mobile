#!/usr/bin/env bash
# pull した APK 群から lib/arm64-v8a/*.so を取り出す。
# split APK(config.arm64_v8a)側にネイティブライブラリが入っていることが多い。
set -euo pipefail

SRC="${1:-apk}"
OUT="${2:-out/libs}"
mkdir -p "$OUT"

found=0
for apk in "$SRC"/*.apk; do
  [ -e "$apk" ] || continue
  # unzip -Z1 で中身を見て arm64-v8a の .so だけ取り出す
  libs=$(unzip -Z1 "$apk" 2>/dev/null | grep "^lib/arm64-v8a/.*\.so$" || true)
  [ -z "$libs" ] && continue
  echo "[*] $apk から抽出:"
  for l in $libs; do
    echo "    $l"
    unzip -o -j "$apk" "$l" -d "$OUT" >/dev/null
    found=$((found+1))
  done
done

if [ "$found" -eq 0 ]; then
  echo "[!] arm64-v8a の .so が1つも無い。split APK が揃っているか確認すること。" >&2
  exit 1
fi
echo "[+] $found 個を $OUT に展開。次は re/so_exports.py と re/strings_grep.py を実行する。"
