#!/data/data/com.termux/files/usr/bin/bash
# Installs the Termux side of XDesk. Run it inside Termux, safe to repeat.
#   bash install.sh /path/to/termux-x11-nightly-*.deb
# The .deb comes from the same build as the APK ("termux-companion packages" artifact).
# It holds the loader that starts the X server for this app id, the official one will not work.
set -e
PKG=${1:?usage: install.sh /path/to/termux-x11-nightly-*.deb}
HERE=$(cd "$(dirname "$0")" && pwd)
DEST=/sdcard/Termux/xdesk
LOADER=$HOME/.local/libexec/xdesk/loader.apk
PROPS=$HOME/.termux/termux.properties

[ -d /sdcard ] && touch /sdcard/.xdesk-write-test 2>/dev/null && rm -f /sdcard/.xdesk-write-test \
    || { echo "Cannot write to /sdcard. Run termux-setup-storage first."; exit 1; }
for c in dpkg-deb flock xrandr pulseaudio startxfce4; do
    command -v "$c" >/dev/null || { echo "Missing command: $c (see README, Termux packages)"; exit 1; }
done

mkdir -p "$DEST/termux" "$DEST/dist" "$(dirname "$LOADER")"
cp "$HERE/x11-session.sh" "$HERE/xdesk-xstartup.sh" "$HERE/startx.sh" "$DEST/termux/"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
dpkg-deb -x "$PKG" "$TMP"
cp "$(find "$TMP" -name loader.apk)" "$DEST/dist/loader.apk"
# Android refuses to load a writable dex
rm -f "$LOADER"
cp "$DEST/dist/loader.apk" "$LOADER"
chmod 400 "$LOADER"

# Lets XDesk start the desktop through Termux's RUN_COMMAND
mkdir -p "$(dirname "$PROPS")"
touch "$PROPS"
if ! grep -q '^allow-external-apps *= *true' "$PROPS"; then
    sed -i 's/^# *allow-external-apps *=.*/allow-external-apps = true/' "$PROPS"
    grep -q '^allow-external-apps *= *true' "$PROPS" || echo 'allow-external-apps = true' >> "$PROPS"
    termux-reload-settings 2>/dev/null || true
fi

echo "Done. Install the XDesk APK if you have not, plug in a display and open XDesk."
