#!/data/data/com.termux/files/usr/bin/bash
# XDesk X11 原型的 Termux 侧入口，由 App 经 RUN_COMMAND 调用。官方 Termux:X11 和 xfce 不受影响，但两套不要同时跑。
#   start <宽x高> <音频端口>   用 XDesk 的 loader 起 X（进程名 xdesk-x11）和 XFCE（已在运行就保留），声音转给 App
#   stop            结束
SRC=/sdcard/Termux/xdesk/termux
L=$HOME/.local/libexec/xdesk/loader.apk
RUN=$PREFIX/tmp/xdesk
LOG=$PREFIX/tmp/xdesk.log
# 优先用用户自己的 startx.sh（已有 XFCE 配置时），没有就用随 XDesk 安装的那份
STARTX=/sdcard/Termux/startx.sh
[ -f "$STARTX" ] || STARTX=$SRC/startx.sh
mkdir -p "$RUN"
exec >>"$LOG" 2>&1
echo "=== $(date +%T) x11 $1"
# 重置 Termux 后 loader 没了，从 /sdcard 补回。必须只读，否则 Android 不让加载
if [ ! -f "$L" ]; then
    mkdir -p "$(dirname "$L")"
    cp /sdcard/Termux/xdesk/dist/loader.apk "$L" && chmod 400 "$L" && echo "已恢复 loader"
fi
exec 9>"$RUN/session.lock"
flock -w 60 9 || { echo "等锁超时"; exit 1; }
setup_audio() {  # 声音进 null sink，App 从本机端口取 PCM，由 App 决定从哪个设备放
    # PulseAudio 可能已经被杀（不重启桌面时不会有人再拉它），这里补上；已在运行时这两步都没有副作用
    timeout 5 pulseaudio --start --exit-idle-time=-1 </dev/null 9>&-
    timeout 3 pacmd load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1 >/dev/null 2>&1
    export PULSE_SERVER=127.0.0.1
    for i in $(seq 10); do timeout 2 pactl info >/dev/null 2>&1 && break; sleep 0.5; done
    timeout 2 pactl info >/dev/null 2>&1 || { echo "PulseAudio 没有起来，没有声音"; return 1; }
    pactl unload-module module-simple-protocol-tcp 2>/dev/null
    pactl list short sinks | grep -q '^[0-9]*[[:space:]]xdesk[[:space:]]' || \
        pactl load-module module-null-sink sink_name=xdesk rate=48000 channels=2 >/dev/null
    pactl load-module module-simple-protocol-tcp port="$AUDIO" listen=127.0.0.1 source=xdesk.monitor \
        record=true playback=false format=s16le rate=48000 channels=2 >/dev/null || { echo "音频端口失败"; return 1; }
    pactl set-default-sink xdesk
    pactl list short sink-inputs | cut -f1 | while read -r id; do pactl move-sink-input "$id" xdesk; done
    echo "声音已转到端口 $AUDIO"
}
case "$1" in
start)
    RES=$2; AUDIO=$3
    if pgrep -x xfce4-session >/dev/null; then
        echo "桌面已在运行"
        setup_audio
        exit 0
    fi
    echo "$RES" > "$RUN/res"
    date +%s > "$RUN/session"
    # 用 XDesk 的 loader 替换 startx.sh 里的 termux-x11，其余（Mesa、声音）照旧；界面由 App 放到外屏
    # 环境变量处理和官方 termux-x11 脚本一致：XFCE 要经 XSTARTUP_* 拿回 LD_PRELOAD
    sed -e '/^am start/d' -e 's#^termux-x11 :1 -ac -xstartup "dbus-run-session startxfce4" &#[ -z "${LD_LIBRARY_PATH+x}" ] || export XSTARTUP_LD_LIBRARY_PATH="$LD_LIBRARY_PATH"\n[ -z "${LD_PRELOAD+x}" ] || export XSTARTUP_LD_PRELOAD="$LD_PRELOAD"\nenv -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH='"$L"' /system/bin/app_process -Xnoimage-dex2oat / --nice-name="xdesk-x11 :1" com.termux.x11.Loader :1 -ac -xstartup "bash '"$SRC"'/xdesk-xstartup.sh" \&#' "$STARTX" > "$RUN/startx.sh"
    grep -q 'com.termux.x11.Loader :1' "$RUN/startx.sh" || { echo "startx.sh 的启动行变了"; exit 1; }
    setsid nohup bash "$RUN/startx.sh" log </dev/null >/dev/null 2>&1 9>&- &
    for i in $(seq 40); do
        DISPLAY=:1 timeout 2 xrandr >/dev/null 2>&1 && break
        sleep 0.5
    done
    setup_audio
    echo "已启动"
    ;;
stop)
    DISPLAY=:1 timeout 5 xfce4-session-logout --logout 2>/dev/null
    for i in $(seq 10); do pgrep -x xfce4-session >/dev/null || break; sleep 1; done
    pkill -x xfce4-session
    sleep 1
    pkill -f '^xdesk-x11 :1'
    pulseaudio --kill 2>/dev/null
    rm -f "$RUN/session"
    echo "已结束"
    ;;
esac
