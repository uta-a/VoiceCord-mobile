#!/usr/bin/env bash
# voicecordmod Xposed モジュール(libvoicecord.so ローダー)を raw build-tools で組む。
# Gradle 不要。Xposed API はスタブでコンパイルし dex には含めない(実体は LSPatch が提供)。
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-21.0.12}"
BT="${BT:-/c/Users/utaaa/Android/sdk/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-/c/Users/utaaa/Android/sdk/platforms/android-34/android.jar}"
JC="$JAVA_HOME/bin/javac"
KT="$JAVA_HOME/bin/keytool"

SRC=voicecordmod
BUILD=voicecordmod/build
rm -rf "$BUILD"; mkdir -p "$BUILD/classes"

echo "[1/6] javac(スタブ+Entry+NativeBridge, ShadowHook は classpath 参照)"
find "$SRC/src" -name "*.java" > "$BUILD/sources.txt"
JAVAC_CP="$(cygpath -m -a "$ANDROID_JAR");$(cygpath -m -a "$SRC/shadowhook-classes.jar")"
"$JC" --release 8 -cp "$JAVAC_CP" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[2/6] d8(dev/uta/voicecord + ShadowHook Java classes を dex 化。Xposedスタブは classpath 解決のみ)"
APP_CLASSES=$(find "$BUILD/classes/dev/uta/voicecord" -name "*.class")
"$BT/d8.bat" --min-api 24 --lib "$ANDROID_JAR" --classpath "$BUILD/classes" \
  --output "$BUILD" $APP_CLASSES "$SRC/shadowhook-classes.jar"

echo "[3/6] aapt2 link(manifest + assets)"
"$BT/aapt2.exe" link -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  -A "$SRC/assets" \
  --min-sdk-version 24 --target-sdk-version 34 \
  -o "$BUILD/base.apk"

echo "[4/6] classes.dex と lib/arm64-v8a/*.so(非圧縮) を APK に追加"
cp "$BUILD/base.apk" "$BUILD/tmp.apk"
( cd "$BUILD" && "$JAVA_HOME/bin/jar" uf tmp.apk classes.dex )
# .so は STORED(非圧縮)で入れる(APK 直 mmap ロードのため)。jar の 0 オプション=無圧縮。
( cd "$SRC" && "$JAVA_HOME/bin/jar" u0f "../$BUILD/tmp.apk" lib )
mv "$BUILD/tmp.apk" "$BUILD/base.apk"

echo "[5/6] zipalign(-p で .so をページ境界に整列)"
"$BT/zipalign.exe" -f -p 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "[6/6] 署名(デバッグ鍵)"
KS="$BUILD/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KT" -genkeypair -keystore "$KS" -alias key0 -storepass 123456 -keypass 123456 \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=VoiceCord Debug" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:123456 --key-pass pass:123456 \
  --out voicecordmod.apk "$BUILD/aligned.apk"

echo "[+] 完了: tools/dynamic/voicecordmod.apk"
"$BT/apksigner.bat" verify voicecordmod.apk >/dev/null 2>&1 && echo "署名OK" || echo "署名検証NG"
