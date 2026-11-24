#!/bin/sh
# Build the Chrome-private Gallium library against the same Ubuntu arm64 ABI as the image.
set -eu
revision=2118596185954c118db2eb42598972e2b56af672
wsi_revision=5bc820780d20fa26f198b1c136cca6f032f4fb60
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
# The WSI fix and the existing Gallium pin were reviewed independently.
git -C "$work/mesa" fetch --quiet --depth=1 https://github.com/n2ray/mesa-for-android-container.git "$wsi_revision"
git -C "$work/mesa" diff "$revision" "$wsi_revision" -- src/vulkan/wsi/wsi_common_x11.c > "$work/wsi.patch"
git -C "$work/mesa" apply "$work/wsi.patch"
meson setup "$work/build" "$work/mesa" -Dplatforms=x11,wayland \
    -Dgallium-drivers=zink,freedreno -Dfreedreno-kmds=kgsl -Dvulkan-drivers=freedreno \
    -Dgallium-va=disabled -Dllvm=disabled -Degl=enabled -Dglx=dri -Dglvnd=enabled -Dbuildtype=release
target=src/gallium/targets/dri/libgallium-27.0.0-devel.so
vk=src/freedreno/vulkan
manifest=freedreno_icd.aarch64.json
ninja -C "$work/build" "$target" "$vk/libvulkan_freedreno.so" "$vk/$manifest"
readelf -d "$work/build/$vk/libvulkan_freedreno.so" | grep -q 'NEEDED.*libxcb-dri3'
install -D -s -m755 "$work/build/$target" "$out/${target##*/}"
# Keep the Vulkan loader manifest outside the system ICD search path.
install -D -s -m755 "$work/build/$vk/libvulkan_freedreno.so" "$out/../vk/libvulkan_freedreno.so"
python3 - "$work/build/$vk/$manifest" "$out/../vk/$manifest" <<'PYTHON'
import json, sys
with open(sys.argv[1]) as src:
    manifest = json.load(src)
manifest["ICD"]["library_path"] = "/usr/lib/xdesk/chrome-zink/vk/libvulkan_freedreno.so"
with open(sys.argv[2], "w") as dst:
    json.dump(manifest, dst, indent=2)
    dst.write("\n")
PYTHON
