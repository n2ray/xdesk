#!/bin/sh
# Builds XDesk's built-in Linux (Debian 13 + XFCE) inside a debian:trixie arm64 container.
# Run by .github/workflows/linux-rootfs.yml; the result is exported from the container by the workflow.
set -eu
export DEBIAN_FRONTEND=noninteractive
MESA_URL=https://github.com/lfdevs/mesa-for-android-container/releases/download/mesa-27.0.0-devel-20261025-r2/mesa-for-android-container_27.0.0-devel-20261025-r2_debian_trixie_arm64.tar.gz

apt-get update
apt-get install -y --no-install-recommends \
    xfce4 xfce4-terminal xfce4-notifyd xfce4-taskmanager mousepad ristretto thunar \
    tango-icon-theme adwaita-icon-theme librsvg2-common dbus-x11 at-spi2-core xkb-data x11-xserver-utils x11-utils xdg-utils \
    pulseaudio pulseaudio-utils pavucontrol \
    locales fonts-noto-cjk fonts-dejavu-core \
    firefox-esr \
    mesa-utils mesa-vulkan-drivers libgl1-mesa-dri vulkan-tools \
    ca-certificates curl wget nano less procps psmisc file unzip xz-utils sudo

# Locales the app picks from (the phone's language): English and Chinese
sed -i -E 's/^# *(en_US.UTF-8|zh_CN.UTF-8)/\1/' /etc/locale.gen
locale-gen

# Mesa with Turnip on KGSL (Adreno) and Zink, over Debian's own; held so an upgrade does not take it back
curl -fsSL -o /tmp/mesa.tgz "$MESA_URL"
tar -xzf /tmp/mesa.tgz -C /
rm /tmp/mesa.tgz
apt-mark hold libgl1-mesa-dri libglx-mesa0 libegl-mesa0 libgbm1 mesa-vulkan-drivers mesa-libgallium 2>/dev/null || true
ldconfig

# apt under a fake root: its sandbox user cannot be switched to
printf 'APT::Sandbox::User "root";\n' > /etc/apt/apt.conf.d/99xdesk
# No systemd, no real devices: these only print errors
rm -f /etc/xdg/autostart/xfce4-power-manager.desktop /etc/xdg/autostart/light-locker.desktop \
      /etc/xdg/autostart/xscreensaver.desktop /etc/xdg/autostart/blueman.desktop 2>/dev/null || true

install -m 755 /src/lorie-app/src/main/assets/linux/xdesk-session /usr/local/bin/xdesk-session
mkdir -p /etc/xdesk
echo "debian-13-r1 $(date -u +%Y-%m-%d)" > /etc/xdesk/image

printf '127.0.0.1 localhost\n::1 localhost\n' > /etc/hosts
echo xdesk > /etc/hostname
apt-get clean
rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* /var/log/*.log /var/cache/debconf/*-old
