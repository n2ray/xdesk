set -eu
cd "$HOME/.local/share/xdesx"
export DISPLAY=:19
export XDG_CONFIG_HOME="$PWD/config" XDG_DATA_HOME="$PWD/data" XDG_CACHE_HOME="$PWD/cache"
export XDG_RUNTIME_DIR="$PWD/runtime" GDK_BACKEND=x11
umask 077
mkdir -p "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$XDG_RUNTIME_DIR"
exec 8>"$PWD/session.lock"
flock -n 8 || exit 0
echo $$ > session.pid
unset DBUS_SESSION_BUS_ADDRESS SESSION_MANAGER TERMUX_X11_XSTARTUP LD_PRELOAD LD_LIBRARY_PATH
export TERMUX_X11_GLAMOR=1 CLASSPATH="$HOME/.local/libexec/xdesk/loader.apk"
test -r "$CLASSPATH"
for program in xfwm4 xfce4-panel dbus-run-session xrandr python; do command -v "$program" >/dev/null; done
/system/bin/app_process / --nice-name=xdesx-x11 com.termux.x11.Loader "$DISPLAY" -ac -nolisten tcp -nolisten local &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT
for ((i=0;i<100;i++)); do
    if xrandr >/dev/null 2>&1; then break; fi
    kill -0 "$server"; sleep .1
done
xrandr >/dev/null
dbus-run-session -- bash -c '
    xfwm4 --compositor=on &
    wm=$!
    xfce4-panel --disable-wm-check &
    panel=$!
    trap "kill $wm $panel 2>/dev/null || true" EXIT
    wait -n "$wm" "$panel"
'
