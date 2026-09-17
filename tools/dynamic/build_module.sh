#!/usr/bin/env bash
# gadgetloader Xposed モジュール(frida-gadget ローダー)を raw build-tools で組む。
# Gradle 不要。Xposed API はスタブでコンパイルし dex には含めない(実体は LSPatch が提供)。
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-21.0.12}"
BT="${BT:-/c/Users/utaaa/Android/sdk/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-/c/Users/utaaa/Android/sdk/platforms/android-34/android.jar}"
JC="$JAVA_HOME/bin/javac"
KT="$JAVA_HOME/bin/keytool"

SRC=gadgetloader
BUILD=gadgetloader/build
rm -rf "$BUILD"; mkdir -p "$BUILD/classes"

echo "[1/6] javac(スタブ+Entry)"
find "$SRC/src" -name "*.java" > "$BUILD/sources.txt"
"$JC" --release 8 -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[2/6] d8(Entry のみ dex 化。スタブは classpath 解決のみ)"
ENTRY_CLASSES=$(find "$BUILD/classes/dev/uta/gadgetloader" -name "*.class")
"$BT/d8.bat" --min-api 24 --lib "$ANDROID_JAR" --classpath "$BUILD/classes" \
  --output "$BUILD" $ENTRY_CLASSES
# d8 は $BUILD/classes.dex を出力

echo "[3/6] aapt2 link(manifest + assets)"
"$BT/aapt2.exe" link -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  -A "$SRC/assets" \
  --min-sdk-version 24 --target-sdk-version 34 \
  -o "$BUILD/base.apk"

echo "[4/6] classes.dex を APK に追加"
( cd "$BUILD" && "$JAVA_HOME/bin/jar" uf base.apk classes.dex )

echo "[5/6] zipalign"
"$BT/zipalign.exe" -f -p 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "[6/6] 署名(デバッグ鍵を自動生成)"
KS="$BUILD/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KT" -genkeypair -keystore "$KS" -alias key0 -storepass 123456 -keypass 123456 \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=VoiceCord Debug" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:123456 --key-pass pass:123456 \
  --out gadgetloader.apk "$BUILD/aligned.apk"

echo "[+] 完了: tools/dynamic/gadgetloader.apk"
"$BT/apksigner.bat" verify --print-certs gadgetloader.apk | head -3 || true
