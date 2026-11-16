#!/data/data/com.termux/files/usr/bin/bash
# Tests of the xdesk command with a fake HOME and PREFIX: no X, no Termux, no proot-distro.
# Run: bash xdesk/test/cli-test.sh
set -u

here=$(cd "$(dirname "$0")/.." && pwd)
XDESK=$here/xdesk
TMP=$(mktemp -d)
trap 'rm -rf "${TMP:?}"' EXIT
export HOME=$TMP/home
export PREFIX=$TMP/prefix
export LANGUAGE=en_US
unset DISPLAY XAUTHORITY
BIN=$TMP/bin
export PATH="$BIN:$PATH"

pass=0
fail=0
ok() { echo "PASS $*"; pass=$((pass + 1)); }
bad() { echo "FAIL $*"; fail=$((fail + 1)); }
check() {  # description, command...
    local d=$1
    shift
    if "$@"; then ok "$d"; else bad "$d"; fi
}
has_line() { printf '%s\n' "$1" | grep -qxF -- "$2"; }
lacks_line() { ! printf '%s\n' "$1" | grep -qxF -- "$2"; }
has_text() { printf '%s\n' "$1" | grep -qF -- "$2"; }
lacks_text() { ! printf '%s\n' "$1" | grep -qE -- "$2"; }

# Stubs: ps keeps xdesk from renice-ing itself, xrandr and pgrep say no X runs, timeout runs its command
mkdir -p "$BIN" "$HOME/.termux" "$HOME/.local/libexec/xdesk" \
    "$PREFIX/tmp" "$PREFIX/share/icons/Adwaita/cursors"
stub() {
    printf '#!/bin/sh\n%s\n' "$2" >"$BIN/$1"
    chmod +x "$BIN/$1"
}
stub ps 'echo 5'
stub xrandr 'exit 1'
stub pgrep 'exit 1'
stub pkill 'exit 1'
stub timeout 'shift; exec "$@"'
stub pulseaudio 'exit 0'
stub xfwm4 'exit 0'
stub startxfce4 'exit 0'
stub proot-distro 'exit 0'
stub fc-list 'echo "/usr/share/fonts/NotoSansCJK-Regular.ttc: Noto Sans CJK SC"'
stub pm 'exit 1'
stub getprop 'exit 1'
stub flock 'exit 0'
stub setsid 'exit 0'
stub seq 'exit 0'

: >"$HOME/.local/libexec/xdesk/loader.apk"
printf 'allow-external-apps = true\n' >"$HOME/.termux/termux.properties"

# A container with a desktop, and one with only a program
deb=$PREFIX/var/lib/proot-distro/installed-rootfs/debian
mkdir -p "$deb/usr/bin" "$deb/usr/share/applications"
printf '#!/bin/sh\nexit 0\n' >"$deb/usr/bin/startxfce4"
chmod +x "$deb/usr/bin/startxfce4"
cat >"$deb/usr/share/applications/debian-app.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Debian Program
Exec=debian-program
DESKTOP
alpine=$PREFIX/var/lib/proot-distro/containers/alpine/rootfs
mkdir -p "$alpine/usr/share/applications"
cat >"$alpine/usr/share/applications/alpine-app.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Alpine Program
Exec=alpine-program
DESKTOP

out=$(bash "$XDESK" setup 2>&1); rc=$?
check "setup exits 0" test "$rc" -eq 0
check "setup finds Termux's XFCE" has_text "$out" "found termux-xfce"
check "setup finds the container's XFCE" has_text "$out" "found proot-debian-xfce"
check "setup finds the container with only a program" has_text "$out" "found proot-alpine"
check "setup says how to add other systems" has_text "$out" "xdesk add NAME --root DIR --launch"

check "setup writes the environment's root" test -f "$HOME/.config/xdesk/envs/proot-alpine.root"
check "setup writes the environment's launch command" \
    has_text "$(cat "$HOME/.config/xdesk/envs/proot-alpine.launch" 2>/dev/null)" \
    'exec proot-distro login alpine --shared-tmp -- "$@"'

# A second setup keeps what is there: the files belong to the user once written
printf 'custom root\n' >"$HOME/.config/xdesk/envs/proot-alpine.root"
printf '# custom launch\n' >"$HOME/.config/xdesk/envs/proot-alpine.launch"
bash "$XDESK" setup >/dev/null 2>&1
check "setup keeps an existing root file" \
    test "$(cat "$HOME/.config/xdesk/envs/proot-alpine.root")" = "custom root"
check "setup keeps an existing launch file" \
    test "$(cat "$HOME/.config/xdesk/envs/proot-alpine.launch")" = "# custom launch"
printf '%s\n' "$alpine" >"$HOME/.config/xdesk/envs/proot-alpine.root"
printf 'exec proot-distro login alpine --shared-tmp -- "$@"\n' >"$HOME/.config/xdesk/envs/proot-alpine.launch"

out=$(bash "$XDESK" envs 2>&1); rc=$?
check "envs exits 0" test "$rc" -eq 0
check "envs: Termux is first" \
    test "$(printf '%s\n' "$out" | sed -n 2p | cut -f1)" = termux
check "envs: Termux lists its XFCE session" \
    has_text "$(printf '%s\n' "$out" | sed -n 2p)" "termux-xfce"
check "envs: a container with a desktop is its sessions" \
    has_line "$out" $'proot-debian-xfce\tproot\tapps\tproot-debian-xfce'
check "envs: a container with only a program is an environment" \
    has_line "$out" $'proot-alpine\tproot\tapps\t'
check "envs: no second line for the container with a desktop" \
    lacks_text "$out" $'^proot-debian\t'

out=$(bash "$XDESK" apps 2>&1); rc=$?
check "apps exits 0" test "$rc" -eq 0
check "apps lists the Debian program" has_text "$out" "proot-debian-xfce/debian-app"
check "apps lists the Alpine program" has_text "$out" "proot-alpine/alpine-app"
out=$(bash "$XDESK" apps --path 2>&1); rc=$?
check "apps --path exits 0" test "$rc" -eq 0
check "apps --path names an existing catalog" test -s "$out"

bash "$XDESK" launch >/dev/null 2>&1; rc=$?
check "launch without an app exits 2" test "$rc" -eq 2
bash "$XDESK" launch --bogus >/dev/null 2>&1; rc=$?
check "launch with a bad option exits 2" test "$rc" -eq 2
out=$(bash "$XDESK" launch 'a/../b' 2>&1); rc=$?
check "launch with .. exits 2" test "$rc" -eq 2
check "launch with .. says the app is invalid" has_text "$out" "Invalid app"

out=$(bash "$XDESK" app state 2>/dev/null); rc=$?
check "app state exits 0" test "$rc" -eq 0
check "app state is one line" test "$(printf '%s\n' "$out" | wc -l)" -eq 1
case "$out" in
\{*\}) ok "app state is a JSON object" ;;
*) bad "app state is a JSON object" ;;
esac
if command -v jq >/dev/null 2>&1; then
    if printf '%s\n' "$out" | jq -e '
        .format == 1 and .version == "dev"
        and (.sessions | index("termux-xfce")) and (.sessions | index("proot-debian-xfce"))
        and (.default | type == "string")
        and .running == null and .x.running == false and .x.started == null
        and .ime == false and .layout == null
        and (.envs | map(.name) | index("termux"))
        and (.envs | map(.name) | index("proot-alpine"))
        and (.envs | map(select(.name == "proot-alpine"))[0].apps == true)
        and (.envs | map(select(.name == "proot-alpine"))[0].sessions == [])
    ' >/dev/null 2>&1; then
        ok "app state is valid JSON with the expected fields"
    else
        bad "app state is valid JSON with the expected fields"
    fi
else
    check "app state: format" has_text "$out" '"format":1'
    check "app state: version" has_text "$out" '"version":"dev"'
    check "app state: sessions" has_text "$out" '"termux-xfce"'
    check "app state: environments" has_text "$out" '"name":"proot-alpine"'
    check "app state: X is not running" has_text "$out" '"x":{"running":false,"started":null}'
    check "app state: no session is running" has_text "$out" '"running":null'
    check "app state: no layout" has_text "$out" '"layout":null'
fi

bash "$XDESK" app >/dev/null 2>&1; rc=$?
check "app without state exits 2" test "$rc" -eq 2
bash "$XDESK" app foo >/dev/null 2>&1; rc=$?
check "app with an unknown action exits 2" test "$rc" -eq 2

# A fake X server that answers: the state must carry its start time from /proc
cat >"$BIN/pgrep" <<'EOF'
#!/bin/sh
echo "$FAKE_X_PID"
EOF
chmod +x "$BIN/pgrep"
stub xrandr 'exit 0'
FAKE_X_PID=$$
export FAKE_X_PID
out=$(bash "$XDESK" app state 2>/dev/null)
check "app state sees a running X" has_text "$out" '"x":{"running":true,'
started=$(printf '%s\n' "$out" | sed -n 's/.*"started":\([0-9]*\).*/\1/p')
now=$(date +%s)
check "app state gives the X start time" test -n "$started"
if [ -n "$started" ] && [ "$started" -le "$now" ] && [ $((now - started)) -lt 3600 ]; then
    ok "app state's X start time is plausible"
else
    bad "app state's X start time is plausible (got '$started')"
fi
stub pgrep 'exit 1'
stub xrandr 'exit 1'
unset FAKE_X_PID

out=$(bash "$XDESK" version 2>&1); rc=$?
check "version exits 0" test "$rc" -eq 0
check "version prints the command version" test "$out" = "xdesk dev"
for f in --version -V; do
    out=$(bash "$XDESK" "$f" 2>&1)
    check "$f prints the command version" test "$out" = "xdesk dev"
done

out=$(bash "$XDESK" status 2>/dev/null); rc=$?
check "status exits 0" test "$rc" -eq 0
check "status still starts as before" has_text "$out" "X server: not running"
check "status ends with the command version" \
    test "$(printf '%s\n' "$out" | tail -1)" = "xdesk: dev"

out=$(bash "$XDESK" help 2>&1); rc=$?
check "help exits 0" test "$rc" -eq 0
check "help lists doctor" has_text "$out" "xdesk doctor"
check "help lists version" has_text "$out" "xdesk version"
check "help lists update" has_text "$out" "xdesk update"
check "help lists help all" has_text "$out" "xdesk help [all]"
check "help hides the app's scale" lacks_text "$out" 'xdesk scale'
check "help hides the app's envs" lacks_text "$out" 'xdesk envs'
check "help hides the app's recover" lacks_text "$out" 'xdesk recover'
out=$(bash "$XDESK" help all 2>&1); rc=$?
check "help all exits 0" test "$rc" -eq 0
check "help all lists scale" has_text "$out" "xdesk scale"
check "help all lists envs" has_text "$out" "xdesk envs"
check "help all lists recover" has_text "$out" "xdesk recover"
check "help all lists enable-app-start" has_text "$out" "xdesk enable-app-start"
check "help all lists apps --path" has_text "$out" "xdesk apps --path"
check "help all lists launch --startup-id" has_text "$out" "xdesk launch --startup-id"
check "help all lists app state" has_text "$out" "xdesk app state"

bash "$XDESK" >/dev/null 2>&1; rc=$?
check "no command exits 0" test "$rc" -eq 0
out=$(bash "$XDESK" frobnicate 2>&1); rc=$?
check "unknown command exits non-zero" test "$rc" -ne 0
check "unknown command prints the usage" has_text "$out" "xdesk start"

mkdir -p "$TMP/testroot"
out=$(bash "$XDESK" add testenv --root "$TMP/testroot" \
    --launch 'proot-distro login testenv --shared-tmp --' 2>&1); rc=$?
check "add of an environment exits 0" test "$rc" -eq 0
check "add writes the environment's root" test -f "$HOME/.config/xdesk/envs/testenv.root"
check "add writes the environment's launch" test -f "$HOME/.config/xdesk/envs/testenv.launch"
check "add writes no session for it" test ! -f "$HOME/.config/xdesk/sessions/testenv.sh"
out=$(bash "$XDESK" envs 2>&1)
check "envs lists the added environment" has_line "$out" $'testenv\tproot\tapps\t'
out=$(bash "$XDESK" list 2>&1)
check "list stays sessions only" lacks_line "$out" "  testenv"
check "list stays sessions only (chosen)" lacks_line "$out" "* testenv"
out=$(bash "$XDESK" rm testenv 2>&1); rc=$?
check "rm of an environment exits 0" test "$rc" -eq 0
check "rm removes the environment's root" test ! -e "$HOME/.config/xdesk/envs/testenv.root"
check "rm removes the environment's launch" test ! -e "$HOME/.config/xdesk/envs/testenv.launch"
out=$(bash "$XDESK" envs 2>&1)
check "envs no longer lists it" lacks_text "$out" $'^testenv\t'

out=$(bash "$XDESK" doctor 2>&1); rc=$?
check "doctor exits 0 in the fake setup" test "$rc" -eq 0
check "doctor reports that X is not running" has_text "$out" "X server: not running"
check "doctor reports the Termux environment" has_text "$out" "environment termux:"
check "doctor does not ask for a Termux root" lacks_text "$out" 'environment termux: no root'
check "doctor reports the container with only a program" has_text "$out" "environment proot-alpine"
check "doctor says how to add other systems" has_text "$out" "xdesk add NAME --root DIR --launch"
start=$(date +%s)
bash "$XDESK" doctor >/dev/null 2>&1
end=$(date +%s)
check "doctor is fast without X (under 10 s, a busy phone is slower)" test $((end - start)) -le 10
mv "$HOME/.local/libexec/xdesk/loader.apk" "$TMP/loader.apk"
out=$(bash "$XDESK" doctor 2>&1); rc=$?
check "doctor exits 1 with a missing loader" test "$rc" -eq 1
check "doctor names the missing loader" has_text "$out" "✗ loader"
mv "$TMP/loader.apk" "$HOME/.local/libexec/xdesk/loader.apk"

# A session with a command still works as before, next to the environments
out=$(bash "$XDESK" add testsess 'echo hi' --root "$TMP/testroot" \
    --launch "chroot $TMP/testroot sh" 2>&1); rc=$?
check "add of a session exits 0" test "$rc" -eq 0
check "add still writes the session" test -f "$HOME/.config/xdesk/sessions/testsess.sh"
check "add still writes the session's root" test -f "$HOME/.config/xdesk/sessions/testsess.root"
out=$(bash "$XDESK" list 2>&1)
check "list shows the session" has_line "$out" "  testsess"
out=$(bash "$XDESK" envs 2>&1)
check "envs shows the session's environment" has_line "$out" $'testsess\tother\tapps\ttestsess'
bash "$XDESK" rm testsess >/dev/null 2>&1
check "rm removes the session" test ! -e "$HOME/.config/xdesk/sessions/testsess.sh"

# JSON escaping: a session name with a quote and a backslash
name='we"ird\name'
bash "$XDESK" add "$name" 'echo hi' >/dev/null 2>&1; rc=$?
check "add accepts a name with a quote and a backslash" test "$rc" -eq 0
out=$(bash "$XDESK" app state 2>/dev/null)
check "app state escapes quotes and backslashes" has_text "$out" '"we\"ird\\name"'
bash "$XDESK" rm "$name" >/dev/null 2>&1

# and a control character, which JSON only allows as \u00XX
name=$'ctl\001name'
bash "$XDESK" add "$name" 'echo hi' >/dev/null 2>&1; rc=$?
check "add accepts a name with a control character" test "$rc" -eq 0
out=$(bash "$XDESK" app state 2>/dev/null)
check "app state escapes control characters" has_text "$out" '"ctl\u0001name"'
bash "$XDESK" rm "$name" >/dev/null 2>&1

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
