package com.termux.x11;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The icons of the switchboard's items on the external display (the Launchpad's tiles, the Dock), and the key of its
 * own tile. What the items are, their labels and what opening one does is the Switchboard's. Main thread only.
 */
final class ExternalLauncher {
    /** The dock's last tile: opens the switcher (SwitcherOverlay) on the external display. */
    static final String SWITCHER = "x:switcher";

    private ExternalLauncher() { }

    static View icon(Context c, String key, int size) {
        if (SWITCHER.equals(key)) {
            ImageView all = new ImageView(c);
            all.setImageDrawable(Style.letterIcon(c, "\u229E"));
            return all;
        }
        if (Contents.sourceOf(key) != null) {
            ContentSource.Entry e = Contents.find(key);
            ImageView icon = new ImageView(c);
            if (e != null && e.icon != null) icon.setImageBitmap(e.icon);
            // A whole desktop without a picture: the glyph it has in the phone's list
            else icon.setImageDrawable(Style.letterIcon(c, ChooserView.isDesktopKey(key) ? "\u25A3" : e != null ? e.label : "?"));
            return icon;
        }
        AppCatalog.App a = ChooserView.isAppKey(key) ? AppCatalog.get(c).find(ChooserView.componentOf(key)) : null;
        if (a != null) {
            ImageView icon = new ImageView(c);
            AppCatalog.get(c).showIcon(a, icon);
            return icon;
        }
        TextView tile = new TextView(c);
        tile.setText(">_");
        tile.setTextColor(Style.TEXT);
        tile.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * 0.36f);
        tile.setTypeface(Typeface.MONOSPACE);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Style.rounded(c, Style.SURFACE_RAISED, Style.RADIUS * 1.5f));
        return tile;
    }
}
