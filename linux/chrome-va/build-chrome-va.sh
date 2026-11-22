#!/bin/sh
# Build in a clean lfdevs checkout on ARM64 Ubuntu 26.04.
set -eu
if [ "$#" -ne 2 ]; then
    echo "usage: $0 MESA_CHECKOUT OUTPUT_DIRECTORY" >&2
    exit 2
fi
patches=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
dest=$(realpath -m "$2")
cd "$1"
base=6f90e92dfdacc4759d8e9ccb328805d0f0b4439b
[ "$(git rev-parse HEAD)" = "$base" ] || { echo "expected Mesa $base" >&2; exit 1; }
for patch in h264-defaults av1-original-obu av1-limit prime-vpp-wait; do
    if git apply --reverse --check "$patches/$patch.patch" 2>/dev/null; then
        continue
    fi
    git apply "$patches/$patch.patch"
done
meson setup out -Dplatforms=x11,wayland -Dgallium-drivers=freedreno,zink \
    -Dfreedreno-kmds=kgsl -Dgallium-va=enabled -Dtermux-va-bridge=enabled \
    -Dtermux-va-wayland-shim=enabled -Dvideo-codecs=all -Dvulkan-drivers= \
    -Dllvm=disabled -Dbuildtype=release -Dglx=dri -Degl=disabled \
    -Dgles1=disabled -Dgles2=disabled -Dopengl=true
driver=src/gallium/targets/dri/libgallium-27.0.0-devel.so
ninja -C out -j "${JOBS:-4}" "$driver"
mkdir -p "$dest"
install -s -m 755 "out/$driver" "$dest/termuxva_drv_video.so"
