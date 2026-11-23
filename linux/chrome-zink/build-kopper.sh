#!/bin/sh
# Build the Chrome-private Gallium library against the same Ubuntu arm64 ABI as the image.
set -eu
revision=2118596185954c118db2eb42598972e2b56af672
out=$1
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# Use a private apt source list for build-dep. The rootfs builder removes the
# packages installed by the build before exporting the system.
sed 's/^Types:.*/Types: deb deb-src/' /etc/apt/sources.list.d/ubuntu.sources > "$work/ubuntu.sources"
# apt's unprivileged downloader needs to traverse this directory.
chmod 755 "$work"
buildapt() {
    apt-get -o APT::Get::List-Cleanup=false -o Dir::Etc::sourcelist="$work/ubuntu.sources" -o Dir::Etc::sourceparts=- "$@"
}
buildapt update -q
buildapt build-dep -y --no-install-recommends mesa
apt-get install -y --no-install-recommends git glslang-tools libxcb-shm0-dev libxfixes-dev
git init -q "$work/mesa"
git -C "$work/mesa" fetch --quiet --depth=1 https://github.com/n2ray/mesa-for-android-container.git "$revision"
git -C "$work/mesa" checkout --quiet --detach FETCH_HEAD
test "$(git -C "$work/mesa" rev-parse HEAD)" = "$revision"
meson setup "$work/build" "$work/mesa" -Dplatforms=x11,wayland \
    -Dgallium-drivers=zink,freedreno -Dfreedreno-kmds=kgsl -Dvulkan-drivers= \
    -Dgallium-va=disabled -Dllvm=disabled -Degl=enabled -Dglx=dri -Dglvnd=enabled -Dbuildtype=release
target=src/gallium/targets/dri/libgallium-27.0.0-devel.so
ninja -C "$work/build" "$target"
install -D -s -m755 "$work/build/$target" "$out/${target##*/}"
