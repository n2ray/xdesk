package com.termux.x11;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * 启动器和控制界面共用的一行：先是当前应用（高亮）和上一个应用，方便来回切换，然后一条竖线，后面是收藏，可以横向滚动。这两个也可能在
 * 收藏里，两边都显示。两个都没有时连竖线一起隐藏。点一下切换外接屏上的内容，长按打开设置卡片。不抢键盘焦点。
 */
final class DockBar extends LinearLayout {
    interface Host {
        void onDockTap(String key);
        /** 长按一个图标，打开它的设置卡片。 */
        void onItemLongPress(String key);
    }

    private final Host host;
    private final boolean showLabels, hintWhenEmpty;
    private final LinearLayout previous, row;
    private final View divider;

    DockBar(Context c, Host host, boolean showLabels, boolean hintWhenEmpty) {
        super(c);
        this.host = host;
        this.showLabels = showLabels;
        this.hintWhenEmpty = hintWhenEmpty;
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(false);
        previous = new LinearLayout(c);
        previous.setGravity(Gravity.CENTER_VERTICAL);
        addView(previous, new LayoutParams(-2, -1));
        divider = new View(c);
        divider.setBackgroundColor(Style.SURFACE_RAISED);
        LayoutParams dp = new LayoutParams(dp(1), dp(32));
        dp.leftMargin = dp.rightMargin = dp(Style.GAP);
        addView(divider, dp);
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFocusable(false);
        row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        addView(scroll, new LayoutParams(0, -1, 1));
    }

    private int dp(float v) {
        return Style.dp(getContext(), v);
    }

    /**
     * @param previousKey 上一个应用，没有是 null
     * @param current 正在外接屏上的那一项，没有是 null
     */
    void setItems(List<String> keys, String previousKey, String current) {
        previous.removeAllViews();
        row.removeAllViews();
        View now = current != null ? item(current, true) : null;
        if (now != null) previous.addView(now);
        View prev = previousKey != null && !previousKey.equals(current) ? item(previousKey, false) : null;
        if (prev != null) previous.addView(prev);
        boolean hasLeft = now != null || prev != null;
        previous.setVisibility(hasLeft ? VISIBLE : GONE);
        divider.setVisibility(hasLeft ? VISIBLE : GONE);
        for (String key : keys) {
            View v = item(key, false);
            if (v != null) row.addView(v);
        }
        if (hintWhenEmpty && keys.isEmpty()) {
            row.addView(Style.label(getContext(), "点列表右边的星把常用的加到这里", Style.TEXT_DIM, 13));
        }
    }

    private View item(final String key, boolean current) {
        AppCatalog catalog = AppCatalog.get(getContext());
        boolean isApp = ChooserView.isAppKey(key);
        AppCatalog.App app = isApp ? catalog.find(ChooserView.componentOf(key)) : null;
        if (isApp && app == null) return null;  // 卸载了，或者列表还没读到
        View v = build(app, app != null ? app.label : ChooserView.sessionOf(key), current);
        v.setOnClickListener(view -> {
            Style.tick(view);
            host.onDockTap(key);
        });
        v.setOnLongClickListener(view -> {
            host.onItemLongPress(key);
            return true;
        });
        return v;
    }

    private View build(AppCatalog.App app, String label, boolean current) {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(false);
        box.setMinimumWidth(dp(Style.TARGET));
        box.setMinimumHeight(dp(Style.TARGET));
        box.setPadding(dp(4), dp(2), dp(4), dp(2));
        box.setBackground(Style.ripple(c, current ? Style.ACCENT : Style.BACKGROUND, Style.RADIUS));
        box.setContentDescription(label);
        int size = dp(36);
        if (app != null) {
            ImageView icon = new ImageView(c);
            AppCatalog.get(c).showIcon(app, icon);
            box.addView(icon, new LayoutParams(size, size));
        } else {
            TextView tile = new TextView(c);
            tile.setText(">_");
            tile.setTextColor(Style.TEXT);
            tile.setTextSize(14);
            tile.setTypeface(Typeface.MONOSPACE);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(Style.rounded(c, Style.SURFACE_RAISED, Style.RADIUS));
            box.addView(tile, new LayoutParams(size, size));
        }
        if (showLabels) {
            TextView t = Style.label(c, label, Style.TEXT, Style.TEXT_TINY);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            box.addView(t, new LayoutParams(dp(52), -2));
        }
        LayoutParams p = new LayoutParams(-2, -2);
        p.rightMargin = dp(4);
        box.setLayoutParams(p);
        return box;
    }
}
