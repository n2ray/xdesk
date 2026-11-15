package com.termux.x11;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;

/**
 * The icons of the switchboard's items, drawn the same on every surface (the phone's list, the Launchpad's tiles, the
 * Docks), and the key of its own tile. What the items are, their labels and what opening one does is the Switchboard's.
 * Main thread only.
 */
final class ExternalLauncher {
    /** The dock's last tile: opens the switcher (SwitcherOverlay) on the external display. */
    static final String SWITCHER = "x:switcher";

    private ExternalLauncher() { }

    /** The icon of an item in a view of its own (the phone's list, its dock). */
    static View icon(Context c, Switchboard.Item it) {
        return icon(c, it, c.getResources().getDisplayMetrics().density);
    }

    /**
     * The same on the external screen: the shell's factor (Sizing.shell) is passed in, so the letter tile's corners
     * follow b·u and not the density override the Android apps on the display set.
     */
    static View icon(Context c, Switchboard.Item it, float density) {
        ImageView icon = new ImageView(c);
        icon(c, it, icon, density);
        return icon;
    }

    /**
     * Draws an item's icon into {@code into}: its app's picture, its content's picture, or the one letter tile every
     * view uses. A whole desktop gets the same glyph here, in the phone's list and in both Docks.
     */
    static void icon(Context c, Switchboard.Item it, ImageView into) {
        icon(c, it, into, c.getResources().getDisplayMetrics().density);
    }

    private static void icon(Context c, Switchboard.Item it, ImageView into, float density) {
        into.setTag(null);
        if (SWITCHER.equals(it.key)) {
            into.setImageDrawable(Style.letterIcon(c, "⊞", density));
            return;
        }
        if (it.app != null) {
            AppCatalog.get(c).showIcon(it.app, into);
            return;
        }
        if (it.bitmap != null) {
            into.setImageBitmap(it.bitmap);
            return;
        }
        into.setImageDrawable(Style.letterIcon(c, it.desktop ? "▣" : it.label, density));
    }
}
