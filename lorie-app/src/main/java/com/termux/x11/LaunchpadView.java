package com.termux.x11;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The switchboard on the phone's screen in "Launchpad" mode (Settings › General › Phone interface), as a grid: the
 * top line (what is on the external screen), the search, the tabs, the device bar, the items of the chosen tab as
 * tiles page by page, and the bottom bar (touchpad, keyboard, keys, sound, dim, settings); in landscape the bar is a
 * rail at the left. It draws the same model as ChooserView's list, the external screen's Launchpad (SwitcherOverlay)
 * and the Docks, and uses the phone's tab and search (Switchboard.PHONE): the same items, labels, state words,
 * current tint and badges. A tap opens its item and leaves the phone here; a long press opens its card (ItemCard).
 * The touchpad page is the running screen (XDeskActivity), one tap away through the bottom bar.
 */
final class LaunchpadView extends LinearLayout {
    /** What only the phone's window can do (XDeskActivity). */
    interface Host {
        /** Open this item; the phone stays on the grid (the external screen takes it). */
        void onLaunchpadOpen(String key);
        /** The item's card, a dialog on the phone (ItemCard). */
        void onItemLongPress(String key);
        /** The bottom bar's touchpad button: the running screen (touchpad page). */
        void onTouchpad();
        /** Whose the physical keyboard and mouse are, switched (as Ctrl+Alt+M). */
        void onToggleKeys();
        /** The keyboard button: the search line with the phone's keyboard. */
        void onSearchTouched();
        void onChooseOutput();
        void onDim();
        void onOpenSettings();
    }

    private static final int PORTRAIT_COLUMNS = 4, LANDSCAPE_COLUMNS = 8;
    /** One cell: the icon, a line with the running dot and the label, and a line of what it is (state, device). */
    private static final float PORTRAIT_ICON = 58, LANDSCAPE_ICON = 54, PORTRAIT_CELL = 96, LANDSCAPE_CELL = 92;
    private static final int DEFAULT_PORTRAIT_ROWS = 6, DEFAULT_LANDSCAPE_ROWS = 3;

    private final Host host;
    private final Switchboard board;
    private final boolean landscape;
    private final int columns, iconSize, cellH;
    private final float density;

    private final LinearLayout statusRow;
    private final View statusDot;
    private final TextView statusText;
    private final FrameLayout searchBox;
    private final EditText search;
    private final ImageView clear;
    private final HorizontalScrollView tabScroll;
    private final LinearLayout tabRow;
    private final LinearLayout notes;
    private final LinearLayout barRow;
    private final View barDot;
    private final TextView barText;
    private final LinearLayout barActions;
    private final FrameLayout gridBox;
    private final Pager grid;
    private final LinearLayout footer;
    private Style.IconButton outputBtn, keysBtn;
    private final List<Style.IconButton> barButtons = new ArrayList<>();

    /** The model's items of the phone's tab (or its search): the tiles, over all pages. */
    private final List<Switchboard.Item> items = new ArrayList<>();
    /** The rows the grid does not make tiles of: the device's hints, and the hidden fold's title. */
    private final List<Switchboard.Item> noteItems = new ArrayList<>();
    private int hiddenCount;
    /** What the model says in place of items (nothing running, no match), or null. */
    private String empty;
    /** A line about the screen or the shell, above the grid (the list's notice). */
    private String notice;

    private int rows = -1, page;
    private String tabsShown = "", barShown = "", shownTab, shownQuery;
    private boolean settingQuery, stale = true, rendering;
    private int lastGridHeight = -1;

    private final Runnable boardChanged = this::rebuild;

    LaunchpadView(Context c, Host host) {
        super(c);
        this.host = host;
        board = Switchboard.get(c);
        landscape = c.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        columns = landscape ? LANDSCAPE_COLUMNS : PORTRAIT_COLUMNS;
        iconSize = dp(landscape ? LANDSCAPE_ICON : PORTRAIT_ICON);
        cellH = dp(landscape ? LANDSCAPE_CELL : PORTRAIT_CELL);
        density = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Style.BACKGROUND);
        setClickable(true);  // covers the touchpad page under it
        setFocusableInTouchMode(true);  // don't take the search line's (and the keyboard's) focus on open
        setOrientation(landscape ? HORIZONTAL : VERTICAL);
        setPadding(dp(Style.EDGE), dp(landscape ? Style.EDGE : 4), dp(Style.EDGE), dp(Style.EDGE));

        statusDot = new View(c);
        statusText = Style.label(c, "", Style.TEXT, 13);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusRow = new LinearLayout(c);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(4), 0, dp(4), 0);
        statusRow.addView(statusDot, new LayoutParams(dp(8), dp(8)));
        LayoutParams sp = new LayoutParams(0, -2, 1);
        sp.leftMargin = dp(8);
        statusRow.addView(statusText, sp);

        search = new EditText(c);
        search.setHint(R.string.xd_chooser_search);
        search.setHintTextColor(Style.TEXT_DIM);
        search.setTextColor(Style.TEXT);
        search.setTextSize(13);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(searchBack(false));
        search.setPadding(dp(12), 0, dp(Style.TARGET), 0);
        Drawable lens = c.getResources().getDrawable(R.drawable.xd_ic_search, c.getTheme()).mutate();
        lens.setTint(Style.TEXT_DIM);
        lens.setBounds(0, 0, dp(18), dp(18));
        search.setCompoundDrawablesRelative(lens, null, null, null);
        search.setCompoundDrawablePadding(dp(8));
        search.setOnFocusChangeListener((v, has) -> search.setBackground(searchBack(has)));
        search.setOnEditorActionListener((v, action, e) -> {
            // As the mockup says: Enter opens the first result
            if (items.isEmpty()) return false;
            Style.tick(v);
            host.onLaunchpadOpen(items.get(0).key);
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? VISIBLE : GONE);
                if (!settingQuery) board.setQuery(Switchboard.PHONE, s.toString());
            }
        });
        searchBox = new FrameLayout(c);
        searchBox.addView(search, new FrameLayout.LayoutParams(-1, dp(landscape ? 38 : 42)));
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
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(Style.TARGET), dp(landscape ? 38 : 42));
        cp.gravity = Gravity.END;
        searchBox.addView(clear, cp);
        search.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) host.onSearchTouched();
            return false;
        });

        tabScroll = new HorizontalScrollView(c);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setFocusable(false);
        tabRow = new LinearLayout(c);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabRow, new HorizontalScrollView.LayoutParams(-2, -2));

        notes = new LinearLayout(c);
        notes.setOrientation(VERTICAL);
        notes.setVisibility(GONE);

        barRow = new LinearLayout(c);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        barRow.setBackground(Style.rounded(c, Style.SURFACE, 14));
        barRow.setPadding(dp(12), dp(6), dp(8), dp(6));
        barRow.setVisibility(GONE);
        barDot = new View(c);
        LayoutParams bp = new LayoutParams(dp(8), dp(8));
        bp.rightMargin = dp(8);
        barRow.addView(barDot, bp);
        barText = Style.label(c, "", Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        barText.setMaxLines(2);
        barRow.addView(barText, new LayoutParams(0, -2, 1));
        barActions = new LinearLayout(c);
        barActions.setGravity(Gravity.CENTER_VERTICAL);
        barRow.addView(barActions, new LayoutParams(-2, -2));

        grid = new Pager(c, by -> setPage(page + by));
        grid.setOrientation(VERTICAL);
        gridBox = new FrameLayout(c);
        gridBox.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        gridBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int height = b - t;
            if (height == lastGridHeight) return;
            lastGridHeight = height;
            // The rows a page holds follow what is left after the IME and the bars: build again once it is known
            if (height > 0 && attached() && getVisibility() == VISIBLE && !rendering) {
                int want = Math.max(1, height / cellH);
                if (want != rows) rebuild();
            }
        });

        footer = new LinearLayout(c);
        footer.setGravity(Gravity.CENTER);
        footer.setMinimumHeight(dp(24));

        if (landscape) {
            LinearLayout rail = new LinearLayout(c);
            rail.setOrientation(VERTICAL);
            rail.setGravity(Gravity.CENTER);
            for (View b : bottomButtons()) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(64), dp(52));
                p.bottomMargin = dp(5);
                rail.addView(b, p);
            }
            addView(rail, new LayoutParams(dp(64), -1));

            LinearLayout main = new LinearLayout(c);
            main.setOrientation(VERTICAL);
            LinearLayout topRow = new LinearLayout(c);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            topRow.addView(statusRow, new LayoutParams(0, -2, 1));
            LayoutParams sep = new LayoutParams(0, dp(38), 1);
            sep.leftMargin = dp(8);
            topRow.addView(searchBox, sep);
            main.addView(topRow, new LayoutParams(-1, -2));
            LayoutParams tp = new LayoutParams(-1, -2);
            tp.topMargin = dp(6);
            main.addView(tabScroll, tp);
            LayoutParams np = new LayoutParams(-1, -2);
            np.topMargin = dp(4);
            main.addView(notes, np);
            LayoutParams brp = new LayoutParams(-1, -2);
            brp.topMargin = dp(4);
            main.addView(barRow, brp);
            main.addView(gridBox, new LayoutParams(-1, 0, 1));
            main.addView(footer, new LayoutParams(-1, dp(24)));
            LayoutParams mp = new LayoutParams(0, -1, 1);
            mp.leftMargin = dp(12);
            addView(main, mp);
        } else {
            addView(statusRow, new LayoutParams(-1, -2));
            LayoutParams tsp = new LayoutParams(-1, -2);
            tsp.topMargin = dp(6);
            addView(searchBox, tsp);
            LayoutParams tp = new LayoutParams(-1, -2);
            tp.topMargin = dp(6);
            addView(tabScroll, tp);
            LayoutParams np = new LayoutParams(-1, -2);
            np.topMargin = dp(2);
            addView(notes, np);
            LayoutParams brp = new LayoutParams(-1, -2);
            brp.topMargin = dp(6);
            addView(barRow, brp);
            LayoutParams gp = new LayoutParams(-1, 0, 1);
            gp.topMargin = dp(4);
            addView(gridBox, gp);
            addView(footer, new LayoutParams(-1, dp(24)));
            LinearLayout bottom = new LinearLayout(c);
            bottom.setGravity(Gravity.CENTER);
            for (View b : bottomButtons()) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1);
                p.leftMargin = p.rightMargin = dp(3);
                bottom.addView(b, p);
            }
            LayoutParams bp2 = new LayoutParams(-1, -2);
            bp2.topMargin = dp(6);
            addView(bottom, bp2);
        }
        statusRow.setVisibility(GONE);
    }

    /** The six buttons of the bottom bar (portrait) and of the rail (landscape), made once. */
    private List<View> bottomButtons() {
        if (barButtons.isEmpty()) {
            makeButton(R.drawable.xd_ic_pointer, R.string.xd_chooser_touchpad, host::onTouchpad);
            makeButton(R.drawable.xd_ic_keyboard, R.string.xd_keyboard, host::onSearchTouched);
            makeButton(R.drawable.xd_ic_keys_phone, R.string.xd_keys_phone_short, host::onToggleKeys);
            makeButton(R.drawable.xd_ic_sound, 0, host::onChooseOutput);
            makeButton(R.drawable.xd_ic_moon, R.string.xd_dim_now, host::onDim);
            makeButton(R.drawable.xd_ic_settings, R.string.xd_settings, host::onOpenSettings);
        }
        return new ArrayList<View>(barButtons);
    }

    private void makeButton(int icon, int label, final Runnable action) {
        Style.IconButton b = new Style.IconButton(getContext(), icon, label != 0 ? str(label) : "", Style.SURFACE);
        b.onTap(action);
        int at = barButtons.size();
        barButtons.add(b);
        if (at == 2) keysBtn = b;          // whose the physical keyboard and mouse are
        else if (at == 3) outputBtn = b;   // its label says where the sound goes (setOutputLabel)
    }

    private int dp(float v) {
        return Style.dp(getContext(), v);
    }

    private boolean attached() {
        return isAttachedToWindow();
    }

    private String str(int id) {
        return getContext().getString(id);
    }

    private GradientDrawable searchBack(boolean focused) {
        GradientDrawable g = Style.rounded(getContext(), Style.SURFACE, Style.RADIUS);
        if (focused) g.setStroke(Math.max(1, dp(1.5f)), Style.ACCENT);
        return g;
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

    /** Behind the touchpad page the grid is not kept up (every key typed on the Launchpad would rebuild it): now it is. */
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE && stale && attached()) rebuild();
    }

    // --- what the window says ---

    /** What is on the external screen (XDeskActivity.setStatus), shown above the search while there is something. */
    void setStatus(int color, String text) {
        statusText.setText(text);
        statusRow.setVisibility(text != null && !text.isEmpty() ? VISIBLE : GONE);
        statusDot.setBackground(Style.dot(color));
    }

    /** A line above the grid: no display, a shell problem, the desktop ended. Null takes it away. */
    void setNotice(String text) {
        if (text != null ? text.equals(notice) : notice == null) return;
        notice = text;
        showNotes();
    }

    void setOutputLabel(String label) {
        if (outputBtn != null) outputBtn.setLabel(label);
    }

    /**
     * The keys button, as the touchpad page's: whose the physical keyboard and mouse are, tapping switches.
     * @param said where they are, read aloud (the icon and colour show it); null: no button
     */
    void setKeys(String said, boolean onDisplay) {
        if (keysBtn == null) return;
        keysBtn.setVisibility(said != null ? VISIBLE : GONE);
        if (said == null) return;
        keysBtn.setLabel(str(onDisplay ? R.string.xd_keys_display_short : R.string.xd_keys_phone_short));
        keysBtn.setIcon(onDisplay ? R.drawable.xd_ic_keys_external : R.drawable.xd_ic_keys_phone);
        keysBtn.setContentDescription(said);
        keysBtn.setActive(onDisplay);
    }

    /** The search line with the focus and the keyboard, after the window became focusable for it (onSearchTouched). */
    void focusSearch() {
        search.requestFocus();
        InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
        if (ime != null) ime.showSoftInput(search, 0);
    }

    void clearSearch() {
        board.setQuery(Switchboard.PHONE, "");
        search.clearFocus();
    }

    // --- the model ---

    private void collect() {
        items.clear();
        noteItems.clear();
        hiddenCount = 0;
        empty = null;
        for (Switchboard.Item i : board.items(Switchboard.PHONE)) {
            if (i.kind == Switchboard.ITEM) items.add(i);
            else if (i.kind == Switchboard.FOLD) hiddenCount = i.count;
            else if (i.kind == Switchboard.NOTE) {
                // A line the list makes tappable (a hint, the command's update) keeps its action here too
                if (i.action != null) noteItems.add(i);
                else if (empty == null) empty = i.label;
            }
        }
    }

    private int perPage() {
        return columns * Math.max(1, rows);
    }

    private int pages() {
        return Math.max(1, (items.size() + perPage() - 1) / perPage());
    }

    private void rebuild() {
        if (getVisibility() != VISIBLE || !attached()) {
            stale = true;
            return;
        }
        stale = false;
        rendering = true;
        try {
            int height = gridBox.getHeight();
            int want = height > 0 ? Math.max(1, height / cellH) : (landscape ? DEFAULT_LANDSCAPE_ROWS : DEFAULT_PORTRAIT_ROWS);
            int was = rows;
            rows = want;
            if (was != rows) page = 0;
            collect();
            String tab = board.tab(Switchboard.PHONE), query = board.query(Switchboard.PHONE);
            if (!tab.equals(shownTab) || !query.equals(shownQuery)) page = 0;
            shownTab = tab;
            shownQuery = query;
            page = Math.max(0, Math.min(page, pages() - 1));
            buildTabs(tab);
            // The search page has no tab strip, as drawn: the query is looking through every device
            tabScroll.setVisibility(board.searching(Switchboard.PHONE) ? GONE : VISIBLE);
            if (!search.getText().toString().equals(query)) {
                // Typed in the list (the phone's one search), or cleared by opening something
                settingQuery = true;
                search.setText(query);
                search.setSelection(query.length());
                settingQuery = false;
            }
            showBar(board.bar(Switchboard.PHONE));
            showNotes();
            renderGrid();
            showFooter();
        } finally {
            rendering = false;
        }
    }

    // --- tabs ---

    private void buildTabs(String selected) {
        boolean searching = board.searching(Switchboard.PHONE);
        StringBuilder sig = new StringBuilder(searching ? "" : selected);
        for (Switchboard.Tab t : board.tabs()) sig.append('|').append(t.id).append(t.state).append(t.name);
        if (sig.toString().equals(tabsShown)) return;
        tabsShown = sig.toString();
        tabRow.removeAllViews();
        for (Switchboard.Tab t : board.tabs()) addTab(t, t.id.equals(selected) && !searching);
        TextView add = tabChip("+", false);
        add.setContentDescription(str(R.string.xd_dev_connect_devices));
        add.setOnClickListener(v -> {
            Style.tick(v);
            DeviceSettings.open(getContext());
        });
        tabRow.addView(add, tabParams());
    }

    private LayoutParams tabParams() {
        LayoutParams p = new LayoutParams(-2, dp(landscape ? 30 : 32));
        p.rightMargin = dp(Style.GAP);
        return p;
    }

    private TextView tabChip(String text, boolean selected) {
        Context c = getContext();
        float radius = landscape ? 15 : 16;
        TextView t = Style.button(c, text, Style.SURFACE, landscape ? 12 : 13, radius);
        t.setTextColor(selected ? Style.ACCENT_2 : Style.TEXT_DIM);
        t.setBackground(selected ? Style.toggle(c, true, Style.SURFACE, radius) : Style.ripple(c, Style.SURFACE, radius));
        t.setSingleLine(true);
        t.setPadding(dp(12), 0, dp(12), 0);
        return t;
    }

    private void addTab(final Switchboard.Tab tab, boolean selected) {
        TextView t = tabChip(tab.name, selected);
        if (tab.state != null) {
            GradientDrawable dot = Style.dot(Devices.color(tab.state));
            int s = dp(8);
            dot.setBounds(0, 0, s, s);
            t.setCompoundDrawablesRelative(dot, null, null, null);
            t.setCompoundDrawablePadding(dp(6));
        }
        t.setOnClickListener(v -> {
            Style.tick(v);
            board.setTab(Switchboard.PHONE, tab.id);
        });
        tabRow.addView(t, tabParams());
    }

    // --- the device bar ---

    private void showBar(Switchboard.Bar bar) {
        if (bar == null) {
            barRow.setVisibility(GONE);
            barShown = "";
            return;
        }
        barRow.setVisibility(VISIBLE);
        barDot.setVisibility(bar.dot != 0 ? VISIBLE : GONE);
        if (bar.dot != 0) barDot.setBackground(Style.dot(bar.dot));
        barText.setText(bar.text);
        StringBuilder sig = new StringBuilder();
        for (Switchboard.Action a : bar.actions) sig.append(a.label).append(a.enabled).append('|');
        // Kept while it is the same: this runs on every change, also in the middle of a tap
        if (sig.toString().equals(barShown) && barActions.getChildCount() == bar.actions.size()) {
            for (int i = 0; i < bar.actions.size(); i++) bind(barActions.getChildAt(i), bar.actions.get(i));
            return;
        }
        barShown = sig.toString();
        barActions.removeAllViews();
        Context c = getContext();
        for (Switchboard.Action a : bar.actions) {
            TextView b = Style.button(c, a.label, Style.SURFACE_RAISED, Style.TEXT_SMALL + 1, Style.RADIUS);
            b.setSingleLine(true);
            b.setPadding(dp(12), 0, dp(12), 0);
            b.setEnabled(a.enabled);
            b.setAlpha(a.enabled ? 1f : 0.4f);
            LayoutParams p = new LayoutParams(-2, dp(32));
            p.leftMargin = dp(Style.GAP);
            barActions.addView(b, p);
            bind(b, a);
        }
    }

    private void bind(View button, final Switchboard.Action a) {
        button.setOnClickListener(v -> {
            Style.tick(v);
            a.run();
        });
    }

    // --- the hints above the grid ---

    private void showNotes() {
        notes.removeAllViews();
        boolean any = false;
        if (notice != null) {
            notes.addView(noteLine(notice, null, Style.WARN));
            any = true;
        }
        for (Switchboard.Item it : noteItems) {
            String line = it.detail != null ? it.label + "\n" + it.detail : it.label;
            notes.addView(noteLine(line, it.action, it.action != null ? Style.ACCENT : Style.TEXT_DIM));
            any = true;
        }
        notes.setVisibility(any ? VISIBLE : GONE);
    }

    private TextView noteLine(String text, final Runnable action, int color) {
        TextView t = Style.label(getContext(), text, color, Style.TEXT_SMALL);
        t.setPadding(dp(6), dp(3), dp(6), dp(3));
        if (action != null) {
            t.setClickable(true);
            t.setOnClickListener(v -> {
                Style.tick(v);
                action.run();
            });
        }
        return t;
    }

    // --- the grid ---

    private void renderGrid() {
        grid.removeAllViews();
        if (items.isEmpty()) {
            TextView none = Style.label(getContext(), empty != null ? empty : str(R.string.xd_dev_empty), Style.TEXT_DIM, 14);
            none.setGravity(Gravity.CENTER);
            none.setPadding(dp(12), dp(12), dp(12), dp(12));
            grid.addView(none, new LayoutParams(-1, -2));
            return;
        }
        int from = page * perPage(), to = Math.min(items.size(), from + perPage());
        LinearLayout row = null;
        for (int i = from; i < to; i++) {
            if ((i - from) % columns == 0) {
                row = new LinearLayout(getContext());
                grid.addView(row, new LayoutParams(-1, cellH));
            }
            row.addView(tile(items.get(i)), new LayoutParams(0, cellH, 1));
        }
    }

    private View tile(final Switchboard.Item it) {
        Context c = getContext();
        boolean searching = board.searching(Switchboard.PHONE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClickable(true);
        box.setPadding(dp(3), dp(2), dp(3), dp(2));
        // What is on the external screen stands out as its row on the phone's list does (Switchboard.CURRENT_TINT)
        box.setBackground(it.current ? Style.rounded(c, Switchboard.CURRENT_TINT, 14) : null);
        box.setAlpha(it.hidden || it.dim ? 0.55f : 1f);
        ImageView icon = new ImageView(c);
        ExternalLauncher.icon(c, it, icon, Style.BACKGROUND, board.ownTab(Switchboard.PHONE, it.key), density);
        box.addView(icon, new LayoutParams(iconSize, iconSize));

        LinearLayout nameRow = new LinearLayout(c);
        nameRow.setGravity(Gravity.CENTER);
        View dot = new View(c);
        LayoutParams dotParams = new LayoutParams(dp(5), dp(5));
        dotParams.rightMargin = dp(4);
        if (it.session || it.running) {
            dot.setBackground(Style.dot(it.running ? Style.OK : Style.SURFACE_RAISED));
            nameRow.addView(dot, dotParams);
        }
        TextView name = Style.label(c, it.label, it.desktop && (it.display == 0 || it.running) ? Style.ACCENT : Style.TEXT, 11.5f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(dp(landscape ? 84 : 86));
        name.setIncludeFontPadding(false);
        nameRow.addView(name);
        LayoutParams np = new LayoutParams(-1, -2);
        np.topMargin = dp(2);
        box.addView(nameRow, np);

        // The state words of the list (or, while searching, whose the result is): the same line, made small
        String detail = searching ? it.where : it.detail;
        TextView sub = Style.label(c, detail != null ? detail : "", it.current ? Switchboard.ACCENT_TEXT : Style.TEXT_DIM, 10);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setGravity(Gravity.CENTER);
        sub.setIncludeFontPadding(false);
        box.addView(sub, new LayoutParams(-1, dp(13)));
        box.setContentDescription(it.title != null ? it.title : it.label);

        box.setOnClickListener(v -> {
            Style.tick(v);
            host.onLaunchpadOpen(it.key);
        });
        box.setOnLongClickListener(v -> {
            host.onItemLongPress(it.key);
            return true;
        });
        // A mouse on the phone: the secondary button opens its card, as the external Launchpad's
        box.setOnContextClickListener(v -> {
            host.onItemLongPress(it.key);
            return true;
        });
        return box;
    }

    private void setPage(int p) {
        int clamped = Math.max(0, Math.min(pages() - 1, p));
        if (clamped == page) return;
        page = clamped;
        renderGrid();
        showFooter();
    }

    // --- the page dots, and the hidden fold ---

    private void showFooter() {
        footer.removeAllViews();
        int n = pages();
        if (n > 1) {
            for (int i = 0; i < n; i++) {
                final int to = i;
                View dot = new View(getContext());
                dot.setBackground(Style.dot(i == page ? Style.TEXT : 0xFF3A414C));
                FrameLayout hit = new FrameLayout(getContext());
                hit.addView(dot, new FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER));
                hit.setOnClickListener(v -> setPage(to));
                footer.addView(hit, new LayoutParams(dp(22), dp(22)));
            }
        }
        // The hidden fold of the list: here a button that puts the hidden ones at the end
        if (hiddenCount > 0 && !board.searching(Switchboard.PHONE)) {
            final boolean open = board.hiddenOpen(Switchboard.PHONE);
            TextView fold = Style.label(getContext(), getContext().getString(open ? R.string.xd_sw_hide_hidden : R.string.xd_sw_show_hidden, hiddenCount),
                Style.TEXT_DIM, Style.TEXT_SMALL);
            fold.setGravity(Gravity.CENTER);
            fold.setPadding(dp(10), 0, dp(10), 0);
            fold.setBackground(Style.ripple(getContext(), Style.SURFACE, 12));
            fold.setOnClickListener(v -> {
                Style.tick(v);
                board.setHiddenOpen(Switchboard.PHONE, !open);
                if (!open) setPage(pages() - 1);
            });
            LayoutParams p = new LayoutParams(-2, dp(22));
            p.leftMargin = dp(12);
            footer.addView(fold, p);
        }
    }

    /** Swipes a page; a tap goes to the tile under the finger as usual. */
    private static final class Pager extends LinearLayout {
        interface OnPage {
            void page(int by);
        }

        private final OnPage onPage;
        private final int slop;
        private float downX, downY;
        private boolean dragging;

        Pager(Context c, OnPage onPage) {
            super(c);
            this.onPage = onPage;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setClickable(true);  // a swipe that starts on empty grid space is still ours
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true;
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
                dragging = false;
            } else if (action == MotionEvent.ACTION_MOVE) {
                // Here too, for a swipe that started on empty grid space (no child took the touch)
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) dragging = true;
            } else if (action == MotionEvent.ACTION_UP) {
                if (dragging) {
                    float dx = e.getX() - downX;
                    if (Math.abs(dx) > getWidth() * 0.12f) onPage.page(dx < 0 ? 1 : -1);
                }
                dragging = false;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
            }
            return true;
        }
    }
}
