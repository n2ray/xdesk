#!/bin/sh
# xdesk-core's install test, in a fresh ubuntu:26.04 (linux-apt.yml): test-xdesk-core.sh DEBS [old]
# "old" first makes the system look like an image from before the package (r1 and r2's first builds): the local
# diversion of Chrome's command with its unowned wrapper, the old preferences file, the session's profile.d file.
set -eu
export DEBIAN_FRONTEND=noninteractive
DEB=$(ls "$1"/xdesk-core_*_arm64.deb)
ok() { echo "ok: $*"; }
no() { echo "FAIL: $*" >&2; exit 1; }
apt-get update -q
if [ "${2:-}" = old ]; then
    dpkg-divert --local --rename --add --divert /usr/bin/google-chrome-stable.real /usr/bin/google-chrome-stable
    printf '#!/bin/sh\necho old wrapper\n' > /usr/bin/google-chrome-stable; chmod 755 /usr/bin/google-chrome-stable
    printf 'Package: snapd\nPin: release a=*\nPin-Priority: -10\n' > /etc/apt/preferences.d/xdesk-no-snap
    echo 'export PULSE_SERVER=unix:/tmp/xdesk-pulse' > /etc/profile.d/xdesk-pulse.sh
    printf 'APT::Sandbox::User "root";\n' > /etc/apt/apt.conf.d/99xdesk
fi
apt-get install -y -q -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "$DEB"

getent passwd xdesk | grep -q ':1000:' && ok "user xdesk is 1000" || no "user xdesk"
for g in sudo audio video; do id -nG xdesk | tr ' ' '\n' | grep -qx $g || no "xdesk not in $g"; done; ok "groups"
[ -d /home/xdesk ] && ok "home" || no "home"
[ "$(readlink -f /usr/bin/sudo)" = /usr/lib/xdesk/sudo ] && ok "sudo alternative" || no "sudo is $(readlink -f /usr/bin/sudo)"
# sudo from the user with all ids the user's (as xdesk-asuser leaves them), through the set-user-ID setpriv copy
[ "$(stat -c %a:%U /usr/lib/xdesk/setpriv-root)" = 4755:root ] && ok "setpriv-root" || no "setpriv-root: $(stat -c %a:%U /usr/lib/xdesk/setpriv-root)"
asx() { setpriv --reuid=xdesk --regid=xdesk --init-groups env USER=xdesk HOME=/home/xdesk PATH=/usr/bin:/bin "$@"; }
[ "$(asx sudo id -u)" = 0 ] && [ "$(asx sudo id -G)" = 0 ] && ok "sudo: root" || no "sudo: $(asx sudo id 2>&1)"
[ "$(asx sudo sh -c 'echo $SUDO_USER:$SUDO_UID:$HOME')" = xdesk:1000:/root ] && ok "sudo: SUDO_USER, HOME" || no "sudo env: $(asx sudo env 2>&1)"
[ "$(asx sudo -u xdesk id -u):$(asx sudo -i pwd)" = 1000:/root ] && ok "sudo -u, -i" || no "sudo -u/-i"
[ "$(dpkg-divert --listpackage /usr/bin/google-chrome-stable)" = xdesk-core ] && ok "chrome diversion" || no "chrome diversion"
grep -q 'CHROME_FLAGS' /usr/bin/google-chrome-stable && ok "chrome wrapper" || no "chrome wrapper"
# Exercise the fallback in the same unprivileged shell as the desktop.
asx sh -ec 'CHROME_FLAGS=original; XDESK_CHROME_ZINK=0; . /usr/lib/xdesk/chrome-zink.sh
    test "$CHROME_FLAGS" = original' && ok "Chrome opt-out"
z=/usr/lib/xdesk/chrome-zink
for lib in "$z/libxdesk-dri3.so" "$z/gl/libGL.so.1" "$z/va/termuxva_drv_video.so"; do
    [ -r "$lib" ] || no "missing $lib"
done
[ ! -e /etc/apt/preferences.d/xdesk-no-snap ] && ok "old preferences gone" || no "old preferences"
grep -q packages.mozilla.org /etc/apt/sources.list.d/mozilla.list && grep -q dl.google.com /etc/apt/sources.list.d/google-chrome.list && ok "browser sources" || no "browser sources"
grep -qs 'directory = \*' /etc/gitconfig && ok "git safe.directory" || no "gitconfig"
# (XDesk's own repository aside: this tests a build that may not be published yet)
apt-get update -q 2>&1 | tee /tmp/upd; ! grep '^[EW]:' /tmp/upd | grep -qv n2ray.github.io && ok "apt update clean" || no "apt update warns"
apt-cache policy snapd | grep -q 'Candidate: (none)' && ok "no snapd" || no "snapd installable"

# The trigger: xfwm4's defaults get vblank off however xfwm4 comes; then the packaged sudos stay below ours
apt-get install -y -q --no-install-recommends xfwm4 sudo-rs sudo
grep -qx 'vblank_mode=off' /usr/share/xfwm4/defaults && ok "xfwm4 vblank off (trigger)" || no "vblank: $(grep vblank /usr/share/xfwm4/defaults)"
[ "$(readlink -f /usr/bin/sudo)" = /usr/lib/xdesk/sudo ] && ok "sudo still ours" || no "sudo is $(readlink -f /usr/bin/sudo)"
for f in xfce4-power-manager light-locker xscreensaver blueman; do [ ! -e /etc/xdg/autostart/$f.desktop ] || no "autostart $f"; done
apt-get install -y -q --reinstall util-linux
[ "$(stat -c %a /usr/lib/xdesk/setpriv-root)" = 4755 ] && cmp -s /usr/bin/setpriv /usr/lib/xdesk/setpriv-root && [ "$(asx sudo id -u)" = 0 ] && ok "setpriv-root after util-linux (trigger)" || no "setpriv-root after util-linux"

test -s /usr/lib/xdesk/chrome-zink/gl/libgallium-27.0.0-devel.so &&
    ok "private Kopper driver packaged" || no "private Kopper driver missing"

private_vk=/usr/lib/xdesk/chrome-zink/vk
test -s "$private_vk/libvulkan_freedreno.so" &&
    grep -Fq "$private_vk/libvulkan_freedreno.so" "$private_vk/freedreno_icd.aarch64.json" &&
    ok "private Turnip and manifest packaged" || no "private Turnip missing or incorrect manifest"

# The GPU: lfdevs' Mesa over Ubuntu's as soon as Ubuntu's comes, pinned, and back after a reinstall of Ubuntu's
apt-get install -y -q --no-install-recommends libgl1-mesa-dri libglx-mesa0 libegl-mesa0 libgbm1 mesa-vulkan-drivers
kgsl() { grep -aq /dev/kgsl-3d0 /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so; }
kgsl && ok "lfdevs Turnip (KGSL) over Ubuntu's" || no "Turnip is Ubuntu's"
[ -e /usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so ] && [ -e /usr/local/lib/xdesk-hwvideo/termuxva_drv_video.so ] && ok "kgsl_dri, termuxva" || no "kgsl_dri or termuxva missing"
apt-cache policy mesa-vulkan-drivers | grep -q '1001' && ok "Ubuntu's Mesa pinned" || no "not pinned: $(apt-cache policy mesa-vulkan-drivers)"
apt-get install -y -q --reinstall mesa-vulkan-drivers libglx-mesa0
kgsl && ok "back over a reinstall (trigger)" || no "reinstall left Ubuntu's Turnip"
/usr/lib/xdesk/xdesk-gpu | grep -q . && no "a run with nothing to do did something" || ok "nothing to do: quiet"

apt-get install -y -q --reinstall "$DEB" && ok "reinstall"
apt-get remove -y -q xdesk-core
[ "$(dpkg-divert --listpackage /usr/bin/google-chrome-stable)" = "" ] && ok "diversion removed" || no "diversion left"
[ "$(readlink -f /usr/bin/sudo)" != /usr/lib/xdesk/sudo ] && ok "sudo given back" || no "sudo still ours"
[ ! -e /usr/lib/xdesk/setpriv-root ] && ok "setpriv-root removed" || no "setpriv-root left"
echo "all ok"
