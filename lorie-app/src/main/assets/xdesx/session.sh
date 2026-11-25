set -eu
umask 077
# Child stdin is the controller's pipe. Its EOF also arrives if Android kills the activity process.
children=""
trap 'for pid in $children; do kill "$pid" 2>/dev/null || :; done' EXIT
(
  unset LD_LIBRARY_PATH
  exec /system/bin/app_process / --nice-name=xdesx-x11 com.termux.x11.CmdEntryPoint :19 -ac -nolisten tcp -nolisten local
) &
server=$!
children="$server"
"$PWD/bin/dbus-daemon" --config-file="$PWD/dbus.conf" --nofork &
bus=$!
children="$bus $children"
for i in $(seq 1 120); do
  if [ -S "$TMPDIR/.X11-unix/X19" ] && [ -S "$XDG_RUNTIME_DIR/bus" ]; then break; fi
  kill -0 "$server" && kill -0 "$bus" || exit 1
  sleep .1
done
"$PWD/lib/xfce4/xfconf/xfconfd" &
children="$! $children"
"$PWD/bin/xfwm4" --compositor=on &
children="$! $children"
"$PWD/bin/xfce4-panel" --disable-wm-check &
children="$! $children"
read -r stop || :
