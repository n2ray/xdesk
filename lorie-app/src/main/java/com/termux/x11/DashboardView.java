package com.termux.x11;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * What the external display shows when nothing else does: the clock and the state of XDesk. Used by DashboardActivity
 * and ExternalCover. Call start while it is on screen and stop when it is not.
 *
 * All sizes are parts of the window's size, not dp, so density changes and different displays need no special handling.
 * Add further content to column in the constructor.
 */
final class DashboardView extends FrameLayout {
    /** Text sizes as parts of the window's shorter side. */
    private static final float CLOCK = 0.2f, DATE = 0.05f, HINT = 0.034f, STATUS = 0.028f;
    /** Burn-in: the content moves this far (part of the shorter side) every SHIFT_MS, and dims after DIM_AFTER_MS. */
    private static final float SHIFT_RANGE = 0.04f, DIM_ALPHA = 0.35f;
    private static final long SHIFT_MS = 60000, DIM_AFTER_MS = 300000, STATUS_MS = 2000;
    /** With favourites the clock steps aside for them. Parts of the shorter side, or of the height for GRID_HEIGHT. */
    private static final float GRID_CLOCK = 0.09f, GRID_DATE = 0.03f, GRID_STATUS = 0.024f, CELL = 0.18f, ICON = 0.105f, TILE_TEXT = 0.024f, GRID_HEIGHT = 0.62f, GRID_WIDTH = 0.84f;
    private static final float TILE_HOVER = 1.07f;
    private static final int TILE_BACK = 0x22FFFFFF, MIN_COLUMNS = 3, MAX_COLUMNS = 9;
    private static final long TILE_ZOOM_MS = 110;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final LinearLayout column;
    private final TextView clock, date, hint, status;
    private long brightSince;
    private String lastInput;
    private int sizedW = -1, sizedH = -1;
    private final boolean showInput;
    private boolean started, gridShown;
    private final ScrollView grid;
    private final LinearLayout gridRows;
    private final Runnable favoritesChanged = this::buildGrid;

    private final BroadcastReceiver timeChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            updateStatus();
            ui.postDelayed(this, STATUS_MS);
        }
    };

    private final Runnable shift = new Runnable() {
        @Override public void run() {
            float range = Math.min(getWidth(), getHeight()) * SHIFT_RANGE;
            column.setTranslationX((random.nextFloat() * 2 - 1) * range);
            column.setTranslationY((random.nextFloat() * 2 - 1) * range);
            if (SystemClock.uptimeMillis() - brightSince >= DIM_AFTER_MS) column.setAlpha(DIM_ALPHA);
            ui.postDelayed(this, SHIFT_MS);
        }
    };

    DashboardView(Context c, String hintText, boolean showInput) {
        super(c);
        this.showInput = showInput;
        setBackgroundColor(Color.BLACK);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        clock = text(1f);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date = text(0.8f);
        hint = text(0.6f);
        hint.setText(hintText);
        status = text(0.5f);
        grid = new ScrollView(c);
        grid.setVerticalScrollBarEnabled(false);
        grid.setFocusable(false);
        grid.setVisibility(GONE);
        gridRows = new LinearLayout(c);
        gridRows.setOrientation(LinearLayout.VERTICAL);
        gridRows.setGravity(Gravity.CENTER_HORIZONTAL);
        grid.addView(gridRows, new ScrollView.LayoutParams(-1, -2));
        column.addView(grid, 2, new LinearLayout.LayoutParams(-2, -2));
        LayoutParams p = new LayoutParams(-2, -2);
        p.gravity = Gravity.CENTER;
        addView(column, p);
    }

    private TextView text(float alpha) {
        TextView t = new TextView(getContext());
        t.setTextColor(Color.WHITE);
        t.setAlpha(alpha);
        t.setGravity(Gravity.CENTER);
        column.addView(t, new LinearLayout.LayoutParams(-2, -2));
        return t;
    }

    // Before measuring the children: set during layout, the new sizes draw inside the old, smaller boxes until
    // something else asks for a layout
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        if (w != sizedW || h != sizedH) {
            sizedW = w;
            sizedH = h;
            float side = Math.min(w, h);
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * (gridShown ? GRID_CLOCK : CLOCK));
            date.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * (gridShown ? GRID_DATE : DATE));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * HINT);
            status.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * (gridShown ? GRID_STATUS : STATUS));
            hint.setPadding(0, Math.round(side * 0.06f), 0, 0);
            status.setPadding(0, Math.round(side * 0.04f), 0, 0);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // Not during layout: this adds and removes views
        post(favoritesChanged);
    }

    /** "My favourites" from the phone, as a grid. Only on XDesk's own home window, and edited only on the phone. */
    private void buildGrid() {
        if (!showInput || getWidth() == 0) return;
        Context c = getContext();
        ExternalLauncher.Host host = ExternalLauncher.host();
        List<String> keys = new ArrayList<>();
        if (host != null) for (String key : host.favorites()) if (ExternalLauncher.exists(c, key)) keys.add(key);
        boolean show = !keys.isEmpty();
        if (show != gridShown) {
            gridShown = show;
            sizedW = -1;
        }
        grid.setVisibility(show ? VISIBLE : GONE);
        hint.setVisibility(show ? GONE : VISIBLE);
        gridRows.removeAllViews();
        if (show) {
            float side = Math.min(getWidth(), getHeight());
            int cell = Math.round(side * CELL), columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, (int) (getWidth() * GRID_WIDTH / cell)));
            int across = Math.min(columns, keys.size());
            int rows = (keys.size() + columns - 1) / columns;
            LinearLayout row = null;
            for (int i = 0; i < keys.size(); i++) {
                if (i % columns == 0) {
                    row = new LinearLayout(c);
                    row.setClipChildren(false);
                    gridRows.addView(row, new LinearLayout.LayoutParams(-2, -2));
                }
                row.addView(tile(keys.get(i), Math.round(side * ICON), side), new LinearLayout.LayoutParams(cell, cell));
            }
            grid.setLayoutParams(new LinearLayout.LayoutParams(cell * across, Math.min(cell * rows, Math.round(getHeight() * GRID_HEIGHT))));
            ((LinearLayout.LayoutParams) grid.getLayoutParams()).topMargin = Math.round(side * 0.05f);
        }
        requestLayout();
    }

    private View tile(final String key, int iconSize, float side) {
        final Context c = getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(false);
        int pad = Math.round(side * 0.012f);
        box.setPadding(pad, pad, pad, pad);
        final GradientDrawable back = new GradientDrawable();
        back.setCornerRadius(side * 0.024f);
        back.setColor(Color.TRANSPARENT);
        box.setBackground(back);
        box.addView(ExternalLauncher.icon(c, key, iconSize), new LinearLayout.LayoutParams(iconSize, iconSize));
        TextView name = new TextView(c);
        name.setText(ExternalLauncher.label(c, key));
        name.setTextColor(Color.WHITE);
        name.setAlpha(0.85f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * TILE_TEXT);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2);
        at.topMargin = Math.round(side * 0.012f);
        box.addView(name, at);
        box.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) {
                brighten();
                back.setColor(TILE_BACK);
                v.animate().scaleX(TILE_HOVER).scaleY(TILE_HOVER).setDuration(TILE_ZOOM_MS).start();
            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {
                back.setColor(Color.TRANSPARENT);
                v.animate().scaleX(1f).scaleY(1f).setDuration(TILE_ZOOM_MS).start();
            }
            return false;
        });
        box.setOnClickListener(v -> {
            brighten();
            ExternalLauncher.open(c, key);
        });
        return box;
    }

    private void brighten() {
        brightSince = SystemClock.uptimeMillis();
        column.setAlpha(1f);
    }

    private void updateClock() {
        Context c = getContext();
        Date now = new Date();
        String clockPattern = DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm";
        clock.setText(DateFormat.format(clockPattern, now));
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMdEEEE"), now));
    }

    private void updateStatus() {
        Context c = getContext();
        StringBuilder s = new StringBuilder();
        Intent battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int state = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (level >= 0 && scale > 0) s.append(c.getString(R.string.xd_dash_battery, level * 100 / scale));
            if (state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL) s.append(' ').append(c.getString(R.string.xd_dash_charging));
            s.append("   ·   ");
        }
        s.append(c.getString(R.string.xd_dash_sound, AudioRoute.get(c).shortLabel()));
        if (!showInput) {
            status.setText(s);
            return;
        }
        s.append("   ·   ");
        AppSession apps = AppSession.get(c);
        String input = c.getString(apps.isForwarding() ? R.string.xd_dash_input_display
            : apps.hasInjector() ? R.string.xd_dash_input_phone_hotkey : R.string.xd_dash_input_phone);
        s.append(input);
        status.setText(s);
        if (!input.equals(lastInput)) {
            lastInput = input;
            brighten();
        }
    }

    void start() {
        if (started) return;
        started = true;
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(timeChanged, time);
        updateClock();
        brighten();
        ui.post(statusTick);
        ui.postDelayed(shift, SHIFT_MS);
        ExternalLauncher.listen(favoritesChanged);
        buildGrid();
    }

    /** Covered by an app or a desktop, or removed: nothing to update. */
    void stop() {
        if (!started) return;
        started = false;
        getContext().unregisterReceiver(timeChanged);
        ui.removeCallbacksAndMessages(null);
        ExternalLauncher.unlisten(favoritesChanged);
    }
}
