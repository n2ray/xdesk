#!/data/data/com.termux/files/usr/bin/bash
# One command install, run inside Termux:
#   curl -fsSL https://raw.githubusercontent.com/n2ray/xdesk/xdesk/xdesk/install.sh | bash
# Safe to repeat. Installs the xdesk command and its loader, and downloads the app.
# XDESK_VERSION=v0.1.0 picks a version, the newest release (pre-releases included) is the default.
set -e
REPO=n2ray/xdesk
LOADER=$HOME/.local/libexec/xdesk/loader.apk

missing=
for pair in xrandr:xorg-xrandr pulseaudio:pulseaudio flock:util-linux setsid:util-linux sha256sum:coreutils pgrep:procps; do
    command -v "${pair%%:*}" >/dev/null || missing="$missing ${pair##*:}"
done
# Adreno GPUs work with Turnip, Mesa's open Vulkan driver, and Mesa then runs OpenGL on the real GPU
# through zink. Mesa picks this by itself and falls back to software rendering when the GPU is too old
# for Turnip, so installing it is safe on every Adreno.
turnip=
if [ -e /sys/class/kgsl/kgsl-3d0 ] && ! ls "$PREFIX"/share/vulkan/icd.d/*freedreno* >/dev/null 2>&1; then
    turnip=1
fi
if [ -n "$missing$turnip" ]; then
    # Termux does not support partial upgrades: a new package against old libraries can break both
    pkg upgrade -y
    pkg install -y x11-repo
fi
if [ -n "$missing" ]; then
    echo "Installing:$missing"
    # shellcheck disable=SC2086
    pkg install -y $missing
fi
if [ -n "$turnip" ]; then
    echo "Installing Turnip, the Adreno Vulkan driver"
    # Not fatal: without it the desktop uses software rendering
    if pkg install -y mesa mesa-vulkan-icd-freedreno; then
        echo "If the desktop shows broken graphics, remove it again: pkg uninstall mesa-vulkan-icd-freedreno"
    else
        echo "Turnip was not installed; the desktop will use software rendering."
    fi
fi

TAG=${XDESK_VERSION:-$(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=1" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1)}
[ -n "$TAG" ] || { echo "Could not find a release of $REPO"; exit 1; }
echo "Installing XDesk $TAG"

TMP=$(mktemp -d)
trap 'rm -r "${TMP:?}"' EXIT
for f in xdesk loader.apk xdesk.apk SHA256SUMS; do
    curl -fL --progress-bar -o "$TMP/$f" "https://github.com/$REPO/releases/download/$TAG/$f"
done
# The list also has files this script does not download; all three of these must be in it
(cd "$TMP" && grep -E '  (xdesk|loader\.apk|xdesk\.apk)$' SHA256SUMS > wanted && [ "$(wc -l < wanted)" -eq 3 ] && sha256sum -c --quiet wanted)

install -m 755 "$TMP/xdesk" "$PREFIX/bin/xdesk"
mkdir -p "$(dirname "$LOADER")"
[ ! -e "$LOADER" ] || rm -f "${LOADER:?}"
cp "$TMP/loader.apk" "$LOADER"
chmod 400 "$LOADER"   # Android refuses to load a writable dex

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
 2. Plug in a display, open the XDesk app and choose a desktop.

No desktop yet? For example: pkg install xfce4 xfce4-terminal && xdesk setup
DONE
