package com.termux.x11;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.termux.x11.app.R;

/**
 * The switchboard's short strip on the phone's touchpad page (Switchboard.strip, as the external Dock): what is on the
 * external screen (highlighted) and the one before it, for going back and forth, then a line, then the favourites,
 * which scroll sideways. Icons only; the names are in the accessibility descriptions. Without the first two the line
 * goes too. A tap puts the item on the external screen, a long press opens its card. Never takes the keyboard focus.
 */
final class DockBar extends LinearLayout {
    interface Host {
        void onDockTap(String key);
        /** 长按一个图标，打开它的设置卡片。 */
        void onItemLongPress(String key);
    }

    private final Host host;
    private final boolean hintWhenEmpty;
    private final LinearLayout previous, row;
    private final View divider;

    DockBar(Context c, Host host, boolean hintWhenEmpty) {
        super(c);
        this.host = host;
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

    /** The strip as the model has it now. */
    void refresh() {
        previous.removeAllViews();
        row.removeAllViews();
        boolean hasLeft = false, any = false;
        for (Switchboard.Item it : Switchboard.get(getContext()).strip(false)) {
            View v = item(it);
            if (it.strip == Switchboard.STRIP_FAVOURITE) {
                row.addView(v);
                any = true;
            } else {
                previous.addView(v);
                hasLeft = true;
            }
        }
        previous.setVisibility(hasLeft ? VISIBLE : GONE);
        divider.setVisibility(hasLeft ? VISIBLE : GONE);
        if (hintWhenEmpty && !any) {
            row.addView(Style.label(getContext(), getContext().getString(R.string.xd_dock_empty), Style.TEXT_DIM, 13));
        }
    }

    private View item(final Switchboard.Item it) {
        final String key = it.key;
        boolean current = it.strip == Switchboard.STRIP_CURRENT;
        View v = build(it, current);
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

    private View build(Switchboard.Item it, boolean current) {
        Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(false);
        box.setMinimumWidth(dp(Style.TARGET));
        box.setMinimumHeight(dp(Style.TARGET));
        box.setPadding(dp(4), dp(2), dp(4), dp(2));
        // A device badge hangs a little past its icon's edge: don't cut it at the box's padding
        box.setClipToPadding(false);
        box.setBackground(Style.ripple(c, current ? Style.ACCENT : Style.BACKGROUND, Style.RADIUS));
        box.setContentDescription(it.title);
        int size = dp(36);
        ImageView icon = new ImageView(c);
        ExternalLauncher.icon(c, it, icon, Style.BACKGROUND, false);
        box.addView(icon, new LayoutParams(size, size));
        LayoutParams p = new LayoutParams(-2, -2);
        p.rightMargin = dp(4);
        box.setLayoutParams(p);
        return box;
    }
}
