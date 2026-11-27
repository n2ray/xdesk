#!/bin/sh
# Run under dbus-run-session and Xvfb. Exercise the same fallback the guest uses.
set -eu
export XDESX_CARET_TRACE=1 ACCESSIBILITY_ENABLED=1
tmp=$(mktemp -d)
openbox >"$tmp/wm.log" 2>&1 &
wm=$!
/tmp/xdesx-accessible-caret >"$tmp/bridge.log" 2>&1 &
bridge=$!
cc -fPIC -shared tools/xdesx/vte-anchor.c $(pkg-config --cflags --libs gtk+-3.0 x11) -o "$tmp/im-anchor.so"
sed "s|/usr/local/lib/xdesx/im-vte-anchor.so|$tmp/im-anchor.so|" lorie-app/src/main/assets/linux/xdesx-immodules.cache >"$tmp/gtk.cache"
export GTK_IM_MODULE=xdesx GTK_IM_MODULE_FILE="$tmp/gtk.cache"
chrome=
trap 'kill "$bridge" "$wm" ${chrome:+"$chrome"} 2>/dev/null || :; cat "$tmp/bridge.log"' EXIT
echo '<title>xdesx-caret-check</title><input autofocus value="abcdefghijklm">' >"$tmp/editor.html"
google-chrome --user-data-dir="$tmp/profile" --ozone-platform=x11 --gtk-version=3 --no-first-run \
    --no-default-browser-check --force-renderer-accessibility "file://$tmp/editor.html" >"$tmp/chrome.log" 2>&1 &
chrome=$!
window=
for attempt in $(seq 1 40); do
    window=$(xdotool search --onlyvisible --name 'xdesx-caret-check' 2>/dev/null | head -1)
    [ -z "$window" ] || break
    sleep 1
done
if [ -z "$window" ]; then tail -30 "$tmp/chrome.log"; exit 1; fi
xdotool windowactivate --sync "$window"
read_position() {
    xprop -id "$window" _XDESX_INSERTION_RECT _XDESX_ACCESSIBLE_INSERTION_RECT | sed -n 's/^.*(INTEGER) = \([0-9][0-9]*\), [0-9][0-9]*, [1-9][0-9]*$/\1/p' | head -1
}
move_caret() {
    previous=$1
    for key in "$2" "$3" "$2"; do
        xdotool key "$key"
        for unused in 1 2 3 4 5; do
            sleep .2
            x=$(read_position)
            if [ -n "$x" ] && [ "$x" != "$previous" ]; then echo "$x"; return; fi
        done
    done
    echo "No measurable movement from x=$previous; property: $(xprop -id "$window" _XDESX_ACCESSIBLE_INSERTION_RECT)" >&2
    return 1
}
xdotool key ctrl+l
xdotool type 'abcdefghijklm'
first=$(move_caret '' Home End)
second=$(move_caret "$first" End Left)
echo "Address bar caret $first -> $second"
xdotool key Escape
xdotool key ctrl+r
sleep 1
first=$(move_caret '' Home Right)
second=$(move_caret "$first" End Left)
echo "Page input caret $first -> $second"
