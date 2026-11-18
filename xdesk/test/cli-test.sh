#!/data/data/com.termux/files/usr/bin/bash
# Tests of the xdesk command with a fake HOME and PREFIX: no X, no Termux, no proot-distro.
# Run: bash xdesk/test/cli-test.sh
set -u

here=$(cd "$(dirname "$0")/.." && pwd)
XDESK=$here/xdesk
TMP=$(mktemp -d) || exit 1
trap 'rm -rf "${TMP:?}"' EXIT
export HOME=$TMP/home
export PREFIX=$TMP/prefix
export LANGUAGE=en_US
unset DISPLAY XAUTHORITY
BIN=$TMP/bin
# $PREFIX/bin is on PATH as in Termux: launched programs and Terminal=true lookup find them there
export PATH="$BIN:$PREFIX/bin:$PATH"

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
    "$PREFIX/bin" "$PREFIX/tmp" "$PREFIX/share/applications" "$PREFIX/share/icons/Adwaita/cursors"
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
# stop_audio talks to the local daemon if there is one: fail instead of touching a real one in the test machine
stub pactl 'exit 1'
stub xfwm4 'exit 0'
stub startxfce4 'exit 0'
stub proot-distro 'exit 0'
stub fc-list 'echo "/usr/share/fonts/NotoSansCJK-Regular.ttc: Noto Sans CJK SC"'
stub pm 'exit 1'
stub getprop 'exit 1'
stub flock 'exit 0'
# The launch wrapper starts the program through setsid; the stub drops options and runs it, as the real one does
stub setsid 'while [ $# -gt 0 ]; do case "$1" in -*) shift ;; *) break ;; esac; done
exec "$@"'
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

# New names are plain file names (A14): quotes, control characters, spaces and +apps are refused
out=$(bash "$XDESK" add 'we"ird\name' 'echo hi' 2>&1); rc=$?
check "add rejects a name with a quote and a backslash" test "$rc" -eq 2
out=$(bash "$XDESK" add $'ctl\001name' 'echo hi' 2>&1); rc=$?
check "add rejects a name with a control character" test "$rc" -eq 2
out=$(bash "$XDESK" add 'has space' 'echo hi' 2>&1); rc=$?
check "add rejects a name with a space" test "$rc" -eq 2
out=$(bash "$XDESK" add $'has\ttab' 'echo hi' 2>&1); rc=$?
check "add rejects a name with a tab" test "$rc" -eq 2
out=$(bash "$XDESK" add $'has\nnewline' 'echo hi' 2>&1); rc=$?
check "add rejects a name with a newline" test "$rc" -eq 2
out=$(bash "$XDESK" add 'a+apps' 'echo hi' 2>&1); rc=$?
check "add rejects a +apps name" test "$rc" -eq 2
out=$(bash "$XDESK" add '.hidden' 'echo hi' 2>&1); rc=$?
check "add rejects a name that starts with a dot" test "$rc" -eq 2
out=$(bash "$XDESK" add '-dash' 'echo hi' 2>&1); rc=$?
check "add rejects a name that starts with a dash" test "$rc" -eq 2
out=$(bash "$XDESK" add 'a/b' 'echo hi' 2>&1); rc=$?
check "add rejects a name with a slash" test "$rc" -eq 2

# JSON escaping (json_str) still covers names older xdesk accepted: written directly into the sessions directory
name='we"ird\name'
printf 'exec echo hi\n' >"$HOME/.config/xdesk/sessions/$name.sh"
out=$(bash "$XDESK" app state 2>/dev/null)
check "app state escapes quotes and backslashes" has_text "$out" '"we\"ird\\name"'
bash "$XDESK" rm "$name" >/dev/null 2>&1
check "rm removes an older name with a quote and a backslash" test ! -e "$HOME/.config/xdesk/sessions/$name.sh"

# and a control character, which JSON only allows as \u00XX
name=$'ctl\001name'
printf 'exec echo hi\n' >"$HOME/.config/xdesk/sessions/$name.sh"
out=$(bash "$XDESK" app state 2>/dev/null)
check "app state escapes control characters" has_text "$out" '"ctl\u0001name"'
bash "$XDESK" rm "$name" >/dev/null 2>&1

# Discovery without setup (CLI.md §3.1): what is installed later shows up in the next app state
fedora=$PREFIX/var/lib/proot-distro/installed-rootfs/fedora
mkdir -p "$fedora/usr/bin" "$fedora/usr/share/applications"
out=$(bash "$XDESK" app state 2>/dev/null)
check "app state finds a container installed later" has_text "$out" '"name":"proot-fedora"'
check "app state stays one line of JSON after finding it" test "$(printf '%s\n' "$out" | wc -l)" -eq 1
printf '#!/bin/sh\nexit 0\n' >"$fedora/usr/bin/startlxde"
out=$(bash "$XDESK" envs 2>/dev/null)
check "envs finds a desktop installed later" has_text "$out" "proot-fedora-lxde"
# Removed stays removed, until setup asks for everything again
bash "$XDESK" rm proot-fedora-lxde >/dev/null 2>&1
bash "$XDESK" rm proot-fedora >/dev/null 2>&1
out=$(bash "$XDESK" app state 2>/dev/null)
check "a removed environment is not found again by app state" lacks_text "$out" 'proot-fedora'
out=$(bash "$XDESK" setup 2>&1)
check "setup finds a removed environment again" has_text "$out" "found proot-fedora-lxde"
# A container removed with proot-distro: what setup wrote for it goes too
rm -rf "${fedora:?}"
out=$(bash "$XDESK" app state 2>/dev/null)
check "a container that is gone leaves app state" lacks_text "$out" 'proot-fedora'
check "its desktop session file is removed" test ! -e "$HOME/.config/xdesk/sessions/proot-fedora-lxde.sh"
check "its environment files are removed" test ! -e "$HOME/.config/xdesk/envs/proot-fedora.root"
# A file of the user's own for a missing root is kept: only what setup wrote is cleaned up
printf '/nowhere\n' >"$HOME/.config/xdesk/envs/mine.root"
printf 'exec chroot /nowhere "$@"\n' >"$HOME/.config/xdesk/envs/mine.launch"
bash "$XDESK" app state >/dev/null 2>&1
check "an environment the user added is kept" test -e "$HOME/.config/xdesk/envs/mine.launch"
bash "$XDESK" rm mine >/dev/null 2>&1

# The program list for the app before any X runs: the catalog's header and rows, icons left out
out=$(bash "$XDESK" app catalog 2>/dev/null); rc=$?
check "app catalog exits 0" test "$rc" -eq 0
check "app catalog starts with the catalog header" test "$(printf '%s\n' "$out" | head -1 | cut -c1-13)" = "#xdesk-apps 1"
check "app catalog lists a container's program" has_text "$out" "Alpine Program"
check "app catalog has no icons" test -z "$(printf '%s\n' "$out" | awk -F'\t' 'NR > 1 && $5 != ""')"
bash "$XDESK" app nonsense >/dev/null 2>&1; rc=$?
check "app with an unknown subcommand exits 2" test "$rc" -eq 2

# --- A1: an unknown session name fails before anything starts ---
for cmd in start switch use; do
    out=$(bash "$XDESK" "$cmd" nosuchsession 2>&1); rc=$?
    check "$cmd with an unknown session exits 1" test "$rc" -eq 1
    check "$cmd names the missing session" has_text "$out" "No session named nosuchsession (xdesk list shows them)"
done
out=$(bash "$XDESK" start nosuchsession+apps 2>&1); rc=$?
check "start of an unknown NAME+apps exits 1" test "$rc" -eq 1
check "start of an unknown NAME+apps names it" has_text "$out" "No session named nosuchsession+apps"

# A2: a pid whose start time does not match is not the session; status says none and stop leaves that process alone
sleep 60 &
ghost=$!
printf '%s\n' "$ghost" >"$PREFIX/tmp/xdesk/session.pid"
printf 'not-the-start-time\n' >"$PREFIX/tmp/xdesk/session.started"
printf 'ghost\n' >"$PREFIX/tmp/xdesk/session.name"
out=$(bash "$XDESK" status 2>/dev/null)
check "a reused session pid is not a running session" has_text "$out" "Session: none"
bash "$XDESK" stop >/dev/null 2>&1
check "stop leaves a process that is not the session alone" kill -0 "$ghost"
kill "$ghost" 2>/dev/null
wait "$ghost" 2>/dev/null

# --- A15: --startup-id needs its value ---
out=$(bash "$XDESK" launch --startup-id 2>&1); rc=$?
check "launch --startup-id without a value exits 2" test "$rc" -eq 2
check "launch --startup-id without a value says bad-key" has_text "$out" "xdesk-error bad-key"

# --- A16: two catalog builds at once do not corrupt the catalog ---
rm -f "$HOME/.cache/xdesk/apps.tsv" "$HOME/.cache/xdesk/apps.sig"
bash "$XDESK" apps --path >"$TMP/cat1" 2>/dev/null &
p1=$!
bash "$XDESK" apps --path >"$TMP/cat2" 2>/dev/null &
p2=$!
wait "$p1"; r1=$?
wait "$p2"; r2=$?
check "two concurrent catalog builds both exit 0" test "$r1" -eq 0 -a "$r2" -eq 0
check "both name the same catalog file" test "$(cat "$TMP/cat1")" = "$(cat "$TMP/cat2")"
check "the catalog has its header after concurrent builds" \
    test "$(head -1 "$(cat "$TMP/cat1")" | cut -c1-13)" = "#xdesk-apps 1"
check "the catalog still lists a program after concurrent builds" \
    has_text "$(cat "$(cat "$TMP/cat1")")" "Debian Program"

# --- A20: an edited desktop file and an edited session file change the catalog ---
bash "$XDESK" apps >/dev/null 2>&1
check "the catalog lists the container's program" has_text "$(bash "$XDESK" apps 2>&1)" "Debian Program"
sleep 1
sed -i 's/^Name=Debian Program$/Name=Debian Program Edited/' "$deb/usr/share/applications/debian-app.desktop"
out=$(bash "$XDESK" apps 2>&1)
check "an edited .desktop file rebuilds the catalog" has_text "$out" "Debian Program Edited"
sed -i 's/^Name=Debian Program Edited$/Name=Debian Program/' "$deb/usr/share/applications/debian-app.desktop"

printf 'exec proot-distro login debian --shared-tmp -- env DISPLAY=:1 true\n' >"$HOME/.config/xdesk/sessions/editme.sh"
bash "$XDESK" apps >/dev/null 2>&1
check "a session file's proot root is in the catalog" has_text "$(bash "$XDESK" apps 2>&1)" "editme/debian-app"
sleep 1
printf 'exec proot-distro login alpine --shared-tmp -- env DISPLAY=:1 true\n' >"$HOME/.config/xdesk/sessions/editme.sh"
out=$(bash "$XDESK" apps 2>&1)
check "an edited session file rebuilds the catalog" has_text "$out" "editme/alpine-app"
check "the edited session no longer lists the old root" lacks_text "$out" "editme/debian-app"
bash "$XDESK" rm editme >/dev/null 2>&1
bash "$XDESK" apps --refresh >/dev/null 2>&1

# --- A23: desktop files written with CRLF line ends ---
mkdir -p "$PREFIX/share/applications"
printf '[Desktop Entry]\r\nType=Application\r\nName=CRLF Program\r\nExec=crlf-program\r\n' \
    >"$PREFIX/share/applications/crlf.desktop"
out=$(bash "$XDESK" apps 2>&1)
check "a CRLF desktop file is read" has_text "$out" "termux/crlf"
check "a CRLF desktop file has its name" has_text "$out" "CRLF Program"
rm -f "$PREFIX/share/applications/crlf.desktop"
bash "$XDESK" apps --refresh >/dev/null 2>&1

# --- A24: rm of a name that is not there; add keeps old root/launch files ---
out=$(bash "$XDESK" rm definitely-not-there 2>&1); rc=$?
check "rm of an unknown name exits 1" test "$rc" -eq 1
check "rm says there is no such session or environment" \
    has_text "$out" "No session or environment named definitely-not-there"
check "rm of an unknown name does not ignore it" \
    lacks_line "$(cat "$HOME/.config/xdesk/ignored" 2>/dev/null)" "definitely-not-there"

mkdir -p "$TMP/keeproot"
bash "$XDESK" add keepme 'echo hi' --root "$TMP/keeproot" >/dev/null 2>&1
out=$(bash "$XDESK" add keepme 'echo hi' 2>&1); rc=$?
check "add with a command keeps an old root file" \
    test "$rc" -eq 0 -a -f "$HOME/.config/xdesk/sessions/keepme.root"
check "add says the old root is kept" has_text "$out" "kept keepme.root from before"
bash "$XDESK" rm keepme >/dev/null 2>&1

# --- A26: doctor counts the existing catalog without building one ---
bash "$XDESK" apps >/dev/null 2>&1
out=$(bash "$XDESK" doctor 2>&1)
line=$(printf '%s\n' "$out" | grep -F "environment proot-debian-xfce:" | head -1)
check "doctor counts the Debian scope's catalog rows" has_text "$line" "1 programs"
mv "$HOME/.cache/xdesk/apps.tsv" "$TMP/apps.tsv"
out=$(bash "$XDESK" doctor 2>&1)
check "doctor without a catalog says programs are not listed yet" has_text "$out" "programs: not listed yet"
check "doctor does not build the catalog" test ! -e "$HOME/.cache/xdesk/apps.tsv"
mv "$TMP/apps.tsv" "$HOME/.cache/xdesk/apps.tsv"

# --- §5.3: doctor checks the container's PulseAudio client library ---
mkdir -p "$deb/etc"
printf 'ID=debian\n' >"$deb/etc/os-release"
out=$(bash "$XDESK" doctor 2>&1)
check "doctor reports a missing PulseAudio library with the Debian package" \
    has_text "$out" "no PulseAudio library (fix: apt install libpulse0)"
mkdir -p "$deb/usr/lib/aarch64-linux-gnu"
: >"$deb/usr/lib/aarch64-linux-gnu/libpulse.so.0"
out=$(bash "$XDESK" doctor 2>&1)
check "doctor finds the PulseAudio library" has_text "$out" "PulseAudio library present"

# --- §5.2: doctor's GPU line, software rendering and Turnip detection ---
out=$(bash "$XDESK" doctor 2>&1)
check "doctor reports software rendering for the container" \
    has_text "$out" "environment proot-debian-xfce: GPU: software rendering"
check "doctor's software hint names the Debian Turnip package" \
    has_text "$out" "(fix: apt install mesa-vulkan-icd-freedreno)"
mkdir -p "$deb/usr/share/vulkan/icd.d"
: >"$deb/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"
out=$(bash "$XDESK" doctor 2>&1)
check "doctor reports Turnip hardware rendering" \
    has_text "$out" "environment proot-debian-xfce: GPU: hardware (Turnip)"
check "doctor reports a GPU line for Termux" has_text "$out" "environment termux: GPU:"

# --- §5.1 and §5.4: launch checks, the wrapper's status and the software retry ---
# XDESK_TEST_NO_X=1 is the test hook: it skips only X and window manager startup
fake_prog() { printf '#!/bin/sh\n%s\n' "$2" >"$PREFIX/bin/$1"; chmod +x "$PREFIX/bin/$1"; }
fake_desktop() {  # id (= program), Exec
    printf '[Desktop Entry]\nType=Application\nName=%s\nExec=%s\n' "$1" "$2" \
        >"$PREFIX/share/applications/$1.desktop"
}
fake_prog fake-ok 'exit 0'
fake_prog fake-127 'exit 127'
fake_prog fake-nosuch 'echo "sh: 1: fake-nosuch: No such file or directory" >&2
exit 1'
fake_prog fake-lib 'echo "error while loading shared libraries: libfoo.so.1: cannot open shared object file" >&2
exit 127'
fake_prog fake-segv 'kill -SEGV $$'
fake_prog fake-gpu 'if [ "${LIBGL_ALWAYS_SOFTWARE:-}" = 1 ]; then exit 0; fi
echo "zink: failed to create context" >&2
exit 1'
fake_desktop fake-ok fake-ok
fake_desktop fake-127 fake-127
fake_desktop fake-nosuch fake-nosuch
fake_desktop fake-lib fake-lib
fake_desktop fake-segv fake-segv
fake_desktop fake-gpu fake-gpu
fake_desktop missing definitely-not-installed-xyz

launch_and_status() {  # app key; sets LAUNCH_OUT, LAUNCH_RC, LAUNCH_PID, LAUNCH_STATUS
    LAUNCH_OUT=$(XDESK_TEST_NO_X=1 bash "$XDESK" launch "$1" 2>/dev/null)
    LAUNCH_RC=$?
    LAUNCH_PID=$(printf '%s\n' "$LAUNCH_OUT" | sed -n 's/^pid //p' | head -1)
    LAUNCH_STATUS=""
    if [ -n "$LAUNCH_PID" ]; then
        LAUNCH_STATUS=$(cat "$PREFIX/tmp/xdesk/launch/$LAUNCH_PID.status" 2>/dev/null)
    fi
}

launch_and_status termux/fake-ok
check "launch of a program that exits 0 exits 0" test "$LAUNCH_RC" -eq 0
check "launch prints only the pid line" test "$(printf '%s\n' "$LAUNCH_OUT" | wc -l)" -eq 1
check "the pid line starts with pid" test "${LAUNCH_OUT%% *}" = pid
check "the wrapper keeps a launch log" test -f "$PREFIX/tmp/xdesk/launch/$LAUNCH_PID.log"
check "the wrapper writes a status file" test -f "$PREFIX/tmp/xdesk/launch/$LAUNCH_PID.status"
check "exit 0 is ok" has_text "$LAUNCH_STATUS" "reason=ok"
check "the status has the exit code" has_text "$LAUNCH_STATUS" "exit=0"
check "a plain success has no retried line" lacks_text "$LAUNCH_STATUS" "retried="

launch_and_status termux/fake-127
check "a program exiting 127 is not-found" has_text "$LAUNCH_STATUS" "reason=not-found"
check "not-found's detail is the program" has_text "$LAUNCH_STATUS" "detail=fake-127"
check "not-found keeps the exit code" has_text "$LAUNCH_STATUS" "exit=127"
check "the wrapper exits with the program's code" test "$LAUNCH_RC" -eq 127

launch_and_status termux/fake-nosuch
check "No such file or directory with a nonzero exit is not-found" has_text "$LAUNCH_STATUS" "reason=not-found"
check "that not-found's detail is the program" has_text "$LAUNCH_STATUS" "detail=fake-nosuch"

launch_and_status termux/fake-lib
check "a missing library is missing-lib" has_text "$LAUNCH_STATUS" "reason=missing-lib"
check "missing-lib's detail is the library" has_text "$LAUNCH_STATUS" "detail=libfoo.so.1"
check "the library error is in the launch log" \
    has_text "$(cat "$PREFIX/tmp/xdesk/launch/$LAUNCH_PID.log")" "libfoo.so.1"
check "the library error is in xdesk.log" has_text "$(cat "$PREFIX/tmp/xdesk.log")" "libfoo.so.1"

launch_and_status termux/fake-segv
check "a signaled program is a crash" has_text "$LAUNCH_STATUS" "reason=crash"
check "crash's detail is the signal" has_text "$LAUNCH_STATUS" "detail=SIGSEGV"
check "crash's exit code is 128+N" has_text "$LAUNCH_STATUS" "exit=139"

launch_and_status termux/fake-gpu
check "a GPU failure is retried in software" has_text "$LAUNCH_STATUS" "retried=software"
check "the software retry succeeds" has_text "$LAUNCH_STATUS" "reason=ok"
check "the retried program exits 0" test "$LAUNCH_RC" -eq 0

out=$(XDESK_TEST_NO_X=1 bash "$XDESK" launch termux/missing 2>&1); rc=$?
check "launch of a missing program exits 1" test "$rc" -eq 1
check "launch of a missing program prints not-installed" \
    has_text "$out" "xdesk-error not-installed definitely-not-installed-xyz"
check "launch of a missing program prints no pid" lacks_text "$out" "^pid "
check "launch of a missing program names the environment in the sentence" \
    has_text "$out" "is not installed in Termux"

out=$(XDESK_TEST_NO_X=1 bash "$XDESK" launch termux/no-such-app 2>&1); rc=$?
check "launch of an unknown app exits 1" test "$rc" -eq 1
check "launch of an unknown app prints no-app" has_text "$out" "xdesk-error no-app"

# The launch directory keeps the last 20
for ((i = 0; i < 25; i++)); do
    : >"$PREFIX/tmp/xdesk/launch/old$i.log"
    : >"$PREFIX/tmp/xdesk/launch/old$i.status"
done
launch_and_status termux/fake-ok
n=$(ls "$PREFIX/tmp/xdesk/launch"/*.log 2>/dev/null | wc -l)
check "the launch directory keeps the last 20" test "$n" -le 20
check "no fifo is left behind" test -z "$(ls "$PREFIX/tmp/xdesk/launch"/*.fifo 2>/dev/null)"

# --- A5: LANGUAGE from the phone's locale, with the 4-letter script subtag dropped ---
fake_prog fake-lang 'echo "LANGUAGE=$LANGUAGE"'
fake_desktop fake-lang fake-lang
stub getprop 'echo zh-Hans-CN'
launch_and_status termux/fake-lang
check "zh-Hans-CN becomes zh_CN" \
    has_text "$(cat "$PREFIX/tmp/xdesk/launch/$LAUNCH_PID.log")" "LANGUAGE=zh_CN"
stub getprop 'echo zh-Hant'
launch_and_status termux/fake-lang
check "zh-Hant without a region becomes zh_TW" \
    has_text "$(cat "$PREFIX/tmp/xdesk/launch/$LAUNCH_PID.log")" "LANGUAGE=zh_TW"
stub getprop 'echo zh-Hant-TW'
launch_and_status termux/fake-lang
check "zh-Hant-TW becomes zh_TW" \
    has_text "$(cat "$PREFIX/tmp/xdesk/launch/$LAUNCH_PID.log")" "LANGUAGE=zh_TW"
stub getprop 'echo zh-Hans'
launch_and_status termux/fake-lang
check "zh-Hans without a region becomes zh_CN" \
    has_text "$(cat "$PREFIX/tmp/xdesk/launch/$LAUNCH_PID.log")" "LANGUAGE=zh_CN"
stub getprop 'exit 1'

# --- A4: a new Termux session file has no frozen environment ---
check "a new Termux session file holds only the exec line" \
    test "$(cat "$HOME/.config/xdesk/sessions/termux-xfce.sh")" = "exec dbus-run-session startxfce4"
check "a new Termux session file freezes no language" \
    lacks_text "$(cat "$HOME/.config/xdesk/sessions/termux-xfce.sh")" "export LANGUAGE="

# --- A18: update validates the tag before any network use ---
out=$(bash "$XDESK" update 'bad tag' 2>&1); rc=$?
check "update rejects an invalid version" test "$rc" -eq 2
check "update says the version is invalid" has_text "$out" "Invalid version"
out=$(bash "$XDESK" update 'v1/2' 2>&1); rc=$?
check "update rejects a version with a slash" test "$rc" -eq 2

# --- A17: install.sh with XDESK_CLI_ONLY=1 never runs pkg ---
mkdir -p "$TMP/pkgbin"
printf '#!/bin/sh\necho "$*" >>"%s"\nexit 1\n' "$TMP/pkg.log" >"$TMP/pkgbin/pkg"
printf '#!/bin/sh\nexit 1\n' >"$TMP/pkgbin/curl"
chmod +x "$TMP/pkgbin/pkg" "$TMP/pkgbin/curl"
out=$(XDESK_CLI_ONLY=1 XDESK_VERSION=v0.1.0 PATH="$TMP/pkgbin:$PATH" bash "$here/install.sh" 2>&1); rc=$?
check "install.sh in CLI-only mode fails when the download fails" test "$rc" -ne 0
check "install.sh in CLI-only mode never runs pkg" test ! -e "$TMP/pkg.log"
# Only when something is missing on this PATH (a full Termux has it all, and then there is nothing to say)
need=""
for c in xrandr pulseaudio flock setsid sha256sum pgrep xfwm4 xfsettingsd; do
    PATH="$TMP/pkgbin:$PATH" command -v "$c" >/dev/null 2>&1 || need=1
done
if [ -n "$need" ]; then
    check "install.sh in CLI-only mode says what to install" has_text "$out" "pkg install"
else
    check "install.sh in CLI-only mode says nothing to install when nothing is missing" lacks_text "$out" "pkg install"
fi
out=$(XDESK_CLI_ONLY=1 XDESK_VERSION=nope PATH="$TMP/pkgbin:$PATH" bash "$here/install.sh" 2>&1); rc=$?
check "install.sh rejects an invalid XDESK_VERSION" test "$rc" -ne 0
check "install.sh names the invalid version" has_text "$out" "Invalid version"
check "install.sh still ran no pkg for an invalid version" test ! -e "$TMP/pkg.log"

# --- Release lines (CLI.md §1): which tag update asks for, without the network ---
mkdir -p "$TMP/relbin"
cat >"$TMP/relbin/curl" <<CURL
#!/bin/sh
for a; do u=\$a; done
echo "\$u" >>"$TMP/curl.log"
case "\$u" in
*/releases/latest) echo '{"tag_name": "v0.5.1"}' ;;
*'/releases?per_page=1') echo '{"tag_name": "v0.6.0-beta.5"}' ;;
*) echo 404 ;;
esac
CURL
chmod +x "$TMP/relbin/curl"
rel() { PATH="$TMP/relbin:$PATH" bash "$@" 2>&1; }
sed 's/^XDESK_CLI_VERSION=.*/XDESK_CLI_VERSION=0.6.0/' "$XDESK" >"$TMP/xdesk-stable"
sed 's/^XDESK_CLI_VERSION=.*/XDESK_CLI_VERSION=0.6.0-beta.4/' "$XDESK" >"$TMP/xdesk-beta"
out=$(rel "$TMP/xdesk-stable" update)
check "update on a release stays on releases" has_text "$out" "Newest stable: v0.5.1"
out=$(rel "$TMP/xdesk-beta" update)
check "update on a beta takes the newest of all" has_text "$out" "Newest beta: v0.6.0-beta.5"
out=$(rel "$TMP/xdesk-stable" update beta)
check "update beta switches to the newest of all" has_text "$out" "Newest beta: v0.6.0-beta.5"
out=$(rel "$TMP/xdesk-beta" update stable)
check "update stable switches back to releases" has_text "$out" "Newest stable: v0.5.1"
: >"$TMP/curl.log"
out=$(rel "$TMP/xdesk-beta" update 0.6.0-beta.3)
check "update VERSION asks for that tag only" has_text "$(cat "$TMP/curl.log")" "/releases/download/v0.6.0-beta.3/install.sh"
check "update VERSION looks up no line" lacks_text "$(cat "$TMP/curl.log")" "api.github.com"
check "update of a release without install.sh says it is too old" has_text "$out" "older than xdesk update supports"

# --- A pid file from an older xdesk (no start time beside it) still counts for the process it names ---
sleep 300 &
old=$!
printf '%s\n' "$old" >"$PREFIX/tmp/xdesk/session.pid"
printf 'termux-xfce\n' >"$PREFIX/tmp/xdesk/session.name"
rm -f "$PREFIX/tmp/xdesk/session.started"
out=$(bash "$XDESK" status 2>/dev/null)
check "a session from an older xdesk is still seen as running" has_line "$out" "Session: termux-xfce"
touch -d '-1 hour' "$PREFIX/tmp/xdesk/session.pid"
out=$(bash "$XDESK" status 2>/dev/null)
check "a pid file older than its process is not trusted" has_line "$out" "Session: none"
kill "$old" 2>/dev/null; wait "$old" 2>/dev/null
rm -f "$PREFIX/tmp/xdesk/session.pid" "$PREFIX/tmp/xdesk/session.name"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
