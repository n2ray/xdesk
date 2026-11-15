package com.termux.x11;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The switchboard on the external display (DEVICES.md §3), as a grid like the Mac's Launchpad: a dark scrim over
 * whatever is shown, a search line and the tabs at the top, the tab's device bar, and a grid of big icons, page by
 * page. What the tabs, the items and the bar are comes from Switchboard, the same model the phone draws as a list
 * (ChooserView): the same tab and the same search on both, live. This draws it and has what belongs to this screen:
 * its own pointer, the keyboard navigation, the typed search line, the pages. The dock's "All", four fingers up and
 * the handle open it on the tab last used; three fingers up ("overview") on 正在运行. With nothing else on the screen
 * it is the screen's home (showHome).
 *
 * Sizes come from Sizing.shell (§4.1: b·u dp), not the display context's Android density, so whatever density
 * override the Android apps on the screen set, the grid stays the same: icon and cell sizes in dp, capped by the
 * screen, and every row is given its exact width.
 *
 * A plain overlay window like ExternalCover, not focusable: nothing is composed into the content and it only exists
 * while open. The phone's touchpad and keyboard reach it through INPUT (its own pointer; typing filters). Only the
 * page shown has views, so opening stays instant with hundreds of apps. Main thread only.
 */
final class SwitcherOverlay {
    private static final String TAG = "XDeskSwitcher";
    /**
     * The home has nothing to show through: under it is the dashboard, whose big clock would read as a second layer,
     * or the bare background of an X server with no window open. It says the time itself (CLOCK_MS).
     */
    private static final int HOME_BACK = 0xFF0B0D10;
    private static final int SCRIM = 0xE00B0D10, HOVER = 0x2EFFFFFF, CURRENT = Switchboard.CURRENT_TINT, FIELD = 0x26FFFFFF;
    private static final long CLOCK_MS = 15000;
    private static final int MAX_COLUMNS = 8, MAX_ROWS = 5;
    private static final long NOTICE_MS = 4000;

    interface Host {
        /** The switcher was closed (so the cursor overlay can be raised again, the dock re-armed). */
        void closed();
    }

    private static Panel panel;

    /**
     * While the switcher is open it takes the input itself, whatever is under it (an X desktop or a Mac stream do not
     * get Android clicks): moves move its own pointer, a click opens what is under it, keys navigate and type into the
     * search. No injection, no focus change.
     */
    static final InputSink INPUT = new InputSink() {
        @Override public void move(int dx, int dy) { onUi(p -> p.place(p.px + dx, p.py + dy)); }
        @Override public void moveToFraction(float x, float y) { onUi(p -> p.place(x * p.width, y * p.height)); }
        @Override public void button(int button, boolean down) { if (!down) onUi(p -> p.click(button)); }
        @Override public void click(int button) { onUi(p -> p.click(button)); }
        @Override public void scroll(int dy, int dx) { onUi(p -> p.wheel(dy != 0 ? dy : dx)); }
        // A mouse wheel (phase WHEEL) turns a page per notch; a touchpad's fine scrolling goes on as whole steps (scroll)
        @Override public boolean scrollFine(float dy, float dx, int phase) {
            if (phase != io.github.n2ray.xdesk.input.Input.WHEEL) return false;
            float v = dy != 0 ? dy : dx;
            onUi(p -> p.wheelNotch(v));
            return true;
        }
        @Override public void key(int scan, boolean down) { onUi(p -> p.key(scan, down)); }
        @Override public void tapKey(int scan) { onUi(p -> { p.key(scan, true); p.key(scan, false); }); }
        @Override public void text(String s) { onUi(p -> p.type(s)); }
        @Override public void androidButton(int b) { onUi(p -> { if (!p.home) hide(p.host); }); }
    };

    private interface PanelAction {
        void run(Panel p);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Receivers and browsers send input on their network thread: the views are touched on the main thread only. */
    private static void onUi(PanelAction a) {
        Runnable r = () -> {
            if (panel != null) a.run(panel);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else UI.post(r);
    }

    private SwitcherOverlay() { }

    static boolean isShown() {
        return panel != null;
    }

    /**
     * @param onlySystem the overview: open on 正在运行 instead of the last tab
     * @return false when it could not be shown (no overlay permission): the caller keeps its input.
     */
    static boolean show(Context context, Display display, final Host host, boolean onlySystem) {
        return show(context, display, host, onlySystem, false);
    }

    /**
     * The external screen's home (INPUT.md: the home is a target like any other): nothing else is on the screen, so
     * the switchboard is what the screen shows, over the dashboard, with or without a mouse on the phone (the phone's
     * touchpad moves its pointer just the same). It does not close itself: Esc
     * only clears the search, a click on empty space and Back do nothing; it goes when something is opened or the
     * caller hides it.
     */
    static boolean showHome(Context context, Display display, Host host) {
        return show(context, display, host, false, true);
    }

    static boolean isHome() {
        return panel != null && panel.home;
    }

    private static boolean show(Context context, Display display, final Host host, boolean onlySystem, boolean home) {
        hide(null);
        if (display == null || !android.provider.Settings.canDrawOverlays(context)) return false;
        Panel p = new Panel(context.createDisplayContext(display), display, host, home);
        // The overview is the running tab; it does not become the tab to come back to
        if (onlySystem) p.board.setTab(Switchboard.TAB_RUNNING, false);
        p.build();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("XDesk switcher");
        try {
            p.wm.addView(p.root, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "can't show the switcher", e);
            return false;
        }
        panel = p;
        p.board.addListener(p.changed);
        if (home) p.clock.run();
        p.place(p.width / 2f, p.height / 2f);
        // Its own pointer shows once something moves it: over an Android app the mouse is a real Android pointer
        // with the app's cursor, and a second dot resting in the middle would be taken for it
        p.pointer.setVisibility(View.INVISIBLE);
        return true;
    }

    static void hide(Host host) {
        Panel p = panel;
        if (p == null) return;
        panel = null;
        p.board.removeListener(p.changed);
        UI.removeCallbacks(p.refresh);
        UI.removeCallbacks(p.noticeGone);
        UI.removeCallbacks(p.clock);
        // A search is for finding one thing: it does not wait for the next time (the home's stays, it is not closed)
        if (!p.home) p.board.setQuery("");
        try {
            p.wm.removeView(p.root);
        } catch (RuntimeException ignored) { }
        if (host != null) host.closed();
    }

    /** Something the pointer can point at. */
    private static final class Target {
        final View view;
        final Runnable action;
        /** Its place in the page's tiles, -1 for tabs and buttons. */
        final int tile;

        Target(View view, Runnable action, int tile) {
            this.view = view;
            this.action = action;
            this.tile = tile;
        }
    }

    private static final class Panel {
        final Context c;
        /** The screen's home (showHome): it stays until something is opened. */
        final boolean home;
        final WindowManager wm;
        final Host host;
        final Switchboard board;
        final float density;
        final int width, height;
        /** Pixel sizes worked out once per show from the shell's dp (b·u). */
        final int icon, cellW, cellH, columns, rows, label, small, gap;
        final FrameLayout root;
        final LinearLayout column, tabRow, deviceBar, grid, bottom;
        final TextView searchText, deviceState, time;
        final View pointer;
        final List<Target> targets = new ArrayList<>();
        final List<View> tiles = new ArrayList<>();
        /** The model's items of the tab (or the search), without its titles and notes: the tiles, over all pages. */
        final List<Switchboard.Item> items = new ArrayList<>();
        final Runnable refresh = this::rebuildKeep;
        /** The home's own clock, as the dashboard behind it can't be seen. */
        final Runnable clock = new Runnable() {
            @Override public void run() {
                time.setText(DashboardView.clockLine(c));
                UI.postDelayed(this, CLOCK_MS);
            }
        };
        final Runnable changed = () -> {
            UI.removeCallbacks(refresh);
            UI.postDelayed(refresh, 120);
        };
        /** A line shown in place of the search hint for a moment ("Its settings are on the phone"). */
        String notice;
        final Runnable noticeGone = () -> {
            notice = null;
            if (panel == this) showSearch();
        };
        /** The tab and the search the grid was last built for: changed from the phone, the page starts over. */
        String shownTab, shownQuery;
        /** What the model says in place of items (nothing running, no match), or null. */
        String empty;
        int page, selected = -1, hiddenCount;
        boolean ctrl, shift;
        float px, py, wheel;

        Panel(Context dc, Display display, Host host, boolean home) {
            c = dc;
            this.home = home;
            wm = dc.getSystemService(WindowManager.class);
            this.host = host;
            board = Switchboard.get(dc);
            Point size = new Point();
            display.getRealSize(size);
            width = size.x;
            height = size.y;
            // §4.1: the shell's own factor (b·u), never the density override an Android app put on the display
            density = Sizing.shell(c, display).density;
            float wDp = width / density, hDp = height / density;
            // Big icons: about a twelfth of the shorter side, 52–96 dp (Launchpad's are ~ 80 dp on a laptop)
            float iconDp = clamp(Math.min(wDp, hDp) * 0.085f, 52, 96);
            float labelDp = clamp(iconDp * 0.19f, 12, 16);
            icon = dp(iconDp);
            label = dp(labelDp);
            small = dp(labelDp * 0.82f);
            gap = dp(8);
            cellW = dp(iconDp * 1.85f);
            cellH = icon + Math.round(label * 1.4f + small * 1.4f) + dp(18);
            // Room above the grid: margin, search, tabs, the device line; below: the page dots
            int top = dp(20 + 40 + 14 + 38 + 8 + 30 + 10), below = dp(56);
            columns = Math.max(3, Math.min(MAX_COLUMNS, (int) (width * 0.92f / cellW)));
            rows = Math.max(1, Math.min(MAX_ROWS, (height - top - below) / cellH));

            root = new FrameLayout(c);
            root.setBackgroundColor(home ? HOME_BACK : SCRIM);
            root.setOnClickListener(v -> { if (!home) hide(host); });
            column = new LinearLayout(c);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setPadding(0, dp(20), 0, dp(10));
            root.addView(column, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            // The search line: not an EditText (the window takes no focus), the keys come through INPUT
            searchText = text("", label, Color.WHITE);
            searchText.setSingleLine(true);
            searchText.setEllipsize(TextUtils.TruncateAt.START);
            searchText.setGravity(Gravity.CENTER_VERTICAL);
            searchText.setPadding(dp(16), 0, dp(16), 0);
            searchText.setBackground(round(FIELD, dp(20)));
            searchText.setClickable(true);
            column.addView(searchText, new LinearLayout.LayoutParams(Math.min(dp(420), Math.round(width * 0.5f)), dp(40)));

            tabRow = new LinearLayout(c);
            tabRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            tp.topMargin = dp(14);
            column.addView(tabRow, tp);

            deviceBar = new LinearLayout(c);
            deviceBar.setGravity(Gravity.CENTER);
            deviceState = text("", small, Style.TEXT_DIM);
            deviceState.setSingleLine(true);
            deviceBar.addView(deviceState);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
            bp.topMargin = dp(8);
            column.addView(deviceBar, bp);

            // The grid: exactly columns × rows cells, centred in what is left
            FrameLayout gridBox = new FrameLayout(c);
            grid = new LinearLayout(c);
            grid.setOrientation(LinearLayout.VERTICAL);
            gridBox.addView(grid, new FrameLayout.LayoutParams(columns * cellW, rows * cellH, Gravity.CENTER));
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
            gp.topMargin = dp(10);
            column.addView(gridBox, gp);

            bottom = new LinearLayout(c);
            bottom.setGravity(Gravity.CENTER);
            column.addView(bottom, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

            time = text("", small, Style.TEXT_DIM);
            time.setVisibility(home ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams clockAt = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            clockAt.topMargin = dp(30);
            clockAt.rightMargin = dp(28);
            root.addView(time, clockAt);

            pointer = new View(c);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(Color.WHITE);
            d.setStroke(Math.max(2, dp(2)), Style.ACCENT);
            pointer.setBackground(d);
            int dot = Math.max(12, dp(12));
            root.addView(pointer, new FrameLayout.LayoutParams(dot, dot));
        }

        private static float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        int dp(float v) {
            return Math.round(v * density);
        }

        TextView text(String s, int px, int color) {
            TextView t = new TextView(c);
            t.setText(s);
            t.setTextColor(color);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
            t.setIncludeFontPadding(false);
            return t;
        }

        static GradientDrawable round(int color, float radius) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(color);
            g.setCornerRadius(radius);
            return g;
        }

        // --- what the model holds ---

        void collect() {
            items.clear();
            hiddenCount = 0;
            empty = null;
            for (Switchboard.Item i : board.items()) {
                if (i.kind == Switchboard.ITEM) items.add(i);
                else if (i.kind == Switchboard.FOLD) hiddenCount = i.count;
                else if (i.kind == Switchboard.NOTE && empty == null) empty = i.label;
            }
        }

        int perPage() {
            return columns * rows;
        }

        int pages() {
            return Math.max(1, (items.size() + perPage() - 1) / perPage());
        }

        // --- building ---

        void build() {
            UI.removeCallbacks(refresh);
            collect();
            String tab = board.tab(), query = board.query();
            if (!tab.equals(shownTab) || !query.equals(shownQuery)) page = 0;
            shownTab = tab;
            shownQuery = query;
            page = Math.max(0, Math.min(page, pages() - 1));
            render();
        }

        /**
         * Something changed while open (a Mac connected, icons came): same page, same tile. The tab or the search
         * changed on the phone: from the start, as when it is changed here.
         */
        void rebuildKeep() {
            if (panel != this) return;
            boolean moved = !board.tab().equals(shownTab) || !board.query().equals(shownQuery);
            int keep = selected;
            build();
            // While searching the first result is the one Enter opens
            select(moved ? (board.searching() ? 0 : -1) : Math.min(keep, tiles.size() - 1));
        }

        void render() {
            targets.clear();
            tiles.clear();
            selected = -1;
            showSearch();
            // Clicks on the search line and the device line do nothing (elsewhere on the scrim closes)
            targets.add(new Target(searchText, () -> { }, -1));
            targets.add(new Target(deviceBar, () -> { }, -1));
            renderTabs();
            renderDeviceBar();
            renderGrid();
            renderBottom();
        }

        void renderTabs() {
            tabRow.removeAllViews();
            boolean searching = board.searching();
            String tab = board.tab();
            for (Switchboard.Tab t : board.tabs()) addTab(t, t.id.equals(tab) && !searching);
        }

        void addTab(final Switchboard.Tab tab, boolean on) {
            TextView t = text(tab.name, small, on ? Color.WHITE : Style.TEXT_DIM);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(14), 0, dp(14), 0);
            t.setBackground(round(on ? Style.ACCENT : 0x00000000, dp(16)));
            if (tab.state != null) {
                GradientDrawable dot = Style.dot(Devices.color(tab.state));
                int s = dp(7);
                dot.setBounds(0, 0, s, s);
                t.setCompoundDrawablesRelative(dot, null, null, null);
                t.setCompoundDrawablePadding(dp(6));
            }
            Runnable go = () -> setTab(tab.id);
            t.setOnClickListener(v -> go.run());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
            p.leftMargin = p.rightMargin = dp(3);
            tabRow.addView(t, p);
            targets.add(new Target(t, go, -1));
        }

        /**
         * The same bar as on the phone: the device's state and its actions, here to click with the pointer. What an
         * action shows (a confirm, a settings page) is on the phone, and the search line says so.
         */
        void renderDeviceBar() {
            while (deviceBar.getChildCount() > 1) deviceBar.removeViewAt(1);
            Switchboard.Bar bar = board.bar();
            deviceState.setText(bar != null ? bar.text : "");
            if (bar == null) return;
            if (bar.dot != 0) {
                GradientDrawable dot = Style.dot(bar.dot);
                int s = dp(7);
                dot.setBounds(0, 0, s, s);
                deviceState.setCompoundDrawablesRelative(dot, null, null, null);
                deviceState.setCompoundDrawablePadding(dp(8));
            } else {
                deviceState.setCompoundDrawablesRelative(null, null, null, null);
            }
            boolean first = true;
            for (final Switchboard.Action a : bar.actions) {
                TextView b = text(a.label, small, Color.WHITE);
                b.setPadding(dp(14), 0, dp(14), 0);
                b.setGravity(Gravity.CENTER);
                // The first is what the device is waiting for (Connect, Stop); the rest are quieter
                b.setBackground(round(first ? Style.ACCENT : FIELD, dp(14)));
                b.setAlpha(a.enabled ? 1f : 0.4f);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28));
                ap.leftMargin = dp(first ? 12 : 8);
                deviceBar.addView(b, ap);
                Runnable go = () -> {
                    if (!a.enabled) return;
                    if (a.onPhone) say(c.getString(R.string.xd_sw_on_phone));
                    a.run();
                };
                b.setOnClickListener(v -> go.run());
                targets.add(new Target(b, go, -1));
                first = false;
            }
        }

        void renderGrid() {
            grid.removeAllViews();
            if (items.isEmpty()) {
                TextView none = text(empty != null ? empty : c.getString(R.string.xd_dev_empty), label, Style.TEXT_DIM);
                none.setGravity(Gravity.CENTER);
                grid.addView(none, new LinearLayout.LayoutParams(columns * cellW, rows * cellH));
                return;
            }
            int from = page * perPage(), to = Math.min(items.size(), from + perPage());
            LinearLayout row = null;
            for (int i = from; i < to; i++) {
                if ((i - from) % columns == 0) {
                    row = new LinearLayout(c);
                    grid.addView(row, new LinearLayout.LayoutParams(columns * cellW, cellH));
                }
                row.addView(tile(items.get(i), i - from), new LinearLayout.LayoutParams(cellW, cellH));
            }
        }

        void showSearch() {
            String query = board.query();
            String hint = notice != null ? notice : "⌕  " + c.getString(R.string.xd_sw_search);
            // A notice is about what was just clicked: it shows also over a search, which comes back after it
            searchText.setText(notice != null ? notice : query.isEmpty() ? hint : query + "▏");
            searchText.setTextColor(query.isEmpty() && notice == null ? Style.TEXT_DIM : Color.WHITE);
        }

        /** A line in place of the search for a moment: where to look for what a click here opened. */
        void say(String line) {
            notice = line;
            showSearch();
            UI.removeCallbacks(noticeGone);
            UI.postDelayed(noticeGone, NOTICE_MS);
        }

        /**
         * The item's card. It is a dialog on the phone, where its switches and numbers can be touched and typed; the
         * Launchpad stays as it is and says where to look.
         */
        void card(String key) {
            if (board.card(key)) say(c.getString(R.string.xd_card_on_phone));
        }

        View tile(final Switchboard.Item it, final int index) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(gap / 2, dp(8), gap / 2, dp(4));
            box.setClickable(true);
            // What is on this screen now stands out, the same way as its row on the phone
            box.setBackground(tileBack(it, it.current ? CURRENT : 0x00000000));
            box.setAlpha(it.hidden || it.dim ? 0.5f : 1f);
            // The badge's ring in the grid's surface colour; the scrim is the same colour, only translucent
            box.addView(ExternalLauncher.icon(c, it, Style.BACKGROUND, board.ownTab(it.key), density),
                new LinearLayout.LayoutParams(icon, icon));
            TextView name = text(it.label, label, Color.WHITE);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            np.topMargin = dp(6);
            box.addView(name, np);
            if (it.detail != null) {
                TextView detail = text(it.detail, small, it.current ? Switchboard.ACCENT_TEXT : Style.TEXT_DIM);
                detail.setSingleLine(true);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                detail.setGravity(Gravity.CENTER);
                box.addView(detail, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            Runnable go = () -> open(it.key);
            box.setOnClickListener(v -> go.run());
            // A mouse that is Android's own on this screen: its secondary button, as click(3) below
            box.setOnContextClickListener(v -> {
                card(it.key);
                return true;
            });
            box.setOnHoverListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) select(index);
                return false;
            });
            tiles.add(box);
            targets.add(new Target(box, go, index));
            return box;
        }

        /** A whole desktop gets a ring, so it stands apart from the apps. */
        GradientDrawable tileBack(Switchboard.Item it, int color) {
            GradientDrawable g = round(color, dp(18));
            if (it.desktop) g.setStroke(Math.max(2, dp(2)), Style.ACCENT);
            return g;
        }

        void renderBottom() {
            bottom.removeAllViews();
            int n = pages();
            if (n > 1) {
                for (int i = 0; i < n; i++) {
                    final int to = i;
                    View dot = new View(c);
                    dot.setBackground(Style.dot(i == page ? Color.WHITE : 0x55FFFFFF));
                    // A bigger box than the dot, to hit it with the pointer
                    FrameLayout hit = new FrameLayout(c);
                    hit.addView(dot, new FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER));
                    Runnable go = () -> setPage(to);
                    hit.setOnClickListener(v -> go.run());
                    bottom.addView(hit, new LinearLayout.LayoutParams(dp(22), dp(32)));
                    targets.add(new Target(hit, go, -1));
                }
            }
            // The hidden fold of the list: here a button that puts the hidden ones at the end
            if (hiddenCount > 0 && !board.searching()) {
                final boolean open = board.hiddenOpen();
                TextView fold = text(open ? c.getString(R.string.xd_sw_hide_hidden) : c.getString(R.string.xd_sw_show_hidden, hiddenCount), small, Style.TEXT_DIM);
                fold.setGravity(Gravity.CENTER);
                fold.setPadding(dp(12), 0, dp(12), 0);
                fold.setBackground(round(FIELD, dp(14)));
                Runnable go = () -> {
                    board.setHiddenOpen(!open);
                    build();
                    if (!open) setPage(pages() - 1);
                };
                fold.setOnClickListener(v -> go.run());
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28));
                fp.leftMargin = dp(16);
                bottom.addView(fold, fp);
                targets.add(new Target(fold, go, -1));
            }
        }

        // --- doing ---

        void open(String key) {
            hide(host);
            board.open(key);
        }

        void setTab(String id) {
            board.setTab(id);
            page = 0;
            build();
            select(-1);
        }

        void stepTab(int by) {
            board.stepTab(by);
            page = 0;
            build();
            select(-1);
        }

        void setPage(int p) {
            p = Math.max(0, Math.min(pages() - 1, p));
            if (p == page) return;
            page = p;
            render();
            select(-1);
        }

        void select(int i) {
            if (selected >= 0 && selected < tiles.size()) {
                Switchboard.Item it = items.get(page * perPage() + selected);
                tiles.get(selected).setBackground(tileBack(it, it.current ? CURRENT : 0x00000000));
            }
            selected = i >= 0 && i < tiles.size() ? i : -1;
            if (selected >= 0) tiles.get(selected).setBackground(tileBack(items.get(page * perPage() + selected), HOVER));
        }

        // --- pointer ---

        void place(float x, float y) {
            px = Math.max(0, Math.min(width - 1, x));
            py = Math.max(0, Math.min(height - 1, y));
            if (pointer.getVisibility() != View.VISIBLE) pointer.setVisibility(View.VISIBLE);
            pointer.setTranslationX(px);
            pointer.setTranslationY(py);
            Target t = under();
            if (t != null && t.tile >= 0) {
                if (t.tile != selected) select(t.tile);
            }
        }

        Target under() {
            int[] at = new int[2];
            // Last added first: a button inside a line wins over the line
            for (int i = targets.size() - 1; i >= 0; i--) {
                Target t = targets.get(i);
                View v = t.view;
                if (v.getVisibility() != View.VISIBLE || !v.isAttachedToWindow()) continue;
                v.getLocationOnScreen(at);
                if (px >= at[0] && px < at[0] + v.getWidth() && py >= at[1] && py < at[1] + v.getHeight()) return t;
            }
            return null;
        }

        void click(int button) {
            Target t = under();
            if (button != 1) {
                // The secondary button on a tile is its card; anywhere else it closes, as any other button does
                if (button == 3 && t != null && t.tile >= 0) card(items.get(page * perPage() + t.tile).key);
                else if (!home) hide(host);
                return;
            }
            if (t != null) t.action.run();
            else if (!home) hide(host);  // a click on empty space closes, as Launchpad
        }

        void wheel(int steps) {
            wheel += steps;
            // A page per three wheel steps, so a fine-scrolling touchpad does not race through them
            if (Math.abs(wheel) >= 3) {
                setPage(page + (wheel > 0 ? 1 : -1));
                wheel = 0;
            }
        }

        /**
         * A mouse wheel: a page per notch (three notches for one page felt like no paging at all). A high resolution
         * wheel's fractions add up to a notch; after a turn the next waits NOTCH_PAUSE_MS, so a spun wheel turns one
         * page at a time and not several at once.
         */
        void wheelNotch(float v) {
            long now = android.os.SystemClock.uptimeMillis();
            notch += v;
            if (Math.abs(notch) < 1f) return;
            boolean ahead = notch > 0;
            notch = 0;
            if (now - lastNotchPage < NOTCH_PAUSE_MS) return;
            lastNotchPage = now;
            setPage(page + (ahead ? 1 : -1));
        }

        private float notch;
        private long lastNotchPage;
        private static final long NOTCH_PAUSE_MS = 250;

        // --- keyboard ---

        private static final String LETTERS = "qwertyuiop", HOME = "asdfghjkl", BOTTOM = "zxcvbnm";

        /** A Linux key code to the letter or digit it types on a US layout, or 0. */
        static char typed(int scan) {
            if (scan >= 2 && scan <= 10) return (char) ('1' + scan - 2);
            if (scan == 11) return '0';
            if (scan >= 16 && scan <= 25) return LETTERS.charAt(scan - 16);
            if (scan >= 30 && scan <= 38) return HOME.charAt(scan - 30);
            if (scan >= 44 && scan <= 50) return BOTTOM.charAt(scan - 44);
            if (scan == 57) return ' ';
            if (scan == 12) return '-';
            if (scan == 52) return '.';
            return 0;
        }

        void key(int scan, boolean down) {
            if (scan == 29 || scan == 97) { ctrl = down; return; }
            if (scan == 42 || scan == 54) { shift = down; return; }
            if (!down) return;
            // A search or tab changed on the phone can still be waiting for the delayed redraw.
            if (!board.tab().equals(shownTab) || !board.query().equals(shownQuery)) rebuildKeep();
            String query = board.query();
            switch (scan) {
                case 1:  // Esc: clear the search, else close
                    if (!query.isEmpty()) setQuery("");
                    else if (!home) hide(host);
                    return;
                case 14:  // Backspace
                    if (!query.isEmpty()) setQuery(query.substring(0, query.offsetByCodePoints(query.length(), -1)));
                    return;
                case 28: case 96:  // Enter
                    if (selected >= 0) open(items.get(page * perPage() + selected).key);
                    else if (!items.isEmpty() && board.searching()) open(items.get(page * perPage()).key);
                    return;
                case 15:  // Tab, Ctrl+Tab: the next tab; with Shift the one before
                    stepTab(shift ? -1 : 1);
                    return;
                case 105: arrow(-1, 0); return;
                case 106: arrow(1, 0); return;
                case 103: arrow(0, -1); return;
                case 108: arrow(0, 1); return;
                case 104: setPage(page - 1); return;  // Page Up
                case 109: setPage(page + 1); return;  // Page Down
                default:
            }
            if (ctrl) return;
            char ch = typed(scan);
            if (ch != 0) type(String.valueOf(ch));
        }

        void type(String s) {
            if (s == null) return;
            if (s.equals("\n")) { key(28, true); return; }
            if (s.equals("\b")) { key(14, true); return; }
            String clean = s.replace("\n", "").replace("\r", "");
            String query = board.query();
            if (clean.isEmpty() || (query.isEmpty() && clean.trim().isEmpty())) return;
            setQuery(query + clean);
        }

        void setQuery(String q) {
            board.setQuery(q);
            page = 0;
            build();
            // While searching the first result is the one Enter opens
            select(board.searching() ? 0 : -1);
        }

        void arrow(int dx, int dy) {
            int count = tiles.size();
            if (count == 0) {
                if (dx != 0) stepTab(dx);
                return;
            }
            if (selected < 0) {
                select(0);
                return;
            }
            int col = selected % columns, row = selected / columns;
            if (dx < 0) {
                if (col > 0) select(selected - 1);
                else if (page > 0) { setPage(page - 1); select(Math.min(tiles.size() - 1, row * columns + columns - 1)); }
                else stepTab(-1);
            } else if (dx > 0) {
                if (col < columns - 1 && selected + 1 < count) select(selected + 1);
                else if (page < pages() - 1) { setPage(page + 1); select(Math.min(tiles.size() - 1, row * columns)); }
                else stepTab(1);
            } else if (dy < 0) {
                if (row > 0) select(selected - columns);
            } else if (selected + columns < count) {
                select(selected + columns);
            } else if (page < pages() - 1) {
                setPage(page + 1);
                select(Math.min(tiles.size() - 1, col));
            }
        }
    }
}
