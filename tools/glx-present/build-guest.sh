#!/usr/bin/env bash
# Run on native arm64 Ubuntu 26.04 with Mesa build dependencies.
set -euo pipefail
src=$(cd "$(dirname "$0")" && pwd)
out=${1:?Usage: build-guest.sh OUT}
mkdir -p "$out"
out=$(cd "$out" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git -C "$work" init -q
git -C "$work" fetch -q --depth=1 https://github.com/n2ray/mesa-for-android-container.git 2118596185954c118db2eb42598972e2b56af672
git -C "$work" checkout -q --detach FETCH_HEAD
git -C "$work" apply "$src/mesa.patch"
meson setup "$work/out" "$work" -Dplatforms=x11,wayland -Dgallium-drivers=zink,freedreno -Dfreedreno-kmds=kgsl -Dvulkan-drivers= -Dgallium-va=disabled -Dllvm=disabled -Degl=enabled -Dglx=dri -Dglvnd=enabled -Dbuildtype=release
target=src/gallium/targets/dri/libgallium-27.0.0-devel.so
ninja -C "$work/out" "$target"
install -s -m755 "$work/out/$target" "$out/"
cc -shared -fPIC -O2 "$src/geometry.c" -o "$out/geometry-only.so" -lX11 -ldl
