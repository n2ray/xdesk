#!/usr/bin/env bash
# Build the side-by-side XDesk experiment without rebuilding the desktop.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_SDK_ROOT:?set the Android SDK path}"
: "${ANDROID_NDK_ROOT:?set the Android NDK path}"
: "${JAVA_HOME:?use a JDK such as 21}"
out=${1:?usage: bash build.sh OUTPUT_DIRECTORY BUSYBOX_STATIC_DEB}
deb=${2:?supply the Ubuntu resolute arm64 busybox-static deb}
mkdir -p "$out"
out=$(cd "$out" && pwd)
sdk="$ANDROID_SDK_ROOT/platforms/android-35"
bt="$ANDROID_SDK_ROOT/build-tools/35.0.0"
case "$(uname -s)" in
    Darwin) host=darwin-x86_64 ;;
    Linux) host=linux-x86_64 ;;
    *) exit 1 ;;
esac
export PATH="$JAVA_HOME/bin:$PATH"
pkg=io/github/n2ray/xdesk/probe
stage=$(mktemp -d "$out/build.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
lib="$stage/lib/arm64-v8a"
mkdir -p "$lib" "$stage/classes" "$stage/dex" "$stage/generated"
expected=fcf50794faf920455895d7ef103d1c54b9ebf7f18d2e52982dbf3903b7769592
actual=$(shasum -a 256 "$deb" | cut -d ' ' -f 1)
[[ "$actual" == "$expected" ]] || { echo 'Wrong Ubuntu busybox-static package' >&2; exit 1; }
ar -p "$deb" data.tar.zst | bsdtar -xOf - ./usr/bin/busybox > "$lib/libubuntu_busybox.so"
cp "$here/../../lorie-app/src/main/jniLibs/arm64-v8a/libburrow.so" "$lib/"
"$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android30-clang" \
    -shared -fPIC -O2 -Wall -Wextra -Werror "$here/runner.c" -o "$lib/libfdprobe.so"
"$bt/aidl" -p"$sdk/framework.aidl" -I"$here/src" -o"$stage/generated" "$here/src/$pkg/IProbe.aidl"
javac --release 8 -cp "$sdk/android.jar" -d "$stage/classes" \
    "$here/src/$pkg/"*.java "$stage/generated/$pkg/IProbe.java"
jar cf "$stage/classes.jar" -C "$stage/classes" .
"$bt/d8" --min-api 30 --lib "$sdk/android.jar" --output "$stage/dex" "$stage/classes.jar"
"$bt/aapt2" link -I "$sdk/android.jar" --manifest "$here/AndroidManifest.xml" -o "$stage/base.apk"
cp "$stage/dex/classes.dex" "$stage/"
(cd "$stage" && zip -q -0 base.apk classes.dex lib/arm64-v8a/*.so)
"$bt/zipalign" -P 16 -f 4 "$stage/base.apk" "$stage/aligned.apk"
if [[ ! -f "$out/probe.jks" ]]; then
    keytool -genkeypair -keystore "$out/probe.jks" -storepass android -keypass android \
        -alias probe -keyalg RSA -keysize 2048 -validity 365 -dname CN=XDeskProbe
fi
"$bt/apksigner" sign --ks "$out/probe.jks" --ks-pass pass:android \
    --out "$out/probe.apk" "$stage/aligned.apk"
"$bt/apksigner" verify "$out/probe.apk"
echo "$out/probe.apk"
