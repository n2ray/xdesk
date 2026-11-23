#!/bin/sh
# XDesk's built-in Ubuntu: its look (owner, 22-23 November). Flat Remix GTK, dark with a blue accent as XDesk itself,
# and its window borders; Papirus-Dark icons; one tidy panel at the top (no dock, the clock on one line, no workspace
# switcher: one workspace); a dark wallpaper in XDesk's colours. Used by build-rootfs.sh (with look/ beside it), and
# once on a system already installed (it also fixes that user's settings where they still have the stock values).
# Flat Remix GTK: Daniel Ruiz de Alegría, GPL-3.0, https://github.com/daniruiz/flat-remix-gtk
set -e
LOOK=${LOOK:-$(dirname "$0")/look}
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

# The panel: XDesk's own default (look/panel.xml) instead of the stock two panels
install -m 644 "$LOOK/panel.xml" /etc/xdg/xfce4/panel/default.xml
# Icons: Papirus-Dark (the image installs papirus-icon-theme); the stock setting named a theme the image never had
[ -d /usr/share/icons/Papirus-Dark ] && sed -i 's|<property name="IconThemeName" type="string" value="[^"]*"/>|<property name="IconThemeName" type="string" value="Papirus-Dark"/>|' $X/xsettings.xml
# One workspace, window buttons without "shade"
sed -i 's/^workspace_count=.*/workspace_count=1/; s/^button_layout=.*/button_layout=O|HMC/' /usr/share/xfwm4/defaults
# The wallpaper: xfdesktop's default picture (4.18 on: xfce-x.svg) becomes XDesk's, diverted so updates keep it
mkdir -p /usr/share/backgrounds/xdesk
install -m 644 "$LOOK/xdesk-dark.svg" /usr/share/backgrounds/xdesk/xdesk-dark.svg
B=/usr/share/backgrounds/xfce/xfce-x.svg
if command -v dpkg-divert >/dev/null && [ "$(dpkg-divert --truename $B)" = $B ]; then
    dpkg-divert --local --rename --add --divert $B.xfce $B >/dev/null
fi
ln -sf /usr/share/backgrounds/xdesk/xdesk-dark.svg $B

# A system already in use: its user's settings where they are still the stock ones
for h in /root /home/*; do
    U=$h/.config/xfce4/xfconf/xfce-perchannel-xml
    [ -d "$U" ] || continue
    # The stock panel (a menu, a task list, no launchers of the user's): XDesk's instead, the old one kept beside it
    if [ -f "$U/xfce4-panel.xml" ] && grep -q 'value="tasklist"' "$U/xfce4-panel.xml" && ! grep -q 'value="launcher"' "$U/xfce4-panel.xml" \
        && ! grep -q 'name="digital-layout" type="uint" value="3"' "$U/xfce4-panel.xml"; then
        cp "$U/xfce4-panel.xml" "$U/xfce4-panel.xml.xdesk-old"
        cp "$LOOK/panel.xml" "$U/xfce4-panel.xml"
    fi
    [ -f "$U/xfwm4.xml" ] && sed -i -e "s|<property name=\"theme\" type=\"string\" value=\"Default\"/>|<property name=\"theme\" type=\"string\" value=\"$WM\"/>|" \
        -e 's|<property name="workspace_count" type="int" value="4"/>|<property name="workspace_count" type="int" value="1"/>|' \
        -e 's/<property name="button_layout" type="string" value="O|SHMC"\/>/<property name="button_layout" type="string" value="O|HMC"\/>/' "$U/xfwm4.xml"
    [ -f "$U/xsettings.xml" ] && sed -i "s|<property name=\"ThemeName\" type=\"string\" value=\"Greybird\"/>|<property name=\"ThemeName\" type=\"string\" value=\"$GTK\"/>|" "$U/xsettings.xml"
    [ -f "$U/xsettings.xml" ] && [ -d /usr/share/icons/Papirus-Dark ] && sed -i 's|<property name="IconThemeName" type="string" value="elementary-xfce-dark"/>|<property name="IconThemeName" type="string" value="Papirus-Dark"/>|' "$U/xsettings.xml"
done
true
