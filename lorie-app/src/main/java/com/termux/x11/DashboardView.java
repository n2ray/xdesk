package com.termux.x11;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Date;
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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final LinearLayout column;
    private final TextView clock, date, hint, status;
    private long brightSince;
    private String lastInput;
    private int sizedW = -1, sizedH = -1;
    private boolean started;

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

    DashboardView(Context c, String hintText) {
        super(c);
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
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * CLOCK);
            date.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * DATE);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * HINT);
            status.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * STATUS);
            hint.setPadding(0, Math.round(side * 0.06f), 0, 0);
            status.setPadding(0, Math.round(side * 0.04f), 0, 0);
        }
        super.onMeasure(widthSpec, heightSpec);
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
            if (level >= 0 && scale > 0) s.append("电量 ").append(level * 100 / scale).append('%');
            if (state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL) s.append(" 充电中");
            s.append("   ·   ");
        }
        s.append("声音：").append(AudioRoute.get(c).shortLabel()).append("   ·   ");
        AppSession apps = AppSession.get(c);
        String input = apps.isForwarding() ? "键鼠在外接屏，Ctrl+Alt+M 交还手机"
            : apps.hasInjector() ? "键鼠在手机，Ctrl+Alt+M 接管" : "键鼠在手机";
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
    }

    /** Covered by an app or a desktop, or removed: nothing to update. */
    void stop() {
        if (!started) return;
        started = false;
        getContext().unregisterReceiver(timeChanged);
        ui.removeCallbacksAndMessages(null);
    }
}
