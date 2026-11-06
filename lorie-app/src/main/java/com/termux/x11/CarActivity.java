package com.termux.x11;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.WindowInsets;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Android Auto's entry while parked (CAR_LAUNCHER): the dashboard on the car's screen. A first step that also writes
 * what the car's display is and what happens to this activity to car_log.txt in the app's files, to plan the next steps.
 */
public class CarActivity extends Activity {
    private static final String TAG = "XDeskCar";
    private DashboardView view;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        view = new DashboardView(this, "车机已连接 · 在手机上用 XDesk 操作", true);
        setContentView(view);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        record("onCreate " + describe(getDisplay()) + " density=" + getResources().getConfiguration().densityDpi);
    }

    @Override protected void onStart() {
        super.onStart();
        view.start();
        record("onStart");
    }

    @Override protected void onResume() {
        super.onResume();
        record("onResume focus=" + hasWindowFocus());
    }

    @Override protected void onPause() {
        record("onPause");
        super.onPause();
    }

    @Override protected void onStop() {
        view.stop();
        record("onStop");
        super.onStop();
    }

    @Override protected void onDestroy() {
        record("onDestroy finishing=" + isFinishing());
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        record("config " + c.screenWidthDp + "x" + c.screenHeightDp + "dp " + c.densityDpi + "dpi " + describe(getDisplay()));
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        record("focus " + focus);
    }

    private static String describe(Display d) {
        if (d == null) return "display=null";
        Point real = new Point();
        d.getRealSize(real);
        StringBuilder s = new StringBuilder();
        s.append("display=").append(d.getDisplayId()).append(" name=").append(d.getName())
            .append(" size=").append(real.x).append('x').append(real.y)
            .append(" flags=0x").append(Integer.toHexString(d.getFlags()));
        try {
            s.append(" type=").append(Display.class.getMethod("getType").invoke(d));
            s.append(" owner=").append(Display.class.getMethod("getOwnerPackageName").invoke(d));
            s.append(" uid=").append(Display.class.getMethod("getOwnerUid").invoke(d));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return s.toString();
    }

    private void record(String what) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + what;
        Log.w(TAG, line);
        try (FileWriter w = new FileWriter(new java.io.File(getFilesDir(), "car_log.txt"), true)) {
            w.write(line + "\n");
        } catch (IOException e) {
            Log.w(TAG, "log", e);
        }
    }
}
