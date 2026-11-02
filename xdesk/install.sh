#!/data/data/com.termux/files/usr/bin/bash
# Installs the xdesk command. Run it inside Termux, safe to repeat.
#   bash install.sh /path/to/termux-x11-nightly-*.deb
# The .deb comes from the same build as the APK ("termux-companion packages" artifact).
# It holds the loader that starts the X server for this app id, the official one will not work.
set -e
PKG=${1:?usage: install.sh /path/to/termux-x11-nightly-*.deb}
HERE=$(cd "$(dirname "$0")" && pwd)
LOADER=$HOME/.local/libexec/xdesk/loader.apk
for c in dpkg-deb flock xrandr; do
    command -v "$c" >/dev/null || { echo "Missing command: $c (see README)"; exit 1; }
done
install -m 755 "$HERE/xdesk" "$PREFIX/bin/xdesk"
mkdir -p "$(dirname "$LOADER")"
TMP=$(mktemp -d)
trap 'rm -r "${TMP:?}"' EXIT
dpkg-deb -x "$PKG" "$TMP"
[ ! -e "$LOADER" ] || rm -f "${LOADER:?}"
cp "$(find "$TMP" -name loader.apk)" "$LOADER"
chmod 400 "$LOADER"   # Android refuses to load a writable dex
if [ -d /sdcard/Termux/xdesk/dist ]; then cp -f "$LOADER" /sdcard/Termux/xdesk/dist/loader.apk; fi
"$PREFIX/bin/xdesk" setup
echo
echo "Done. Plug in a display, open XDesk, then run: xdesk start"
