#!/data/data/com.termux/files/usr/bin/bash
# One command install, run inside Termux:
#   curl -fsSL https://raw.githubusercontent.com/n2ray/xdesk/xdesk/xdesk/install.sh | bash
# Safe to repeat. Installs the xdesk command and its loader, and downloads the app.
# XDESK_VERSION=v0.1.0 picks a version, the newest release (pre-releases included) is the default.
set -e
REPO=n2ray/xdesk
LOADER=$HOME/.local/libexec/xdesk/loader.apk

missing=
for pair in xrandr:xorg-xrandr pulseaudio:pulseaudio flock:util-linux sha256sum:coreutils; do
    command -v "${pair%%:*}" >/dev/null || missing="$missing ${pair##*:}"
done
if [ -n "$missing" ]; then
    echo "Installing:$missing"
    pkg upgrade -y
    pkg install -y x11-repo
    # shellcheck disable=SC2086
    pkg install -y $missing
fi

TAG=${XDESK_VERSION:-$(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=1" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1)}
[ -n "$TAG" ] || { echo "Could not find a release of $REPO"; exit 1; }
echo "Installing XDesk $TAG"

TMP=$(mktemp -d)
trap 'rm -r "${TMP:?}"' EXIT
for f in xdesk loader.apk xdesk.apk SHA256SUMS; do
    curl -fL --progress-bar -o "$TMP/$f" "https://github.com/$REPO/releases/download/$TAG/$f"
done
(cd "$TMP" && sha256sum -c --quiet SHA256SUMS)

install -m 755 "$TMP/xdesk" "$PREFIX/bin/xdesk"
mkdir -p "$(dirname "$LOADER")"
[ ! -e "$LOADER" ] || rm -f "${LOADER:?}"
cp "$TMP/loader.apk" "$LOADER"
chmod 400 "$LOADER"   # Android refuses to load a writable dex
if [ -d /sdcard/Termux/xdesk/dist ]; then cp -f "$LOADER" /sdcard/Termux/xdesk/dist/loader.apk; fi

APK=$HOME/xdesk.apk
if touch /sdcard/Download/.xdesk-test 2>/dev/null; then
    rm -f /sdcard/Download/.xdesk-test
    APK=/sdcard/Download/xdesk.apk
fi
cp "$TMP/xdesk.apk" "$APK"

"$PREFIX/bin/xdesk" setup

cat <<DONE

Done. Next:
 1. Install the app: $APK
    Open it from a file manager, or if that fails: adb install -r xdesk.apk
 2. Plug in a display and open XDesk.
 3. Run: xdesk start

No desktop yet? For example: pkg install xfce4 xfce4-terminal && xdesk setup
DONE
