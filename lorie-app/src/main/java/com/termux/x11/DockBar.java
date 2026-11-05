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

/** 用户自己定的一排应用和 Termux 环境。点一下切换外接屏上的内容，长按打开设置卡片。不抢键盘焦点。 */
final class DockBar extends HorizontalScrollView {
    interface Host {
        void onDockTap(String key);
        /** 长按一个图标，打开它的设置卡片。 */
        void onItemLongPress(String key);
        /** 点了“全部”。 */
        void onDockAll();
    }

    private final Host host;
    private final boolean showLabels, showAll;
    private final LinearLayout row;

    DockBar(Context c, Host host, boolean showLabels, boolean showAll) {
        super(c);
        this.host = host;
        this.showLabels = showLabels;
        this.showAll = showAll;
        setHorizontalScrollBarEnabled(false);
        setFocusable(false);
        row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(-2, -1));
    }

    private int dp(float v) {
        return Style.dp(getContext(), v);
    }

    /** @param current 正在外接屏上的那一项，会高亮 */
    void setItems(List<String> keys, String current) {
        row.removeAllViews();
        Context c = getContext();
        if (showAll) {
            View all = item(null, "⋯", "全部", false);
            all.setOnClickListener(v -> {
                Style.tick(v);
                host.onDockAll();
            });
            row.addView(all);
        }
        AppCatalog catalog = AppCatalog.get(c);
        for (final String key : keys) {
            AppCatalog.App app = ChooserView.isAppKey(key) ? catalog.find(ChooserView.componentOf(key)) : null;
            if (ChooserView.isAppKey(key) && app == null) continue;  // 卸载了，或者列表还没读到
            View v = app != null ? item(app, null, app.label, key.equals(current))
                : item(null, ">_", ChooserView.sessionOf(key), key.equals(current));
            v.setOnClickListener(view -> {
                Style.tick(view);
                host.onDockTap(key);
            });
            v.setOnLongClickListener(view -> {
                host.onItemLongPress(key);
                return true;
            });
            row.addView(v);
        }
        if (keys.isEmpty() && !showAll) {
            TextView hint = new TextView(c);
            hint.setText("点列表右边的 ☆ 把常用的加到这里");
            hint.setTextColor(Style.TEXT_DIM);
            hint.setTextSize(13);
            row.addView(hint);
        }
    }

    private View item(AppCatalog.App app, String glyph, String label, boolean current) {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(false);
        box.setPadding(dp(4), dp(2), dp(4), dp(2));
        box.setBackground(Style.ripple(c, current ? Style.ACCENT : Style.BACKGROUND, 10));
        box.setContentDescription(label);
        int size = dp(36);
        if (app != null) {
            ImageView icon = new ImageView(c);
            AppCatalog.get(c).showIcon(app, icon);
            box.addView(icon, new LinearLayout.LayoutParams(size, size));
        } else {
            TextView tile = new TextView(c);
            tile.setText(glyph);
            tile.setTextColor(Style.TEXT);
            tile.setTextSize(14);
            tile.setTypeface(Typeface.MONOSPACE);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(Style.rounded(c, Style.SURFACE_RAISED, 10));
            box.addView(tile, new LinearLayout.LayoutParams(size, size));
        }
        if (showLabels) {
            TextView t = new TextView(c);
            t.setText(label);
            t.setTextColor(Style.TEXT);
            t.setTextSize(10);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            box.addView(t, new LinearLayout.LayoutParams(dp(52), -2));
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = dp(4);
        box.setLayoutParams(p);
        return box;
    }
}
