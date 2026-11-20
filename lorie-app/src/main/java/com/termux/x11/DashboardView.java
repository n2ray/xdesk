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
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * What is at the bottom of the external display: the clock, the date and the state of XDesk. Used by DashboardActivity
 * (the dashboard behind everything), ExternalCover (the privacy cover) and CarActivity (Android Auto, ExtCar.dc.html).
 *
 * With nothing else on the screen the switchboard (SwitcherOverlay's home) lies over it, so the dashboard is seen while
 * that is not there: the moment before it comes, on the car's cover, and without the permission to draw over other apps,
 * when the hint says so. It has nothing to open things with: that is the switchboard's. Call start while it is on
 * screen and stop when it is not.
 *
 * All sizes are dp from the screen's shell factor (Sizing.shell, §4.1: b·u), so the display's Android density and
 * the system's font scale do not size it. The dp values are the old parts of the window's size on a 1080 screen at
 * b·u = 1: the mockups' 720-px pieces scaled by the same parts. Add further content to column in the constructor.
 */
final class DashboardView extends FrameLayout {
    /** What this dashboard is under: the launcher's screen, the privacy cover, or the car (ExtCover, ExtCar). */
    static final int DASHBOARD = 0, COVER = 1, CAR = 2;
    /** Text sizes in dp. */
    private static final float CLOCK = 216, DATE = 54, HINT = 36, STATUS = 30;
    /** The cover's parts (ExtCover.dc.html): a 150 px clock, 24 px date, 18 px hint and 14 px state on 720 px. */
    private static final float COVER_CLOCK = 150, COVER_DATE = 24, COVER_HINT = 18, COVER_STATUS = 14, COVER_NOTE = 12;
    /** The car's (ExtCar.dc.html at 480 px high): a 120 px clock, 24 px date, 18 px states. */
    private static final float CAR_CLOCK = 120, CAR_DATE = 24, CAR_STATUS = 18;
    /** Space above the hint and the status lines, dp. */
    private static final float HINT_PAD = 65, STATUS_PAD = 43, COVER_HINT_PAD = 22, COVER_STATUS_PAD = 14;
    /** Burn-in: the content moves this far (part of the shorter side) every SHIFT_MS, and dims after DIM_AFTER_MS. */
    private static final float SHIFT_RANGE = 0.04f, DIM_ALPHA = 0.35f;
    private static final long SHIFT_MS = 60000, DIM_AFTER_MS = 300000, STATUS_MS = 2000;
    /** The cover's own black (xd.css' #050608 under a lock screen), and its bottom line's faint grey. */
    private static final int COVER_BACK = 0xFF050608, NOTE_COLOR = 0xFF3F4650;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final int kind;
    private final LinearLayout column;
    private TextView clock, date, hint, status, note;
    /** The car's four lines: the car's state, the battery, the sound and where the keys and mouse are. */
    private TextView carBattery, carSound, carInput;
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
        this(c, hintText, showInput, DASHBOARD);
    }

    DashboardView(Context c, String hintText, boolean showInput, int kind) {
        super(c);
        this.showInput = showInput;
        this.kind = kind;
        setBackgroundColor(kind == COVER ? COVER_BACK : Color.BLACK);
        column = new LinearLayout(c);
        column.setGravity(Gravity.CENTER);
        LayoutParams at = new LayoutParams(-2, -2);
        at.gravity = Gravity.CENTER;
        if (kind == CAR) {
            buildCar(c);
            hint = new TextView(c);
            status = new TextView(c);
            note = null;
        } else {
            column.setOrientation(LinearLayout.VERTICAL);
            clock = text(c, 1f);
            clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            date = text(c, 0.8f);
            hint = text(c, 0.6f);
            hint.setText(hintText);
            status = text(c, 0.5f);
            note = null;
            if (kind == COVER) {
                // ExtCover.dc.html's greys: the date and the lock line #C9D0D8, the state #8D97A3
                date.setAlpha(1f);
                date.setTextColor(Style.TEXT_SOFT);
                hint.setAlpha(1f);
                hint.setTextColor(Style.TEXT_SOFT);
                status.setAlpha(1f);
                status.setTextColor(Style.TEXT_DIM);
                note = new TextView(c);
                note.setText(R.string.xd_dash_cover_note);
                note.setTextColor(NOTE_COLOR);
                note.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                np.bottomMargin = Math.round(22 * c.getResources().getDisplayMetrics().density);
                addView(note, np);
            }
        }
        addView(column, at);
    }

    /** A line of the column: the clock, the date, the hint or the state. */
    private TextView text(Context c, float alpha) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setAlpha(alpha);
        t.setGravity(Gravity.CENTER);
        column.addView(t, new LinearLayout.LayoutParams(-2, -2));
        return t;
    }

    /** ExtCar.dc.html: the clock and date on the left, a hairline, and the states on the right, 80 dp apart. */
    private void buildCar(Context c) {
        column.setOrientation(LinearLayout.HORIZONTAL);
        column.setGravity(Gravity.CENTER);
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        clock = textInto(c, left, CAR_CLOCK, Style.TEXT, false);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date = textInto(c, left, CAR_DATE, Style.TEXT_SOFT, false);
        column.addView(left);
        View line = new View(c);
        line.setBackgroundColor(Style.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, Math.round(1 * c.getResources().getDisplayMetrics().density)),
            Math.round(200 * c.getResources().getDisplayMetrics().density));
        lp.leftMargin = lp.rightMargin = Math.round(80 * c.getResources().getDisplayMetrics().density);
        column.addView(line, lp);
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout connected = new LinearLayout(c);
        connected.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(c);
        dot.setBackground(Style.dot(Style.OK));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Math.round(10 * c.getResources().getDisplayMetrics().density),
            Math.round(10 * c.getResources().getDisplayMetrics().density));
        dp.rightMargin = Math.round(10 * c.getResources().getDisplayMetrics().density);
        connected.addView(dot, dp);
        TextView state = Style.label(c, c.getString(R.string.xd_dash_car), Style.TEXT, CAR_STATUS);
        connected.addView(state);
        right.addView(connected, new LinearLayout.LayoutParams(-2, -2));
        carBattery = carLine(c, right, R.drawable.xd_ic_battery);
        carSound = carLine(c, right, R.drawable.xd_ic_sound);
        carInput = carLine(c, right, R.drawable.xd_ic_keys_phone);
        column.addView(right);
    }

    private TextView carLine(Context c, LinearLayout parent, int icon) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView im = new ImageView(c);
        im.setImageResource(icon);
        im.setColorFilter(Style.TEXT_SOFT);
        int s = Math.round(20 * c.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
        ip.rightMargin = Math.round(10 * c.getResources().getDisplayMetrics().density);
        row.addView(im, ip);
        TextView t = Style.label(c, "", Style.TEXT_SOFT, CAR_STATUS);
        row.addView(t);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.topMargin = Math.round(16 * c.getResources().getDisplayMetrics().density);
        parent.addView(row, rp);
        return t;
    }

    private static TextView textInto(Context c, LinearLayout parent, float sp, int color, boolean bold) {
        TextView t = Style.label(c, "", color, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.START);
        parent.addView(t);
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
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Math.round((kind == CAR ? CAR_CLOCK : kind == COVER ? COVER_CLOCK : CLOCK) * density));
            date.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Math.round((kind == CAR ? CAR_DATE : kind == COVER ? COVER_DATE : DATE) * density));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round((kind == COVER ? COVER_HINT : HINT) * density));
            status.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Math.round((kind == CAR ? CAR_STATUS : kind == COVER ? COVER_STATUS : STATUS) * density));
            if (kind == COVER) {
                hint.setPadding(0, Math.round(COVER_HINT_PAD * density), 0, 0);
                status.setPadding(0, Math.round(COVER_STATUS_PAD * density), 0, 0);
                if (note != null) {
                    note.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round(COVER_NOTE * density));
                    FrameLayout.LayoutParams np = (FrameLayout.LayoutParams) note.getLayoutParams();
                    np.bottomMargin = Math.round(22 * density);
                }
                // The mockup's lock before "External screen hidden": drawn from the line drawable at its left
                android.graphics.drawable.Drawable lock = getContext().getDrawable(R.drawable.xd_ic_lock);
                if (lock != null) {
                    int s = Math.round(20 * density);
                    lock.setBounds(0, 0, s, s);
                    lock.setTint(Style.TEXT_DIM);
                    hint.setCompoundDrawablesRelative(lock, null, null, null);
                    hint.setCompoundDrawablePadding(Math.round(10 * density));
                }
            } else {
                hint.setPadding(0, Math.round(HINT_PAD * density), 0, 0);
                status.setPadding(0, Math.round(STATUS_PAD * density), 0, 0);
            }
            if (kind == CAR) {
                // The car's four lines are 18 dp too (their rows are built once; the text sizes follow here)
                LinearLayout right = (LinearLayout) column.getChildAt(2);
                for (int i = 0; i < right.getChildCount(); i++) {
                    View row = right.getChildAt(i);
                    if (row instanceof LinearLayout) {
                        View last = ((LinearLayout) row).getChildAt(((LinearLayout) row).getChildCount() - 1);
                        if (last instanceof TextView) ((TextView) last).setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round(CAR_STATUS * density));
                    }
                }
            }
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

    /** Just the time and just the date: the switchboard's home draws them as the mockup lays them out. */
    static String timeLine(Context c) {
        return time(c, new Date()).toString();
    }

    static String dayLine(Context c) {
        return day(new Date()).toString();
    }

    /**
     * The battery, the sound output and where the keys and the mouse are, in one line (ExtHome.dc.html's bottom-right,
     * the dashboard's line, and the cover's own). {@code home}: the Launchpad's home has the keyboard and mouse.
     */
    static String statusLine(Context c, boolean home) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        // ExtHome.dc.html's order: where the keys are, the sound, the phone's battery
        s.append(inputLine(c, home));
        s.append("   ·   ").append(c.getString(R.string.xd_dash_sound, AudioRoute.get(c).shortLabel()));
        int level = batteryLevel(c);
        if (level >= 0) {
            s.append("   ·   ").append(c.getString(R.string.xd_dash_battery, level));
            if (batteryCharging(c)) s.append(' ').append(c.getString(R.string.xd_dash_charging));
        }
        return s.toString();
    }

    private static Intent battery(Context c) {
        return c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private static int batteryLevel(Context c) {
        Intent battery = battery(c);
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level >= 0 && scale > 0 ? level * 100 / scale : -1;
    }

    private static boolean batteryCharging(Context c) {
        Intent battery = battery(c);
        if (battery == null) return false;
        int state = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL;
    }

    /** The low-battery warning lives on this line on the car: the battery turns amber at 15% (NOTIFY.md §4). */
    private void updateStatus() {
        Context c = getContext();
        if (kind == COVER) {
            int level = batteryLevel(c);
            if (level >= 0) status.setText(c.getString(R.string.xd_dash_cover_status, level));
            else status.setText(c.getString(R.string.xd_dash_sound_paused) + "   ·   " + c.getString(R.string.xd_dash_input_phone));
            return;
        }
        if (kind == CAR) {
            int level = batteryLevel(c);
            if (carBattery != null) {
                carBattery.setText(level >= 0 ? c.getString(R.string.xd_dash_battery, level) : "");
                carBattery.setTextColor(level >= 0 && level <= 15 && !batteryCharging(c) ? Style.WARN : Style.TEXT_SOFT);
            }
            if (carSound != null) carSound.setText(c.getString(R.string.xd_dash_sound, AudioRoute.get(c).shortLabel()));
            if (carInput != null) carInput.setText(inputLine(c, false));
            return;
        }
        SpannableStringBuilder s = new SpannableStringBuilder();
        int level = batteryLevel(c);
        if (level >= 0) {
            int at = s.length();
            s.append(c.getString(R.string.xd_dash_battery, level));
            if (level <= 15 && !batteryCharging(c))
                s.setSpan(new ForegroundColorSpan(Style.WARN), at, s.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (batteryCharging(c)) s.append(' ').append(c.getString(R.string.xd_dash_charging));
            s.append("   ·   ");
        }
        s.append(c.getString(R.string.xd_dash_sound, AudioRoute.get(c).shortLabel()));
        if (!showInput) {
            status.setText(s);
            return;
        }
        s.append("   ·   ");
        String input = inputLine(c, SwitcherOverlay.isHome());
        s.append(input);
        status.setText(s);
        if (!input.equals(lastInput)) {
            lastInput = input;
            brighten();
        }
    }

    /** Where the keys and the mouse are, as the dashboard says it; home: the Launchpad has them. */
    private static String inputLine(Context c, boolean home) {
        AppSession apps = AppSession.get(c);
        return c.getString(home ? R.string.xd_dash_input_home
            : apps.isForwarding() ? R.string.xd_dash_input_display
            : apps.hasInjector() ? R.string.xd_dash_input_phone_hotkey : R.string.xd_dash_input_phone);
    }

    void start() {
        if (started) return;
        started = true;
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(timeChanged, time);
        updateClock();
        updateStatus();
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
