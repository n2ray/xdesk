package com.termux.x11;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 打开 App 时看到的界面：选外接屏上运行什么。最近用过的在最前面。没有外接屏时也能看，只是启动不了。 */
final class ChooserView extends LinearLayout {
    /** 一个可启动的安卓应用。 */
    static final class App {
        final String label, packageName, activityName;
        final ResolveInfo info;

        App(String label, ResolveInfo info) {
            this.label = label;
            this.info = info;
            packageName = info.activityInfo.packageName;
            activityName = info.activityInfo.name;
        }

        String component() {
            return packageName + "/" + activityName;
        }
    }

    interface Host {
        void onSessionChosen(String name);
        /** 只当显示器，等用户自己运行 xdesk 命令。 */
        void onAttachChosen();
        void onAppChosen(App app);
        /** 点了 Termux 那一节的提示行。 */
        void onTermuxHint();
    }

    private static final String RECENT_SESSION = "t:", RECENT_APP = "a:";

    private static final class Row {
        boolean header;
        String title, subtitle;
        App app;
        Runnable action;
    }

    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService iconLoader = Executors.newSingleThreadExecutor();
    private final HashMap<String, Drawable> icons = new HashMap<>();
    private final TextView banner;
    private final Adapter adapter = new Adapter();
    private final ArrayList<Row> rows = new ArrayList<>();
    private List<String> sessions = new ArrayList<>();
    private String runningSession, defaultSession, termuxHint;
    private boolean xRunning;
    private List<App> apps = new ArrayList<>();
    private List<String> recents = new ArrayList<>();

    ChooserView(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(Style.BACKGROUND);
        setClickable(true);  // 挡住下面的触摸板
        int pad = Style.dp(c, 12);
        setPadding(pad, pad, pad, 0);

        TextView title = new TextView(c);
        title.setText("外接屏上运行什么？");
        title.setTextColor(Style.TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        addView(title, new LayoutParams(-1, -2));

        banner = new TextView(c);
        banner.setTextSize(14);
        banner.setPadding(0, Style.dp(c, 6), 0, Style.dp(c, 8));
        addView(banner, new LayoutParams(-1, -2));

        ListView list = new ListView(c);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Row r = rows.get(position);
            if (r.action != null) r.action.run();
        });
        addView(list, new LayoutParams(-1, 0, 1));
        setDisplayConnected(false);
    }

    void setDisplayConnected(boolean connected) {
        banner.setText(connected ? "外接屏已连接，点一项就开始。" : "没有外接屏。可以先看看，插上显示器后才能启动。");
        banner.setTextColor(connected ? Style.TEXT_DIM : Style.WARN);
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

    void setApps(List<App> a) {
        apps = a;
        rebuild();
    }

    void setRecents(List<String> keys) {
        recents = keys;
        rebuild();
    }

    static String recentKey(String sessionName) {
        return RECENT_SESSION + sessionName;
    }

    static String recentKey(App a) {
        return RECENT_APP + a.component();
    }

    private Row header(String title) {
        Row r = new Row();
        r.header = true;
        r.title = title;
        return r;
    }

    private Row sessionRow(final String name) {
        Row r = new Row();
        r.title = name;
        if (name.equals(runningSession)) r.subtitle = "正在运行，点按接回";
        else if (name.equals(defaultSession)) r.subtitle = "Termux 桌面（默认）";
        else r.subtitle = "Termux 桌面";
        r.action = () -> host.onSessionChosen(name);
        return r;
    }

    private Row appRow(final App a) {
        Row r = new Row();
        r.title = a.label;
        r.app = a;
        r.action = () -> host.onAppChosen(a);
        return r;
    }

    private void rebuild() {
        rows.clear();
        ArrayList<Row> recentRows = new ArrayList<>();
        for (String key : recents) {
            if (key.startsWith(RECENT_SESSION)) {
                String name = key.substring(RECENT_SESSION.length());
                if (sessions.contains(name)) recentRows.add(sessionRow(name));
            } else {
                for (App a : apps) {
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

        rows.add(header("Termux 桌面"));
        for (String name : sessions) rows.add(sessionRow(name));
        if (termuxHint != null) {
            Row hint = new Row();
            hint.title = termuxHint;
            hint.action = host::onTermuxHint;
            rows.add(hint);
        }
        Row attach = new Row();
        if (xRunning && runningSession == null) {
            attach.title = "接回正在运行的 X 显示";
            attach.subtitle = "X 服务器正在运行";
        } else {
            attach.title = "只当显示器";
            attach.subtitle = "等 Termux 里的 xdesk 命令连上来";
        }
        attach.action = host::onAttachChosen;
        rows.add(attach);

        rows.add(header("安卓应用（需要 Shizuku）"));
        if (apps.isEmpty()) {
            Row loading = new Row();
            loading.title = "正在读取应用列表…";
            rows.add(loading);
        }
        for (App a : apps) rows.add(appRow(a));
        adapter.notifyDataSetChanged();
    }

    /** 后台线程读，应用多时很慢。 */
    static List<App> loadApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ArrayList<App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            if (c.getPackageName().equals(ri.activityInfo.packageName)) continue;
            out.add(new App(String.valueOf(ri.loadLabel(pm)), ri));
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    private void loadIcon(final App a, final ImageView into) {
        final String key = a.component();
        Drawable d = icons.get(key);
        into.setTag(key);
        into.setImageDrawable(d);
        if (d != null) return;
        final PackageManager pm = getContext().getPackageManager();
        iconLoader.execute(() -> {
            final Drawable loaded = a.info.loadIcon(pm);
            ui.post(() -> {
                icons.put(key, loaded);
                if (key.equals(into.getTag())) into.setImageDrawable(loaded);
            });
        });
    }

    private static final class Holder {
        ImageView icon;
        TextView title, subtitle;
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
            Row r = rows.get(i);
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
                loadIcon(r.app, h.icon);
            } else {
                h.icon.setVisibility(GONE);
                h.icon.setTag(null);
            }
            return v;
        }
    }
}
