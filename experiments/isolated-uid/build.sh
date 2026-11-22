#!/usr/bin/env bash
# Build the side-by-side XDesk experiment without rebuilding the desktop.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_SDK_ROOT:?set the Android SDK path}"
: "${ANDROID_NDK_ROOT:?set the Android NDK path}"
: "${JAVA_HOME:?use a JDK such as 21}"
if [[ $# != 2 && $# != 3 ]]; then
    echo 'usage: build.sh OUT BUSYBOX_STATIC_DEB | build.sh OUT DASH_DEB LIBC6_DEB | build.sh OUT --rootfs XDESK_TAR_GZ' >&2
    exit 1
fi
out=$1
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
mkdir -p "$lib" "$stage/classes" "$stage/dex" "$stage/generated/$pkg"
verify_input() {
    local actual
    actual=$(shasum -a 256 "$1" | cut -d ' ' -f 1)
    [[ "$actual" == "$2" ]] || { echo "Unexpected Ubuntu input digest: $1" >&2; exit 1; }
}
extract_elf() {
    ar -p "$1" data.tar.zst | bsdtar -xOf - "$2" > "$lib/$3"
}
dynamic=false
rootfs=false
if [[ $# == 3 && $2 == --rootfs ]]; then
    dynamic=true
    rootfs=true
    cp "$here/zygote-handshake.py" "$lib/libzygote_probe.so"
    cp "$here/socket-peer.py" "$lib/libsocket_peer.so"
    verify_input "$3" b2efc37b009681c24eb128c103264c808559ab8e3c3af8ea4adf4f62fcf3068e
    bash "$here/rootfs/build-image.sh" "$stage" "$lib" "$3" "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$host/bin/clang"
elif [[ $# == 3 ]]; then
    dynamic=true
    verify_input "$2" 55d2de41583e56ced56cdd0ed830704a936fd78a00435ad7ba142797546e8758
    verify_input "$3" 183cc011d1c93bc15778e02b4a8d9b0cbdbe29e0c0ea59a1cb80ccf5f5dcb157
    extract_elf "$2" ./usr/bin/dash libubuntu_dash.so
    extract_elf "$3" ./usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 libubuntu_ld.so
    extract_elf "$3" ./usr/lib/aarch64-linux-gnu/libc.so.6 libubuntu_libc.so
else
    verify_input "$2" fcf50794faf920455895d7ef103d1c54b9ebf7f18d2e52982dbf3903b7769592
    extract_elf "$2" ./usr/bin/busybox libubuntu_busybox.so
fi
printf 'package io.github.n2ray.xdesk.probe; class GuestBuild { static final boolean DYNAMIC = %s; static final boolean ROOTFS = %s; }\n' \
    "$dynamic" "$rootfs" > "$stage/generated/$pkg/GuestBuild.java"
if [[ $rootfs == false ]]; then
    cp "$here/../../lorie-app/src/main/jniLibs/arm64-v8a/libburrow.so" "$lib/"
fi
"$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android30-clang" \
    -shared -fPIC -O2 -Wall -Wextra -Werror "$here/runner.c" -o "$lib/libfdprobe.so"
"$bt/aidl" -p"$sdk/framework.aidl" -I"$here/src" -o"$stage/generated" "$here/src/$pkg/IProbe.aidl"
javac --release 8 -cp "$sdk/android.jar" -d "$stage/classes" \
    "$here/src/$pkg/"*.java "$stage/generated/$pkg/"*.java
jar cf "$stage/classes.jar" -C "$stage/classes" .
"$bt/d8" --min-api 30 --lib "$sdk/android.jar" --output "$stage/dex" "$stage/classes.jar"
"$bt/aapt2" link -I "$sdk/android.jar" --manifest "$here/AndroidManifest.xml" -o "$stage/base.apk"
cp "$stage/dex/classes.dex" "$stage/"
if [[ $rootfs == true ]]; then
    (cd "$stage" && zip -q -1 -r base.apk classes.dex lib)
else
    (cd "$stage" && zip -q -0 base.apk classes.dex lib/arm64-v8a/*.so)
fi
"$bt/zipalign" -P 16 -f 4 "$stage/base.apk" "$stage/aligned.apk"
if [[ ! -f "$out/probe.jks" ]]; then
    keytool -genkeypair -keystore "$out/probe.jks" -storepass android -keypass android \
        -alias probe -keyalg RSA -keysize 2048 -validity 365 -dname CN=XDeskProbe
fi
"$bt/apksigner" sign --ks "$out/probe.jks" --ks-pass pass:android \
    --out "$out/probe.apk" "$stage/aligned.apk"
"$bt/apksigner" verify "$out/probe.apk"
echo "$out/probe.apk"
