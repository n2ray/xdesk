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
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * What is at the bottom of the external display: the clock, the date and the state of XDesk. Used by DashboardActivity
 * and ExternalCover. With nothing else on the screen the switchboard (SwitcherOverlay's home) lies over it, so this is
 * seen while that is not there: the moment before it comes, on the car's cover, and without the permission to draw
 * over other apps, when the hint says so. It has nothing to open things with: that is the switchboard's. Call start
 * while it is on screen and stop when it is not.
 *
 * All sizes are dp from the screen's shell factor (Sizing.shell, §4.1: b·u), so the display's Android density and
 * the system's font scale do not size it. The dp values are the old parts of the window's size on a 1080 screen at
 * b·u = 1. Add further content to column in the constructor.
 */
final class DashboardView extends FrameLayout {
    /** Text sizes in dp. */
    private static final float CLOCK = 216, DATE = 54, HINT = 36, STATUS = 30;
    /** Space above the hint and the status lines, dp. */
    private static final float HINT_PAD = 65, STATUS_PAD = 43;
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
    private float sizedDensity;
    private final boolean showInput;
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
        // §4.1: the shell's factor (b·u), the same the Launchpad and the Dock use; the cache makes this cheap, and
        // checking it here sizes the dashboard again when the screen's kind or u changes while it shows
        float density = Sizing.shell(getContext(), getContext().getDisplay()).density;
        if (w != sizedW || h != sizedH || density != sizedDensity) {
            sizedW = w;
            sizedH = h;
            sizedDensity = density;
            // Pixels from the shell's factor: px text sizes, so neither the density override nor the font scale gets in
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round(CLOCK * density));
            date.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round(DATE * density));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round(HINT * density));
            status.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round(STATUS * density));
            hint.setPadding(0, Math.round(HINT_PAD * density), 0, 0);
            status.setPadding(0, Math.round(STATUS_PAD * density), 0, 0);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    /** What to do here, under the clock: it changes with what XDesk may do on this screen (DashboardActivity). */
    void setHint(String text) {
        hint.setText(text);
    }

    private void brighten() {
        brightSince = SystemClock.uptimeMillis();
        column.setAlpha(1f);
    }

    private void updateClock() {
        Context c = getContext();
        Date now = new Date();
        clock.setText(time(c, now));
        date.setText(day(now));
    }

    private static CharSequence time(Context c, Date now) {
        return DateFormat.format(DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm", now);
    }

    private static CharSequence day(Date now) {
        return DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMdEEEE"), now);
    }

    /** The time and the date in one line, for the switchboard's home, which covers this clock. */
    static String clockLine(Context c) {
        Date now = new Date();
        return time(c, now) + "   ·   " + day(now);
    }

    private void updateStatus() {
        Context c = getContext();
        SpannableStringBuilder s = new SpannableStringBuilder();
        Intent battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int state = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL;
            if (level >= 0 && scale > 0) {
                // NOTIFY.md: on the car the low-battery warning lives here — the battery turns amber at 15%
                int at = s.length();
                s.append(c.getString(R.string.xd_dash_battery, level * 100 / scale));
                if (!charging && level * 100 / scale <= 15)
                    s.setSpan(new ForegroundColorSpan(Style.WARN), at, s.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (charging) s.append(' ').append(c.getString(R.string.xd_dash_charging));
            s.append("   ·   ");
        }
        s.append(c.getString(R.string.xd_dash_sound, AudioRoute.get(c).shortLabel()));
        if (!showInput) {
            status.setText(s);
            return;
        }
        s.append("   ·   ");
        AppSession apps = AppSession.get(c);
        // The home (the Launchpad over this) has the mouse and the keyboard, captured by the phone for this screen
        String input = c.getString(SwitcherOverlay.isHome() ? R.string.xd_dash_input_home
            : apps.isForwarding() ? R.string.xd_dash_input_display
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
    }

    /** Covered by an app or a desktop, or removed: nothing to update. */
    void stop() {
        if (!started) return;
        started = false;
        getContext().unregisterReceiver(timeChanged);
        ui.removeCallbacksAndMessages(null);
    }
}
