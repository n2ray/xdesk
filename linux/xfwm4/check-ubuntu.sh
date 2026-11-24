#!/bin/sh
# Is XDesk's xfwm4 still Ubuntu's current one with the patch? check-ubuntu.sh DEBS (in ubuntu:26.04, apt lists fresh)
# XDesk's repository has Ubuntu's priority (xdesk-core's preferences): the higher version wins. Ubuntu moving on
# means users get Ubuntu's (fine: its fixes first) and XDesk should rebuild to keep the patch; Ubuntu numbering a
# newer upload so that it sorts below ours ("1build1ubuntu0.1" < "1build1+xdesk1") would keep ours over Ubuntu's
# fixes, and must be rebuilt at once. Warnings for the workflow's summary; never fails the build.
set -eu
deb=$(ls "$1"/xfwm4_*_arm64.deb)
ours=$(dpkg-deb -f "$deb" Version)
base=${ours%+xdesk*}
ubuntu=$(apt-cache madison xfwm4 | awk -F'|' '$3 ~ /ubuntu/ {gsub(/ /, "", $2); print $2; exit}')
echo "xfwm4: XDesk $ours (from $base), Ubuntu $ubuntu"
[ -n "$ubuntu" ] || { echo "::warning::xfwm4: Ubuntu's version not found"; exit 0; }
if [ "$ubuntu" = "$base" ]; then
    echo "xfwm4: current"
elif dpkg --compare-versions "$ubuntu" gt "$base" && dpkg --compare-versions "$ours" gt "$ubuntu"; then
    echo "::warning::xfwm4: Ubuntu $ubuntu is newer than $base but sorts below $ours: users keep XDesk's without Ubuntu's fixes. Rebuild on $ubuntu now."
elif dpkg --compare-versions "$ubuntu" gt "$base"; then
    echo "::warning::xfwm4: Ubuntu moved to $ubuntu (XDesk's is from $base): users get Ubuntu's; rebuild to keep the patch."
fi
