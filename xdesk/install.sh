#!/data/data/com.termux/files/usr/bin/bash
# One command install, run inside Termux:
#   curl -fsSL https://raw.githubusercontent.com/n2ray/xdesk/xdesk/xdesk/install.sh | bash
# Safe to repeat. Installs the xdesk command and its loader, and downloads the app.
# XDESK_VERSION=v0.1.0 picks a version, XDESK_VERSION=beta the newest of all (pre-releases included); the newest
# release (no pre-release) is the default. The app's install line always names its own version.
# XDESK_CLI_ONLY=1 replaces only the xdesk command and the loader (xdesk update uses this).
set -e
REPO=n2ray/xdesk
LOADER=$HOME/.local/libexec/xdesk/loader.apk
CLI_ONLY=${XDESK_CLI_ONLY:-}
# A tag from xdesk update or the app: vX.Y.Z with an optional suffix. Nothing else is ever fetched or run
case "${XDESK_VERSION:-}" in
''|beta|stable) ;;
*) [[ $XDESK_VERSION =~ ^v[0-9][0-9A-Za-z.+-]*$ ]] || { echo "Invalid version: $XDESK_VERSION (expected vX.Y.Z)"; exit 1; } ;;
esac

missing=
# xfwm4 and xfsettingsd: a program opened alone needs a window manager (the app makes its window the full screen one
# through it), so with these a Termux without any desktop can already open its programs on the display
for pair in xrandr:xorg-xrandr pulseaudio:pulseaudio flock:util-linux setsid:util-linux sha256sum:coreutils pgrep:procps \
            xfwm4:xfwm4 xfsettingsd:xfce4-settings; do
    command -v "${pair%%:*}" >/dev/null || missing="$missing ${pair##*:}"
done
# Adreno GPUs work with Turnip, Mesa's open Vulkan driver, and Mesa then runs OpenGL on the real GPU
# through zink. Mesa picks this by itself and falls back to software rendering when the GPU is too old
# for Turnip, so installing it is safe on every Adreno.
turnip=
if [ -z "$CLI_ONLY" ] && [ -e /sys/class/kgsl/kgsl-3d0 ] && ! ls "$PREFIX"/share/vulkan/icd.d/*freedreno* >/dev/null 2>&1; then
    turnip=1
fi
# Ask before changing more than XDesk's own commands, the same way the allow-external-apps question works: y/N, and
# with no terminal to ask on (a pipe, the app) the answer is No and the command is printed instead
ask_yn() {  # question
    local answer=
    if [ -r /dev/tty ]; then
        printf '%s [y/N] ' "$1"
        { read -r answer </dev/tty; } 2>/dev/null || { answer=; echo; }
    else
        echo "$1 [y/N] No, no terminal to ask on"
    fi
    case "$answer" in [yY]*) return 0 ;; *) return 1 ;; esac
}
if [ -n "$CLI_ONLY" ]; then
    # xdesk update runs from the app, without a tty: pkg could wait for an answer and hang it. Only say what is missing
    if [ -n "$missing" ]; then
        echo "Missing commands. Install them with: pkg install${missing}"
    fi
else
    # The commands XDesk itself needs to run a display: say so before installing them
    if [ -n "$missing" ]; then
        echo "XDesk needs these Termux packages:$missing (x11-repo carries xorg-xrandr) -- installing them now."
    fi
    # Termux does not support partial upgrades: a new package against old libraries can break both. Upgrading all of
    # Termux is far beyond XDesk, so it is the user's decision
    # Only asked when something is going to be installed: nothing to install, nothing to keep in step
    if [ -n "$missing$turnip" ]; then
        if ask_yn "Upgrade all Termux packages first (pkg upgrade -y, recommended before installing)?"; then
            pkg upgrade -y
        else
            echo "Not upgrading. To upgrade later: pkg upgrade -y"
        fi
    fi
    if [ -n "$missing" ]; then
        # xorg-xrandr comes from x11-repo; it only adds a repository, no package of the user's changes
        pkg install -y x11-repo
        # shellcheck disable=SC2086
        pkg install -y $missing
    fi
    if [ -n "$turnip" ]; then
        # Turnip is Mesa's open Vulkan driver: it changes how every OpenGL program in Termux renders (through zink),
        # not only XDesk's, so it is the user's decision too
        if ask_yn "Install Turnip (Mesa's Adreno Vulkan driver) for GPU acceleration?"; then
            # Not fatal: without it the desktop uses software rendering
            if pkg install -y mesa mesa-vulkan-icd-freedreno; then
                echo "If the desktop shows broken graphics, remove it again: pkg uninstall mesa-vulkan-icd-freedreno"
            else
                echo "Turnip was not installed; the desktop will use software rendering."
            fi
        else
            echo "Not installing it; the desktop will use software rendering. To install it later:"
            echo "  pkg install mesa mesa-vulkan-icd-freedreno"
        fi
    fi
fi

# A line: stable is GitHub's latest release (never a pre-release), beta the newest of all
case "${XDESK_VERSION:-stable}" in
stable) TAG=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1) ;;
beta) TAG=$(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=1" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1) ;;
*) TAG=$XDESK_VERSION ;;
esac
[ -n "$TAG" ] || { echo "Could not find a release of $REPO"; exit 1; }
[[ $TAG =~ ^v[0-9][0-9A-Za-z.+-]*$ ]] || { echo "Invalid release tag: $TAG"; exit 1; }
if [ -z "$CLI_ONLY" ]; then echo "Installing XDesk $TAG"; fi

TMP=$(mktemp -d)
trap 'rm -r "${TMP:?}"' EXIT
# With XDESK_CLI_ONLY the app is left alone: only what is replaced is downloaded and checked
if [ -n "$CLI_ONLY" ]; then
    files=(xdesk loader.apk SHA256SUMS)
    wanted_re='  (xdesk|loader\.apk)$'
    wanted_n=2
    curl_opts=(-fsSL)
else
    files=(xdesk loader.apk xdesk.apk SHA256SUMS)
    wanted_re='  (xdesk|loader\.apk|xdesk\.apk)$'
    wanted_n=3
    curl_opts=(-fL --progress-bar)
fi
for f in "${files[@]}"; do
    curl "${curl_opts[@]}" -o "$TMP/$f" "https://github.com/$REPO/releases/download/$TAG/$f"
done
# The list also has files this script does not download; all of these must be in it
(cd "$TMP" && grep -E "$wanted_re" SHA256SUMS > wanted && [ "$(wc -l < wanted)" -eq "$wanted_n" ] && sha256sum -c --quiet wanted)

install -m 755 "$TMP/xdesk" "$PREFIX/bin/xdesk"
mkdir -p "$(dirname "$LOADER")"
# Replaced by a rename: the old loader stays until the new one is complete and has its mode
rm -f "${LOADER:?}.new"
cp "$TMP/loader.apk" "$LOADER.new"
chmod 400 "$LOADER.new"   # Android refuses to load a writable dex
mv -f "$LOADER.new" "$LOADER"

# xdesk update prints one short line and changes nothing else
if [ -n "$CLI_ONLY" ]; then
    echo "xdesk updated to $TAG"
    exit 0
fi

APK=$HOME/xdesk.apk
if touch /sdcard/Download/.xdesk-test 2>/dev/null; then
    rm -f /sdcard/Download/.xdesk-test
    APK=/sdcard/Download/xdesk.apk
fi
cp "$TMP/xdesk.apk" "$APK"

# XDesk starts desktops and reads what is set up through Termux's RUN_COMMAND, which a fresh Termux refuses.
# Allowing it opens Termux to other apps, so it is the user's choice: asked, never done silently
if ! grep -qs '^allow-external-apps *= *true' "$HOME/.termux/termux.properties"; then
    cat <<'ASK'

XDesk starts your desktops and lists your programs by running commands in Termux.
Termux refuses that until you allow other apps to run commands (allow-external-apps).
Only apps you also grant the "Run commands in Termux" permission can then use it,
and XDesk asks for that permission itself. Without it XDesk cannot use Termux.
ASK
    answer=
    if [ -r /dev/tty ]; then
        printf 'Allow it now? [y/N] '
        { read -r answer </dev/tty; } 2>/dev/null || { answer=; echo; }
    fi
    case "$answer" in
        [yY]*) "$PREFIX/bin/xdesk" enable-app-start ;;
        *) echo "Not changed. To allow it later: xdesk enable-app-start" ;;
    esac
fi
# Discovery, not setup: new desktops and containers are found, and the user's list of removed ones (xdesk rm) stays as
# it is. xdesk setup clears that list, which is a user's explicit choice, not an installer's
"$PREFIX/bin/xdesk" discover

cat <<DONE

Done. Next:
 1. Install the app: $APK
    Open it from a file manager, or if that fails: adb install -r xdesk.apk
    After installing it, that copy may be deleted.
 2. Open XDesk, Add device › Linux › Termux, and allow it to run commands in Termux.
 3. Plug in a display and choose a desktop or a program.

No desktop yet? For example: pkg install xfce4 xfce4-terminal && xdesk setup
DONE
