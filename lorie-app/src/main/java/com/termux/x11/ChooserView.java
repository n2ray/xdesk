package com.termux.x11;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.util.ArrayList;
import java.util.List;

/**
 * 手机屏上的启动器：标题栏；我的收藏（第一项是上一个应用）；所有应用和搜索，Linux 桌面是最上面的一小组。
 * 没有外接屏时也能看，只是启动不了。会话进行中它只是盖在会话上的一层，外接屏不变。
 */
final class ChooserView extends LinearLayout {
    interface Host extends DockBar.Host {
        void onSessionChosen(String name);
        /** 只当显示器，等用户自己运行 xdesk 命令。 */
        void onAttachChosen();
        void onAppChosen(AppCatalog.App app);
        /** 点了 Termux 那一节的提示行。 */
        void onTermuxHint();
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
        Runnable action;
        /** Linux 桌面的状态和按钮。 */
        boolean isSession, running;
        String sessionName;
    }

    private final Host host;
    private final TextView banner, notice, favouritesLabel;
    private final Style.IconButton output;
    private final TextView close;
    private final EditText search;
    private final ImageView clear;
    private final DockBar dock;
    private final Adapter adapter = new Adapter();
    private final ArrayList<Row> rows = new ArrayList<>();
    private List<String> sessions = new ArrayList<>();
    private String runningSession, defaultSession, termuxHint, current, query = "";
    private boolean xRunning;
    private List<AppCatalog.App> apps = new ArrayList<>();
    private List<String> dockKeys = new ArrayList<>();

    ChooserView(Context c, Host host) {
        super(c);
        this.host = host;
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
        close = Style.button(c, "回到触摸板", Style.ACCENT, Style.TEXT_SMALL + 1, Style.RADIUS);
        close.setPadding(Style.dp(c, 12), 0, Style.dp(c, 12), 0);
        close.setOnClickListener(v -> host.onChooserClose());
        close.setVisibility(GONE);
        LayoutParams cp = new LayoutParams(-2, Style.dp(c, Style.TARGET));
        cp.rightMargin = Style.dp(c, Style.GAP);
        head.addView(close, cp);
        output = new Style.IconButton(c, R.drawable.xd_ic_sound, "", Style.SURFACE);
        output.onTap(host::onChooseOutput);
        head.addView(output, new LayoutParams(-2, -2));
        Style.IconButton settings = new Style.IconButton(c, R.drawable.xd_ic_settings, "设置", Style.SURFACE);
        settings.onTap(host::onOpenSettings);
        LayoutParams sp = new LayoutParams(-2, -2);
        sp.leftMargin = Style.dp(c, Style.GAP);
        head.addView(settings, sp);
        addView(head, new LayoutParams(-1, -2));

        banner = Style.label(c, "", Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        banner.setPadding(0, Style.dp(c, 4), 0, 0);
        addView(banner, new LayoutParams(-1, -2));

        notice = Style.label(c, "", Style.WARN, Style.TEXT_SMALL + 1);
        notice.setPadding(0, Style.dp(c, 4), 0, 0);
        notice.setVisibility(GONE);
        addView(notice, new LayoutParams(-1, -2));

        favouritesLabel = sectionLabel("我的收藏");
        addView(favouritesLabel, new LayoutParams(-1, -2));
        dock = new DockBar(c, host, true, true);
        addView(dock, new LayoutParams(-1, Style.dp(c, 62)));

        addView(sectionLabel("所有应用"), new LayoutParams(-1, -2));
        FrameLayout searchBox = new FrameLayout(c);
        search = new EditText(c);
        search.setHint("搜索应用名或包名");
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
        clear.setContentDescription("清除");
        clear.setVisibility(GONE);
        clear.setOnClickListener(v -> {
            Style.tick(v);
            search.setText("");
        });
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(Style.dp(c, Style.TARGET), Style.dp(c, Style.TARGET));
        clearParams.gravity = Gravity.END;
        searchBox.addView(clear, clearParams);
        addView(searchBox, new LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase();
                clear.setVisibility(s.length() > 0 ? VISIBLE : GONE);
                applySearching();
                rebuild();
            }
        });
        // 搜索时把收藏收起来，位置留给结果
        search.setOnFocusChangeListener((v, focused) -> applySearching());

        ListView list = new ListView(c);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Row r = rows.get(position);
            if (r.action != null) r.action.run();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Row r = rows.get(position);
            if (r.key == null) return false;
            host.onItemLongPress(r.key);
            return true;
        });
        addView(list, new LayoutParams(-1, 0, 1));
        setDisplayConnected(false);
    }

    private TextView sectionLabel(String text) {
        TextView t = Style.label(getContext(), text, Style.TEXT_DIM, Style.TEXT_SMALL);
        t.setPadding(0, Style.dp(getContext(), 10), 0, Style.dp(getContext(), 4));
        return t;
    }

    private void applySearching() {
        boolean searching = search.hasFocus() || !query.isEmpty();
        favouritesLabel.setVisibility(searching ? GONE : VISIBLE);
        dock.setVisibility(searching ? GONE : VISIBLE);
    }

    void setDisplayConnected(boolean connected) {
        banner.setText("没有外接屏。可以先看看，插上显示器后才能启动。");
        banner.setTextColor(Style.WARN);
        banner.setVisibility(connected ? GONE : VISIBLE);
    }

    /** 盖在运行中的会话上时才有回到触摸板。 */
    void setOverSession(boolean over) {
        close.setVisibility(over ? VISIBLE : GONE);
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
        if (search.length() > 0) search.setText("");
        search.clearFocus();
    }

    /** @param hint 列不出环境时的说明，点按见 Host.onTermuxHint；null 表示正常 */
    void setTermux(List<String> sessionNames, String defaultName, String running, boolean x, String hint) {
        sessions = sessionNames;
        defaultSession = defaultName;
        runningSession = running;
        xRunning = x;
        termuxHint = hint;
        rebuild();
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
        current = currentKey;
        dock.setItems(keys, previousKey, currentKey);
        rebuild();
    }

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
        r.running = name.equals(runningSession);
        if (r.key.equals(current)) r.subtitle = "正在外接屏上";
        else if (r.running) r.subtitle = "正在运行，点按接回";
        else if (name.equals(defaultSession)) r.subtitle = "默认";
        r.action = () -> host.onSessionChosen(name);
        return r;
    }

    private Row appRow(final AppCatalog.App a) {
        Row r = new Row();
        r.key = recentKey(a);
        r.title = a.label;
        r.app = a;
        if (r.key.equals(current)) r.subtitle = "正在外接屏上";
        r.action = () -> host.onAppChosen(a);
        return r;
    }

    private boolean sessionMatches(String name) {
        return query.isEmpty() || name.toLowerCase().contains(query);
    }

    private void rebuild() {
        rows.clear();
        boolean searching = !query.isEmpty();

        ArrayList<Row> desktops = new ArrayList<>();
        for (String name : sessions) if (sessionMatches(name)) desktops.add(sessionRow(name));
        if (!searching) {
            if (termuxHint != null) {
                Row hint = new Row();
                hint.title = termuxHint;
                hint.action = host::onTermuxHint;
                desktops.add(hint);
            }
            // 环境都在列表里。只有 xdesk run 的程序，或者读不到 Termux 的状态时，才需要直接接上 X
            if ((xRunning && runningSession == null) || termuxHint != null) {
                Row attach = new Row();
                attach.title = xRunning ? "接回正在运行的 X 显示" : "接上 X 显示";
                attach.subtitle = xRunning ? "比如 xdesk run 启动的程序" : "自己在 Termux 里运行 xdesk 命令";
                attach.action = host::onAttachChosen;
                desktops.add(attach);
            }
        }
        if (!desktops.isEmpty()) {
            rows.add(header("Linux 桌面"));
            rows.addAll(desktops);
        }

        ArrayList<Row> appRows = new ArrayList<>();
        for (AppCatalog.App a : apps) if (!searching || a.matches(query)) appRows.add(appRow(a));
        if (!appRows.isEmpty()) rows.add(header("安卓应用（需要 Shizuku）"));
        if (apps.isEmpty()) {
            Row loading = new Row();
            loading.title = "正在读取应用列表…";
            rows.add(loading);
        }
        rows.addAll(appRows);
        if (searching && rows.isEmpty()) {
            Row none = new Row();
            none.title = "没有找到";
            rows.add(none);
        }
        adapter.notifyDataSetChanged();
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
                t.setTextColor(Style.TEXT_DIM);
                t.setTextSize(Style.TEXT_SMALL);
                t.setPadding(0, Style.dp(c, 12), 0, Style.dp(c, 2));
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
            h.title.setText(r.title);
            h.subtitle.setText(r.subtitle);
            h.subtitle.setVisibility(r.subtitle != null ? VISIBLE : GONE);
            if (r.app != null) {
                h.icon.setVisibility(VISIBLE);
                AppCatalog.get(c).showIcon(r.app, h.icon);
            } else {
                h.icon.setVisibility(GONE);
                h.icon.setTag(null);
            }
            h.dot.setVisibility(r.isSession ? VISIBLE : GONE);
            if (r.isSession) h.dot.setBackground(Style.dot(r.running ? Style.OK : Style.SURFACE_RAISED));
            if (r.isSession) {
                h.action.setVisibility(VISIBLE);
                h.action.setText(r.running ? "结束" : "启动");
                h.action.setOnClickListener(view -> {
                    Style.tick(view);
                    if (r.running) host.onSessionStop(r.sessionName);
                    else host.onSessionChosen(r.sessionName);
                });
            } else {
                h.action.setVisibility(GONE);
                h.action.setOnClickListener(null);
            }
            if (r.key != null) {
                boolean docked = dockKeys.contains(r.key);
                h.star.setVisibility(VISIBLE);
                h.star.setImageResource(docked ? R.drawable.xd_ic_star : R.drawable.xd_ic_star_outline);
                h.star.setColorFilter(docked ? Style.ACCENT : Style.TEXT_DIM);
                h.star.setContentDescription(docked ? "移出收藏" : "加入收藏");
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
