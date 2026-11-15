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

import com.termux.x11.Sizing;

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
    /**
     * Every screen of a Mac that exists, oldest first (main thread). Not only the newest: with two Macs, the one put
     * on the screen first is still to be finished after the second has opened over it, or it would stay underneath
     * and come up again, without a stream, when the second goes.
     */
    private static final java.util.ArrayList<MacActivity> live = new java.util.ArrayList<>();

    /** The newest screen of this Mac that is not on its way out, or null. */
    private static MacActivity of(String macId) {
        for (int i = live.size() - 1; i >= 0; i--) {
            MacActivity a = live.get(i);
            if (!a.isFinishing() && a.client != null && a.client.mac().id.equals(macId)) return a;
        }
        return null;
    }

    /** Whether another screen (not on its way out) shows this activity's Mac. */
    private boolean replaced() {
        for (MacActivity a : live) if (a != this && !a.isFinishing() && a.client == client) return true;
        return false;
    }

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
        for (MacActivity a : new java.util.ArrayList<>(live)) {
            if (a.isFinishing() || a.client == null || !a.client.mac().id.equals(macId)) continue;
            a.quiet = true;
            a.finish();
        }
    }

    /** XDesk ends (结束 XDesk, swiped from the recents): no Mac goes on streaming to a screen nobody controls. */
    public static void finishAll() {
        for (MacActivity a : new java.util.ArrayList<>(live)) {
            if (a.isFinishing()) continue;
            a.quiet = true;
            if (a.client != null) a.client.show(MacClient.NONE);
            a.finish();
        }
    }

    /** The Mac can't be shown any more (it ended the session for good): the screen closes and says why. */
    public static void failedFor(String macId, String why) {
        MacActivity a = of(macId);
        if (a == null) return;
        if (why != null && !why.isEmpty())
            android.widget.Toast.makeText(a.getApplicationContext(), why, android.widget.Toast.LENGTH_LONG).show();
        a.end();
    }

    public static boolean isShowing(String macId) {
        return of(macId) != null;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        root = new FrameLayout(this) {
            /**
             * Nothing of this window is reported as transparent. A SurfaceView has its window tell SurfaceFlinger
             * that the picture's rectangle is see-through, minus the views over it where they were at the last
             * layout; the compositor then leaves that region of the window out. The cursor moves by translation,
             * with no layout, so everywhere but where it was at the last layout (the last shape change) it was in
             * that region: over a picture with no bars above it, it showed only below that place (dumpsys
             * SurfaceFlinger 2026-11-14: transparentRegionHint all but 42×60 px, displayFrame [0 1383 2560 1600]).
             * The hole itself is still cleared in the window's pixels, so the picture shows through as before.
             */
            @Override public boolean gatherTransparentRegion(android.graphics.Region region) {
                if (region != null) {
                    int[] at = new int[2];
                    getLocationInWindow(at);
                    region.op(at[0], at[1], at[0] + getWidth(), at[1] + getHeight(), android.graphics.Region.Op.DIFFERENCE);
                }
                return true;
            }
        };
        root.setBackgroundColor(Color.BLACK);
        picture = new SurfaceView(this);
        picture.getHolder().addCallback(this);
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        cursor = new ImageView(this);
        cursor.setVisibility(View.GONE);
        root.addView(cursor, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        // §4.1: this notice is part of the shell (Sizing.shell): b·u in px, no density override, no font scale
        Sizing.Shell shell = Sizing.shell(this, getDisplay());
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, shell.text(18));
        status.setGravity(Gravity.CENTER);
        int pad = shell.dp(16);
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
        if (client != null && !replaced()) client.show(MacClient.NONE);
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
        if (!live.contains(this)) live.add(this);
        displayWanted = i.getIntExtra(EXTRA_DISPLAY, Display.INVALID_DISPLAY);
        client.attach(this);
        if (picture.getHolder().getSurface().isValid()) giveSurface();
    }

    @Override protected void onDestroy() {
        root.removeCallbacks(hideBlank);
        if (displayWatch != null) ((DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(displayWatch);
        live.remove(this);
        boolean last = client != null && !replaced();
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

    /** The Mac's app shows an empty cursor (a player over its video): the arrow shows only while the mouse moves. */
    private boolean blankCursor;
    private static final long BLANK_HIDE_MS = 2000;
    private final Runnable hideBlank = () -> {
        if (blankCursor) show(false, "the Mac's cursor is empty and the mouse rests");
    };

    private static final String TAG = "XDeskMac";
    private long noteSecond;
    private int notes, moves;

    /** A line for logcat, at most NOTES_A_SECOND of them: what the pointer does is asked about after the fact. */
    private void note(String line) {
        long s = android.os.SystemClock.uptimeMillis() / 1000;
        if (s != noteSecond) {
            if (notes > NOTES_A_SECOND) android.util.Log.d(TAG, "pointer: " + (notes - NOTES_A_SECOND) + " more lines left out");
            noteSecond = s;
            notes = 0;
        }
        if (++notes <= NOTES_A_SECOND) android.util.Log.d(TAG, "pointer: " + line);
    }

    private static final int NOTES_A_SECOND = 6;

    /** The cursor shown or not, with the reason in the log when that changes. */
    private void show(boolean visible, String why) {
        boolean was = cursor.getVisibility() == View.VISIBLE;
        if (was == visible) return;
        cursor.setVisibility(visible ? View.VISIBLE : View.GONE);
        note((visible ? "shown: " : "hidden: ") + why + ", at " + place());
    }

    private String place() {
        int[] r = client != null ? client.frameRect() : new int[6];
        return Math.round(cursor.getTranslationX() + hotX) + "," + Math.round(cursor.getTranslationY() + hotY)
                + " picture " + r[0] + "," + r[1] + " " + r[2] + "x" + r[3] + " screen " + r[4] + "x" + r[5];
    }

    @Override public void cursor(Bitmap shape, float hx, float hy, float width, float height, boolean blank) {
        root.removeCallbacks(hideBlank);
        blankCursor = blank;
        note("shape " + shape.getWidth() + "x" + shape.getHeight() + " px, " + width + "x" + height + " pt, hot " + hx + "," + hy
                + (blank ? ", empty" : "") + ", " + moves + " moves since the last");
        moves = 0;
        if (blank) {
            // Nothing to draw. The Mac means it (Sill sends no "hidden", only the shape), so the pointer goes, but
            // not under a moving mouse: an arrow of our own until it rests, or the pointer could not be found again
            defaultArrow();
            root.postDelayed(hideBlank, BLANK_HIDE_MS);
            placeCursor(client.pointerX(), client.pointerY());
            return;
        }
        // Sizes are Mac points, as many pixels each as the client asked the Mac window for
        float density = client.pointScale();
        int w = Math.round(width * density), h = Math.round(height * density);
        cursor.setImageBitmap(shape);
        cursor.setScaleType(ImageView.ScaleType.FIT_XY);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) cursor.getLayoutParams();
        p.width = w;
        p.height = h;
        cursor.setLayoutParams(p);
        hotX = hx * density;
        hotY = hy * density;
        show(true, "the Mac sent a shape");
        placeCursor(client.pointerX(), client.pointerY());
    }

    /**
     * The pointer moved: always shown, where the user's mouse (or the Mac's own) put it. Nothing the Mac reports
     * hides it (MacClient, kind 26); only an empty cursor of the Mac's does, once the pointer rests (hideBlank).
     */
    @Override public void pointer(float x, float y) {
        // A locked Mac sends no cursor shape (Sill's LockPolicy): an arrow of our own, so the pointer can be found
        if (cursor.getDrawable() == null) defaultArrow();
        moves++;
        show(true, "the pointer moved");
        if (blankCursor) {
            root.removeCallbacks(hideBlank);
            root.postDelayed(hideBlank, BLANK_HIDE_MS);
        }
        placeCursor(x, y);
    }

    /** A Mac-like arrow (black, white edge), hot spot at its tip, until the Mac sends its own shape. */
    private void defaultArrow() {
        float density = client.pointScale();
        int w = Math.round(17 * density), h = Math.round(25 * density);
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        float u = density;
        android.graphics.Path arrow = new android.graphics.Path();
        arrow.moveTo(1.5f * u, 1.5f * u);
        arrow.lineTo(1.5f * u, 19f * u);
        arrow.lineTo(5.8f * u, 15f * u);
        arrow.lineTo(8.8f * u, 22f * u);
        arrow.lineTo(11.6f * u, 20.8f * u);
        arrow.lineTo(8.7f * u, 14f * u);
        arrow.lineTo(14.5f * u, 14f * u);
        arrow.close();
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        paint.setStrokeWidth(2f * u);
        paint.setColor(Color.WHITE);
        c.drawPath(arrow, paint);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        c.drawPath(arrow, paint);
        cursor.setImageBitmap(b);
        cursor.setScaleType(ImageView.ScaleType.FIT_XY);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) cursor.getLayoutParams();
        p.width = w;
        p.height = h;
        cursor.setLayoutParams(p);
        hotX = 1.5f * u;
        hotY = 1.5f * u;
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
