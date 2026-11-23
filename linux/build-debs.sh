#!/bin/sh
# XDesk's own packages for its built-in Ubuntu (linux/packages/*), built into OUT: build-debs.sh OUT
# Run in Ubuntu (dpkg-deb, curl, gpg): by build-rootfs.sh for the image and by linux-apt.yml for XDesk's repository.
# A package's version is the one in its DEBIAN/control: raise it with every change (the app asks for a version,
# lorie-app/src/main/assets/linux/xdesk-update).
set -eu
SRC=$(cd "$(dirname "$0")" && pwd)
OUT=$1
# XDesk's repository (linux/make-repo.sh makes it). Its public key, once there is one, is linux/apt/xdesk-archive.asc:
# only then does xdesk-core name the repository (a source without its key would make every apt update fail)
REPO_URL=https://n2ray.github.io/xdesk-apt
SUITE=resolute
# The signing keys' fingerprints, checked before xdesk-core carries them
MOZILLA_KEY=35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3
GOOGLE_KEY=EB4C1BFD4F042F6DDDCCEC917721F63BD38B4796

# Also used by xdesk-apt's publisher in a minimal Ubuntu build container.
[ "$(dpkg --print-architecture)" = arm64 ] || { echo "build in an arm64 Ubuntu container" >&2; exit 1; }
apt-get install -y --no-install-recommends g++ libva-dev libglx-dev libgl-dev libxcb1-dev libxrandr-dev
mkdir -p "$OUT"
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
export GNUPGHOME=$W/gnupg
mkdir -m 700 "$GNUPGHOME"

fetch_key() {  # url fingerprint file
    curl -fsSL --retry 3 "$1" | gpg --dearmor > "$3"
    gpg --show-keys --with-colons "$3" | awk -F: '$1 == "fpr" { print $10 }' | grep -qx "$2" ||
        { echo "key $1: fingerprint is not $2" >&2; exit 1; }
    chmod 644 "$3"
}

for p in "$SRC"/packages/*/; do
    name=$(basename "$p")
    t=$W/$name
    cp -a "$p" "$t"
    if [ "$name" = xdesk-core ]; then
        sh "$SRC/chrome-zink/build.sh" "$t/usr/lib/xdesk/chrome-zink"
        sh "$SRC/chrome-zink/build-kopper.sh" "$t/usr/lib/xdesk/chrome-zink/gl"
        mkdir -p "$t/usr/share/xdesk/keyrings"
        fetch_key https://packages.mozilla.org/apt/repo-signing-key.gpg "$MOZILLA_KEY" "$t/usr/share/xdesk/keyrings/packages.mozilla.org.gpg"
        fetch_key https://dl.google.com/linux/linux_signing_key.pub "$GOOGLE_KEY" "$t/usr/share/xdesk/keyrings/google-chrome.gpg"
        if [ -f "$SRC/apt/xdesk-archive.asc" ]; then
            install -D -m 644 "$SRC/apt/xdesk-archive.asc" "$t/usr/share/keyrings/xdesk-archive.asc"
            mkdir -p "$t/etc/apt/sources.list.d"
            printf 'Types: deb\nURIs: %s\nSuites: %s\nComponents: main\nSigned-By: /usr/share/keyrings/xdesk-archive.asc\n' \
                "$REPO_URL" "$SUITE" > "$t/etc/apt/sources.list.d/xdesk.sources"
        fi
    fi
    # Everything under etc/ is a conffile (the user's changes survive an upgrade)
    (cd "$t" && find etc -type f 2>/dev/null | sed 's|^|/|') > "$t/DEBIAN/conffiles" || true
    [ -s "$t/DEBIAN/conffiles" ] || rm -f "$t/DEBIAN/conffiles"
    find "$t" -type d -exec chmod 755 {} +
    v=$(sed -n 's/^Version: //p' "$t/DEBIAN/control")
    arch=$(sed -n 's/^Architecture: //p' "$t/DEBIAN/control")
    dpkg-deb --root-owner-group -Zxz -b "$t" "$OUT/${name}_${v}_${arch}.deb" >/dev/null
    echo "$OUT/${name}_${v}_${arch}.deb"
done
