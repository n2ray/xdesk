#!/bin/sh
# Build the Ubuntu package with the window-decoration change, keeping Ubuntu's other patches.
set -eu
srcdir=$(cd "$(dirname "$0")" && pwd)
out=$(realpath -m "$1")
mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"
# Keep source indices local to this build.
sed 's/^Types: deb$/Types: deb-src/' /etc/apt/sources.list.d/ubuntu.sources > "$work/source.sources"
chmod 755 "$work"
srcapt() { apt-get -o "Dir::Etc::sourcelist=$work/source.sources" -o Dir::Etc::sourceparts=- "$@"; }
srcapt update --list-cleanup=0
apt-get install -y --no-install-recommends build-essential dpkg-dev fakeroot
srcapt source xfwm4
cd xfwm4-*/
apt-get build-dep -y --no-install-recommends .
patch -p1 < "$srcdir/window-icons.patch"
version=$(dpkg-parsechangelog -S Version)
{
    printf 'xfwm4 (%s+xdesk1) resolute; urgency=medium\n\n' "$version"
    printf '  * Avoid building unused menu icons and reading back their background.\n\n'
    printf ' -- n2ray <n2ray@users.noreply.github.com>  %s\n\n' "$(date -Ru)"
    cat debian/changelog
} > debian/changelog.xdesk
mv debian/changelog.xdesk debian/changelog
DEB_BUILD_OPTIONS="parallel=4 noddebs" dpkg-buildpackage -B -us -uc
cp "../xfwm4_${version}+xdesk1_arm64.deb" "$out/"
