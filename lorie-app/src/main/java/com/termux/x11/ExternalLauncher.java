package com.termux.x11;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.termux.x11.app.R;
import java.util.ArrayList;
import java.util.List;

/**
 * What the grid and the dock on the external display show, kept by the phone's window: "my favourites" there is the one
 * list, edited only on the phone. Main thread only.
 */
final class ExternalLauncher {
    interface Host {
        List<String> favorites();
        /** Null when there is none. */
        String current();
        String previous();
        void open(String key);
    }

    /** The dock's last tile: opens the switcher (SwitcherOverlay) on the external display. */
    static final String SWITCHER = "x:switcher";

    private static Host host;
    private static final List<Runnable> listeners = new ArrayList<>();

    private ExternalLauncher() { }

    static void setHost(Host h) {
        host = h;
        changed();
    }

    static Host host() {
        return host;
    }

    static void listen(Runnable r) {
        listeners.add(r);
    }

    static void unlisten(Runnable r) {
        listeners.remove(r);
    }

    static void changed() {
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    static void open(Context c, String key) {
        if (host != null) host.open(key);
        else Toast.makeText(c, R.string.xd_launcher_need_phone, Toast.LENGTH_LONG).show();
    }

    /** False for an app that was uninstalled, or that the list has not reached yet. */
    static boolean exists(Context c, String key) {
        if (SWITCHER.equals(key)) return true;
        if (Contents.sourceOf(key) != null) return Contents.find(key) != null;
        return !ChooserView.isAppKey(key) || AppCatalog.get(c).find(ChooserView.componentOf(key)) != null;
    }

    static String label(Context c, String key) {
        if (SWITCHER.equals(key)) return c.getString(R.string.xd_switcher_short);
        if (Contents.sourceOf(key) != null) {
            ContentSource.Entry e = Contents.find(key);
            return e != null ? e.label : "";
        }
        if (!ChooserView.isAppKey(key)) return ChooserView.sessionOf(key);
        AppCatalog.App a = AppCatalog.get(c).find(ChooserView.componentOf(key));
        return a != null ? a.label : "";
    }

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
            else icon.setImageDrawable(Style.letterIcon(c, e != null ? e.label : "?"));
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
