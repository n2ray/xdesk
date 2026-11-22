#!/bin/sh
# Builds XDesk's built-in Linux (Ubuntu 26.04 LTS + XFCE) inside an ubuntu:26.04 arm64 container.
# Run by .github/workflows/linux-rootfs.yml; the result is exported from the container by the workflow.
# No browser comes with it: users install what they want. Ubuntu's own Firefox and Chromium are snaps, which cannot
# run in an app's container, so Google's and Mozilla's apt repositories are set up instead (apt install
# google-chrome-stable / firefox), and snapd is pinned away.
set -eu
export DEBIAN_FRONTEND=noninteractive
MESA_URL=https://github.com/lfdevs/mesa-for-android-container/releases/download/mesa-27.0.0-devel-20261025-r2/mesa-for-android-container_27.0.0-devel-20261025-r2_ubuntu_resolute_arm64.tar.gz
# The signing keys' fingerprints, checked before the repositories are trusted
MOZILLA_KEY=35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3
GOOGLE_KEY=EB4C1BFD4F042F6DDDCCEC917721F63BD38B4796

# Ubuntu's container image leaves out translations: the desktop would be in English only
sed -i '/\/usr\/share\/locale/d' /etc/dpkg/dpkg.cfg.d/excludes 2>/dev/null || true

# No snaps: the transitional firefox/chromium packages would install a stub that only says to use snapd
mkdir -p /etc/apt/preferences.d /etc/apt/keyrings
cat > /etc/apt/preferences.d/xdesk-no-snap <<'EOF'
Package: snapd
Pin: release a=*
Pin-Priority: -10

Package: firefox* thunderbird*
Pin: origin packages.mozilla.org
Pin-Priority: 1000
EOF

apt-get update
apt-get install -y --no-install-recommends ca-certificates curl gnupg

# GNU coreutils instead of Ubuntu's default uutils: uutils is one binary that tells which tool to be by the path it was
# run from, and the image cannot keep its hard links (Android apps cannot make them; as symlinks all paths lead to the
# one file, as copies it is 1.1 GB)
apt-get install -y --allow-remove-essential coreutils-from-gnu
ls --version | head -1

# The makers' repositories, each key checked against its known fingerprint
fetch_key() {  # url fingerprint file
    curl -fsSL "$1" | gpg --dearmor > "$3"
    gpg --show-keys --with-colons "$3" | awk -F: '$1 == "fpr" { print $10 }' | grep -qx "$2" ||
        { echo "key $1: fingerprint is not $2" >&2; exit 1; }
}
fetch_key https://packages.mozilla.org/apt/repo-signing-key.gpg "$MOZILLA_KEY" /etc/apt/keyrings/packages.mozilla.org.gpg
echo "deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.gpg] https://packages.mozilla.org/apt mozilla main" \
    > /etc/apt/sources.list.d/mozilla.list
fetch_key https://dl.google.com/linux/linux_signing_key.pub "$GOOGLE_KEY" /etc/apt/keyrings/google-chrome.gpg
echo "deb [arch=arm64 signed-by=/etc/apt/keyrings/google-chrome.gpg] https://dl.google.com/linux/chrome/deb/ stable main" \
    > /etc/apt/sources.list.d/google-chrome.list

apt-get update
apt-get install -y --no-install-recommends \
    xfce4 xfce4-terminal xfce4-notifyd xfce4-taskmanager mousepad ristretto thunar \
    tango-icon-theme adwaita-icon-theme librsvg2-common dbus-x11 at-spi2-core xkb-data x11-xserver-utils x11-utils xdg-utils \
    pulseaudio pulseaudio-utils pavucontrol \
    locales fonts-noto-cjk fonts-dejavu-core \
    mesa-utils mesa-vulkan-drivers libgl1-mesa-dri vulkan-tools \
    nano less procps psmisc file unzip xz-utils wget sudo

# English by default; other languages are the user's to add (language-pack-*, update-locale): translations are kept
locale-gen en_US.UTF-8

# Mesa with Turnip on KGSL (Adreno) and Zink, over Ubuntu's own; held so an upgrade does not take it back
curl -fsSL -o /tmp/mesa.tgz "$MESA_URL"
tar -xzf /tmp/mesa.tgz -C /
rm /tmp/mesa.tgz
apt-mark hold libgl1-mesa-dri libglx-mesa0 libegl-mesa0 libgbm1 mesa-vulkan-drivers mesa-libgallium 2>/dev/null || true
ldconfig

# Chrome, once the user installs it, with the flags and environment the session writes for this phone's GPU
# (/etc/xdesk/chrome.sh): its own launcher reads none, so the command everything starts it by is a small wrapper,
# diverted in advance; Chrome's package then puts its own link aside. Its updates keep the diversion.
dpkg-divert --local --rename --add --divert /usr/bin/google-chrome-stable.real /usr/bin/google-chrome-stable
cat > /usr/bin/google-chrome-stable <<'EOF'
#!/bin/sh
# XDesk: Google Chrome with the flags for this container (/etc/xdesk/chrome.sh, written by xdesk-session at each start)
CHROME_FLAGS=
[ -r /etc/xdesk/chrome.sh ] && . /etc/xdesk/chrome.sh
if [ ! -x /opt/google/chrome/google-chrome ]; then
    echo "Google Chrome is not installed: sudo apt install google-chrome-stable" >&2
    exit 127
fi
exec /opt/google/chrome/google-chrome $CHROME_FLAGS "$@"
EOF
chmod 755 /usr/bin/google-chrome-stable

# xfwm4's compositor without GLX vblank: through Zink on XDesk's X it leaves the screen black (xdesk-session also
# fixes the user's own settings)
sed -i 's/^vblank_mode=.*/vblank_mode=off/' /usr/share/xfwm4/defaults

# apt under a fake root: its sandbox user cannot be switched to
printf 'APT::Sandbox::User "root";\n' > /etc/apt/apt.conf.d/99xdesk
# No systemd, no real devices: these only print errors
rm -f /etc/xdg/autostart/xfce4-power-manager.desktop /etc/xdg/autostart/light-locker.desktop \
      /etc/xdg/autostart/xscreensaver.desktop /etc/xdg/autostart/blueman.desktop 2>/dev/null || true

install -m 755 /src/lorie-app/src/main/assets/linux/xdesk-session /usr/local/bin/xdesk-session
mkdir -p /etc/xdesk
echo "ubuntu-26.04-r1 $(date -u +%Y-%m-%d)" > /etc/xdesk/image

printf '127.0.0.1 localhost\n::1 localhost\n' > /etc/hosts
echo xdesk > /etc/hostname
apt-get clean
# What takes the room (in the build log)
du -xsh /usr/share/locale /usr/share/doc /usr/share/fonts /usr/lib/aarch64-linux-gnu /usr/share/icons 2>/dev/null || true
rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* /var/log/*.log /var/cache/debconf/*-old
