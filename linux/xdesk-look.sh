#!/bin/sh
# XDesk's built-in Ubuntu: its look (owner, 22 November). Flat Remix GTK, dark with a blue accent as XDesk itself, and
# its window borders; one panel at the top, no dock at the bottom. Used by build-rootfs.sh, and once on a system already
# installed (it also fixes that user's settings where they still have the stock values).
# Flat Remix GTK: Daniel Ruiz de Alegría, GPL-3.0, https://github.com/daniruiz/flat-remix-gtk
set -e
TAG=20240730
SHA=622ea576999bbdd5c76faa685dd58fff6a34cf3d7d6939a94b3742ed8cfd37ba
GTK=Flat-Remix-GTK-Blue-Dark
WM=Flat-Remix-Dark-XFWM

work=$(mktemp -d)
curl -fsSL -o "$work/fr.tgz" "https://github.com/daniruiz/flat-remix-gtk/archive/refs/tags/$TAG.tar.gz"
echo "$SHA  $work/fr.tgz" | sha256sum -c - >/dev/null
for t in $GTK $GTK-Solid $WM $WM-HiDPI $WM-xHiDPI; do
    tar xzf "$work/fr.tgz" -C "$work" "flat-remix-gtk-$TAG/themes/$t"
    rm -rf "/usr/share/themes/$t"
    mv "$work/flat-remix-gtk-$TAG/themes/$t" /usr/share/themes/
done
mkdir -p /usr/share/doc/flat-remix-gtk
tar xzf "$work/fr.tgz" -C "$work" "flat-remix-gtk-$TAG/LICENSE" && cp "$work/flat-remix-gtk-$TAG/LICENSE" /usr/share/doc/flat-remix-gtk/
rm -rf "$work"

# The defaults: GTK theme (xsettings), window borders (xfwm4 reads its defaults file, and writes them into the user's
# settings on its first start)
X=/etc/xdg/xfce4/xfconf/xfce-perchannel-xml
sed -i "s|<property name=\"ThemeName\" type=\"string\" value=\"[^\"]*\"/>|<property name=\"ThemeName\" type=\"string\" value=\"$GTK\"/>|" $X/xsettings.xml
sed -i "s/^theme=.*/theme=$WM/" /usr/share/xfwm4/defaults

# No dock: panel 2 (the bottom one) and its plugins 11-18 out of a panel config
nodock() {
    [ -f "$1" ] || return 0
    awk '
        skip { if ($0 ~ /^    <\/property>/) skip = 0; next }
        /^    <value type="int" value="2"\/>$/ { next }
        /^    <property name="panel-2"/ { skip = 1; next }
        /^    <property name="plugin-1[1-8]"/ { if ($0 !~ /\/>$/) skip = 1; next }
        { print }
    ' "$1" > "$1.new" && mv "$1.new" "$1"
}
nodock /etc/xdg/xfce4/panel/default.xml

# A system already in use: its user's settings where they are still the stock ones
for h in /root /home/*; do
    U=$h/.config/xfce4/xfconf/xfce-perchannel-xml
    [ -d "$U" ] || continue
    nodock "$U/xfce4-panel.xml"
    [ -f "$U/xfwm4.xml" ] && sed -i "s|<property name=\"theme\" type=\"string\" value=\"Default\"/>|<property name=\"theme\" type=\"string\" value=\"$WM\"/>|" "$U/xfwm4.xml"
    [ -f "$U/xsettings.xml" ] && sed -i "s|<property name=\"ThemeName\" type=\"string\" value=\"Greybird\"/>|<property name=\"ThemeName\" type=\"string\" value=\"$GTK\"/>|" "$U/xsettings.xml"
done
true
