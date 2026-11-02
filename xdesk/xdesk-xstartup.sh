#!/data/data/com.termux/files/usr/bin/bash
# X 起来后先等分辨率到位再启动 XFCE，否则桌面和壁纸会停在手机的分辨率。
# 目标分辨率由 x11-session.sh 写在 $PREFIX/tmp/xdesk/res（环境变量未必能穿过 termux-x11），等 10 秒还不到位就照常启动。
export DISPLAY=:1
XDESK_RES=$(cat "$PREFIX/tmp/xdesk/res" 2>/dev/null)
WANT="current ${XDESK_RES%x*} x ${XDESK_RES#*x},"
for i in $(seq 40); do
    timeout 2 xrandr 2>/dev/null | grep -q "$WANT" && break
    sleep 0.25
done
echo "$(date +%T) xstartup: $(timeout 2 xrandr 2>/dev/null | sed -n 's/.*\(current [0-9]* x [0-9]*\).*/\1/p') 等了 ${i} 次，目标 $XDESK_RES" >> "$PREFIX/tmp/xdesk.log"
exec dbus-run-session startxfce4
