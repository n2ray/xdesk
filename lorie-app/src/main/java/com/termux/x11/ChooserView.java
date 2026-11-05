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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 选外接屏上运行什么：常用（Dock）、搜索、最近使用、全部。没有外接屏时也能看，只是启动不了。
 * 会话进行中也能盖在触摸板上打开，用来切换到不在 Dock 里的应用。
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
        /** 盖在会话上时点了关闭。 */
        void onChooserClose();
        /** 明确结束 XDesk。 */
        void onEndXDesk();
        /** 结束运行中的 Termux 环境。打开或退出 XDesk 不会结束它，这是明确的操作。 */
        void onSessionStop(String name);
        /** 声音输出，和设置里、触摸板界面上是同一个设置。 */
        void onChooseOutput();
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
        /** 运行中的环境，这一行多一个结束按钮。 */
        String stoppable;
        AppCatalog.App app;
        Runnable action;
    }

    private final Host host;
    private final TextView banner, notice, output;
    private final LinearLayout sessionRow;
    private final EditText search;
    private final DockBar dock;
    private final Adapter adapter = new Adapter();
    private final ArrayList<Row> rows = new ArrayList<>();
    private List<String> sessions = new ArrayList<>();
    private String runningSession, defaultSession, termuxHint, current, query = "";
    private boolean xRunning;
    private List<AppCatalog.App> apps = new ArrayList<>();
    private List<String> recents = new ArrayList<>(), dockKeys = new ArrayList<>();

    ChooserView(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(Style.BACKGROUND);
        setClickable(true);  // 挡住下面的触摸板
        setFocusableInTouchMode(true);  // 别一打开就把焦点给搜索框、弹出键盘
        int pad = Style.dp(c, 12);
        setPadding(pad, pad, pad, 0);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(c);
        title.setText("外接屏上运行什么？");
        title.setTextColor(Style.TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LayoutParams(0, -2, 1));
        output = Style.button(c, "", Style.SURFACE_RAISED, 13, 16);
        output.setPadding(Style.dp(c, 12), Style.dp(c, 6), Style.dp(c, 12), Style.dp(c, 6));
        output.setOnClickListener(v -> host.onChooseOutput());
        LayoutParams op = new LayoutParams(-2, -2);
        op.rightMargin = Style.dp(c, 6);
        head.addView(output, op);
        addView(head, new LayoutParams(-1, -2));

        // 只在会话进行时有：这时启动器只是盖在会话上的一层，外接屏不变
        sessionRow = new LinearLayout(c);
        sessionRow.setGravity(Gravity.CENTER_VERTICAL);
        sessionRow.setPadding(0, Style.dp(c, 6), 0, 0);
        TextView close = Style.button(c, "回到触摸板", Style.ACCENT, 14, 16);
        close.setPadding(Style.dp(c, 14), Style.dp(c, 8), Style.dp(c, 14), Style.dp(c, 8));
        close.setOnClickListener(v -> host.onChooserClose());
        LayoutParams closeParams = new LayoutParams(0, -2, 1);
        closeParams.rightMargin = Style.dp(c, 8);
        sessionRow.addView(close, closeParams);
        TextView end = Style.button(c, "结束 XDesk", Style.SURFACE_RAISED, 14, 16);
        end.setTextColor(Style.DANGER);
        end.setPadding(Style.dp(c, 14), Style.dp(c, 8), Style.dp(c, 14), Style.dp(c, 8));
        end.setOnClickListener(v -> host.onEndXDesk());
        sessionRow.addView(end, new LayoutParams(-2, -2));
        sessionRow.setVisibility(GONE);
        addView(sessionRow, new LayoutParams(-1, -2));

        banner = new TextView(c);
        banner.setTextSize(14);
        banner.setPadding(0, Style.dp(c, 4), 0, Style.dp(c, 6));
        addView(banner, new LayoutParams(-1, -2));

        notice = new TextView(c);
        notice.setTextColor(Style.WARN);
        notice.setTextSize(14);
        notice.setPadding(0, 0, 0, Style.dp(c, 6));
        notice.setVisibility(GONE);
        addView(notice, new LayoutParams(-1, -2));

        dock = new DockBar(c, host, true, false);
        LayoutParams dp = new LayoutParams(-1, Style.dp(c, 62));
        dp.bottomMargin = Style.dp(c, 6);
        addView(dock, dp);

        search = new EditText(c);
        search.setHint("搜索应用名或包名");
        search.setHintTextColor(Style.TEXT_DIM);
        search.setTextColor(Style.TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(Style.rounded(c, Style.SURFACE, 12));
        search.setPadding(Style.dp(c, 12), Style.dp(c, 8), Style.dp(c, 12), Style.dp(c, 8));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase();
                rebuild();
            }
        });
        // 横屏弹出键盘后高度很紧，搜索时把位置留给结果
        search.setOnFocusChangeListener((v, focused) -> {
            boolean tight = focused && getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            dock.setVisibility(tight ? GONE : VISIBLE);
            banner.setVisibility(tight ? GONE : VISIBLE);
        });
        addView(search, new LayoutParams(-1, -2));

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

    void setDisplayConnected(boolean connected) {
        banner.setText(connected ? "外接屏已连接，点一项就开始。" : "没有外接屏。可以先看看，插上显示器后才能启动。");
        banner.setTextColor(connected ? Style.TEXT_DIM : Style.WARN);
    }

    /** 盖在运行中的会话上时才有关闭按钮。 */
    void setOverSession(boolean over) {
        sessionRow.setVisibility(over ? VISIBLE : GONE);
    }

    /** 刚发生的事，比如桌面停止了。null 去掉。 */
    void setNotice(String text) {
        notice.setText(text);
        notice.setVisibility(text != null ? VISIBLE : GONE);
    }

    void setOutputLabel(String label) {
        output.setText(label);
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

    void setRecents(List<String> keys) {
        recents = keys;
        rebuild();
    }

    /** @param currentKey 正在外接屏上的，可以是 null */
    void setDock(List<String> keys, String currentKey) {
        dockKeys = keys;
        current = currentKey;
        dock.setItems(keys, currentKey);
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
        if (r.key.equals(current)) r.subtitle = "正在外接屏上";
        else if (name.equals(runningSession)) r.subtitle = "正在运行，点按接回";
        else if (name.equals(defaultSession)) r.subtitle = "Termux 桌面（默认）";
        else r.subtitle = "Termux 桌面";
        if (name.equals(runningSession)) r.stoppable = name;
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
        if (!searching) {
            ArrayList<Row> recentRows = new ArrayList<>();
            for (String key : recents) {
                if (!isAppKey(key)) {
                    String name = sessionOf(key);
                    if (sessions.contains(name)) recentRows.add(sessionRow(name));
                } else {
                    for (AppCatalog.App a : apps) {
                        if (key.equals(recentKey(a))) {
                            recentRows.add(appRow(a));
                            break;
                        }
                    }
                }
            }
            if (!recentRows.isEmpty()) {
                rows.add(header("最近使用"));
                rows.addAll(recentRows);
            }
        }

        ArrayList<Row> termux = new ArrayList<>();
        for (String name : sessions) if (sessionMatches(name)) termux.add(sessionRow(name));
        if (!searching) {
            if (termuxHint != null) {
                Row hint = new Row();
                hint.title = termuxHint;
                hint.action = host::onTermuxHint;
                termux.add(hint);
            }
            // 环境都在上面的列表里。只有 xdesk run 的程序，或者读不到 Termux 的状态时，才需要直接接上 X
            if ((xRunning && runningSession == null) || termuxHint != null) {
                Row attach = new Row();
                attach.title = xRunning ? "接回正在运行的 X 显示" : "接上 X 显示";
                attach.subtitle = xRunning ? "比如 xdesk run 启动的程序" : "自己在 Termux 里运行 xdesk 命令";
                attach.action = host::onAttachChosen;
                termux.add(attach);
            }
        }
        if (!termux.isEmpty()) {
            rows.add(header("Termux 桌面"));
            rows.addAll(termux);
        }

        ArrayList<Row> appRows = new ArrayList<>();
        for (AppCatalog.App a : apps) if (!searching || a.matches(query)) appRows.add(appRow(a));
        if (!appRows.isEmpty() || !searching) rows.add(header("安卓应用（需要 Shizuku）"));
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
        ImageView icon;
        TextView title, subtitle, star, stop;
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
                t.setTextColor(Style.ACCENT);
                t.setTextSize(13);
                t.setPadding(Style.dp(c, 4), Style.dp(c, 14), 0, Style.dp(c, 4));
                return t;
            }
            Holder h;
            View v = old;
            if (v == null) {
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(Style.dp(c, 56));
                row.setPadding(Style.dp(c, 4), Style.dp(c, 6), Style.dp(c, 4), Style.dp(c, 6));
                h = new Holder();
                h.icon = new ImageView(c);
                LayoutParams ip = new LayoutParams(Style.dp(c, 40), Style.dp(c, 40));
                ip.rightMargin = Style.dp(c, 12);
                row.addView(h.icon, ip);
                LinearLayout text = new LinearLayout(c);
                text.setOrientation(VERTICAL);
                h.title = new TextView(c);
                h.title.setTextColor(Style.TEXT);
                h.title.setTextSize(16);
                text.addView(h.title);
                h.subtitle = new TextView(c);
                h.subtitle.setTextColor(Style.TEXT_DIM);
                h.subtitle.setTextSize(13);
                text.addView(h.subtitle);
                row.addView(text, new LayoutParams(0, -2, 1));
                h.stop = Style.button(c, "结束", Style.SURFACE_RAISED, 12, 14);
                LayoutParams sp = new LayoutParams(Style.dp(c, 52), Style.dp(c, 32));
                sp.rightMargin = Style.dp(c, 4);
                row.addView(h.stop, sp);
                h.star = new TextView(c);
                h.star.setTextSize(22);
                h.star.setGravity(Gravity.CENTER);
                h.star.setFocusable(false);  // 列表项才点得动
                row.addView(h.star, new LayoutParams(Style.dp(c, 48), Style.dp(c, 48)));
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
            if (r.stoppable != null) {
                h.stop.setVisibility(VISIBLE);
                h.stop.setOnClickListener(view -> host.onSessionStop(r.stoppable));
            } else {
                h.stop.setVisibility(GONE);
                h.stop.setOnClickListener(null);
            }
            if (r.key != null) {
                boolean docked = dockKeys.contains(r.key);
                h.star.setVisibility(VISIBLE);
                h.star.setText(docked ? "★" : "☆");
                h.star.setTextColor(docked ? Style.WARN : Style.TEXT_DIM);
                h.star.setContentDescription(docked ? "移出 Dock" : "加入 Dock");
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
