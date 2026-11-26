set -eu
umask 077
# Child stdin is the controller's pipe. Its EOF also arrives if Android kills the activity process.
scale=100
if [ -r run/panel-state ]; then read -r scale < run/panel-state || :; fi
case "$scale" in 133) export XCURSOR_SIZE=32;; 167) export XCURSOR_SIZE=40;; 200) export XCURSOR_SIZE=48;; *) export XCURSOR_SIZE=24;; esac
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
ready=false
for i in $(seq 1 120); do
  if [ -S "$XDG_RUNTIME_DIR/bus" ] && "$PWD/bin/xdesx-window" --check-display; then
    ready=true
    break
  fi
  kill -0 "$server" && kill -0 "$bus" || exit 1
  sleep .1
done
[ "$ready" = true ] || exit 1
"$PWD/lib/xfce4/xfconf/xfconfd" &
children="$! $children"
"$PWD/bin/xfwm4" --compositor=on &
children="$! $children"
"$PWD/bin/xfce4-panel" --disable-wm-check &
children="$! $children"
read -r stop || :
