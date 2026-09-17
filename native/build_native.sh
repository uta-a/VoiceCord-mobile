#!/usr/bin/env bash
# libvoicecord.so(arm64)を NDK + CMake でビルドする。Gradle 不要。
set -euo pipefail
cd "$(dirname "$0")"

NDK="${NDK:-/c/Users/utaaa/AppData/Local/Android/Sdk/ndk/27.2.12479018}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
BUILD=build

rm -rf "$BUILD"; mkdir -p "$BUILD"
cmake -S . -B "$BUILD" -G "Unix Makefiles" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DCMAKE_MAKE_PROGRAM="$NDK/prebuilt/windows-x86_64/bin/make.exe"
cmake --build "$BUILD" -j

echo "[+] 完了: native/$BUILD/libvoicecord.so"
ls -la "$BUILD"/libvoicecord.so
