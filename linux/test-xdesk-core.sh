#!/bin/sh
# xdesk-core's install test, in a fresh ubuntu:26.04 (linux-apt.yml): test-xdesk-core.sh DEBS [old]
# "old" first makes the system look like an image from before the package (r1 and r2's first builds): the local
# diversion of Chrome's command with its unowned wrapper, the old preferences file, the session's profile.d file.
set -eu
export DEBIAN_FRONTEND=noninteractive
DEB=$(ls "$1"/xdesk-core_*_all.deb)
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
[ "$(dpkg-divert --listpackage /usr/bin/google-chrome-stable)" = xdesk-core ] && ok "chrome diversion" || no "chrome diversion"
grep -q 'CHROME_FLAGS' /usr/bin/google-chrome-stable && ok "chrome wrapper" || no "chrome wrapper"
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

apt-get install -y -q --reinstall "$DEB" && ok "reinstall"
apt-get remove -y -q xdesk-core
[ "$(dpkg-divert --listpackage /usr/bin/google-chrome-stable)" = "" ] && ok "diversion removed" || no "diversion left"
[ "$(readlink -f /usr/bin/sudo)" != /usr/lib/xdesk/sudo ] && ok "sudo given back" || no "sudo still ours"
echo "all ok"
