#!/bin/sh
# Run under dbus-run-session and Xvfb. Exercise the same fallback the guest uses.
set -eu
tmp=$(mktemp -d)
openbox >"$tmp/wm.log" 2>&1 &
wm=$!
python3 lorie-app/src/main/assets/linux/xdesx-accessible-caret >"$tmp/bridge.log" 2>&1 &
bridge=$!
chrome=
trap 'kill "$bridge" "$wm" ${chrome:+"$chrome"} 2>/dev/null || :; cat "$tmp/bridge.log"' EXIT
echo '<title>xdesx-caret-check</title><input autofocus value="abcdefghijklm">' >"$tmp/editor.html"
google-chrome --user-data-dir="$tmp/profile" --ozone-platform=x11 --no-first-run \
    --no-default-browser-check --force-renderer-accessibility "file://$tmp/editor.html" >"$tmp/chrome.log" 2>&1 &
chrome=$!
window=$(timeout 40 xdotool search --sync --onlyvisible --name 'xdesx-caret-check' | head -1)
xdotool windowactivate --sync "$window"
read_position() {
    xprop -id "$window" _XDESX_ACCESSIBLE_INSERTION_RECT | sed -n 's/^.*(INTEGER) = \([0-9][0-9]*\), [0-9][0-9]*, [1-9][0-9]*$/\1/p'
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
