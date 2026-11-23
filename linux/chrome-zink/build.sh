#!/bin/sh
# Build private DSOs only for the arm64 Chrome launcher.
set -eu
src=$(dirname "$0")
out=$1
mkdir -p "$out/gl" "$out/va"
compile() { c++ -std=c++17 -O2 -Wall -Wextra -fPIC -shared -Wl,-z,defs -Wl,-z,relro,-z,now "$@"; }
compile "$src/dri3.cc" -lxcb -ldl -pthread -Wl,-soname,libxdesk-dri3.so -o "$out/libxdesk-dri3.so"
compile "$src/va.cc" -L"$out" -lxdesk-dri3 -ldl -Wl,-rpath,'$ORIGIN/..' -o "$out/va/termuxva_drv_video.so"
# Forward the rest of libGL's ABI to the distribution DSO. An absolute NEEDED
# entry selects it even though this Chrome-private facade is named libGL.so.1.
stub=$(mktemp -d)
trap 'rm -rf "$stub"' EXIT
compile -x c++ /dev/null -Wl,-soname,/usr/lib/aarch64-linux-gnu/libGL.so.1 -o "$stub/libforward.so"
compile "$src/glx.cc" -L"$stub" -Wl,--no-as-needed -lforward -lXrandr -lX11 -L"$out" -lxdesk-dri3 \
    -ldl -pthread -Wl,-Bsymbolic-functions -Wl,-rpath,'$ORIGIN/..' -o "$out/gl/libGL.so.1"
