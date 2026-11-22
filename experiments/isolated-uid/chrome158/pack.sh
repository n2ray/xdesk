#!/usr/bin/env bash
# Package pinned upstream files under APK-native names for installer-owned storage.
set -euo pipefail
inputs=$1 lib=$2 cc=$3
here=$(cd "$(dirname "$0")" && pwd)
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
cd "$inputs"
shasum -a 256 -c <<'HASHES'
e06dc16ee0150d9e2decd709dfa5c4d75c6958b6e359474d6eeadb484349129d  chrome.deb
68fbec4a0cf03f76274be9f3f999119ca2ba45c61dc65d64473afe1076f1fb09  nspr.deb
a20c803742476c8b08ae1389f1ffca486075eefe7463e3fa34640873156f931a  nss.deb
HASHES
bsdtar -xOf chrome.deb 'data.tar.xz*' | bsdtar -xf - -C "$stage" ./opt/google/chrome
for pkg in nspr nss; do
    bsdtar -xOf "$pkg.deb" data.tar.zst | bsdtar -xf - -C "$stage" './usr/lib/aarch64-linux-gnu/*.so'
done
index="$lib/libchrome158_index.so"
: > "$index"
i=0
pack() {
    local file=$1 name=$2 object=libchrome158_${i}.so
    cp "$file" "$lib/$object"
    printf '%s %s\n' "$object" "$name" >> "$index"
    i=$((i+1))
}
chrome="$stage/opt/google/chrome"
for name in chrome chrome-sandbox chrome_crashpad_handler icudtl.dat resources.pak \
    chrome_100_percent.pak chrome_200_percent.pak v8_context_snapshot.bin locales/en-US.pak; do
    pack "$chrome/$name" "$name"
done
for file in "$stage"/usr/lib/aarch64-linux-gnu/*.so; do pack "$file" "${file##*/}"; done
"$cc" --target=aarch64-linux-gnu -fuse-ld=lld -nostdlib -fPIC -shared \
    -Wall -Wextra -Werror "$here/browser-load.c" -o "$lib/libchrome158_observer.so"
cp "$here/entry.py" "$lib/libchrome158_entry.so"
cp "$here/dispatch.py" "$lib/libchrome158_dispatch.so"
pack "$here/start-zygote.py" start-zygote.py
