#!/usr/bin/env bash
# VoiceCord コンパニオン(別APK)を raw build-tools で組む。Gradle 不要。
# res は持たず UI はコード生成。framework リソース参照(アイコン)は -I android.jar で解決する。
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-21.0.12}"
BT="${BT:-/c/Users/utaaa/Android/sdk/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-/c/Users/utaaa/Android/sdk/platforms/android-34/android.jar}"
JC="$JAVA_HOME/bin/javac"
KT="$JAVA_HOME/bin/keytool"

BUILD=build
rm -rf "$BUILD"; mkdir -p "$BUILD/classes"

echo "[1/6] javac"
find src -name "*.java" > "$BUILD/sources.txt"
"$JC" --release 8 -cp "$(cygpath -m -a "$ANDROID_JAR")" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[2/6] d8(匿名クラス無しなので単純に dex 化)"
APP_CLASSES=$(find "$BUILD/classes/dev/uta/voicecordcompanion" -name "*.class")
"$BT/d8.bat" --min-api 24 --lib "$ANDROID_JAR" --classpath "$BUILD/classes" \
  --output "$BUILD" $APP_CLASSES

echo "[3/6] aapt2 link(manifest のみ。res 無し)"
"$BT/aapt2.exe" link -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 34 \
  -o "$BUILD/base.apk"

echo "[4/6] classes.dex を APK に追加"
cp "$BUILD/base.apk" "$BUILD/tmp.apk"
( cd "$BUILD" && "$JAVA_HOME/bin/jar" uf tmp.apk classes.dex )
mv "$BUILD/tmp.apk" "$BUILD/base.apk"

echo "[5/6] zipalign"
"$BT/zipalign.exe" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "[6/6] 署名(デバッグ鍵)"
KS="$BUILD/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KT" -genkeypair -keystore "$KS" -alias key0 -storepass 123456 -keypass 123456 \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=VoiceCord Companion Debug" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:123456 --key-pass pass:123456 \
  --out voicecordcompanion.apk "$BUILD/aligned.apk"

echo "[+] 完了: app/voicecordcompanion.apk"
"$BT/apksigner.bat" verify voicecordcompanion.apk >/dev/null 2>&1 && echo "署名OK" || echo "署名検証NG"
