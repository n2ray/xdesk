package com.termux.x11;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
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

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The launcher on the external display, like the Mac's Launchpad (DEVICES.md §4): a dark scrim over whatever is shown,
 * a search line and the tabs at the top (正在运行, 收藏, 手机, each external device), and a grid of big icons, page by
 * page. The tabs and their contents are the phone launcher's (Devices, the favourites, hidden apps). The dock's
 * "All", four fingers up and the handle open it on the last tab; three fingers up ("overview") on 正在运行.
 *
 * Sizes come from the display's own density (its display context), so a 1280×720 TV and a 2560×1600 monitor both get a
 * grid that fits: icon and cell sizes in dp, capped by the screen, and every row is given its exact width.
 *
 * A plain overlay window like ExternalCover, not focusable: nothing is composed into the content and it only exists
 * while open. The phone's touchpad and keyboard reach it through INPUT (its own pointer; typing filters). Only the
 * page shown has views, so opening stays instant with hundreds of apps. Main thread only.
 */
final class SwitcherOverlay {
    private static final String TAG = "XDeskSwitcher";
    private static final int SCRIM = 0xE00B0D10, HOVER = 0x2EFFFFFF, CURRENT = 0x553D7EF5, FIELD = 0x26FFFFFF;
    private static final String TAB_RUNNING = "run", TAB_FAVOURITES = "fav";
    private static final int MAX_COLUMNS = 8, MAX_ROWS = 5;

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
     * The external screen's home (INPUT.md: the home is a target like any other): nothing else is on the screen and
     * a mouse is there, so the Launchpad is what the screen shows, over the dashboard. It does not close itself: Esc
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
        String saved = p.devices.switcherTab();
        p.tab = onlySystem || saved == null ? TAB_RUNNING : saved;
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
        p.devices.addListener(p.changed);
        p.place(p.width / 2f, p.height / 2f);
        return true;
    }

    static void hide(Host host) {
        Panel p = panel;
        if (p == null) return;
        panel = null;
        p.devices.removeListener(p.changed);
        UI.removeCallbacks(p.refresh);
        try {
            p.wm.removeView(p.root);
        } catch (RuntimeException ignored) { }
        if (host != null) host.closed();
    }

    private static final class Item {
        String key, label, detail, search;
        boolean hidden, desktop;
        char initial;
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
        final Devices devices;
        final float density;
        final int width, height;
        /** Pixel sizes worked out once per show from the display's dp. */
        final int icon, cellW, cellH, columns, rows, label, small, gap;
        final FrameLayout root;
        final LinearLayout column, tabRow, deviceBar, grid, bottom;
        final TextView searchText, deviceState, deviceAction;
        final View pointer;
        final Collator collator = Collator.getInstance();
        final List<Target> targets = new ArrayList<>();
        final List<View> tiles = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        final Runnable refresh = this::rebuildKeep;
        final Runnable changed = () -> {
            UI.removeCallbacks(refresh);
            UI.postDelayed(refresh, 120);
        };
        String tab, current, query = "";
        int page, selected = -1, hiddenCount;
        boolean showHidden, ctrl, shift;
        float px, py, wheel;

        Panel(Context dc, Display display, Host host, boolean home) {
            c = dc;
            this.home = home;
            wm = dc.getSystemService(WindowManager.class);
            this.host = host;
            devices = Devices.get(dc);
            Point size = new Point();
            display.getRealSize(size);
            width = size.x;
            height = size.y;
            DisplayMetrics dm = dc.getResources().getDisplayMetrics();
            density = dm.density > 0.5f ? dm.density : 1f;
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
            root.setBackgroundColor(SCRIM);
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
            deviceAction = text("", small, Color.WHITE);
            deviceAction.setPadding(dp(14), 0, dp(14), 0);
            deviceAction.setGravity(Gravity.CENTER);
            deviceAction.setBackground(round(Style.ACCENT, dp(14)));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28));
            ap.leftMargin = dp(12);
            deviceBar.addView(deviceAction, ap);
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

        // --- what each tab holds ---

        List<String> tabIds() {
            ArrayList<String> ids = new ArrayList<>();
            ids.add(TAB_RUNNING);
            ids.add(TAB_FAVOURITES);
            for (Devices.Device d : devices.all()) ids.add(d.id);
            return ids;
        }

        Item item(String key, String label, String detail, String extra) {
            Item i = new Item();
            i.key = key;
            i.label = label != null ? label : "";
            i.detail = detail != null && !detail.equalsIgnoreCase(i.label) ? detail : null;
            i.search = (i.label + " " + (detail != null ? detail : "") + " " + (extra != null ? extra : "")).toLowerCase();
            i.hidden = devices.isHidden(key);
            return i;
        }

        Item itemForKey(String key) {
            if (!ExternalLauncher.exists(c, key)) return null;
            ContentSource.Entry e = Contents.find(key);
            Item i = e != null ? item(key, e.label, e.detail, null) : item(key, ExternalLauncher.label(c, key), null, null);
            i.desktop = ChooserView.isDesktopKey(key) || key.startsWith("t:");
            return i;
        }

        /** A device's desktops and windows first (in their order), then its apps by initial. */
        void deviceItems(Devices.Device d, List<Item> out) {
            final int start = out.size();
            ArrayList<Item> apps = new ArrayList<>();
            if (d.type == Devices.Type.PHONE) {
                for (AppCatalog.App a : AppCatalog.get(c).apps()) apps.add(item(ChooserView.recentKey(a), a.label, null, a.packageName));
            } else {
                // The whole desktop first, always (locked, still connecting): opening it connects and unlocks
                if (d.type == Devices.Type.LINUX)
                    for (String s : devices.sessionsOf(d.id)) {
                        Item i = item(ChooserView.recentKey(s), c.getString(R.string.xd_dev_whole_desktop), s, null);
                        i.desktop = true;
                        out.add(i);
                    }
                ContentSource source = d.type == Devices.Type.LINUX ? LinuxSource.get() : d.type == Devices.Type.MAC ? Contents.sourceOf(MacSource.PREFIX) : null;
                if (source != null) {
                    HashSet<String> shown = new HashSet<>();
                    for (ContentSource.Entry e : source.windows()) {
                        if (!devices.deviceOf(e.key).equals(d.id)) continue;
                        shown.add(e.key);
                        if (ChooserView.isDesktopKey(e.key)) {
                            Item i = item(e.key, c.getString(R.string.xd_dev_whole_desktop), e.detail != null ? e.detail : e.label, e.label);
                            i.desktop = true;
                            out.add(start, i);
                        } else {
                            out.add(item(e.key, e.label, e.detail, null));
                        }
                    }
                    for (ContentSource.Entry e : source.apps())
                        if (!shown.contains(e.key) && devices.deviceOf(e.key).equals(d.id)) apps.add(item(e.key, e.label, null, null));
                }
            }
            ArrayList<String> names = new ArrayList<>(apps.size());
            for (Item i : apps) names.add(i.label);
            Initials.prepare(names, changed);
            for (Item i : apps) i.initial = Initials.of(i.label);
            Collections.sort(apps, (a, b) -> {
                int by = Integer.compare(Initials.rank(a.initial), Initials.rank(b.initial));
                return by != 0 ? by : collator.compare(a.label, b.label);
            });
            out.addAll(apps);
        }

        void collect() {
            items.clear();
            hiddenCount = 0;
            current = host.current();
            ArrayList<Item> all = new ArrayList<>();
            boolean searching = !query.isEmpty();
            if (searching) {
                // A search looks through every device, as Launchpad's does
                for (Devices.Device d : devices.all()) deviceItems(d, all);
            } else if (TAB_RUNNING.equals(tab)) {
                for (String k : new LinkedHashSet<>(host.running())) {
                    Item i = itemForKey(k);
                    if (i != null) all.add(i);
                }
            } else if (TAB_FAVOURITES.equals(tab)) {
                for (String k : new LinkedHashSet<>(host.favorites())) {
                    Item i = itemForKey(k);
                    if (i != null && !i.hidden) all.add(i);
                }
            } else {
                Devices.Device d = devices.find(tab);
                if (d != null) deviceItems(d, all);
            }
            HashSet<String> seen = new HashSet<>();
            ArrayList<Item> hidden = new ArrayList<>();
            for (Item i : all) {
                if (!seen.add(i.key)) continue;
                if (searching && !i.search.contains(query)) continue;
                if (i.hidden && !searching) {
                    hidden.add(i);
                    continue;
                }
                items.add(i);
            }
            hiddenCount = hidden.size();
            // The hidden fold: at the end, only when asked for
            if (showHidden) items.addAll(hidden);
        }

        int perPage() {
            return columns * rows;
        }

        int pages() {
            return Math.max(1, (items.size() + perPage() - 1) / perPage());
        }

        // --- building ---

        void build() {
            if (devices.find(tab) == null && !TAB_RUNNING.equals(tab) && !TAB_FAVOURITES.equals(tab)) tab = TAB_RUNNING;
            collect();
            page = Math.max(0, Math.min(page, pages() - 1));
            render();
        }

        /** Something changed while open (a Mac connected, icons came): same tab and page. */
        void rebuildKeep() {
            if (panel != this) return;
            int keep = selected;
            build();
            select(Math.min(keep, tiles.size() - 1));
        }

        void render() {
            targets.clear();
            tiles.clear();
            selected = -1;
            String hint = c.getString(R.string.xd_sw_search);
            searchText.setText(query.isEmpty() ? "⌕  " + hint : query + "▏");
            searchText.setTextColor(query.isEmpty() ? Style.TEXT_DIM : Color.WHITE);
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
            boolean searching = !query.isEmpty();
            List<Devices.Device> all = devices.all();
            addTab(TAB_RUNNING, c.getString(R.string.xd_sw_running), null, searching);
            addTab(TAB_FAVOURITES, "★ " + c.getString(R.string.xd_chooser_favourites), null, searching);
            for (Devices.Device d : all) addTab(d.id, d.name, d.external() ? d.state : null, searching);
        }

        void addTab(final String id, String name, Devices.State state, boolean searching) {
            boolean on = id.equals(tab) && !searching;
            TextView t = text(name, small, on ? Color.WHITE : Style.TEXT_DIM);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(14), 0, dp(14), 0);
            t.setBackground(round(on ? Style.ACCENT : 0x00000000, dp(16)));
            if (state != null) {
                GradientDrawable dot = Style.dot(Devices.color(state));
                int s = dp(7);
                dot.setBounds(0, 0, s, s);
                t.setCompoundDrawablesRelative(dot, null, null, null);
                t.setCompoundDrawablePadding(dp(6));
            }
            Runnable go = () -> setTab(id);
            t.setOnClickListener(v -> go.run());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
            p.leftMargin = p.rightMargin = dp(3);
            tabRow.addView(t, p);
            targets.add(new Target(t, go, -1));
        }

        void renderDeviceBar() {
            deviceAction.setVisibility(View.GONE);
            Devices.Device d = query.isEmpty() ? devices.find(tab) : null;
            if (!query.isEmpty()) {
                deviceState.setText(c.getString(R.string.xd_sw_results, items.size()));
                return;
            }
            if (d == null || !d.external()) {
                deviceState.setText(TAB_RUNNING.equals(tab) ? c.getString(R.string.xd_sw_running_note) : "");
                return;
            }
            String state = c.getString(Devices.stateLabel(d.state));
            deviceState.setText(Devices.isLocked(c, d.id) ? c.getString(R.string.xd_dev_locked_hint)
                    : d.note != null && !d.note.isEmpty() ? state + " · " + d.note : state);
            if ((d.state == Devices.State.DISCONNECTED || d.state == Devices.State.FAILED) && d.canConnect(devices)) {
                deviceAction.setText(R.string.xd_dev_connect);
                deviceAction.setVisibility(View.VISIBLE);
                final String id = d.id;
                final boolean linux = d.type == Devices.Type.LINUX;
                Runnable go = () -> {
                    // A Linux desktop takes the screen; a Mac's apps come into this grid when it is connected
                    if (linux) hide(host);
                    devices.connect(id);
                };
                deviceAction.setOnClickListener(v -> go.run());
                targets.add(new Target(deviceAction, go, -1));
            } else if (d.state == Devices.State.NOT_SET_UP) {
                deviceState.setText(state + " · " + c.getString(R.string.xd_sw_set_up_on_phone));
            }
        }

        void renderGrid() {
            grid.removeAllViews();
            if (items.isEmpty()) {
                TextView none = text(c.getString(!query.isEmpty() ? R.string.xd_chooser_no_match
                        : TAB_RUNNING.equals(tab) ? R.string.xd_switcher_empty
                        : TAB_FAVOURITES.equals(tab) ? R.string.xd_dev_no_favourites : R.string.xd_dev_empty), label, Style.TEXT_DIM);
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

        View tile(final Item it, final int index) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(gap / 2, dp(8), gap / 2, dp(4));
            box.setClickable(true);
            boolean isCurrent = it.key.equals(current);
            box.setBackground(tileBack(it, isCurrent ? CURRENT : 0x00000000));
            box.setAlpha(it.hidden ? 0.5f : 1f);
            box.addView(ExternalLauncher.icon(c, it.key, icon), new LinearLayout.LayoutParams(icon, icon));
            TextView name = text(it.label, label, Color.WHITE);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            np.topMargin = dp(6);
            box.addView(name, np);
            if (it.detail != null) {
                TextView detail = text(it.detail, small, Style.TEXT_DIM);
                detail.setSingleLine(true);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                detail.setGravity(Gravity.CENTER);
                box.addView(detail, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            Runnable go = () -> open(it.key);
            box.setOnClickListener(v -> go.run());
            box.setOnHoverListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER) select(index);
                return false;
            });
            tiles.add(box);
            targets.add(new Target(box, go, index));
            return box;
        }

        /** A whole desktop gets a ring, so it stands apart from the apps. */
        GradientDrawable tileBack(Item it, int color) {
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
            boolean deviceTab = query.isEmpty() && devices.find(tab) != null;
            if (deviceTab && hiddenCount > 0) {
                TextView fold = text(showHidden ? c.getString(R.string.xd_sw_hide_hidden) : c.getString(R.string.xd_sw_show_hidden, hiddenCount), small, Style.TEXT_DIM);
                fold.setGravity(Gravity.CENTER);
                fold.setPadding(dp(12), 0, dp(12), 0);
                fold.setBackground(round(FIELD, dp(14)));
                Runnable go = () -> {
                    showHidden = !showHidden;
                    build();
                    if (showHidden) setPage(pages() - 1);
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
            host.open(key);
        }

        void setTab(String id) {
            query = "";
            tab = id;
            page = 0;
            showHidden = false;
            devices.setSwitcherTab(id);
            build();
            select(-1);
        }

        void stepTab(int by) {
            List<String> ids = tabIds();
            int at = ids.indexOf(tab);
            setTab(ids.get(((at < 0 ? 0 : at) + by + ids.size()) % ids.size()));
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
                Item it = items.get(page * perPage() + selected);
                tiles.get(selected).setBackground(tileBack(it, it.key.equals(current) ? CURRENT : 0x00000000));
            }
            selected = i >= 0 && i < tiles.size() ? i : -1;
            if (selected >= 0) tiles.get(selected).setBackground(tileBack(items.get(page * perPage() + selected), HOVER));
        }

        // --- pointer ---

        void place(float x, float y) {
            px = Math.max(0, Math.min(width - 1, x));
            py = Math.max(0, Math.min(height - 1, y));
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
            if (button != 1) {
                if (!home) hide(host);
                return;
            }
            Target t = under();
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
                    else if (!items.isEmpty() && !query.isEmpty()) open(items.get(page * perPage()).key);
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
            if (clean.isEmpty() || (query.isEmpty() && clean.trim().isEmpty())) return;
            setQuery(query + clean.toLowerCase());
        }

        void setQuery(String q) {
            query = q;
            page = 0;
            build();
            // While searching the first result is the one Enter opens
            select(query.isEmpty() ? -1 : 0);
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
