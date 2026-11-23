#!/bin/sh
# Builds XDesk's built-in Linux (Ubuntu 26.04 LTS + XFCE) inside an ubuntu:26.04 arm64 container.
# Run by .github/workflows/linux-rootfs.yml; the result is exported from the container by the workflow.
#
# The image is a seed (owner, 23 November): Ubuntu as it comes, plus only what cannot wait for a package. What XDesk
# needs inside is the package xdesk-core (linux/packages), from XDesk's own repository, which the app keeps up to date
# (xdesk-update): so an installed system follows XDesk without a new image. XFCE keeps its own look.
# No browser comes with it: users install what they want (xdesk-core sets up Mozilla's and Google's repositories:
# Ubuntu's own Firefox and Chromium are snaps, which cannot run in an app's container).
set -eu
export DEBIAN_FRONTEND=noninteractive

# Ubuntu's container image leaves out translations: the desktop would be in English only
sed -i '/\/usr\/share\/locale/d' /etc/dpkg/dpkg.cfg.d/excludes 2>/dev/null || true

apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg

# GNU coreutils instead of Ubuntu's default uutils: uutils is one binary that tells which tool to be by the path it was
# run from, and the image cannot keep its hard links (Android apps cannot make them; as symlinks all paths lead to the
# one file, as copies it is 1.1 GB)
apt-get install -y --allow-remove-essential coreutils-from-gnu coreutils-from-uutils-
ls --version | head -1

# XDesk's own package, built from this tree (the repository has the same)
sh /src/linux/build-debs.sh /tmp/debs
apt-get install -y /tmp/debs/xdesk-core_*_all.deb
rm -rf /tmp/debs

apt-get update
# Greybird and elementary-xfce: the theme and icons XFCE's own settings name (without them it falls back to bare GTK)
apt-get install -y --no-install-recommends \
    xfce4 xfce4-terminal xfce4-notifyd xfce4-taskmanager mousepad ristretto thunar \
    greybird-gtk-theme elementary-xfce-icon-theme tango-icon-theme adwaita-icon-theme librsvg2-common \
    dbus-x11 at-spi2-core xkb-data x11-xserver-utils x11-utils xdg-utils \
    pulseaudio pulseaudio-utils pavucontrol \
    locales fonts-noto-cjk fonts-dejavu-core \
    mesa-utils mesa-vulkan-drivers libgl1-mesa-dri vulkan-tools \
    nano less procps psmisc file unzip xz-utils wget openssh-server

# English by default; other languages are the user's to add (language-pack-*, update-locale): translations are kept
locale-gen en_US.UTF-8

# The Adreno drivers (lfdevs' Mesa over Ubuntu's, termuxva) come with xdesk-core: its trigger put them over the
# Mesa packages just installed (linux/packages/xdesk-core/usr/lib/xdesk/xdesk-gpu)
grep -aq /dev/kgsl-3d0 /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so || { echo "no KGSL Turnip: xdesk-gpu failed" >&2; exit 1; }
cat /var/lib/xdesk/gpu

# SSH (XDesk starts it when the user turns on SSH access): no host keys in the image, or every installation would share
# them; xdesk-sshd makes them on first start. Nothing starts it here (no systemd).
rm -f /etc/ssh/ssh_host_*

mkdir -p /etc/xdesk
echo "ubuntu-26.04-r2 $(date -u +%Y-%m-%d)" > /etc/xdesk/image

printf '127.0.0.1 localhost\n::1 localhost\n' > /etc/hosts
echo xdesk > /etc/hostname
apt-get clean
# What takes the room (in the build log)
du -xsh /usr/share/locale /usr/share/doc /usr/share/fonts /usr/lib/aarch64-linux-gnu /usr/share/icons 2>/dev/null || true
rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* /var/log/*.log /var/cache/debconf/*-old
