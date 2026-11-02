#!/data/data/com.termux/files/usr/bin/bash
# XFCE4 桌面启动 (termux-x11 :1, zink + turnip, Adreno 830)
#
# 用法: xfce          静默启动, 不写日志
#       xfce log      日志写到 $TMPDIR/xfce_startup.log (排查问题用)
#       xfce debug    输出直接打在终端
#       xfce stop     退出桌面

if [ "$1" = stop ]; then
    echo "[*] 正在退出 XFCE4 桌面..."
    # 先正常注销, 让 Chromium 等程序自己保存退出; 会话常卡在注销阶段, 最多等 10 秒再强制结束
    # logout 自己有时也会卡住等会话响应, 加超时
    DISPLAY=:1 timeout 5 xfce4-session-logout --logout 2>/dev/null
    for i in $(seq 10); do pgrep -x xfce4-session >/dev/null || break; sleep 1; done
    pkill -x xfce4-session
    sleep 1
    pkill -f "^termux-x11 com.termux.x11"   # 进程名是 main, killall 匹配不到
    pulseaudio --kill 2>/dev/null
    echo "[√] 已退出, Termux:X11 界面可手动划掉"
    exit 0
fi

exec 3>&1
case "$1" in
    log)   exec >"$TMPDIR/xfce_startup.log" 2>&1 ;;
    debug) ;;
    *)     exec >/dev/null 2>&1 ;;
esac
echo "[*] 正在启动 XFCE4 桌面..." >&3

# 菜单和应用名用中文 (读 .desktop 的 zh_CN 条目)。XFCE 自身界面改不了: Termux 的 gettext 是空函数。
export LANGUAGE=zh_CN

# --- 1. 清理上次残留 ---
pkill -9 -f "^termux-x11 com.termux.x11"   # 进程名是 main, killall 匹配不到
killall -9 pulseaudio
rm -rf "$TMPDIR"/.X11-unix/X* "$TMPDIR"/.X*-lock

# --- 2. 音频 ---
pulseaudio --start --exit-idle-time=-1
pacmd load-module module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1
export PULSE_SERVER=127.0.0.1

# --- 3. GPU 加速: zink (OpenGL→Vulkan) + turnip, Mesa 26.2 原生 GL 4.6 ---
# 2026-10 在 Mesa 26.2.4 上实测 (glmark2), 只保留有用的:
export MESA_LOADER_DRIVER_OVERRIDE=zink  # 默认已自动选 zink, 显式固定以防回落软渲染
export ZINK_DESCRIPTORS=lazy             # 重场景比默认 db 模式快 ~40%, 默认模式还出现过卡死
export MESA_GLTHREAD=true
export MESA_VK_WSI_PRESENT_MODE=fifo     # 锁 120 帧, 不空跑。2026-11 无头实测 mailbox ~680 分, immediate ~1300 分, fifo 120 帧
export vblank_mode=0
# 已去掉 TU_DEBUG=sysmem: 它强制关闭 GMEM 分块渲染, 重场景慢 ~30%, 现在 gmem 校验全过。
# 若出现花屏/闪烁, 取消下一行注释:
# export TU_DEBUG=sysmem

# --- 4. 启动 X11 + XFCE ---
# -xstartup: X 就绪后才拉起会话, 不用 sleep 等。
# 边框: xfwm4 合成器已在 xfconf 里关闭 (use_compositing=false), 不再需要事后 xfwm4 --replace。
termux-x11 :1 -ac -xstartup "dbus-run-session startxfce4" &
am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity

echo "[√] 已启动, 切到 Termux:X11 查看。若无边框: xfwm4 --replace &" >&3
