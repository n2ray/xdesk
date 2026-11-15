package com.termux.x11;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.x11.app.R;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * NOTIFY.md §4: the notification centre, a full-height panel sliding in from the external screen's right edge, over
 * a light scrim that closes it when clicked. The header: the time, the date with the weekday, the phone's battery
 * (from ACTION_BATTERY_CHANGED, a bolt while charging), Do not disturb (the phone's interruption filter; Notify
 * guides to the policy access once) and clear all. The list: the phone's notifications grouped by app, the newest
 * first; an entry opens (NOTIFY.md §5), its × clears it (and it goes on the phone), a drag or a swipe to the right
 * clears it, the chevron expands the full text and the picture, the actions and the quick reply are in there.
 *
 * The keyboard works it while it is open (the keys come from Notify, the window never takes the focus): up and down
 * select, Enter opens, Delete clears, R replies, Esc closes. Over Android apps Android's own pointer works it (its
 * leaving closes it after 1 second); over X and a Mac Notify's driven pointer does, clicks landing where the views
 * say. Sizes from Sizing.shell.
 */
final class NotifyPanel {
    static final float WIDTH_DP = 400;
    static final long LEAVE_MS = 1000;
    /** The scrim: barely there, so what is behind stays readable, but it takes the clicks that close the panel. */
    private static final int SCRIM = 0x2A0B0D10;
    private static final int PANEL = 0xF61B1E24, ROW = 0x14FFFFFF, SELECT = 0x223D7EF5, EDGE = 0x33FFFFFF;
    private static final long CLOCK_MS = 60000;
    private static final float SWIPE_DP = 60;

    interface Host {
        /** A notification was chosen: open it (NOTIFY.md §5) and the panel closes. */
        void open(NotifyCenter.Entry e);
        /** An action of an entry (its button, or the reply being sent). */
        void action(NotifyCenter.Entry e, Notification.Action a, String replyText);
        /** Clear one: it also goes on the phone. */
        void clear(NotifyCenter.Entry e);
        void clearAll();
        /** The DND switch was flipped: set the phone's interruption filter (guide once if the policy access is missing). */
        void dnd(boolean on);
        /** The pointer's hover: it keeps the panel open while inside. */
        void hover(boolean inside);
        /** The pointer's place for the bell's fade and the reply field's keys. */
        void replyFocus(boolean on);
        void close();
    }

    private final Host host;
    private final Context dc;
    private final float density;
    private final WindowManager windows;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final NotifyCenter center;

    private final View scrim;
    private final WindowManager.LayoutParams scrimParams;
    private final LinearLayout root;
    private final WindowManager.LayoutParams params;
    private final TextView time, date, dnd, clearAll;
    private final View batteryView;
    private final TextView batteryText;
    private final LinearLayout list;
    private final ScrollView scroll;
    private final LinearLayout replyStrip;
    private final EditText replyField;

    private final List<Row> rows = new ArrayList<>();
    private final Rect rect = new Rect();
    private Row selected;
    private NotifyCenter.Entry replyEntry;
    private Notification.Action replyAction;
    private boolean closing;
    private float downX;
    private final Runnable clock = this::clock;
    private final Runnable onLeave = this::onLeave;

    NotifyPanel(Context dc, float density, Host host, WindowManager windows) {
        this.dc = dc;
        this.density = density;
        this.host = host;
        this.windows = windows;
        center = NotifyCenter.get(dc);

        scrim = new View(dc);
        scrim.setBackgroundColor(SCRIM);
        scrim.setOnClickListener(v -> host.close());
        scrim.setOnHoverListener((v, e) -> {
            // On the scrim the pointer is outside the panel: that is the leaving that closes it in a second
            ui.removeCallbacks(onLeave);
            ui.postDelayed(onLeave, LEAVE_MS);
            return false;
        });
        scrimParams = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        scrimParams.gravity = Gravity.TOP | Gravity.START;
        scrimParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        scrimParams.setFitInsetsTypes(0);
        scrimParams.windowAnimations = 0;
        scrimParams.setTitle("XDesk notification scrim");

        root = new LinearLayout(dc);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(PANEL);
        surface.setStroke(Math.max(1, dp(0.5f)), EDGE);
        root.setBackground(surface);
        root.setPadding(dp(16), dp(14), dp(16), dp(10));
        root.setOnHoverListener((v, e) -> {
            // On the panel itself the leaving is not leaving: the close timer stops
            if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                ui.removeCallbacks(onLeave);
                ui.postDelayed(onLeave, LEAVE_MS);
            } else {
                ui.removeCallbacks(onLeave);
                host.hover(true);
            }
            return false;
        });

        // The header: the clock and the battery first, the DND switch and clear all under it
        time = label(Style.TEXT, 30, true);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(time, new LinearLayout.LayoutParams(-1, -2));
        date = label(Style.TEXT_DIM, 13, true);
        root.addView(date, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout battery = new LinearLayout(dc);
        battery.setGravity(Gravity.CENTER_VERTICAL);
        battery.setPadding(0, dp(10), 0, dp(4));
        batteryView = new BatteryView(dc, density);
        battery.addView(batteryView, new LinearLayout.LayoutParams(dp(26), dp(14)));
        batteryText = label(Style.TEXT, 14, true);
        batteryText.setPadding(dp(8), 0, 0, 0);
        battery.addView(batteryText);
        root.addView(battery, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tools = new LinearLayout(dc);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        dnd = pill(dc.getString(R.string.xd_notify_dnd), false);
        // Toggle the phone's Do not disturb: on when the filter is NONE now
        dnd.setOnClickListener(v -> host.dnd(center.interruptionFilter() != NotificationManager.INTERRUPTION_FILTER_NONE));
        tools.addView(dnd);
        clearAll = pill(dc.getString(R.string.xd_notify_clear_all), false);
        clearAll.setOnClickListener(v -> host.clearAll());
        tools.addView(clearAll);
        View close = pill("×", false);
        close.setOnClickListener(v -> host.close());
        tools.addView(close);
        LinearLayout.LayoutParams toolsAt = new LinearLayout.LayoutParams(-1, -2);
        toolsAt.topMargin = dp(8);
        root.addView(tools, toolsAt);

        scroll = new ScrollView(dc);
        scroll.setFillViewport(true);
        list = new LinearLayout(dc);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        replyStrip = new LinearLayout(dc);
        replyStrip.setGravity(Gravity.CENTER_VERTICAL);
        replyStrip.setVisibility(View.GONE);
        replyStrip.setPadding(0, dp(8), 0, 0);
        replyField = new EditText(dc);
        replyField.setSingleLine(true);
        replyField.setTextColor(Style.TEXT);
        replyField.setHintTextColor(Style.TEXT_DIM);
        replyField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(13 * density));
        replyField.setFocusable(false);
        replyField.setClickable(false);
        replyField.setHint(R.string.xd_notify_reply_hint);
        GradientDrawable field = new GradientDrawable();
        field.setColor(0x22FFFFFF);
        field.setCornerRadius(dp(8));
        replyField.setBackground(field);
        replyStrip.addView(replyField, new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView send = pill(dc.getString(R.string.xd_notify_send), false);
        send.setOnClickListener(v -> sendReply());
        replyStrip.addView(send);
        root.addView(replyStrip, new LinearLayout.LayoutParams(-1, -2));

        params = new WindowManager.LayoutParams(dp(WIDTH_DP), -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.windowAnimations = 0;
        params.setTitle("XDesk notification centre");
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private TextView label(int color, float sp, boolean single) {
        TextView t = new TextView(dc);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(sp * density));
        t.setSingleLine(single);
        return t;
    }

    private TextView pill(String word, boolean on) {
        TextView b = new TextView(dc);
        b.setText(word);
        b.setTextColor(Style.TEXT);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(12 * density));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable back = new GradientDrawable();
        back.setColor(on ? Style.ACCENT : 0x1AFFFFFF);
        back.setCornerRadius(dp(9));
        b.setBackground(back);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2);
        at.rightMargin = dp(6);
        b.setLayoutParams(at);
        return b;
    }

    private String dndLabel(boolean on) {
        return on ? dc.getString(R.string.xd_notify_dnd_on) : dc.getString(R.string.xd_notify_dnd);
    }

    View scrim() {
        return scrim;
    }

    WindowManager.LayoutParams scrimParams() {
        return scrimParams;
    }

    View view() {
        return root;
    }

    WindowManager.LayoutParams params() {
        return params;
    }

    boolean closing() {
        return closing;
    }

    // --- open, refresh, close ---

    void open() {
        closing = false;
        try {
            windows.addView(scrim, scrimParams);
            windows.addView(root, params);
        } catch (RuntimeException e) {
            try {
                windows.removeViewImmediate(scrim);
            } catch (RuntimeException ignored) { }
            android.util.Log.w("XDeskNotify", "no notification panel", e);
            closing = true;
            return;
        }
        fill();
        refreshHeader();
        clock();
        slideIn();
        center.markSeen();
        host.hover(true);
    }

    /** The model changed (or the panel just opened): the list follows it. */
    void fill() {
        rows.clear();
        list.removeAllViews();
        int groupAt = 0;
        for (NotifyCenter.Group g : center.groups()) {
            TextView head = label(Style.TEXT_DIM, 12, true);
            head.setText(g.appLabel);
            head.setPadding(dp(4), dp(12), dp(4), dp(4));
            list.addView(head, new LinearLayout.LayoutParams(-1, -2));
            for (NotifyCenter.Entry e : g.entries) {
                Row row = new Row(e, groupAt);
                rows.add(row);
                list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            groupAt++;
        }
        if (rows.isEmpty()) {
            TextView empty = label(Style.TEXT_DIM, 13, false);
            empty.setText(R.string.xd_notify_empty);
            empty.setPadding(dp(4), dp(20), dp(4), dp(4));
            list.addView(empty);
        }
    }

    /** The clock and the battery (NOTIFY.md §4's header), again every minute while the panel is open. */
    void refreshHeader() {
        java.util.Date now = new java.util.Date();
        time.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(now));
        date.setText(DateFormat.getDateInstance(DateFormat.FULL).format(now));
        int level = center.batteryLevel();
        int filter = center.interruptionFilter();
        boolean on = filter == NotificationManager.INTERRUPTION_FILTER_NONE;
        dnd.setText(dndLabel(on));
        dnd.setAlpha(on ? 1f : 0.75f);
        if (level >= 0) {
            batteryText.setText((center.batteryCharging() ? "⚡" : "") + level + "%");
            batteryView.setVisibility(View.VISIBLE);
            batteryText.setVisibility(View.VISIBLE);
            ((BatteryView) batteryView).setLevel(level, center.batteryCharging());
        } else {
            batteryView.setVisibility(View.GONE);
            batteryText.setVisibility(View.GONE);
        }
    }

    private void clock() {
        if (closing) return;
        refreshHeader();
        ui.removeCallbacks(clock);
        ui.postDelayed(clock, CLOCK_MS);
    }

    void slideIn() {
        root.setTranslationX(dp(WIDTH_DP));
        root.setAlpha(0f);
        root.animate().translationX(0f).alpha(1f).setDuration(180).start();
    }

    void slideOut(Runnable done) {
        if (closing) {
            if (done != null) done.run();
            return;
        }
        closing = true;
        ui.removeCallbacks(clock);
        ui.removeCallbacks(onLeave);
        host.replyFocus(false);
        root.animate().translationX(dp(WIDTH_DP)).alpha(0f).setDuration(180).withEndAction(() -> {
            try {
                windows.removeViewImmediate(root);
                windows.removeViewImmediate(scrim);
            } catch (RuntimeException ignored) { }
            if (done != null) done.run();
        }).start();
    }

    private void onLeave() {
        host.close();
    }

    /** The panel's place on the screen, in pixels. */
    void rect(Rect out) {
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        out.set(at[0], at[1], at[0] + root.getWidth(), at[1] + root.getHeight());
    }

    boolean holdsPanel(int x, int y) {
        rect(rect);
        return x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom;
    }

    // --- the driven pointer (over X and a Mac) ---

    /** A click of XDesk's own pointer: on the view that is there, as the Android pointer's click would be. */
    void clickAt(int x, int y) {
        clickWalk(root, x, y);
    }

    /** The secondary button: on a row it clears that notification, beside them nothing. */
    void secondaryAt(int x, int y) {
        Row row = rowAt(x, y);
        if (row != null) host.clear(row.entry);
    }

    private Row rowAt(int x, int y) {
        int[] at = new int[2];
        for (Row r : rows) {
            r.getLocationOnScreen(at);
            if (x >= at[0] && x < at[0] + r.getWidth() && y >= at[1] && y < at[1] + r.getHeight()) return r;
        }
        return null;
    }

    /** The bottommost clickable view that holds this place: a row's button before the row's body. */
    private static boolean clickWalk(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE) return false;
        if (v instanceof android.view.ViewGroup) {
            for (int i = ((android.view.ViewGroup) v).getChildCount() - 1; i >= 0; i--) {
                if (clickWalk(((android.view.ViewGroup) v).getChildAt(i), x, y)) return true;
            }
        }
        if (!v.isClickable()) return false;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        if (x >= at[0] && x < at[0] + v.getWidth() && y >= at[1] && y < at[1] + v.getHeight()) {
            v.performClick();
            return true;
        }
        return false;
    }

    /** The wheel in whole steps, however the device delivers them (the phone's pad, a mouse, a receiver). */
    void wheel(float steps) {
        scroll.smoothScrollBy(0, -Math.round(steps * dp(40)));
    }

    // --- the keyboard (NOTIFY.md §4: up/down select, Enter opens, Delete clears, R replies) ---

    void select(int by) {
        int at = rows.indexOf(selected);
        at = at < 0 ? (by > 0 ? -1 : 0) : at;
        at = Math.max(0, Math.min(rows.size() - 1, at + by));
        if (rows.isEmpty()) return;
        setSelected(rows.get(at));
        // Keep the selected row in view
        int[] place = new int[2];
        selected.getLocationOnScreen(place);
        scroll.smoothScrollBy(0, place[1] - dp(120));
    }

    void openSelected() {
        if (selected != null) host.open(selected.entry);
    }

    void clearSelected() {
        if (selected != null) host.clear(selected.entry);
    }

    void replySelected() {
        if (selected != null) selected.openReply();
    }

    private void setSelected(Row row) {
        if (selected == row) return;
        if (selected != null) selected.mark(false);
        selected = row;
        if (selected != null) selected.mark(true);
    }

    // --- the quick reply (NOTIFY.md §4) ---

    boolean replyOpen() {
        return replyStrip.getVisibility() == View.VISIBLE;
    }

    void beginReply(NotifyCenter.Entry e, Notification.Action a) {
        replyEntry = e;
        replyAction = a;
        replyField.setText("");
        replyStrip.setVisibility(View.VISIBLE);
        host.replyFocus(true);
    }

    void cancelReply() {
        if (!replyOpen()) return;
        replyEntry = null;
        replyAction = null;
        replyStrip.setVisibility(View.GONE);
        host.replyFocus(false);
    }

    void type(char c) {
        if (!replyOpen()) return;
        String now = replyField.getText().toString();
        replyField.setText(now + c);
        replyField.setSelection(replyField.getText().length());
    }

    void backspace() {
        if (!replyOpen()) return;
        String now = replyField.getText().toString();
        if (!now.isEmpty()) {
            replyField.setText(now.substring(0, now.length() - 1));
            replyField.setSelection(replyField.getText().length());
        }
    }

    void sendReply() {
        if (!replyOpen() || replyEntry == null || replyAction == null) return;
        String textValue = replyField.getText().toString().trim();
        if (textValue.isEmpty()) {
            cancelReply();  // an empty reply is not sent
            return;
        }
        host.action(replyEntry, replyAction, textValue);
        cancelReply();
    }

    // --- one entry ---

    private final class Row extends LinearLayout {
        final NotifyCenter.Entry entry;
        final View body;
        private final View expanded;

        Row(NotifyCenter.Entry e, int groupAt) {
            super(dc);
            this.entry = e;
            setOrientation(VERTICAL);
            setPadding(dp(8), dp(6), dp(8), dp(6));

            body = new LinearLayout(dc);
            ((LinearLayout) body).setOrientation(HORIZONTAL);
            ((LinearLayout) body).setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(dc);
            Drawable d = center.icon(e.pkg);
            icon.setImageDrawable(d);
            int iconSize = dp(30);
            ((LinearLayout) body).addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            LinearLayout mid = new LinearLayout(dc);
            mid.setOrientation(VERTICAL);
            mid.setPadding(dp(8), 0, dp(8), 0);
            TextView t = label(Style.TEXT, 13, false);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setText(e.title.isEmpty() ? e.appLabel : e.title);
            mid.addView(t, new LinearLayout.LayoutParams(-1, -2));
            TextView x = label(Style.TEXT_DIM, 11, false);
            x.setMaxLines(2);
            x.setEllipsize(TextUtils.TruncateAt.END);
            x.setText(e.text);
            if (e.text.isEmpty()) x.setVisibility(View.GONE);
            mid.addView(x, new LinearLayout.LayoutParams(-1, -2));
            ((LinearLayout) body).addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
            TextView when = label(Style.TEXT_DIM, 11, true);
            when.setText(android.text.format.DateFormat.format("HH:mm", e.when));
            ((LinearLayout) body).addView(when);
            View clear = smallButton("×", () -> host.clear(entry));
            ((LinearLayout) body).addView(clear);
            View more = smallButton("▾", () -> toggle());
            ((LinearLayout) body).addView(more);
            body.setClickable(true);
            body.setOnClickListener(v -> host.open(entry));
            body.setOnTouchListener((v, ev) -> swipe(ev));
            addView(body, new LinearLayout.LayoutParams(-1, -2));

            expanded = expanded(entry);
            expanded.setVisibility(GONE);
            addView(expanded, new LinearLayout.LayoutParams(-1, -2));
        }

        /** A drag or a swipe to the right clears the notification (NOTIFY.md §4). */
        private boolean swipe(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (ev.getX() - downX > dp(SWIPE_DP)) {
                        host.clear(entry);
                        return true;
                    }
                    return false;
            }
            return false;
        }

        void toggle() {
            expanded.setVisibility(expanded.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        }

        void openReply() {
            Notification.Action reply = replyOf(entry);
            if (reply != null) beginReply(entry, reply);
        }

        void mark(boolean on) {
            GradientDrawable back = new GradientDrawable();
            back.setColor(on ? SELECT : Color.TRANSPARENT);
            back.setCornerRadius(dp(10));
            setBackground(back);
        }

        /** The expanded card: the full text, the picture, the actions and the reply. */
        private View expanded(NotifyCenter.Entry e) {
            LinearLayout box = new LinearLayout(dc);
            box.setOrientation(VERTICAL);
            box.setPadding(dp(8), dp(4), dp(8), dp(4));
            android.os.Bundle ex = e.sbn.getNotification().extras;
            CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            String full = big != null ? big.toString() : e.text;
            if (!full.isEmpty()) {
                TextView t = label(Style.TEXT_DIM, 12, false);
                t.setText(full);
                box.addView(t, new LinearLayout.LayoutParams(-1, -2));
            }
            Bitmap picture = ex.getParcelable(Notification.EXTRA_PICTURE);
            if (picture != null) {
                ImageView im = new ImageView(dc);
                int w = dp(WIDTH_DP - 60);
                int h = Math.max(1, Math.round(w * (float) picture.getHeight() / Math.max(1, picture.getWidth())));
                im.setImageBitmap(Bitmap.createScaledBitmap(picture, w, Math.min(h, dp(200)), true));
                im.setPadding(0, dp(6), 0, 0);
                box.addView(im, new LinearLayout.LayoutParams(w, -2));
            }
            Notification.Action[] acts = e.sbn.getNotification().actions;
            if (acts != null) {
                LinearLayout row = new LinearLayout(dc);
                row.setGravity(Gravity.END);
                for (Notification.Action a : acts) {
                    if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) {
                        TextView r = smallButton(dc.getString(R.string.xd_notify_reply), () -> beginReply(e, a));
                        row.addView(r);
                    } else {
                        TextView b = smallButton(String.valueOf(a.title), () -> host.action(e, a, null));
                        row.addView(b);
                    }
                }
                box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            return box;
        }
    }

    private static Notification.Action replyOf(NotifyCenter.Entry e) {
        Notification.Action[] acts = e.sbn.getNotification().actions;
        if (acts == null) return null;
        for (Notification.Action a : acts) if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) return a;
        return null;
    }

    private TextView smallButton(String word, Runnable onTap) {
        TextView b = new TextView(dc);
        b.setText(word);
        b.setTextColor(Style.TEXT);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(12 * density));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(7), dp(2), dp(7), dp(2));
        GradientDrawable back = new GradientDrawable();
        back.setColor(0x1AFFFFFF);
        back.setCornerRadius(dp(8));
        b.setBackground(back);
        b.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2);
        at.leftMargin = dp(6);
        b.setLayoutParams(at);
        return b;
    }

    /** The battery's small icon: the outline, its fill, a bolt while charging. Drawn, so it follows b·u. */
    private static final class BatteryView extends View {
        private final float density;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private int level;
        private boolean charging;

        BatteryView(Context dc, float density) {
            super(dc);
            this.density = density;
            body.setColor(Style.TEXT_DIM);
            body.setStyle(Paint.Style.STROKE);
            body.setStrokeWidth(Math.max(1f, density));
            fill.setColor(Style.TEXT);
            bolt.setColor(Style.ACCENT);
            bolt.setStyle(Paint.Style.FILL);
        }

        void setLevel(int level, boolean charging) {
            this.level = level;
            this.charging = charging;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float nub = w * 0.06f;
            box.set(nub, h * 0.22f, w - nub * 2, h * 0.78f);
            c.drawRoundRect(box, h * 0.18f, h * 0.18f, body);
            c.drawRoundRect(new RectF(w - nub * 1.6f, h * 0.38f, w - nub * 0.6f, h * 0.62f), h * 0.08f, h * 0.08f, body);
            float inset = h * 0.06f + Math.max(1f, density);
            float full = box.width() - inset * 2;
            c.drawRoundRect(new RectF(box.left + inset, box.top + inset, box.left + inset + full * level / 100f,
                box.bottom - inset), h * 0.1f, h * 0.1f, fill);
            if (charging) {
                // A bolt: the two halves of a lightning strike
                float cx = w / 2, cy = h / 2, s = h * 0.3f;
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(cx + s * 0.15f, cy - s);
                p.lineTo(cx - s * 0.55f, cy + s * 0.15f);
                p.lineTo(cx - s * 0.05f, cy + s * 0.15f);
                p.lineTo(cx - s * 0.2f, cy + s);
                p.lineTo(cx + s * 0.5f, cy - s * 0.2f);
                p.lineTo(cx + s * 0.05f, cy - s * 0.2f);
                p.close();
                c.drawPath(p, bolt);
            }
        }
    }
}
