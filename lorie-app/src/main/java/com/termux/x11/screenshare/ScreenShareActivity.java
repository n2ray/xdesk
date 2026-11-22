// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.Arrays;

/**
 * A Mac's screen through macOS Screen Sharing, full screen on the display it was started on (the external screen):
 * the picture is the Mac's H.264, decoded by the phone's hardware into this SurfaceView. Input comes from XDesk's
 * touchpad and keyboard (ScreenShareInput), not from this window. The session lives as long as this activity:
 * closing it (taken off the screen, the screen unplugged, the app stopped) ends the session and frees its ports.
 */
public final class ScreenShareActivity extends Activity implements SurfaceHolder.Callback, ScreenShareSession.Listener {
    public static final String EXTRA_HOST = "host";

    /** What the app (ScreenShareSource) hears of a session: ended on its own, or the first picture. */
    public interface Events {
        void connected(String hostId, ScreenShareSession session);
        void ended(String hostId, String reason);
    }
    public static volatile Events events;

    private static ScreenShareActivity showing;
    /** The password for the next session, from the login dialog; cleared as soon as the session took it. */
    private static char[] pendingPassword;
    private static String pendingHost;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScreenShareStore.Host host;
    private SurfaceView view;
    private ScreenShareSession session;
    private int width, height;
    private boolean sampling;

    /** The login dialog hands the password over just before it starts this activity. */
    static synchronized void handOver(String hostId, char[] password) {
        if (pendingPassword != null) Arrays.fill(pendingPassword, (char) 0);
        pendingHost = hostId;
        pendingPassword = password;
    }

    private static synchronized char[] takePassword(String hostId) {
        char[] p = hostId.equals(pendingHost) ? pendingPassword : null;
        pendingPassword = null;
        pendingHost = null;
        return p;
    }

    public static boolean isShowing(String hostId) {
        ScreenShareActivity a = showing;
        return a != null && a.host != null && a.host.id.equals(hostId) && !a.isFinishing();
    }

    public static void finishFor(String hostId) {
        ScreenShareActivity a = showing;
        if (a != null && a.host != null && a.host.id.equals(hostId)) a.finish();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        host = ScreenShareStore.find(this, getIntent().getStringExtra(EXTRA_HOST));
        if (host == null) {
            finish();
            return;
        }
        if (showing != null && showing != this) showing.finish();
        showing = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        // One virtual display on the Mac: 1920×1080 (the baseline), or this screen's size, at most 4K, even
        if (host.fixed1080) {
            width = 1920;
            height = 1080;
        } else {
            Display.Mode mode = getDisplay().getMode();
            double fit = Math.min(1, Math.min(3840.0 / mode.getPhysicalWidth(), 2160.0 / mode.getPhysicalHeight()));
            width = 2 * (int) (mode.getPhysicalWidth() * fit / 2);
            height = 2 * (int) (mode.getPhysicalHeight() * fit / 2);
        }
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        frame.addView(view, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        // The picture as large as it fits, in its own proportions
        frame.addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, ob) -> {
            int w = Math.min(r - l, (bottom - t) * width / height);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, w * height / width, Gravity.CENTER);
            if (view.getWidth() != w || view.getHeight() != lp.height) view.post(() -> view.setLayoutParams(lp));
        });
        view.getHolder().setFixedSize(width, height);
        view.getHolder().addCallback(this);
        setContentView(frame);
        Log.i(ScreenShareSession.TAG, "SCREEN display=" + getDisplay().getDisplayId() + " ask=" + width + "x" + height);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        if (session != null) return;
        char[] password = takePassword(host.id);
        if (password == null) {
            // Nothing to log in with (the activity came back on its own): the user opens it again
            finish();
            return;
        }
        session = ScreenShareSession.start(this, host, password, h.getSurface(), width, height, this);
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        ScreenShareSession s = session;
        if (s != null) s.close();
    }

    @Override protected void onDestroy() {
        sampling = false;
        ScreenShareSession s = session;
        if (s != null) s.close();
        if (showing == this) showing = null;
        super.onDestroy();
    }

    // --- ScreenShareSession.Listener (UI thread) ---

    @Override public void connected() {
        Events e = events;
        if (e != null && session != null) e.connected(host.id, session);
    }

    @Override public void firstFrame(long ms) {
        if (host.measure && session != null && !sampling) {
            sampling = session.clockCalibrated;
            if (sampling) sample();
            // The test's 35 seconds from the first picture (the session's numbers go to the log again at its end)
            final ScreenShareSession s = session;
            ui.postDelayed(() -> { if (s == session) s.summary(); }, 35000);
        }
    }

    @Override public void ended(String reason) {
        sampling = false;
        if (reason != null) Toast.makeText(getApplicationContext(), reason, Toast.LENGTH_LONG).show();
        Events e = events;
        if (e != null) e.ended(host.id, reason);
        if (!isFinishing()) finish();
    }

    /** Test only: reads the Mac's time bar (Marker.swift) off the picture, against the calibrated clock. */
    private void sample() {
        final ScreenShareSession s = session;
        if (!sampling || s == null || view.getWidth() < 1) return;
        final Bitmap bm = Bitmap.createBitmap(256, 144, Bitmap.Config.ARGB_8888);
        PixelCopy.request(view, bm, result -> {
            long done = System.nanoTime();
            if (sampling && result == PixelCopy.SUCCESS) {
                long bits = 0;
                for (int i = 0; i < 48; i++) bits = (bits << 1) | ((bm.getPixel(34 + 4 * i, 72) & 255) > 128 ? 1 : 0);
                long tick = (bits >> 8) & 0xffffffffL;
                if ((bits >>> 40) == 0xd3 && ((tick ^ (tick >> 8) ^ (tick >> 16) ^ (tick >> 24)) % 256) == (bits & 255)) {
                    long age = ((done - s.offsetNs) / 1000000 - tick) & 0xffffffffL;
                    if (age < 10000) s.addAge(age);
                }
            }
            bm.recycle();
            if (sampling) ui.postDelayed(this::sample, 100);
        }, ui);
    }
}
