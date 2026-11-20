package com.termux.x11;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * The switchboard on the phone's screen (DEVICES.md §3), as a list: the top row (to the touchpad, whose the keys are,
 * the sound, the settings); the tabs (running, favourites, the phone, each device, "+"); the tab's device bar; the
 * search; the rows with their section titles, the A–Z bar at the right, the hidden fold at the end. What the tabs,
 * the rows and the bar are comes from Switchboard, the same model the external screen draws as a grid
 * (SwitcherOverlay); this only draws it and has the list's own gestures. Without an external display it can be
 * looked at, only nothing starts. While something runs it is a layer over the touchpad page; the external screen
 * stays as it is.
 *
 * Landscape draws ConsoleListLand.dc.html instead: a left rail (touchpad, keys, sound, settings; the search; the tabs
 * stacked) and the tab's rows in two columns with the A–Z bar at the right. Same model, same handlers.
 */
final class ChooserView extends LinearLayout {
    interface Host extends DockBar.Host {
        /** A whole desktop was chosen (XDeskActivity's own way in, from chooseKey). */
        void onSessionChosen(String name);
        /** 只当显示器，等用户自己运行 xdesk 命令。 */
        void onAttachChosen();
        /** 点了 Termux 那一节的提示行。 */
        void onTermuxHint();
        /** 点了没有外接屏的提示：打开无线显示。 */
        void onNoDisplayHint();
        /** 点了一行右边的星。 */
        void onDockToggle(String key);
        /** The Touchpad button: the touchpad page instead of this list. */
        void onTouchpad();
        /** Whose the physical keyboard and mouse are, switched (as Ctrl+Alt+M). */
        void onToggleKeys();
        /** A finger went down on the search line: it is about to be typed into. */
        void onSearchTouched();
        /** 声音输出，所有地方是同一个设置。 */
        void onChooseOutput();
        void onOpenSettings();
    }

    private static final String RECENT_SESSION = "t:", RECENT_APP = "a:";
    /** Fewer entries than this need no A–Z bar. */
    private static final int INDEX_MIN = 12;
    /** The landscape rail's width (ConsoleListLand.dc.html). */
    private static final int RAIL_WIDTH = 250;

    static String recentKey(String sessionName) {
        return RECENT_SESSION + sessionName;
    }

    static String recentKey(AppCatalog.App a) {
        return RECENT_APP + a.component();
    }

    static boolean isAppKey(String key) {
        return key.startsWith(RECENT_APP);
    }

    static String componentOf(String appKey) {
        return appKey.substring(RECENT_APP.length());
    }

    static String sessionOf(String sessionKey) {
        return sessionKey.substring(RECENT_SESSION.length());
    }

    private final Host host;
    private final Switchboard board;
    private final Devices devices;
    private final boolean landscape;
    private final TextView activate, banner, notice;
    private final Style.IconButton output, keys, settingsBtn;
    private final TextView touchpad;
    private final LinearLayout tabRow, deviceBar, deviceActions;
    private final View deviceDot;
    private final TextView deviceState;
    private final EditText search;
    private final ImageView clear;
    private final ListView list;
    private final AlphaIndexBar index;
    private final Adapter adapter = new Adapter();
    private List<Switchboard.Item> rows = new java.util.ArrayList<>();
    /** Landscape: the rows as one or two items each (a section title is not drawn there). */
    private final List<LandRow> landRows = new java.util.ArrayList<>();
    /** Letter -> the first row of the A–Z part with it. */
    private final HashMap<Character, Integer> letterRows = new HashMap<>();
    private final Runnable boardChanged = this::rebuild;
    private String tabsShown = "", barShown = "", tabShown;
    private boolean settingQuery, stale = true;

    ChooserView(Context c, Host host) {
        super(c);
        this.host = host;
        board = Switchboard.get(c);
        devices = Devices.get(c);
        landscape = c.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setOrientation(landscape ? HORIZONTAL : VERTICAL);
        setBackgroundColor(Style.BACKGROUND);
        setClickable(true);  // 挡住下面的触摸板
        setFocusableInTouchMode(true);  // 别一打开就把焦点给搜索框、弹出键盘
        int edge = Style.dp(c, Style.EDGE + 4);
        setPadding(edge, Style.dp(c, Style.EDGE), edge, landscape ? Style.dp(c, Style.EDGE) : 0);

        // The top row has what the touchpad page's has, so that nothing needs that page: with a mouse on the phone
        // this list is the phone's page (DEVICES.md §3.1)
        TextView title = Style.label(c, "XDesk", Style.TEXT, Style.TEXT_TITLE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        // 结束 XDesk 放在设置里，免得误碰
        touchpad = Style.button(c, c.getString(R.string.xd_chooser_touchpad), Style.ACCENT, Style.TEXT_SMALL + 1, Style.RADIUS);
        touchpad.setSingleLine(true);
        touchpad.setPadding(Style.dp(c, 12), 0, Style.dp(c, 12), 0);
        touchpad.setOnClickListener(v -> {
            Style.tick(v);
            host.onTouchpad();
        });
        touchpad.setVisibility(GONE);
        keys = new Style.IconButton(c, R.drawable.xd_ic_keys_phone, "", Style.SURFACE);
        keys.onTap(host::onToggleKeys);
        keys.setVisibility(GONE);
        output = new Style.IconButton(c, R.drawable.xd_ic_sound, "", Style.SURFACE);
        output.onTap(host::onChooseOutput);
        settingsBtn = new Style.IconButton(c, R.drawable.xd_ic_settings, c.getString(R.string.xd_settings), Style.SURFACE);
        settingsBtn.onTap(host::onOpenSettings);

        // 安卓应用还不能用时，第一眼就看到，点了去激活
        activate = Style.button(c, "", Style.ACCENT, 14, Style.RADIUS);
        activate.setVisibility(GONE);

        banner = Style.label(c, "", Style.TEXT, 13);
        Style.hintCard(banner);
        banner.setOnClickListener(v -> {
            Style.tick(v);
            host.onNoDisplayHint();
        });

        notice = Style.label(c, "", Style.WARN, Style.TEXT_SMALL + 1);
        notice.setPadding(0, Style.dp(c, 4), 0, 0);
        notice.setVisibility(GONE);

        // 设备标签
        tabRow = new LinearLayout(c);
        tabRow.setGravity(landscape ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        if (landscape) tabRow.setOrientation(VERTICAL);
        View tabScroll;
        if (landscape) {
            ScrollView tabs = new ScrollView(c);
            tabs.setFillViewport(true);
            tabs.setFocusable(false);
            tabs.addView(tabRow, new ScrollView.LayoutParams(-1, -2));
            tabScroll = tabs;
        } else {
            HorizontalScrollView tabs = new HorizontalScrollView(c);
            tabs.setHorizontalScrollBarEnabled(false);
            tabs.setFocusable(false);
            tabs.addView(tabRow, new HorizontalScrollView.LayoutParams(-2, -2));
            tabScroll = tabs;
        }

        // The tab's bar: a device's state and what can be done with it (Switchboard.Bar)
        deviceBar = new LinearLayout(c);
        deviceBar.setGravity(Gravity.CENTER_VERTICAL);
        deviceBar.setPadding(Style.dp(c, 4), 0, 0, 0);
        deviceBar.setMinimumHeight(Style.dp(c, 40));
        deviceDot = new View(c);
        LayoutParams dp = new LayoutParams(Style.dp(c, 8), Style.dp(c, 8));
        dp.rightMargin = Style.dp(c, 10);
        deviceBar.addView(deviceDot, dp);
        deviceState = Style.label(c, "", Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        deviceState.setMaxLines(2);
        deviceBar.addView(deviceState, new LayoutParams(0, -2, 1));
        deviceActions = new LinearLayout(c);
        deviceActions.setGravity(Gravity.CENTER_VERTICAL);
        deviceBar.addView(deviceActions, new LayoutParams(-2, -2));

        FrameLayout searchBox = new FrameLayout(c);
        int searchH = Style.dp(c, landscape ? 40 : Style.TARGET);
        search = new EditText(c);
        Style.fitHint(search, R.string.xd_chooser_search, R.string.xd_chooser_search_short);
        search.setHintTextColor(Style.TEXT_DIM);
        search.setTextColor(Style.TEXT);
        search.setTextSize(landscape ? Style.TEXT_SMALL + 1 : Style.TEXT_BODY);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(Style.rounded(c, Style.SURFACE, Style.RADIUS));
        search.setPadding(Style.dp(c, 12), 0, searchH, 0);
        search.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) host.onSearchTouched();
            return false;
        });
        searchBox.addView(search, new FrameLayout.LayoutParams(-1, searchH));
        clear = new ImageView(c);
        clear.setImageResource(R.drawable.xd_ic_close);
        clear.setColorFilter(Style.TEXT_DIM);
        clear.setScaleType(ImageView.ScaleType.CENTER);
        clear.setContentDescription(c.getString(R.string.xd_chooser_clear));
        clear.setVisibility(GONE);
        clear.setOnClickListener(v -> {
            Style.tick(v);
            board.setQuery(Switchboard.PHONE, "");
        });
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(searchH, searchH);
        clearParams.gravity = Gravity.END;
        searchBox.addView(clear, clearParams);
        // This field and the Launchpad grid's search are two widgets on the phone's one search text (Switchboard.query(PHONE))
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? VISIBLE : GONE);
                if (!settingQuery) board.setQuery(Switchboard.PHONE, s.toString());
            }
        });

        FrameLayout listBox = new FrameLayout(c);
        list = new ListView(c);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            // Landscape cells take their own taps (the gap between two of them is not an item)
            if (landscape) return;
            Switchboard.Item r = rows.get(position);
            if (r.kind == Switchboard.ITEM) board.open(Switchboard.PHONE, r.key);
            else if (r.action != null) r.action.run();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Switchboard.Item r = landscape ? landRows.get(position).a : rows.get(position);
            if (landscape || r.kind != Switchboard.ITEM) return false;
            // The same card as everywhere else (ItemCard): favourite, hide, close, and what the item's kind has
            host.onItemLongPress(r.key);
            return true;
        });
        listBox.addView(list, new FrameLayout.LayoutParams(-1, -1));
        index = new AlphaIndexBar(c);
        index.setPadding(0, Style.dp(c, 4), 0, Style.dp(c, 4));
        index.setListener(this::jumpTo);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(Style.dp(c, 22), -1);
        ip.gravity = Gravity.END;
        listBox.addView(index, ip);

        if (landscape) {
            // ConsoleListLand.dc.html: the rail holds what portrait's top rows do; the list gets the rest
            LinearLayout rail = new LinearLayout(c);
            rail.setOrientation(VERTICAL);
            LinearLayout buttons = new LinearLayout(c);
            buttons.setGravity(Gravity.CENTER_VERTICAL);
            buttons.addView(touchpad, new LayoutParams(0, Style.dp(c, 44), 1));
            for (View b : new View[]{keys, output, settingsBtn}) {
                LayoutParams p = new LayoutParams(Style.dp(c, Style.TARGET), Style.dp(c, Style.TARGET));
                p.leftMargin = Style.dp(c, 4);
                buttons.addView(b, p);
            }
            rail.addView(buttons, new LayoutParams(-1, -2));
            LayoutParams sbp = new LayoutParams(-1, -2);
            sbp.topMargin = Style.dp(c, 6);
            rail.addView(searchBox, sbp);
            LayoutParams tp = new LayoutParams(-1, 0, 1);
            tp.topMargin = Style.dp(c, 6);
            rail.addView(tabScroll, tp);
            LayoutParams ap = new LayoutParams(-1, -2);
            ap.topMargin = Style.dp(c, 6);
            rail.addView(activate, ap);
            addView(rail, new LayoutParams(Style.dp(c, RAIL_WIDTH), -1));

            LinearLayout right = new LinearLayout(c);
            right.setOrientation(VERTICAL);
            LayoutParams np = new LayoutParams(-1, -2);
            np.bottomMargin = Style.dp(c, 6);
            right.addView(banner, np);
            right.addView(notice, new LayoutParams(-1, -2));
            LayoutParams bp = new LayoutParams(-1, -2);
            bp.topMargin = Style.dp(c, 6);
            right.addView(deviceBar, bp);
            right.addView(listBox, new LayoutParams(-1, 0, 1));
            LayoutParams rp = new LayoutParams(0, -1, 1);
            rp.leftMargin = Style.dp(c, 12);
            addView(right, rp);
        } else {
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(title, new LayoutParams(0, -2, 1));
            LayoutParams cp = new LayoutParams(-2, Style.dp(c, Style.TARGET));
            cp.rightMargin = Style.dp(c, Style.GAP);
            head.addView(touchpad, cp);
            LayoutParams kp = new LayoutParams(-2, -2);
            kp.rightMargin = Style.dp(c, Style.GAP);
            head.addView(keys, kp);
            head.addView(output, new LayoutParams(-2, -2));
            LayoutParams sp = new LayoutParams(-2, -2);
            sp.leftMargin = Style.dp(c, Style.GAP);
            head.addView(settingsBtn, sp);
            addView(head, new LayoutParams(-1, -2));

            LayoutParams ap = new LayoutParams(-1, -2);
            ap.topMargin = Style.dp(c, Style.GAP);
            addView(activate, ap);
            LayoutParams np = new LayoutParams(-1, -2);
            np.topMargin = Style.dp(c, Style.GAP);
            addView(banner, np);
            addView(notice, new LayoutParams(-1, -2));
            LayoutParams tp = new LayoutParams(-1, -2);
            tp.topMargin = Style.dp(c, 10);
            addView(tabScroll, tp);
            LayoutParams bp = new LayoutParams(-1, -2);
            bp.topMargin = Style.dp(c, 8);
            addView(deviceBar, bp);
            LayoutParams sbp = new LayoutParams(-1, -2);
            sbp.topMargin = Style.dp(c, 8);
            addView(searchBox, sbp);
            addView(listBox, new LayoutParams(-1, 0, 1));
        }

        setDisplayConnected(false, false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        board.addListener(boardChanged);
        rebuild();
    }

    @Override protected void onDetachedFromWindow() {
        board.removeListener(boardChanged);
        super.onDetachedFromWindow();
    }

    /** Behind the touchpad page the list is not kept up (every key typed on the Launchpad would rebuild it): now it is. */
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE && stale && isAttachedToWindow()) rebuild();
    }

    private String str(int id) {
        return getContext().getString(id);
    }

    /** @param car 用的是车机：说法不同 (the items' words for it are the Switchboard's, from its host) */
    void setDisplayConnected(boolean connected, boolean car) {
        banner.setText(R.string.xd_chooser_no_display);
        banner.setVisibility(connected ? GONE : VISIBLE);
    }

    /**
     * The Touchpad button: there whenever the touchpad page has a use, which is while something runs and whenever an
     * external screen is there (its home is worked with the touchpad when the phone has no mouse).
     */
    void setTouchpadShown(boolean shown) {
        touchpad.setVisibility(shown ? VISIBLE : GONE);
    }

    /**
     * The keys button, as the touchpad page's: whose the physical keyboard and mouse are, tapping switches.
     * @param said where they are, read aloud (the icon and colour show it); null while nothing runs that could
     *     have them, or the phone has no keyboard or mouse: no button
     */
    void setKeys(String said, boolean onDisplay) {
        keys.setVisibility(said != null ? VISIBLE : GONE);
        if (said == null) return;
        keys.setLabel(getContext().getString(onDisplay ? R.string.xd_keys_display_short : R.string.xd_keys_phone_short));
        keys.setIcon(onDisplay ? R.drawable.xd_ic_keys_external : R.drawable.xd_ic_keys_phone);
        keys.setContentDescription(said);
        keys.setActive(onDisplay);
    }

    /** 安卓应用要先激活时的提示，点了执行 onTap。text 为 null 时不显示。 */
    void setActivation(String text, final Runnable onTap) {
        activate.setVisibility(text != null ? VISIBLE : GONE);
        if (text == null) return;
        activate.setText(text);
        activate.setOnClickListener(v -> {
            Style.tick(v);
            onTap.run();
        });
    }

    /** 刚发生的事，比如桌面停止了。null 去掉。 */
    void setNotice(String text) {
        notice.setText(text);
        notice.setVisibility(text != null ? VISIBLE : GONE);
    }

    void setOutputLabel(String label) {
        output.setLabel(label);
    }

    void clearSearch() {
        board.setQuery(Switchboard.PHONE, "");
        search.clearFocus();
    }

    /** The search line with the focus and the keyboard, after its window became focusable for it (onSearchTouched). */
    void focusSearch() {
        search.requestFocus();
        android.view.inputmethod.InputMethodManager ime = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (ime != null) ime.showSoftInput(search, 0);
    }

    /**
     * @param hint 列不出环境时的说明，点按见 Host.onTermuxHint；null 表示正常
     * @param versionHint the xdesk command is not this app's version, or too old to say one (CLI.md §1); null when fine
     */
    void setTermux(List<String> sessionNames, String defaultName, String running, boolean x, String hint,
            String versionHint) {
        devices.setLinux(sessionNames, defaultName, running, x, hint, versionHint);  // its listeners (the Switchboard) rebuild
    }

    void setStopping(String name) {
        devices.setLinuxStopping(name);
    }

    /** The content sources (Contents) changed: their rows are rebuilt from the sources themselves. */
    void refreshContents() {
        devices.touch();  // the device states come from the same sources; its listeners (the Switchboard, DeviceSettings) rebuild
    }

    // --- tabs ---

    private void buildTabs(List<Switchboard.Tab> tabs, String selected) {
        StringBuilder sig = new StringBuilder(selected);
        for (Switchboard.Tab t : tabs) sig.append('|').append(t.id).append(t.state).append(t.name);
        if (sig.toString().equals(tabsShown)) return;
        tabsShown = sig.toString();
        tabRow.removeAllViews();
        for (Switchboard.Tab t : tabs) addTab(t, t.id.equals(selected));
        TextView add = tabChip("+", false);
        add.setContentDescription(str(R.string.xd_dev_connect_devices));
        add.setOnClickListener(v -> {
            Style.tick(v);
            DeviceSettings.open(getContext());
        });
        tabRow.addView(add, tabParams());
    }

    private LayoutParams tabParams() {
        if (landscape) {
            LayoutParams p = new LayoutParams(-1, Style.dp(getContext(), 36));
            p.bottomMargin = Style.dp(getContext(), 4);
            return p;
        }
        LayoutParams p = new LayoutParams(-2, Style.dp(getContext(), 36));
        p.rightMargin = Style.dp(getContext(), Style.GAP);
        return p;
    }

    private TextView tabChip(String text, boolean selected) {
        Context c = getContext();
        float radius = 16;
        TextView t = Style.button(c, text, Style.SURFACE, Style.TEXT_SMALL + 2, radius);
        t.setTextColor(selected ? Style.ACCENT_2 : Style.TEXT_DIM);
        // Chosen: accent-soft with an accent border; the others the surface (Components.dc.html)
        t.setBackground(selected ? Style.toggle(c, true, Style.SURFACE, radius) : Style.ripple(c, Style.SURFACE, radius));
        t.setSingleLine(true);
        t.setPadding(Style.dp(c, 14), 0, Style.dp(c, 14), 0);
        if (landscape) t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return t;
    }

    private void addTab(final Switchboard.Tab tab, boolean selected) {
        TextView t = tabChip(tab.name, selected);
        if (tab.state != null) {
            GradientDrawable dot = Style.dot(Devices.color(tab.state));
            int s = Style.dp(getContext(), 8);
            dot.setBounds(0, 0, s, s);
            t.setCompoundDrawablesRelative(dot, null, null, null);
            t.setCompoundDrawablePadding(Style.dp(getContext(), 6));
        }
        t.setOnClickListener(v -> {
            Style.tick(v);
            board.setTab(Switchboard.PHONE, tab.id);
        });
        tabRow.addView(t, tabParams());
    }

    private void showBar(Switchboard.Bar bar) {
        if (bar == null) {
            deviceBar.setVisibility(GONE);
            barShown = "";
            return;
        }
        deviceBar.setVisibility(VISIBLE);
        deviceDot.setVisibility(bar.dot != 0 ? VISIBLE : GONE);
        if (bar.dot != 0) deviceDot.setBackground(Style.dot(bar.dot));
        deviceState.setText(bar.text);
        StringBuilder sig = new StringBuilder();
        for (Switchboard.Action a : bar.actions) sig.append(a.label).append(a.enabled).append('|');
        // The buttons are kept while they are the same: this runs on every change, also in the middle of a tap
        if (sig.toString().equals(barShown) && deviceActions.getChildCount() == bar.actions.size()) {
            for (int i = 0; i < bar.actions.size(); i++) bind(deviceActions.getChildAt(i), bar.actions.get(i));
            return;
        }
        barShown = sig.toString();
        deviceActions.removeAllViews();
        Context c = getContext();
        for (Switchboard.Action a : bar.actions) {
            TextView b = Style.button(c, a.label, Style.SURFACE_RAISED, Style.TEXT_SMALL + 1, Style.RADIUS);
            b.setSingleLine(true);
            b.setPadding(Style.dp(c, 14), 0, Style.dp(c, 14), 0);
            b.setEnabled(a.enabled);
            b.setAlpha(a.enabled ? 1f : 0.4f);
            LayoutParams p = new LayoutParams(-2, Style.dp(c, 40));
            p.leftMargin = Style.dp(c, Style.GAP);
            deviceActions.addView(b, p);
            bind(b, a);
        }
    }

    /** The action as it is now: its device is a new object after every change. */
    private void bind(View button, final Switchboard.Action a) {
        button.setOnClickListener(v -> {
            Style.tick(v);
            a.run();
        });
    }

    // --- rows ---

    private void rebuild() {
        if (getVisibility() != VISIBLE || !isAttachedToWindow()) {
            stale = true;
            return;
        }
        stale = false;
        String tab = board.tab(Switchboard.PHONE), query = board.query(Switchboard.PHONE);
        buildTabs(board.tabs(), board.searching(Switchboard.PHONE) ? "" : tab);
        showBar(board.bar(Switchboard.PHONE));
        if (!search.getText().toString().equals(query)) {
            // Typed in the Launchpad grid (the phone's one search), or cleared by opening something
            settingQuery = true;
            search.setText(query);
            search.setSelection(query.length());
            settingQuery = false;
        }
        rows = board.items(Switchboard.PHONE);
        letterRows.clear();
        int lettered;
        if (landscape) {
            buildLandRows();
            lettered = landLetterRows();
        } else {
            landRows.clear();
            lettered = 0;
            for (int i = 0; i < rows.size(); i++) {
                Switchboard.Item r = rows.get(i);
                if (r.kind != Switchboard.ITEM || r.initial == 0) continue;
                lettered++;
                if (!letterRows.containsKey(r.initial)) letterRows.put(r.initial, i);
            }
        }
        boolean indexed = lettered > INDEX_MIN;
        index.setVisibility(indexed ? VISIBLE : GONE);
        list.setPadding(0, 0, indexed ? Style.dp(getContext(), 22) : 0, 0);
        if (indexed) index.setPresent(new HashSet<>(letterRows.keySet()));
        adapter.notifyDataSetChanged();
        if (!tab.equals(tabShown)) {
            tabShown = tab;
            list.setSelection(0);
        }
    }

    /**
     * ConsoleListLand.dc.html draws the rows in two columns and no section titles: pair the tab's items, and keep a
     * title or hint line (the hidden fold, "nothing here") as a row of its own across both columns.
     */
    private void buildLandRows() {
        landRows.clear();
        Switchboard.Item first = null;
        for (Switchboard.Item r : rows) {
            if (r.kind == Switchboard.ITEM) {
                if (first == null) first = r;
                else {
                    landRows.add(new LandRow(first, r));
                    first = null;
                }
            } else {
                if (first != null) {
                    landRows.add(new LandRow(first, null));
                    first = null;
                }
                landRows.add(new LandRow(r, null));
            }
        }
        if (first != null) landRows.add(new LandRow(first, null));
    }

    /** The A–Z letters of the landscape rows: each item's first letter -> the row it is in. */
    private int landLetterRows() {
        int lettered = 0;
        for (int i = 0; i < landRows.size(); i++) {
            LandRow lr = landRows.get(i);
            for (Switchboard.Item r : new Switchboard.Item[]{lr.a, lr.b}) {
                if (r == null || r.kind != Switchboard.ITEM || r.initial == 0) continue;
                lettered++;
                if (!letterRows.containsKey(r.initial)) letterRows.put(r.initial, i);
            }
        }
        return lettered;
    }

    /** A whole desktop of a device (a Mac's "m:<id>:desktop", or "m:<id>:desktop:<n>" on its display n; later Windows'). */
    static boolean isDesktopKey(String key) {
        return key.startsWith(MacSource.PREFIX) && (key.endsWith(":desktop") || key.matches(".*:desktop:[0-9]+"));
    }

    /** The Mac display a desktop key names (from 1), or 0 for the Mac desktop as such. */
    static int desktopDisplay(String key) {
        int at = key.lastIndexOf(":desktop:");
        if (at < 0) return 0;
        try {
            return Integer.parseInt(key.substring(at + 9));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void jumpTo(char letter) {
        int from = AlphaIndexBar.LETTERS.indexOf(letter);
        for (int i = from; i >= 0 && i < AlphaIndexBar.LETTERS.length(); i++) {
            Integer at = letterRows.get(AlphaIndexBar.LETTERS.charAt(i));
            if (at != null) {
                list.setSelectionFromTop(at, 0);
                return;
            }
        }
        // Nothing at or after it: the last letter that has entries
        for (int i = from; i >= 0; i--) {
            Integer at = letterRows.get(AlphaIndexBar.LETTERS.charAt(i));
            if (at != null) {
                list.setSelectionFromTop(at, 0);
                return;
            }
        }
    }

    /** One landscape row: two items side by side, or a title/hint line across both columns. */
    private static final class LandRow {
        final Switchboard.Item a, b;
        final boolean full;

        LandRow(Switchboard.Item a, Switchboard.Item b) {
            this.a = a;
            this.b = b;
            this.full = b == null && a.kind != Switchboard.ITEM;
        }
    }

    private static final class Holder {
        ImageView icon, star;
        View dot;
        TextView title, subtitle;
    }

    /** A landscape cell: 36 dp icon, name, its line, the star (ConsoleListLand.dc.html). */
    private static final class Cell {
        ImageView icon, star;
        TextView title, subtitle;
        LinearLayout box;
    }

    private Cell landCell(Context c) {
        Cell h = new Cell();
        h.box = new LinearLayout(c);
        h.box.setGravity(Gravity.CENTER_VERTICAL);
        h.box.setMinimumHeight(Style.dp(c, 52));
        h.box.setPadding(Style.dp(c, 6), 0, Style.dp(c, 6), 0);
        h.box.setClipToPadding(false);
        h.icon = new ImageView(c);
        LayoutParams ip = new LayoutParams(Style.dp(c, 36), Style.dp(c, 36));
        ip.rightMargin = Style.dp(c, 10);
        h.box.addView(h.icon, ip);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(VERTICAL);
        h.title = Style.label(c, "", Style.TEXT, 14);
        h.title.setSingleLine(true);
        h.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(h.title);
        h.subtitle = Style.label(c, "", Style.TEXT_DIM, 11);
        h.subtitle.setSingleLine(true);
        h.subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(h.subtitle);
        h.box.addView(text, new LayoutParams(0, -2, 1));
        h.star = new ImageView(c);
        h.star.setScaleType(ImageView.ScaleType.CENTER);
        h.star.setFocusable(false);
        h.box.addView(h.star, new LayoutParams(Style.dp(c, 30), Style.dp(c, 30)));
        h.box.setTag(h);
        return h;
    }

    private void fillLandCell(Cell h, final Switchboard.Item r) {
        Context c = getContext();
        boolean item = r.kind == Switchboard.ITEM;
        h.box.setAlpha(r.hidden || r.dim ? 0.55f : 1f);
        // A row's own surface marks what is on the display (ConsoleListLand.dc.html); the icon ring is for the grids
        h.box.setBackground(r.current ? Style.rounded(c, Style.SURFACE, Style.RADIUS) : null);
        h.title.setText(r.label);
        h.title.setTextColor(r.desktop && (r.display == 0 || r.running) ? Style.ACCENT : Style.TEXT);
        h.subtitle.setText(r.detail);
        h.subtitle.setTextColor(r.current ? Switchboard.ACCENT_TEXT : Style.TEXT_DIM);
        h.subtitle.setVisibility(r.detail != null ? VISIBLE : GONE);
        if (r.app != null || r.content || r.session) {
            h.icon.setVisibility(VISIBLE);
            ExternalLauncher.icon(c, r, h.icon, Style.BACKGROUND, board.ownTab(Switchboard.PHONE, r.key),
                c.getResources().getDisplayMetrics().density);
        } else {
            h.icon.setVisibility(GONE);
            h.icon.setTag(null);
        }
        if (item && !r.hidden) {
            h.star.setVisibility(VISIBLE);
            h.star.setImageResource(r.favourite ? R.drawable.xd_ic_star : R.drawable.xd_ic_star_outline);
            h.star.setColorFilter(r.favourite ? Style.ACCENT : Style.TEXT_DIM);
            h.star.setContentDescription(c.getString(r.favourite ? R.string.xd_chooser_unfavourite : R.string.xd_chooser_favourite));
            h.star.setOnClickListener(view -> {
                Style.tick(view);
                host.onDockToggle(r.key);
            });
        } else {
            h.star.setVisibility(GONE);
            h.star.setOnClickListener(null);
        }
        h.box.setOnClickListener(v -> {
            Style.tick(v);
            if (item) board.open(Switchboard.PHONE, r.key);
            else if (r.action != null) r.action.run();
        });
        h.box.setOnLongClickListener(v -> {
            if (!item) return false;
            host.onItemLongPress(r.key);
            return true;
        });
        // A mouse on the phone: the secondary button opens its card, as the external Launchpad's
        h.box.setOnContextClickListener(v -> {
            if (!item) return false;
            host.onItemLongPress(r.key);
            return true;
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return landscape ? landRows.size() : rows.size(); }
        @Override public Object getItem(int i) { return landscape ? landRows.get(i) : rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int i) {
            if (landscape) return true;  // each cell has its own listeners
            return rows.get(i).kind == Switchboard.ITEM || rows.get(i).action != null;
        }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) {
            return landscape ? (landRows.get(i).full ? 0 : 1) : (title(rows.get(i)) ? 0 : 1);
        }

        private boolean title(Switchboard.Item r) {
            return r.kind == Switchboard.HEADER || r.kind == Switchboard.FOLD;
        }

        @Override public View getView(int i, View old, ViewGroup parent) {
            if (landscape) return landView(i, old, parent);
            final Switchboard.Item r = rows.get(i);
            Context c = parent.getContext();
            if (title(r)) {
                TextView t = old != null ? (TextView) old : new TextView(c);
                t.setText(r.label);
                t.setTextColor(r.action != null ? Style.ACCENT : Style.TEXT_DIM);
                t.setTextSize(r.action != null ? Style.TEXT_SMALL + 2 : Style.TEXT_SMALL);
                t.setPadding(0, Style.dp(c, 12), 0, Style.dp(c, r.action != null ? 10 : 2));
                return t;
            }
            Holder h;
            View v = old;
            if (v == null) {
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(Style.dp(c, 56));
                row.setPadding(Style.dp(c, 4), Style.dp(c, 4), 0, Style.dp(c, 4));
                // A device badge hangs a little past its icon's edge: don't cut it at the row's padding
                row.setClipToPadding(false);
                h = new Holder();
                h.icon = new ImageView(c);
                LayoutParams ip = new LayoutParams(Style.dp(c, 40), Style.dp(c, 40));
                ip.rightMargin = Style.dp(c, 12);
                row.addView(h.icon, ip);
                h.dot = new View(c);
                LayoutParams dp = new LayoutParams(Style.dp(c, 8), Style.dp(c, 8));
                dp.leftMargin = Style.dp(c, 4);
                dp.rightMargin = Style.dp(c, 12);
                row.addView(h.dot, dp);
                LinearLayout text = new LinearLayout(c);
                text.setOrientation(VERTICAL);
                h.title = Style.label(c, "", Style.TEXT, 16);
                text.addView(h.title);
                h.subtitle = Style.label(c, "", Style.TEXT_DIM, Style.TEXT_SMALL + 1);
                text.addView(h.subtitle);
                row.addView(text, new LayoutParams(0, -2, 1));
                h.star = new ImageView(c);
                h.star.setScaleType(ImageView.ScaleType.CENTER);
                h.star.setFocusable(false);  // 列表项才点得动
                row.addView(h.star, new LayoutParams(Style.dp(c, Style.TARGET), Style.dp(c, Style.TARGET)));
                row.setTag(h);
                v = row;
            } else {
                h = (Holder) v.getTag();
            }
            boolean item = r.kind == Switchboard.ITEM;
            v.setAlpha(r.hidden || r.dim ? 0.55f : 1f);
            // What is on the external screen stands out, the same way as its tile there (Switchboard.CURRENT_TINT)
            v.setBackground(r.current ? Style.rounded(c, Switchboard.CURRENT_TINT, Style.RADIUS) : null);
            h.title.setText(r.label);
            h.title.setTextColor(r.desktop && (r.display == 0 || r.running) ? Style.ACCENT : Style.TEXT);
            h.subtitle.setText(r.detail);
            h.subtitle.setTextColor(r.current ? Switchboard.ACCENT_TEXT : Style.TEXT_DIM);
            h.subtitle.setVisibility(r.detail != null ? VISIBLE : GONE);
            if (r.app != null || r.content || r.session) {
                h.icon.setVisibility(VISIBLE);
                ExternalLauncher.icon(c, r, h.icon, Style.BACKGROUND, board.ownTab(Switchboard.PHONE, r.key), c.getResources().getDisplayMetrics().density);
            } else {
                h.icon.setVisibility(GONE);
                h.icon.setTag(null);
            }
            h.dot.setVisibility(r.session ? VISIBLE : GONE);
            if (r.session) h.dot.setBackground(Style.dot(r.running ? Style.OK : Style.SURFACE_RAISED));
            if (item && !r.hidden) {
                h.star.setVisibility(VISIBLE);
                h.star.setImageResource(r.favourite ? R.drawable.xd_ic_star : R.drawable.xd_ic_star_outline);
                h.star.setColorFilter(r.favourite ? Style.ACCENT : Style.TEXT_DIM);
                h.star.setContentDescription(c.getString(r.favourite ? R.string.xd_chooser_unfavourite : R.string.xd_chooser_favourite));
                h.star.setOnClickListener(view -> {
                    Style.tick(view);
                    host.onDockToggle(r.key);
                });
            } else {
                h.star.setVisibility(GONE);
                h.star.setOnClickListener(null);
            }
            return v;
        }

        /** Two cells side by side, or a title/hint line across both (ConsoleListLand.dc.html). */
        private View landView(int i, View old, ViewGroup parent) {
            Context c = parent.getContext();
            LandRow lr = landRows.get(i);
            if (lr.full) {
                TextView t = old instanceof TextView ? (TextView) old : new TextView(c);
                t.setText(lr.a.label);
                t.setTextColor(lr.a.action != null ? Style.ACCENT : Style.TEXT_DIM);
                t.setTextSize(Style.TEXT_SMALL + 1);
                t.setPadding(Style.dp(c, 6), Style.dp(c, 10), Style.dp(c, 6), Style.dp(c, 2));
                if (lr.a.action != null) t.setOnClickListener(v -> {
                    Style.tick(v);
                    lr.a.action.run();
                });
                else t.setOnClickListener(null);
                return t;
            }
            LinearLayout row;
            Cell first, second;
            if (old instanceof LinearLayout) {
                row = (LinearLayout) old;
                first = (Cell) row.getChildAt(0).getTag();
                second = (Cell) row.getChildAt(1).getTag();
            } else {
                row = new LinearLayout(c);
                first = landCell(c);
                second = landCell(c);
                row.addView(first.box, new LayoutParams(0, -2, 1));
                LayoutParams sp = new LayoutParams(0, -2, 1);
                sp.leftMargin = Style.dp(c, 10);
                row.addView(second.box, sp);
            }
            fillLandCell(first, lr.a);
            if (lr.b != null) {
                second.box.setVisibility(VISIBLE);
                fillLandCell(second, lr.b);
            } else {
                second.box.setVisibility(INVISIBLE);
            }
            return row;
        }
    }
}
