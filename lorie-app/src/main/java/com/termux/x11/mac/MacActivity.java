package com.termux.x11.mac;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * A Mac window or the Mac desktop on a screen (DISPLAY.md §3.0): Sill's HEVC decoded by the hardware straight
 * into this SurfaceView, letterboxed to the picture's aspect, nothing composed on the way. The Mac's cursor is a
 * small view over it, moved by translation, in the shape the Mac sends. A line in the middle says when there is
 * no picture: connecting, the Mac locked, the stream taken by another device (tap to take it back).
 *
 * The window never takes focus: keys stay with XDesk on the phone, which hands them to the Mac (MacInput).
 */
public final class MacActivity extends Activity implements MacClient.View, SurfaceHolder.Callback {
    public static final String EXTRA_MAC = "mac";
    /** The display it was opened on: if it shows up anywhere else, that display went away (DISPLAY.md §5.4). */
    public static final String EXTRA_DISPLAY = "display";
    private static MacActivity current;

    /** What XDesk hears when a Mac screen ends on its own (not through finishFor). UI thread. */
    public interface Ended {
        void ended(String macId);
    }

    public static volatile Ended onEnded;

    private MacClient client;
    private FrameLayout root;
    private SurfaceView picture;
    private ImageView cursor;
    private TextView status;
    private int videoW, videoH;
    private float hotX, hotY;
    private boolean takenOver;
    /** Finished by XDesk (another content took the screen): nothing to report. */
    private boolean quiet;
    private int displayWanted = Display.INVALID_DISPLAY;
    private DisplayManager.DisplayListener displayWatch;

    /** The activity showing this Mac, finished: another content took the screen (stream stops, connection stays). */
    public static void finishFor(String macId) {
        MacActivity a = current;
        if (a != null && a.client != null && a.client.mac().id.equals(macId)) {
            a.quiet = true;
            a.finish();
        }
    }

    /** The Mac can't be shown any more (it ended the session for good): the screen closes and says why. */
    public static void failedFor(String macId, String why) {
        MacActivity a = current;
        if (a == null || a.isFinishing() || a.client == null || !a.client.mac().id.equals(macId)) return;
        if (why != null && !why.isEmpty())
            android.widget.Toast.makeText(a.getApplicationContext(), why, android.widget.Toast.LENGTH_LONG).show();
        a.end();
    }

    public static boolean isShowing(String macId) {
        MacActivity a = current;
        return a != null && !a.isFinishing() && a.client != null && a.client.mac().id.equals(macId);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        picture = new SurfaceView(this);
        picture.getHolder().addCallback(this);
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        cursor = new ImageView(this);
        cursor.setVisibility(View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setGravity(Gravity.CENTER);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        status.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xcc202124);
        bg.setCornerRadius(pad);
        status.setBackground(bg);
        status.setVisibility(View.GONE);
        status.setOnClickListener(v -> {
            if (takenOver && client != null) client.takeBack();
        });
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> layoutPicture());
        setContentView(root);
        displayWatch = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayChanged(int id) { }
            @Override public void onDisplayRemoved(int id) {
                if (id == displayWanted) end();
            }
        };
        ((DisplayManager) getSystemService(DISPLAY_SERVICE)).registerDisplayListener(displayWatch, null);
        bind(getIntent());
        checkDisplay();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        checkDisplay();
    }

    /** Android moves a removed display's activities onto the phone: there this Mac would go on streaming. */
    private void checkDisplay() {
        Display d = getDisplay();
        if (displayWanted != Display.INVALID_DISPLAY && d != null && d.getDisplayId() != displayWanted) end();
    }

    /** This screen of the Mac ends on its own: the stream stops (the connection stays), and XDesk hears it in onDestroy. */
    private void end() {
        if (isFinishing()) return;
        if (client != null && current == this) client.show(MacClient.NONE);
        finish();
    }

    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        bind(i);
    }

    private void bind(android.content.Intent i) {
        String id = i.getStringExtra(EXTRA_MAC);
        MacClient next = id != null ? MacClients.get(this, id) : null;
        if (next == null) {
            finish();
            return;
        }
        if (client != null && client != next) {
            client.clearSurface(this);
            client.detach(this);
        }
        client = next;
        current = this;
        displayWanted = i.getIntExtra(EXTRA_DISPLAY, Display.INVALID_DISPLAY);
        client.attach(this);
        if (picture.getHolder().getSurface().isValid()) giveSurface();
    }

    @Override protected void onDestroy() {
        if (displayWatch != null) ((DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(displayWatch);
        boolean last = current == this;
        if (client != null) {
            // Only this activity's own surface: a newer screen of this Mac may already show it (Mac, an app, Mac again)
            client.clearSurface(this);
            client.detach(this);
            if (last && !quiet && !isChangingConfigurations()) {
                client.show(MacClient.NONE);
                Ended e = onEnded;
                if (e != null) e.ended(client.mac().id);
            }
        }
        if (last) current = null;
        super.onDestroy();
    }

    // --- surface ---

    @Override public void surfaceCreated(SurfaceHolder h) { }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int ht) {
        giveSurface();
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        // Before the surface goes: the decoder must not render into it any more
        if (client != null) client.clearSurface(this);
    }

    private void giveSurface() {
        if (client == null) return;
        Display d = getDisplay();
        int fps = 60;
        if (d != null) {
            float max = 0;
            for (Display.Mode m : d.getSupportedModes()) max = Math.max(max, m.getRefreshRate());
            if (max > 0) fps = Math.min(120, Math.round(max));
        }
        // The viewport is the whole screen in dp, not the letterboxed picture
        client.setSurface(this, picture.getHolder().getSurface(), root.getWidth(), root.getHeight(),
                getResources().getDisplayMetrics().density, fps, d != null ? d.getDisplayId() : Display.INVALID_DISPLAY);
    }

    /** Aspect fit: the SurfaceView itself is the picture's rectangle, so the hardware scales it once. */
    private void layoutPicture() {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        int pw = w, ph = h;
        if (videoW > 0 && videoH > 0) {
            if ((long) videoW * h > (long) videoH * w) ph = (int) ((long) w * videoH / videoW);
            else pw = (int) ((long) h * videoW / videoH);
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) picture.getLayoutParams();
        if (p.width != pw || p.height != ph) {
            p.width = pw;
            p.height = ph;
            picture.setLayoutParams(p);
        }
        if (client != null) {
            client.setFrameRect((w - pw) / 2, (h - ph) / 2, pw, ph, w, h);
            placeCursor(client.pointerX(), client.pointerY());
        }
    }

    // --- MacClient.View ---

    @Override public void videoSize(int width, int height) {
        videoW = width;
        videoH = height;
        layoutPicture();
    }

    @Override public void cursor(Bitmap shape, float hx, float hy, float width, float height) {
        float density = getResources().getDisplayMetrics().density;
        // Sizes are Mac points: drawn as dp on this screen
        int w = Math.round(width * density), h = Math.round(height * density);
        cursor.setImageBitmap(shape);
        cursor.setScaleType(ImageView.ScaleType.FIT_XY);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) cursor.getLayoutParams();
        p.width = w;
        p.height = h;
        cursor.setLayoutParams(p);
        hotX = hx * density;
        hotY = hy * density;
        cursor.setVisibility(View.VISIBLE);
        placeCursor(client.pointerX(), client.pointerY());
    }

    @Override public void pointer(float x, float y, boolean inside) {
        if (cursor.getDrawable() == null) return;
        cursor.setVisibility(inside ? View.VISIBLE : View.GONE);
        placeCursor(x, y);
    }

    private void placeCursor(float x, float y) {
        int[] r = client.frameRect();
        if (r[2] <= 0) return;
        cursor.setTranslationX(r[0] + x * r[2] - hotX);
        cursor.setTranslationY(r[1] + y * r[3] - hotY);
    }

    @Override public void status(String line, boolean taken) {
        takenOver = taken;
        status.setText(line);
        status.setVisibility(line != null ? View.VISIBLE : View.GONE);
        status.setClickable(taken);
    }
}
