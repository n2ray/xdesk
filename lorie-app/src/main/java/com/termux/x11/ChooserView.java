package com.termux.x11;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * 手机屏上的启动器（DEVICES.md §3）：标题栏；按设备分的标签（收藏、手机、每个外部设备、「+」）；外部设备的状态和连接/断开；
 * 每个标签自己的搜索；列表按首字母排序，右边是 A–Z 条，隐藏的在最后一折里。
 * 没有外接屏时也能看，只是启动不了。会话进行中它只是盖在会话上的一层，外接屏不变。
 */
final class ChooserView extends LinearLayout {
    interface Host extends DockBar.Host {
        void onSessionChosen(String name);
        /** 只当显示器，等用户自己运行 xdesk 命令。 */
        void onAttachChosen();
        void onAppChosen(AppCatalog.App app);
        /** A Linux program, an X window, a Mac app or window: any key of a ContentSource (Contents). */
        void onContentChosen(String key);
        /** 点了 Termux 那一节的提示行。 */
        void onTermuxHint();
        /** 点了没有外接屏的提示：打开无线显示。 */
        void onNoDisplayHint();
        /** 点了一行右边的星。 */
        void onDockToggle(String key);
        /** 盖在会话上时回到触摸板。 */
        void onChooserClose();
        /** 结束运行中的 Termux 环境。打开或退出 XDesk 不会结束它，这是明确的操作。 */
        void onSessionStop(String name);
        /** 声音输出，所有地方是同一个设置。 */
        void onChooseOutput();
        void onOpenSettings();
    }

    private static final String RECENT_SESSION = "t:", RECENT_APP = "a:";
    private static final String TAB_FAVOURITES = "fav";
    /** Fewer entries than this need no A–Z bar. */
    private static final int INDEX_MIN = 12;

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

    private static final class Row {
        boolean header;
        String title, subtitle, key;
        AppCatalog.App app;
        /** A ContentSource entry's icon; may be null. */
        android.graphics.Bitmap bitmap;
        boolean content;
        Runnable action;
        /** Linux 桌面的状态和按钮。 */
        boolean isSession, running, stopping;
        String sessionName;
        /** An app (not a window or a desktop): can be hidden. */
        boolean hideable, hidden;
        /** Its letter in the A–Z part, 0 outside it. */
        char initial;
    }

    private final Host host;
    private final Devices devices;
    private final TextView activate, banner, notice;
    private final Style.IconButton output;
    private final TextView close;
    private final LinearLayout tabRow, deviceBar;
    private final View deviceDot;
    private final TextView deviceState, deviceButton;
    private final EditText search;
    private final ImageView clear;
    private final ListView list;
    private final AlphaIndexBar index;
    private final Adapter adapter = new Adapter();
    private final ArrayList<Row> rows = new ArrayList<>();
    private final Collator collator = Collator.getInstance();
    /** Letter -> the first row of the A–Z part with it. */
    private final HashMap<Character, Integer> letterRows = new HashMap<>();
    private final HashMap<String, String> queries = new HashMap<>();
    private final Runnable devicesChanged = this::rebuild;
    private String tab, tabsShown = "", current, previous, query = "";
    private boolean onCar, settingQuery, hiddenOpen;
    private List<AppCatalog.App> apps = new ArrayList<>();
    private List<String> dockKeys = new ArrayList<>();

    ChooserView(Context c, Host host) {
        super(c);
        this.host = host;
        devices = Devices.get(c);
        setOrientation(VERTICAL);
        setBackgroundColor(Style.BACKGROUND);
        setClickable(true);  // 挡住下面的触摸板
        setFocusableInTouchMode(true);  // 别一打开就把焦点给搜索框、弹出键盘
        int edge = Style.dp(c, Style.EDGE + 4);
        setPadding(edge, Style.dp(c, Style.EDGE), edge, 0);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Style.label(c, "XDesk", Style.TEXT, Style.TEXT_TITLE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LayoutParams(0, -2, 1));
        // 回到触摸板是这里最常用的按钮，结束 XDesk 放在设置里，免得误碰
        close = Style.button(c, c.getString(R.string.xd_chooser_close), Style.ACCENT, Style.TEXT_SMALL + 1, Style.RADIUS);
        close.setSingleLine(true);
        close.setPadding(Style.dp(c, 12), 0, Style.dp(c, 12), 0);
        close.setOnClickListener(v -> host.onChooserClose());
        close.setVisibility(GONE);
        LayoutParams cp = new LayoutParams(-2, Style.dp(c, Style.TARGET));
        cp.rightMargin = Style.dp(c, Style.GAP);
        head.addView(close, cp);
        output = new Style.IconButton(c, R.drawable.xd_ic_sound, "", Style.SURFACE);
        output.onTap(host::onChooseOutput);
        head.addView(output, new LayoutParams(-2, -2));
        Style.IconButton settings = new Style.IconButton(c, R.drawable.xd_ic_settings, c.getString(R.string.xd_settings), Style.SURFACE);
        settings.onTap(host::onOpenSettings);
        LayoutParams sp = new LayoutParams(-2, -2);
        sp.leftMargin = Style.dp(c, Style.GAP);
        head.addView(settings, sp);
        addView(head, new LayoutParams(-1, -2));

        // 安卓应用还不能用时，第一眼就看到，点了去激活
        activate = Style.button(c, "", Style.ACCENT, 14, Style.RADIUS);
        activate.setVisibility(GONE);
        LayoutParams ap = new LayoutParams(-1, -2);
        ap.topMargin = Style.dp(c, Style.GAP);
        addView(activate, ap);

        banner = Style.label(c, "", Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        banner.setPadding(0, Style.dp(c, 4), 0, 0);
        banner.setOnClickListener(v -> host.onNoDisplayHint());
        addView(banner, new LayoutParams(-1, -2));

        notice = Style.label(c, "", Style.WARN, Style.TEXT_SMALL + 1);
        notice.setPadding(0, Style.dp(c, 4), 0, 0);
        notice.setVisibility(GONE);
        addView(notice, new LayoutParams(-1, -2));

        // 设备标签
        HorizontalScrollView tabs = new HorizontalScrollView(c);
        tabs.setHorizontalScrollBarEnabled(false);
        tabs.setFocusable(false);
        tabRow = new LinearLayout(c);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabs.addView(tabRow, new HorizontalScrollView.LayoutParams(-2, -2));
        LayoutParams tp = new LayoutParams(-1, -2);
        tp.topMargin = Style.dp(c, 10);
        addView(tabs, tp);

        // 外部设备的状态和连接/断开
        deviceBar = new LinearLayout(c);
        deviceBar.setGravity(Gravity.CENTER_VERTICAL);
        deviceBar.setPadding(Style.dp(c, 4), 0, 0, 0);
        deviceDot = new View(c);
        LayoutParams dp = new LayoutParams(Style.dp(c, 8), Style.dp(c, 8));
        dp.rightMargin = Style.dp(c, 10);
        deviceBar.addView(deviceDot, dp);
        deviceState = Style.label(c, "", Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        deviceState.setMaxLines(2);
        deviceBar.addView(deviceState, new LayoutParams(0, -2, 1));
        deviceButton = Style.button(c, "", Style.SURFACE_RAISED, Style.TEXT_SMALL + 1, Style.RADIUS);
        deviceButton.setSingleLine(true);
        deviceButton.setPadding(Style.dp(c, 14), 0, Style.dp(c, 14), 0);
        deviceBar.addView(deviceButton, new LayoutParams(-2, Style.dp(c, 40)));
        LayoutParams bp = new LayoutParams(-1, -2);
        bp.topMargin = Style.dp(c, 8);
        addView(deviceBar, bp);

        FrameLayout searchBox = new FrameLayout(c);
        search = new EditText(c);
        search.setHint(R.string.xd_chooser_search);
        search.setHintTextColor(Style.TEXT_DIM);
        search.setTextColor(Style.TEXT);
        search.setTextSize(Style.TEXT_BODY);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(Style.rounded(c, Style.SURFACE, Style.RADIUS));
        search.setPadding(Style.dp(c, 12), 0, Style.dp(c, Style.TARGET), 0);
        searchBox.addView(search, new FrameLayout.LayoutParams(-1, Style.dp(c, Style.TARGET)));
        clear = new ImageView(c);
        clear.setImageResource(R.drawable.xd_ic_close);
        clear.setColorFilter(Style.TEXT_DIM);
        clear.setScaleType(ImageView.ScaleType.CENTER);
        clear.setContentDescription(c.getString(R.string.xd_chooser_clear));
        clear.setVisibility(GONE);
        clear.setOnClickListener(v -> {
            Style.tick(v);
            search.setText("");
        });
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(Style.dp(c, Style.TARGET), Style.dp(c, Style.TARGET));
        clearParams.gravity = Gravity.END;
        searchBox.addView(clear, clearParams);
        LayoutParams sbp = new LayoutParams(-1, -2);
        sbp.topMargin = Style.dp(c, 8);
        addView(searchBox, sbp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() > 0 ? VISIBLE : GONE);
                if (settingQuery) return;
                query = s.toString().trim().toLowerCase();
                queries.put(tab, query);
                rebuild();
            }
        });

        FrameLayout listBox = new FrameLayout(c);
        list = new ListView(c);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Row r = rows.get(position);
            if (r.action != null) r.action.run();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Row r = rows.get(position);
            if (r.key == null) return false;
            menu(view, r);
            return true;
        });
        listBox.addView(list, new FrameLayout.LayoutParams(-1, -1));
        index = new AlphaIndexBar(c);
        index.setPadding(0, Style.dp(c, 4), 0, Style.dp(c, 4));
        index.setListener(this::jumpTo);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(Style.dp(c, 22), -1);
        ip.gravity = Gravity.END;
        listBox.addView(index, ip);
        addView(listBox, new LayoutParams(-1, 0, 1));

        String saved = devices.tab();
        tab = saved != null ? saved : TAB_FAVOURITES;
        setDisplayConnected(false, false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        devices.addListener(devicesChanged);
        rebuild();
    }

    @Override protected void onDetachedFromWindow() {
        devices.removeListener(devicesChanged);
        super.onDetachedFromWindow();
    }

    private String str(int id) {
        return getContext().getString(id);
    }

    /** @param car 用的是车机：说法不同 */
    void setDisplayConnected(boolean connected, boolean car) {
        banner.setText(R.string.xd_chooser_no_display);
        banner.setTextColor(Style.WARN);
        banner.setVisibility(connected ? GONE : VISIBLE);
        if (car == onCar) return;
        onCar = car;
        rebuild();
    }

    /** 盖在运行中的会话上时才有回到触摸板。 */
    void setOverSession(boolean over) {
        close.setVisibility(over ? VISIBLE : GONE);
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
        queries.clear();
        if (search.length() > 0) search.setText("");
        search.clearFocus();
    }

    /** @param hint 列不出环境时的说明，点按见 Host.onTermuxHint；null 表示正常 */
    void setTermux(List<String> sessionNames, String defaultName, String running, boolean x, String hint) {
        devices.setLinux(sessionNames, defaultName, running, x, hint);  // its listener rebuilds
    }

    void setStopping(String name) {
        devices.setLinuxStopping(name);
    }

    /** The content sources (Contents) changed: their rows are rebuilt from the sources themselves. */
    void refreshContents() {
        devices.touch();  // the device states come from the same sources; its listener (this, DeviceSettings) rebuilds
    }

    void setApps(List<AppCatalog.App> a) {
        apps = a;
        rebuild();
    }

    /**
     * @param previousKey 上一个应用，可以是 null
     * @param currentKey 正在外接屏上的，可以是 null
     */
    void setDock(List<String> keys, String previousKey, String currentKey) {
        dockKeys = keys;
        previous = previousKey;
        current = currentKey;
        rebuild();
    }

    // --- tabs ---

    private void selectTab(String id) {
        if (id.equals(tab)) return;
        tab = id;
        devices.setTab(id);
        hiddenOpen = false;
        String q = queries.get(id);
        settingQuery = true;
        search.setText(q != null ? q : "");
        settingQuery = false;
        query = q != null ? q : "";
        rebuild();
        list.setSelection(0);
    }

    private void buildTabs(List<Devices.Device> external) {
        StringBuilder sig = new StringBuilder(tab);
        for (Devices.Device d : external) sig.append('|').append(d.id).append(d.state).append(d.name);
        if (sig.toString().equals(tabsShown)) return;
        tabsShown = sig.toString();
        tabRow.removeAllViews();
        addTab(TAB_FAVOURITES, "★ " + str(R.string.xd_chooser_favourites), null);
        addTab(Devices.PHONE, str(R.string.xd_dev_phone), null);
        for (Devices.Device d : external) addTab(d.id, d.name, d.state);
        TextView add = tabChip("+", false);
        add.setContentDescription(str(R.string.xd_dev_connect_devices));
        add.setOnClickListener(v -> {
            Style.tick(v);
            DeviceSettings.open(getContext());
        });
        tabRow.addView(add, tabParams());
    }

    private LayoutParams tabParams() {
        LayoutParams p = new LayoutParams(-2, Style.dp(getContext(), 36));
        p.rightMargin = Style.dp(getContext(), Style.GAP);
        return p;
    }

    private TextView tabChip(String text, boolean selected) {
        Context c = getContext();
        TextView t = Style.button(c, text, selected ? Style.ACCENT : Style.SURFACE, Style.TEXT_SMALL + 2, 18);
        t.setSingleLine(true);
        t.setPadding(Style.dp(c, 14), 0, Style.dp(c, 14), 0);
        return t;
    }

    private void addTab(final String id, String name, Devices.State state) {
        TextView t = tabChip(name, id.equals(tab));
        if (state != null) {
            GradientDrawable dot = Style.dot(Devices.color(state));
            int s = Style.dp(getContext(), 8);
            dot.setBounds(0, 0, s, s);
            t.setCompoundDrawablesRelative(dot, null, null, null);
            t.setCompoundDrawablePadding(Style.dp(getContext(), 6));
        }
        t.setOnClickListener(v -> {
            Style.tick(v);
            selectTab(id);
        });
        tabRow.addView(t, tabParams());
    }

    private void showDeviceBar(final Devices.Device d) {
        if (d == null || !d.external()) {
            deviceBar.setVisibility(GONE);
            return;
        }
        deviceBar.setVisibility(VISIBLE);
        deviceDot.setBackground(Style.dot(Devices.color(d.state)));
        String state = str(Devices.stateLabel(d.state));
        deviceState.setText(d.note != null && !d.note.isEmpty() ? state + " · " + d.note : state);
        deviceButton.setEnabled(!d.busy);
        deviceButton.setAlpha(d.busy ? 0.4f : 1f);
        deviceButton.setOnClickListener(null);
        Runnable act = null;
        int label = 0;
        if (d.state == Devices.State.CONNECTED) {
            label = R.string.xd_dev_disconnect;
            act = () -> confirmDisconnect(d);
        } else if (d.state == Devices.State.CONNECTING) {
            label = R.string.xd_dev_state_connecting;
            deviceButton.setEnabled(false);
            deviceButton.setAlpha(0.4f);
        } else if (d.state == Devices.State.NOT_SET_UP) {
            label = R.string.xd_dev_set_up;
            act = () -> DeviceSettings.setUp(getContext(), d.type);
        } else if (d.type == Devices.Type.MAC && d.state == Devices.State.FAILED && d.note != null && isPairingFailure(d)) {
            label = R.string.xd_dev_pair_again;
            act = () -> MacSettings.add(getContext());
        } else if (d.canConnect(devices)) {
            label = R.string.xd_dev_connect;
            act = () -> devices.connect(d.id);
        }
        deviceButton.setVisibility(label != 0 ? VISIBLE : GONE);
        if (label != 0) deviceButton.setText(label);
        final Runnable a = act;
        if (a != null) deviceButton.setOnClickListener(v -> {
            Style.tick(v);
            a.run();
        });
    }

    private boolean isPairingFailure(Devices.Device d) {
        com.termux.x11.mac.MacClient m = com.termux.x11.mac.MacClients.get(getContext(), d.id.substring(Devices.MAC.length()));
        return m != null && m.needsPairing();
    }

    private void confirmDisconnect(final Devices.Device d) {
        if (d.type == Devices.Type.LINUX) {
            devices.disconnect(d.id);  // XDeskActivity asks first, it knows what the X session holds
            return;
        }
        new SettingsUi(getContext()).confirm(getContext().getString(R.string.xd_dev_disconnect_title, d.name),
                str(R.string.xd_dev_disconnect_mac_note), str(R.string.xd_dev_disconnect), () -> devices.disconnect(d.id));
    }

    // --- rows ---

    private Row header(String title) {
        Row r = new Row();
        r.header = true;
        r.title = title;
        return r;
    }

    private Row sessionRow(final String name) {
        Row r = new Row();
        r.key = recentKey(name);
        r.title = name;
        r.isSession = true;
        r.sessionName = name;
        r.running = name.equals(devices.runningSession());
        r.stopping = name.equals(devices.stoppingSession());
        if (r.stopping) r.subtitle = str(R.string.xd_chooser_stopping);
        else if (r.key.equals(current)) r.subtitle = str(onCar ? R.string.xd_on_car : R.string.xd_on_display);
        else if (r.running) r.subtitle = str(R.string.xd_chooser_running);
        else if (name.equals(devices.defaultSession())) r.subtitle = str(R.string.xd_chooser_default);
        r.action = () -> host.onSessionChosen(name);
        return r;
    }

    private Row appRow(final AppCatalog.App a) {
        Row r = new Row();
        r.key = recentKey(a);
        r.title = a.label;
        r.app = a;
        r.hideable = true;
        if (r.key.equals(current)) r.subtitle = str(R.string.xd_on_display);
        r.action = () -> host.onAppChosen(a);
        return r;
    }

    private boolean entryMatches(ContentSource.Entry e) {
        return query.isEmpty() || e.label.toLowerCase().contains(query) || (e.detail != null && e.detail.toLowerCase().contains(query));
    }

    private Row contentRow(final ContentSource.Entry e, boolean app) {
        Row r = new Row();
        r.key = e.key;
        r.title = e.label;
        r.content = true;
        r.bitmap = e.icon;
        r.running = e.running;
        r.hideable = app;
        if (e.key.equals(current)) r.subtitle = str(onCar ? R.string.xd_on_car : R.string.xd_on_display);
        else if (e.detail != null && !e.detail.equalsIgnoreCase(e.label)) r.subtitle = e.detail;
        else if (e.running) r.subtitle = str(R.string.xd_content_running);
        r.action = () -> host.onContentChosen(e.key);
        return r;
    }

    private boolean nameMatches(String name) {
        return query.isEmpty() || name.toLowerCase().contains(query);
    }

    /** A row for any saved key (favourites, the previous app), or null when it is gone. */
    private Row rowForKey(String key) {
        if (isAppKey(key)) {
            AppCatalog.App a = AppCatalog.get(getContext()).find(componentOf(key));
            return a != null ? appRow(a) : null;
        }
        if (key.startsWith(RECENT_SESSION)) return devices.sessions().contains(sessionOf(key)) ? sessionRow(sessionOf(key)) : null;
        ContentSource.Entry e = Contents.find(key);
        return e != null ? contentRow(e, false) : null;
    }

    private void rebuild() {
        List<Devices.Device> all = devices.all();
        Devices.Device device = null;
        boolean known = tab.equals(TAB_FAVOURITES);
        for (Devices.Device d : all) if (d.id.equals(tab)) { device = d; known = true; }
        if (!known) {
            // The device went away (a Mac removed): back to the phone
            tab = Devices.PHONE;
            device = all.get(0);
            query = "";
        }
        buildTabs(all.subList(1, all.size()));
        showDeviceBar(device);
        String tabName = device != null ? device.name : str(R.string.xd_chooser_favourites);
        search.setHint(getContext().getString(R.string.xd_dev_search_in, tabName));

        rows.clear();
        letterRows.clear();
        ArrayList<Row> sorted = new ArrayList<>(), hiddenRows = new ArrayList<>();
        if (device == null) {
            favouriteRows();
        } else if (device.type == Devices.Type.PHONE) {
            for (AppCatalog.App a : apps) if (query.isEmpty() || a.matches(query)) addApp(appRow(a), sorted, hiddenRows);
            if (apps.isEmpty()) {
                Row loading = new Row();
                loading.title = str(R.string.xd_chooser_loading);
                rows.add(loading);
            }
        } else if (device.type == Devices.Type.LINUX) {
            linuxRows(device, sorted, hiddenRows);
        } else if (device.type == Devices.Type.MAC) {
            sourceRows(device, Contents.sourceOf(MacSource.PREFIX), R.string.xd_dev_mac_windows, sorted, hiddenRows);
        }
        if (!sorted.isEmpty()) addSorted(sorted, device != null && device.type != Devices.Type.PHONE);
        if (!hiddenRows.isEmpty()) {
            boolean open = hiddenOpen || !query.isEmpty();
            Row fold = header((open ? "▾ " : "▸ ") + getContext().getString(R.string.xd_dev_hidden, hiddenRows.size()));
            fold.action = () -> {
                hiddenOpen = !hiddenOpen;
                rebuild();
            };
            rows.add(fold);
            if (open) {
                sortRows(hiddenRows);
                rows.addAll(hiddenRows);
            }
        }
        if (rows.isEmpty()) {
            Row none = new Row();
            none.title = str(!query.isEmpty() ? R.string.xd_chooser_no_match
                    : device == null ? R.string.xd_dev_no_favourites : R.string.xd_dev_empty);
            rows.add(none);
        }
        boolean indexed = device != null && sorted.size() > INDEX_MIN;
        index.setVisibility(indexed ? VISIBLE : GONE);
        list.setPadding(0, 0, indexed ? Style.dp(getContext(), 20) : 0, 0);
        if (indexed) index.setPresent(new HashSet<>(letterRows.keySet()));
        adapter.notifyDataSetChanged();
    }

    private void addApp(Row r, List<Row> sorted, List<Row> hiddenRows) {
        r.hidden = devices.isHidden(r.key);
        (r.hidden ? hiddenRows : sorted).add(r);
    }

    private void favouriteRows() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        if (previous != null) keys.add(previous);
        keys.addAll(dockKeys);
        for (String k : keys) {
            Row r = rowForKey(k);
            if (r == null || !nameMatches(r.title)) continue;
            String where = deviceName(k);
            if (k.equals(previous) && !dockKeys.contains(k)) where = str(R.string.xd_dev_previous) + " · " + where;
            r.subtitle = r.subtitle != null ? where + " · " + r.subtitle : where;
            r.hideable = false;
            rows.add(r);
        }
    }

    private String deviceName(String key) {
        String id = devices.deviceOf(key);
        if (id.equals(Devices.PHONE)) return str(R.string.xd_dev_phone);
        if (id.startsWith(Devices.LINUX)) return Devices.linuxName(getContext(), id);
        Devices.Device d = devices.find(id);
        return d != null ? d.name : "";
    }

    private void linuxRows(Devices.Device device, List<Row> sorted, List<Row> hiddenRows) {
        boolean searching = !query.isEmpty();
        boolean termux = device.id.equals(Devices.LINUX + Devices.TERMUX);
        ArrayList<Row> desktops = new ArrayList<>();
        for (String name : devices.sessionsOf(device.id)) if (nameMatches(name)) desktops.add(sessionRow(name));
        if (termux && !searching) {
            String hint = devices.termuxHint();
            if (hint != null) {
                Row h = new Row();
                h.title = hint;
                h.action = host::onTermuxHint;
                desktops.add(h);
            }
            // 环境都在列表里。只有 xdesk run 的程序，或者读不到 Termux 的状态时，才需要直接接上 X
            boolean x = devices.xRunning();
            if (devices.stoppingSession() == null && ((x && devices.runningSession() == null) || hint != null)) {
                Row attach = new Row();
                attach.title = str(x ? R.string.xd_chooser_reattach_x : R.string.xd_chooser_attach_x);
                attach.subtitle = str(x ? R.string.xd_chooser_reattach_x_note : R.string.xd_chooser_attach_x_note);
                attach.action = host::onAttachChosen;
                desktops.add(attach);
            }
        }
        if (!desktops.isEmpty()) {
            rows.add(header(str(R.string.xd_linux_desktops)));
            rows.addAll(desktops);
        }
        sourceRows(device, LinuxSource.get(), R.string.xd_dev_linux_windows, sorted, hiddenRows);
    }

    /** A source's windows of this device as a section, its apps into the sorted part. */
    private void sourceRows(Devices.Device device, ContentSource s, int windowsTitle, List<Row> sorted, List<Row> hiddenRows) {
        if (s == null) return;
        ArrayList<Row> open = new ArrayList<>();
        HashSet<String> shown = new HashSet<>();
        for (ContentSource.Entry e : s.windows()) {
            if (!devices.deviceOf(e.key).equals(device.id)) continue;
            shown.add(e.key);
            if (entryMatches(e)) open.add(contentRow(e, false));
        }
        if (!open.isEmpty()) {
            rows.add(header(str(windowsTitle)));
            rows.addAll(open);
        }
        for (ContentSource.Entry e : s.apps())
            if (!shown.contains(e.key) && entryMatches(e) && devices.deviceOf(e.key).equals(device.id)) addApp(contentRow(e, true), sorted, hiddenRows);
    }

    private void sortRows(List<Row> list) {
        ArrayList<String> names = new ArrayList<>(list.size());
        for (Row r : list) names.add(r.title);
        // Chinese names get their pinyin initial in the background, then one more rebuild
        Initials.prepare(names, this::rebuild);
        for (Row r : list) r.initial = Initials.of(r.title);
        Collections.sort(list, (a, b) -> {
            int by = Integer.compare(Initials.rank(a.initial), Initials.rank(b.initial));
            return by != 0 ? by : collator.compare(a.title, b.title);
        });
    }

    private void addSorted(List<Row> sorted, boolean titled) {
        sortRows(sorted);
        if (titled) rows.add(header(str(R.string.xd_dev_apps)));
        for (Row r : sorted) {
            if (!letterRows.containsKey(r.initial)) letterRows.put(r.initial, rows.size());
            rows.add(r);
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

    // --- the long-press menu ---

    private static final int MENU_STAR = 1, MENU_HIDE = 2, MENU_SETTINGS = 3, MENU_QUIT = 4;

    private void menu(View anchor, final Row r) {
        PopupMenu m = new PopupMenu(getContext(), anchor, Gravity.END);
        Menu items = m.getMenu();
        final boolean docked = dockKeys.contains(r.key);
        final boolean hidden = devices.isHidden(r.key);
        items.add(0, MENU_STAR, 0, docked ? R.string.xd_chooser_unfavourite : R.string.xd_chooser_favourite);
        if (r.hideable || hidden) items.add(0, MENU_HIDE, 1, hidden ? R.string.xd_dev_unhide : R.string.xd_dev_hide);
        if (r.app != null || r.isSession) items.add(0, MENU_SETTINGS, 2, R.string.xd_dev_menu_settings);
        final ContentSource source = Contents.sourceOf(r.key);
        if (source != null && r.running) items.add(0, MENU_QUIT, 3, R.string.xd_dev_quit);
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_STAR:
                    host.onDockToggle(r.key);
                    return true;
                case MENU_HIDE:
                    // Hidden things are not favourites: the favourites must not hold what can't be seen
                    if (!hidden && docked) host.onDockToggle(r.key);
                    devices.setHidden(r.key, !hidden);
                    return true;
                case MENU_SETTINGS:
                    host.onItemLongPress(r.key);
                    return true;
                case MENU_QUIT:
                    source.close(r.key);
                    return true;
            }
            return false;
        });
        m.show();
    }

    private static final class Holder {
        ImageView icon, star;
        View dot;
        TextView title, subtitle, action;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int i) { return rows.get(i).action != null; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return rows.get(i).header ? 0 : 1; }

        @Override public View getView(int i, View old, ViewGroup parent) {
            final Row r = rows.get(i);
            Context c = parent.getContext();
            if (r.header) {
                TextView t = old != null ? (TextView) old : new TextView(c);
                t.setText(r.title);
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
                row.setPadding(0, Style.dp(c, 4), 0, Style.dp(c, 4));
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
                h.action = Style.button(c, "", Style.SURFACE_RAISED, Style.TEXT_SMALL + 1, Style.RADIUS);
                LayoutParams ap = new LayoutParams(Style.dp(c, 60), Style.dp(c, 40));
                ap.rightMargin = Style.dp(c, 4);
                row.addView(h.action, ap);
                h.star = new ImageView(c);
                h.star.setScaleType(ImageView.ScaleType.CENTER);
                h.star.setFocusable(false);  // 列表项才点得动
                row.addView(h.star, new LayoutParams(Style.dp(c, Style.TARGET), Style.dp(c, Style.TARGET)));
                row.setTag(h);
                v = row;
            } else {
                h = (Holder) v.getTag();
            }
            v.setAlpha(r.hidden ? 0.55f : 1f);
            h.title.setText(r.title);
            h.subtitle.setText(r.subtitle);
            h.subtitle.setVisibility(r.subtitle != null ? VISIBLE : GONE);
            if (r.app != null) {
                h.icon.setVisibility(VISIBLE);
                AppCatalog.get(c).showIcon(r.app, h.icon);
            } else if (r.content) {
                h.icon.setVisibility(VISIBLE);
                h.icon.setTag(null);
                if (r.bitmap != null) h.icon.setImageBitmap(r.bitmap);
                else h.icon.setImageDrawable(Style.letterIcon(c, r.title));
            } else {
                h.icon.setVisibility(GONE);
                h.icon.setTag(null);
            }
            h.dot.setVisibility(r.isSession ? VISIBLE : GONE);
            if (r.isSession) h.dot.setBackground(Style.dot(r.running ? Style.OK : Style.SURFACE_RAISED));
            if (r.isSession) {
                h.action.setVisibility(VISIBLE);
                h.action.setText(r.stopping ? R.string.xd_chooser_stopping_short : r.running ? R.string.xd_chooser_stop : R.string.xd_chooser_start);
                boolean busy = devices.stoppingSession() != null;
                h.action.setEnabled(!busy);
                h.action.setAlpha(busy ? 0.4f : 1f);
                h.action.setOnClickListener(view -> {
                    Style.tick(view);
                    if (r.running) host.onSessionStop(r.sessionName);
                    else host.onSessionChosen(r.sessionName);
                });
            } else {
                h.action.setVisibility(GONE);
                h.action.setOnClickListener(null);
            }
            if (r.key != null && !r.hidden) {
                boolean docked = dockKeys.contains(r.key);
                h.star.setVisibility(VISIBLE);
                h.star.setImageResource(docked ? R.drawable.xd_ic_star : R.drawable.xd_ic_star_outline);
                h.star.setColorFilter(docked ? Style.ACCENT : Style.TEXT_DIM);
                h.star.setContentDescription(c.getString(docked ? R.string.xd_chooser_unfavourite : R.string.xd_chooser_favourite));
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
    }
}
