package com.termux.x11;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.input.InputStub;

/**
 * XDesk prototype: the stock MainActivity shows the desktop on the external display, this Activity turns the
 * phone into a touchpad in the same process and drives LorieView directly (no xdeskd, token or TCP).
 */
public class XDeskActivity extends Activity {
    private static final String TAG = "XDesk";
    private static final String PERM = "com.termux.permission.RUN_COMMAND";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String SESSION = "/sdcard/Termux/xdesk/termux/x11-session.sh";
    private static final int DESKTOP_WIDTH = 1920;
    private static final long REFOCUS_DELAY_MS = 800, STATUS_MS = 1000;
    private static final float WHEEL_STEP = 60f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private DisplayManager displays;
    private TextView status;
    private boolean started;
    private int placed, removed;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            LorieView v = view();
            Display d = external();
            status.setText("外屏" + (d != null ? "✓ id=" + d.getDisplayId() : "✗") + " X连接" + (v != null ? "✓" : "✗")
                + " 窗口" + (MainActivity.getInstance("") != null ? "✓" : "✗")
                + " 焦点" + (hasWindowFocus() ? "✓" : "✗") + " 放置" + placed + " 拔" + removed);
            ui.postDelayed(this, STATUS_MS);
        }
    };

    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {
            if (started) place(id);
            else tryStart();
        }
        @Override public void onDisplayRemoved(int id) {
            removed++;
            // 系统把桌面窗口挪到手机屏上，同一个 App 可以直接把触摸板拉回前面
            refocus();
        }
        @Override public void onDisplayChanged(int id) { }
    };

    private final TouchpadView.Output output = new TouchpadView.Output() {
        @Override public void move(int dx, int dy) {
            LorieView v = view();
            if (v != null) v.sendMouseEvent(dx, dy, InputStub.BUTTON_UNDEFINED, false, true);
        }
        @Override public void button(int b, boolean down) {
            LorieView v = view();
            if (v != null) v.sendMouseEvent(0, 0, b, down, true);
        }
        @Override public void click(int b) {
            button(b, true);
            button(b, false);
        }
        @Override public void scroll(int dy, int dx) {
            LorieView v = view();
            if (v != null) v.sendMouseWheelEvent(dx * WHEEL_STEP, dy * WHEEL_STEP);
        }
    };

    private LorieView view() {
        MainActivity m = MainActivity.getInstance("");
        LorieView v = m != null ? m.getLorieView() : null;
        return v != null && v.connected() ? v : null;
    }

    private Display external() {
        for (Display d : displays.getDisplays())
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        return null;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setFitsSystemWindows(true);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(24, 16, 24, 16);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        root.addView(new TouchpadView(this, output), new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = new LinearLayout(this);
        bottom.addView(mouseButton("左键", InputStub.BUTTON_LEFT), new LinearLayout.LayoutParams(0, -1, 1));
        bottom.addView(mouseButton("右键", InputStub.BUTTON_RIGHT), new LinearLayout.LayoutParams(0, -1, 1));
        Button stop = new Button(this);
        stop.setText("结束");
        stop.setOnClickListener(v -> stopDesktop());
        bottom.addView(stop, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, 180));
        setContentView(root);

        displays.registerDisplayListener(listener, ui);
        ui.post(refresh);
        tryStart();
    }

    @Override protected void onDestroy() {
        displays.unregisterDisplayListener(listener);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private Button mouseButton(String label, int button) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) output.button(button, true);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) output.button(button, false);
            return false;
        });
        return b;
    }

    private void tryStart() {
        if (started) return;
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{PERM}, 1);
            return;
        }
        Display d = external();
        if (d == null) return;
        started = true;
        applyPrefs(d);
        place(d.getDisplayId());
        run("start", desktopSize(d));
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) tryStart();
    }

    private String desktopSize(Display d) {
        Point p = new Point();
        d.getRealSize(p);
        if (p.y > p.x) {
            Display.Mode m = d.getMode();
            p.set(m.getPhysicalWidth(), m.getPhysicalHeight());
        }
        return DESKTOP_WIDTH + "x" + (Math.round((float) DESKTOP_WIDTH * p.y / p.x) & ~1);
    }

    /** 这个 App 的设置和官方 Termux:X11 是分开的，手机上的 xfce 不受影响。 */
    private void applyPrefs(Display d) {
        Prefs p = ((LorieApp) getApplication()).getPrefs(createDisplayContext(d));
        p.displayResolutionMode.put("custom");
        p.displayResolutionCustom.put(desktopSize(d));
        p.displayFilteringMode.put("bilinear");
    }

    private void place(int displayId) {
        placed++;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchDisplayId(displayId);
        startActivity(i, o.toBundle());
        refocus();
    }

    private void refocus() {
        ui.postDelayed(() -> {
            Intent self = new Intent(this, XDeskActivity.class);
            self.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(self);
        }, REFOCUS_DELAY_MS);
    }

    private void stopDesktop() {
        started = false;
        run("stop");
        MainActivity m = MainActivity.getInstance("");
        if (m != null) m.finishAndRemoveTask();
        finishAndRemoveTask();
    }

    private void run(String... args) {
        String[] argv = new String[args.length + 1];
        argv[0] = SESSION;
        System.arraycopy(args, 0, argv, 1, args.length);
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", argv);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        try {
            startForegroundService(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "RUN_COMMAND failed", e);
            status.setText("无法调用 Termux: " + e.getMessage());
        }
    }
}
