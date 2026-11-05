package com.termux.x11;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * XDesk's own window at the bottom of the external display, needing no Shizuku. Apps and the Termux window open on top
 * of it, and when an app exits this is what gets uncovered, rather than mirroring. 结束 XDesk removes it.
 *
 * It is not an app to move to the phone and does not count as an app in front: both follow from it being XDesk's own
 * package (see TaskMover). It is an Activity, not a Presentation, so the Presentation warning does not see it either.
 *
 * All sizes are parts of the window's size, not dp, so density changes and different displays need no special handling.
 * Add further content to column in build().
 */
public class DashboardActivity extends Activity {
    /** Text sizes as parts of the window's shorter side. */
    private static final float CLOCK = 0.2f, DATE = 0.05f, HINT = 0.034f, STATUS = 0.028f;
    /** Burn-in: the content moves this far (part of the shorter side) every SHIFT_MS, and dims after DIM_AFTER_MS. */
    private static final float SHIFT_RANGE = 0.04f, DIM_ALPHA = 0.35f;
    private static final long SHIFT_MS = 60000, DIM_AFTER_MS = 300000, STATUS_MS = 2000;

    private static WeakReference<DashboardActivity> instance = new WeakReference<>(null);

    static boolean isAlive() {
        DashboardActivity a = instance.get();
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    static void closeIfAlive() {
        DashboardActivity a = instance.get();
        if (a != null && !a.isFinishing()) a.finishAndRemoveTask();
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private LinearLayout column;
    private TextView clock, date, hint, status;
    private long brightSince;
    private String lastInput;

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
            View root = (View) column.getParent();
            float range = Math.min(root.getWidth(), root.getHeight()) * SHIFT_RANGE;
            column.setTranslationX((random.nextFloat() * 2 - 1) * range);
            column.setTranslationY((random.nextFloat() * 2 - 1) * range);
            if (SystemClock.uptimeMillis() - brightSince >= DIM_AFTER_MS) column.setAlpha(DIM_ALPHA);
            ui.postDelayed(this, SHIFT_MS);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // On the phone it has no purpose. The system moves it there when the display goes away
        Display d = getDisplay();
        if (d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY) {
            finishAndRemoveTask();
            return;
        }
        instance = new WeakReference<>(this);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(build());
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
    }

    private TextView text(float alpha) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setAlpha(alpha);
        t.setGravity(Gravity.CENTER);
        column.addView(t, new LinearLayout.LayoutParams(-2, -2));
        return t;
    }

    private View build() {
        FrameLayout root = new FrameLayout(this) {
            @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
                float side = Math.min(w, h);
                clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * CLOCK);
                date.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * DATE);
                hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * HINT);
                status.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * STATUS);
                hint.setPadding(0, Math.round(side * 0.06f), 0, 0);
                status.setPadding(0, Math.round(side * 0.04f), 0, 0);
            }
        };
        root.setBackgroundColor(Color.BLACK);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        clock = text(1f);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date = text(0.8f);
        hint = text(0.6f);
        hint.setText("在手机上选择一个应用");
        status = text(0.5f);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2);
        p.gravity = Gravity.CENTER;
        root.addView(column, p);
        return root;
    }

    private void brighten() {
        brightSince = SystemClock.uptimeMillis();
        column.setAlpha(1f);
    }

    private void updateClock() {
        Date now = new Date();
        String clockPattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(DateFormat.format(clockPattern, now));
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMdEEEE"), now));
    }

    private void updateStatus() {
        StringBuilder s = new StringBuilder();
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int state = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (level >= 0 && scale > 0) s.append("电量 ").append(level * 100 / scale).append('%');
            if (state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL) s.append(" 充电中");
            s.append("   ·   ");
        }
        s.append("声音：").append(AudioRoute.get(this).shortLabel()).append("   ·   ");
        AppSession apps = AppSession.get(this);
        String input = apps.isForwarding() ? "键鼠在外接屏，Ctrl+Alt+M 交还手机"
            : apps.hasInjector() ? "键鼠在手机，Ctrl+Alt+M 接管" : "键鼠在手机";
        s.append(input);
        status.setText(s);
        if (!input.equals(lastInput)) {
            lastInput = input;
            brighten();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeChanged, time);
        updateClock();
        brighten();
        ui.post(statusTick);
        ui.postDelayed(shift, SHIFT_MS);
    }

    /** Covered by an app or a desktop: nothing to update. */
    @Override protected void onStop() {
        unregisterReceiver(timeChanged);
        ui.removeCallbacksAndMessages(null);
        super.onStop();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
    }

    /** Input can reach this window. Back must not close it, only 结束 XDesk does. */
    @Override public void onBackPressed() { }
}
