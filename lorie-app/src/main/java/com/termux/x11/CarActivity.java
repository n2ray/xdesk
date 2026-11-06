package com.termux.x11;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Android Auto's entry while parked (CAR_LAUNCHER): the dashboard on the car's screen, and what tells XDesk which display
 * is the car's (see TargetDisplay). A Termux desktop goes on top of it. While an Android app session runs on the car it
 * shows the display the injector made for the apps instead (see AppDisplay), in a SurfaceView, and sends the car's touches
 * there. It also writes what the car's display is and what happens to this activity to car_log.txt in the app's files,
 * for testing.
 */
public class CarActivity extends Activity {
    /** AppSession, on the main thread. */
    interface AppScreen {
        /** The surface to show the apps' display in, null when it goes. Detach before returning: it is released after. */
        void onSurface(Surface surface);
        /** A touch on it, action and ids as in MotionEvent, positions in pixels of the apps' display. */
        void onTouch(int action, int[] ids, float[] x, float[] y);
    }

    private static final String TAG = "XDeskCar";
    private static int displayId = Display.INVALID_DISPLAY;
    private static Runnable listener;
    private static AppScreen appScreen;
    /** The size of the apps' display while an app session runs on the car, otherwise 0 and the dashboard shows. */
    private static int appWidth, appHeight;
    /** The instance, so that showApps reaches it. */
    private static CarActivity current;
    private static boolean started;
    private DashboardView view;
    private SurfaceView appView;
    /** The display this one told XDesk about. */
    private int shownOn = Display.INVALID_DISPLAY;

    /** The car's display, or INVALID_DISPLAY when this activity is not on one. */
    static int displayId() {
        return displayId;
    }

    /** Runs on the main thread when the car's display comes or goes. */
    static void setListener(Runnable l) {
        listener = l;
    }

    static void setAppScreen(AppScreen s) {
        appScreen = s;
    }

    /** Visible on the car's screen, not covered by a desktop. */
    static boolean isShowing() {
        return started;
    }

    /** An Android app session runs on the car in a display of this size, or none with 0. */
    static void showApps(int width, int height) {
        appWidth = width;
        appHeight = height;
        if (current != null) current.applyMode();
    }

    /** The SurfaceView has a surface only while visible, so this attaches and detaches through its callbacks. */
    private void applyMode() {
        boolean apps = appWidth > 0;
        // The buffer stays the display's size and the system scales it to the window, so touches scale the other way
        if (apps) appView.getHolder().setFixedSize(appWidth, appHeight);
        appView.setVisibility(apps ? View.VISIBLE : View.GONE);
        // The SurfaceView is drawn behind the window, so nothing may cover it
        view.setVisibility(apps ? View.GONE : View.VISIBLE);
    }

    private SurfaceView buildAppView() {
        SurfaceView v = new SurfaceView(this);
        v.setVisibility(View.GONE);
        v.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                record("app surface created, display " + appWidth + "x" + appHeight);
                if (appScreen != null) appScreen.onSurface(h.getSurface());
            }
            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
                record("app surface " + w + "x" + hh + " in a view of " + appView.getWidth() + "x" + appView.getHeight());
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                record("app surface destroyed");
                if (appScreen != null) appScreen.onSurface(null);
            }
        });
        v.setOnTouchListener((touched, e) -> {
            AppScreen s = appScreen;
            if (s == null || appWidth <= 0 || touched.getWidth() <= 0 || touched.getHeight() <= 0) return true;
            float sx = (float) appWidth / touched.getWidth(), sy = (float) appHeight / touched.getHeight();
            int n = e.getPointerCount();
            int[] ids = new int[n];
            float[] x = new float[n], y = new float[n];
            for (int i = 0; i < n; i++) {
                ids[i] = e.getPointerId(i);
                x[i] = e.getX(i) * sx;
                y[i] = e.getY(i) * sy;
            }
            s.onTouch(e.getAction(), ids, x, y);
            return true;
        });
        return v;
    }

    private void setDisplayId(int id) {
        shownOn = id;
        if (id == displayId) return;
        displayId = id;
        if (listener != null) listener.run();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        view = new DashboardView(this, "车机已连接 · 在手机上用 XDesk 操作", true);
        appView = buildAppView();
        FrameLayout root = new FrameLayout(this);
        root.addView(appView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        current = this;
        applyMode();
        record("onCreate " + describe(getDisplay()) + " density=" + getResources().getConfiguration().densityDpi);
        followDisplay();
    }

    @Override protected void onStart() {
        super.onStart();
        view.start();
        started = true;
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
        started = false;
        record("onStop");
        super.onStop();
    }

    @Override protected void onDestroy() {
        record("onDestroy finishing=" + isFinishing());
        if (shownOn == displayId) setDisplayId(Display.INVALID_DISPLAY);
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        record("config " + c.screenWidthDp + "x" + c.screenHeightDp + "dp " + c.densityDpi + "dpi " + describe(getDisplay()));
        followDisplay();
    }

    /** On the phone it has no purpose. The system may move it there when Android Auto's display goes away. */
    private void followDisplay() {
        Display d = getDisplay();
        if (d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY) {
            setDisplayId(Display.INVALID_DISPLAY);
            if (!isFinishing()) finishAndRemoveTask();
            return;
        }
        setDisplayId(d.getDisplayId());
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
        record(this, what);
    }

    /** Also for XDesk's phone side, which shares this log. */
    static void record(Context c, String what) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + what;
        Log.w(TAG, line);
        try (FileWriter w = new FileWriter(new java.io.File(c.getFilesDir(), "car_log.txt"), true)) {
            w.write(line + "\n");
        } catch (IOException e) {
            Log.w(TAG, "log", e);
        }
    }
}
