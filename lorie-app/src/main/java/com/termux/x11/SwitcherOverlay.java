package com.termux.x11;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The switcher on the external display (DISPLAY.md §6): what is running in every system (Android
 * apps, Linux windows, Mac windows) and the favourites, over whatever is shown. Four fingers up,
 * the handle or the dock open it with every system; three fingers up ("overview") with only the
 * system of what is on the screen. Picking a tile opens it there; a click outside closes it.
 *
 * A plain overlay window like ExternalCover: nothing is composed into the content, and it only
 * exists while open. Main thread only.
 */
final class SwitcherOverlay {
    private static final String TAG = "XDeskSwitcher";
    /** Sizes as parts of the display's shorter side, like DashboardView, so any display works. */
    private static final float ICON = 0.1f, CELL = 0.16f, TITLE = 0.034f, LABEL = 0.022f, SECTION = 0.026f;
    private static final int SCRIM = 0xD0101215, TILE_HOVER_BACK = 0x22FFFFFF;

    interface Host {
        /** Keys of what is running, most recent first (the current one first). */
        List<String> running();
        List<String> favorites();
        /** The key on the screen now, or null. */
        String current();
        void open(String key);
        /** The switcher was closed (so the cursor overlay can be raised again, the dock re-armed). */
        void closed();
    }

    private static View shown;
    private static WindowManager shownIn;
    private static View pointer;
    private static Host shownHost;
    private static final List<View> tiles = new ArrayList<>();
    private static final List<String> tileKeys = new ArrayList<>();
    private static float px, py;
    private static int width, height;

    /**
     * While the switcher is open it takes the input itself, whatever is under it (an X desktop or a Mac
     * stream do not get Android clicks): moves move its own pointer, a click opens the tile under it,
     * Escape closes. No injection, no focus change.
     */
    static final InputSink INPUT = new InputSink() {
        @Override public void move(int dx, int dy) { place(px + dx, py + dy); }
        @Override public void moveToFraction(float x, float y) { place(x * width, y * height); }
        @Override public void button(int button, boolean down) { if (!down) click(button); }
        @Override public void click(int button) { SwitcherOverlay.click(button); }
        @Override public void scroll(int dy, int dx) { }
        @Override public void key(int scan, boolean down) { if (down && scan == 1) hide(shownHost); }
        @Override public void tapKey(int scan) { if (scan == 1) hide(shownHost); }
        @Override public void text(String s) { }
        @Override public void androidButton(int b) { hide(shownHost); }
    };

    private static void place(float x, float y) {
        if (pointer == null) return;
        px = Math.max(0, Math.min(width - 1, x));
        py = Math.max(0, Math.min(height - 1, y));
        pointer.setTranslationX(px);
        pointer.setTranslationY(py);
        int[] at = new int[2];
        for (View t : tiles) {
            t.getLocationOnScreen(at);
            boolean over = px >= at[0] && px < at[0] + t.getWidth() && py >= at[1] && py < at[1] + t.getHeight();
            t.setHovered(over);
        }
    }

    private static void click(int button) {
        if (shown == null) return;
        if (button != 1) { hide(shownHost); return; }
        int[] at = new int[2];
        for (int i = 0; i < tiles.size(); i++) {
            View t = tiles.get(i);
            t.getLocationOnScreen(at);
            if (px >= at[0] && px < at[0] + t.getWidth() && py >= at[1] && py < at[1] + t.getHeight()) {
                Host h = shownHost;
                String key = tileKeys.get(i);
                hide(h);
                if (h != null) h.open(key);
                return;
            }
        }
        hide(shownHost);
    }

    private SwitcherOverlay() { }

    static boolean isShown() {
        return shown != null;
    }

    /**
     * @param onlySystem show only the system (key prefix) of the current content: the overview
     */
    static void show(Context context, Display display, final Host host, boolean onlySystem) {
        hide(null);
        if (display == null) return;
        Context dc = context.createDisplayContext(display);
        WindowManager wm = dc.getSystemService(WindowManager.class);
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        float side = Math.min(size.x, size.y);

        String current = host.current();
        String system = onlySystem && current != null ? systemOf(current) : null;
        List<String> running = filter(host.running(), system, dc);
        List<String> favourites = new ArrayList<>();
        if (system == null) for (String k : host.favorites()) if (!running.contains(k) && ExternalLauncher.exists(dc, k)) favourites.add(k);

        FrameLayout root = new FrameLayout(dc);
        root.setBackgroundColor(SCRIM);
        root.setOnClickListener(v -> hide(host));
        LinearLayout column = new LinearLayout(dc);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClickable(true);  // a click between tiles does not close it
        int pad = Math.round(side * 0.04f);
        column.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(dc);
        title.setText(system != null ? R.string.xd_switcher_overview : R.string.xd_switcher_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * TITLE);
        column.addView(title);
        if (!running.isEmpty()) addSection(dc, column, dc.getString(R.string.xd_switcher_running), running, current, side, host);
        if (!favourites.isEmpty()) addSection(dc, column, dc.getString(R.string.xd_chooser_favourites), favourites, current, side, host);
        if (running.isEmpty() && favourites.isEmpty()) {
            TextView none = new TextView(dc);
            none.setText(R.string.xd_switcher_empty);
            none.setTextColor(Style.TEXT_DIM);
            none.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * LABEL);
            column.addView(none);
        }
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(column, cp);
        width = size.x;
        height = size.y;
        View dot = new View(dc);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.WHITE);
        d.setStroke(Math.max(2, Math.round(side * 0.003f)), Style.ACCENT);
        dot.setBackground(d);
        int dotSize = Math.max(12, Math.round(side * 0.018f));
        root.addView(dot, new FrameLayout.LayoutParams(dotSize, dotSize));
        pointer = dot;
        shownHost = host;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("XDesk switcher");
        try {
            wm.addView(root, p);
            shown = root;
            shownIn = wm;
            place(width / 2f, height / 2f);
        } catch (RuntimeException e) {
            Log.w(TAG, "can't show the switcher", e);
        }
    }

    static void hide(Host host) {
        if (shown == null) return;
        tiles.clear();
        tileKeys.clear();
        pointer = null;
        shownHost = null;
        try {
            shownIn.removeView(shown);
        } catch (RuntimeException ignored) { }
        shown = null;
        shownIn = null;
        if (host != null) host.closed();
    }

    /** "a:" Android, "l:" Linux, "m:<mac>" one Mac (a Mac's keys share the part up to the second ':'). */
    static String systemOf(String key) {
        if (key.startsWith("m:")) {
            int end = key.indexOf(':', 2);
            return end > 0 ? key.substring(0, end + 1) : "m:";
        }
        int colon = key.indexOf(':');
        return colon > 0 ? key.substring(0, colon + 1) : key;
    }

    private static List<String> filter(List<String> keys, String system, Context c) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String k : keys) {
            if (system != null && !k.startsWith(system)) continue;
            if (ExternalLauncher.exists(c, k)) out.add(k);
        }
        return new ArrayList<>(out);
    }

    private static void addSection(Context c, LinearLayout column, String label, List<String> keys, String current, float side, Host host) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(Style.TEXT_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * SECTION);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = Math.round(side * 0.03f);
        column.addView(t, tp);
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        int cell = Math.round(side * CELL);
        for (String k : keys) row.addView(tile(c, k, k.equals(current), side, host), new LinearLayout.LayoutParams(cell, cell));
        scroll.addView(row);
        column.addView(scroll, new LinearLayout.LayoutParams(-2, -2));
    }

    /** Shown while the switcher's own pointer is over a tile (setHovered). */
    private static android.graphics.drawable.Drawable hoverMark(float side) {
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(side * 0.024f);
        on.setColor(TILE_HOVER_BACK);
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_hovered}, on);
        return s;
    }

    private static View tile(Context c, final String key, boolean current, float side, final Host host) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        int pad = Math.round(side * 0.012f);
        box.setPadding(pad, pad, pad, pad);
        final GradientDrawable back = new GradientDrawable();
        back.setCornerRadius(side * 0.024f);
        back.setColor(current ? Style.ACCENT & 0x55FFFFFF : Color.TRANSPARENT);
        box.setBackground(back);
        int icon = Math.round(side * ICON);
        box.addView(ExternalLauncher.icon(c, key, icon), new LinearLayout.LayoutParams(icon, icon));
        TextView name = new TextView(c);
        ContentSource.Entry e = Contents.find(key);
        String label = ExternalLauncher.label(c, key);
        if (e != null && e.detail != null && !e.detail.equals(label)) label = e.detail;
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * LABEL);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = Math.round(side * 0.01f);
        box.addView(name, np);
        box.setOnHoverListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER && !current) back.setColor(TILE_HOVER_BACK);
            else if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT && !current) back.setColor(Color.TRANSPARENT);
            return false;
        });
        box.setForeground(hoverMark(side));
        tiles.add(box);
        tileKeys.add(key);
        box.setOnClickListener(v -> {
            hide(host);
            host.open(key);
        });
        return box;
    }
}
