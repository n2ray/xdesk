#!/usr/bin/env bash
# Build an unsigned feature split. Its package and version must match the installed base.
set -euo pipefail
src=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_HOME:?Set ANDROID_HOME}" "${ANDROID_NDK_HOME:?Use Android NDK r29 or newer}"
out=${1:?Usage: build-display.sh OUT VERSION_CODE}
version=${2:?Pass the installed XDesk versionCode}
[[ $version =~ ^[0-9]+$ ]]
mkdir -p "$out"
out=$(cd "$out" && pwd)
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/lib/arm64-v8a"
cc=("$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/clang)
bt="$ANDROID_HOME/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}"
"${cc[0]}" --target=aarch64-linux-android31 -O2 -std=c11 -shared -fPIC "$src/display.c" -Wl,-z,max-page-size=16384 -landroid -llog -ldl -o "$stage/lib/arm64-v8a/libcubeprobe.so"
"$bt/aapt2" link --manifest "$src/AndroidManifest.xml" --version-code "$version" --replace-version -I "$ANDROID_HOME/platforms/android-35/android.jar" -o "$stage/unsigned.apk"
(cd "$stage" && zip -0q unsigned.apk lib/arm64-v8a/libcubeprobe.so)
"$bt/zipalign" -f -P 16 4 "$stage/unsigned.apk" "$out/xdesk-glx-unsigned.apk"
