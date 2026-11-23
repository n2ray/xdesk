#!/bin/sh
# xdesk-mesa: Ubuntu's own Mesa, rebuilt with one change, Freedreno's KGSL kernel interface instead of msm
# (-Dfreedreno-kmds=kgsl), so Turnip and Freedreno run on an Adreno under Android, where there is no DRM device.
# Ubuntu builds msm only (debian/rules sets no freedreno-kmds). kgsl alone, as lfdevs' builds: with msm too Turnip
# offers VK_KHR_display, which Zink and vulkaninfo then enable, and its KGSL backend refuses any instance that has it
# ("I can't KHR_display": no GPU at all, tested 2026-11-23). msm needs /dev/dri, which an app never has. Everything else, the packages' names, files and
# dependencies, stays Ubuntu's: the version is Ubuntu's plus "+xdeskN", and XDesk's repository is pinned above Ubuntu
# (xdesk-core's preferences.d/xdesk) so an Ubuntu update does not take KGSL away before this is rebuilt.
# Run by n2ray/xdesk-apt's mesa.yml (daily: when Ubuntu's Mesa changes) in ubuntu:26.04 (arm64): build-mesa.sh OUT [REV]
# OUT gets the packages and UBUNTU_VERSION (the Ubuntu version they were rebuilt from).
set -eu
OUT=$1
REV=${2:-1}
export DEBIAN_FRONTEND=noninteractive DEBEMAIL="n2ray <n2ray@users.noreply.github.com>"

# Source packages: the image's sources with deb-src too
sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/ubuntu.sources
apt-get update
apt-get install -y --no-install-recommends dpkg-dev devscripts fakeroot ca-certificates
mkdir -p /build "$OUT"
cd /build
apt-get source mesa
cd mesa-*/
apt-get build-dep -y ./

# The one change
grep -q 'freedreno-kmds' debian/rules && { echo "debian/rules sets freedreno-kmds already: look again" >&2; exit 1; }
sed -i 's/^confflags += \\$/confflags += \\\n\t-Dfreedreno-kmds=kgsl \\/' debian/rules
grep -q -- '-Dfreedreno-kmds=kgsl' debian/rules || { echo "could not add freedreno-kmds" >&2; exit 1; }
v=$(dpkg-parsechangelog -S Version)
echo "$v" > "$OUT/UBUNTU_VERSION"
dch --newversion "$v+xdesk$REV" --distribution "$(dpkg-parsechangelog -S Distribution)" --force-distribution \
    "Rebuilt for XDesk: -Dfreedreno-kmds=kgsl (Adreno's KGSL, for Android)."

DEB_BUILD_OPTIONS="nocheck parallel=$(nproc)" dpkg-buildpackage -b -uc -us
cd ..
# Not the debug symbols (large; Ubuntu's are on its ddebs server, these would not match anyway)
for f in *.deb; do case "$f" in *-dbgsym_*) ;; *) cp "$f" "$OUT/" ;; esac; done
ls -la "$OUT"
# The check that matters: Turnip opens the KGSL device
dpkg-deb -x "$OUT"/mesa-vulkan-drivers_*.deb /tmp/mvd
grep -aq /dev/kgsl-3d0 /tmp/mvd/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so || { echo "Turnip has no KGSL" >&2; exit 1; }
echo "Turnip with KGSL: ok"
