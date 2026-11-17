package com.termux.x11;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;

import java.util.List;

/**
 * The dock at the bottom of the external display: the switchboard's short strip (Switchboard.strip), what is on the
 * screen and the one before it, then the favourites, then "All", which opens the whole of it. It never takes the
 * keyboard focus.
 *
 * It is called the same way over everything: the pointer is at the bottom edge and is pushed further down (EdgePush).
 * Who knows about the push differs.
 *
 * Over an Android app whose pointer is XDesk's (the phone's touchpad, a mouse grabbed for the app) the injector
 * reports what is pushed beyond the edge (pushed). The dock then slides up; it is worked by Android's own pointer,
 * which is what that pointer is, and goes again when the pointer leaves it. An app in touch mode gets no hover events
 * and the input method can cover the dock's edge, so where the pointer is comes from the phone as well (placed).
 *
 * Over an X desktop or a Mac there is no Android pointer on this display at all (the phone's mouse is captured and
 * sent on, the content draws its pointer). There the dock is "driven": DockPointer opens it (openDriven) by the same
 * rule, a receiver's placed pointer resting at the edge opens it (restDriven), and moves and clicks a pointer the
 * dock draws itself, until that pointer leaves upwards, something is opened, or closeDriven.
 *
 * The one case left where resting at the edge opens it: a pointer XDesk does not move. That is a mouse the phone
 * handles itself over an Android app (the keys and mouse are "on the phone": only its hover over a strip a few pixels
 * high is heard, never how far it was pushed), and a pointer placed from a receiver or a browser (it stops at the
 * edge of its own picture). Nothing beyond the edge can be known there. Over X and a Mac that rest opens the dock
 * driven (restDriven), its own arrow under the content's pointer; over an Android app it opens by atBottom's dwell.
 */
final class ExternalDock implements AppSession.EdgeListener {
    private static final String TAG = "XDesk";
    private static final long DWELL_MS = 250, LEAVE_MS = 450, SLIDE_MS = 180;
    /** No hover heard at the edge: the pointer's place is the only sign, and over the input method it has far to go to the dock. */
    private static final long EDGE_DWELL_MS = 350, LONG_WAY_MS = 1800;
    private static final float EDGE_DP = 3, ICON_DP = 54, ITEM_GAP_DP = 8, PAD_DP = 10, ROOM_BELOW_DP = 10, RADIUS_DP = 24;
    private static final float HOVER_ZOOM = 1.16f, WIDTH_USED = 0.9f;
    private static final long ZOOM_MS = 110;
    private static final int MIN_EDGE_PX = 4;
    private static final int PANEL = 0xEB1B1E24, EDGE = 0x33FFFFFF, BUBBLE = 0xF02A2E36, DOT = 0xFFFFFFFF;
    /** The badge's ring is drawn in the dock's surface colour: PANEL made opaque, or the ring would show the icon through. */
    private static final int PANEL_OPAQUE = 0xFF1B1E24;

    private final Context context;
    private final Runnable shown;
    private final ImeProbe ime;
    private int lift;
    private boolean atEdge;
    private volatile boolean showing;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int displayId = Display.INVALID_DISPLAY;
    private Context dc;
    private WindowManager windows;
    private View strip;
    private WindowManager.LayoutParams stripParams;
    /** The display's fastest mode (fastestMode), 0 without one; asked for only while the pointer is in use. */
    private int fastMode;
    private boolean fast;
    private volatile long pointerUsedAt;
    private volatile boolean fastWanted;
    private Panel panel;
    private boolean closing;
    /** Driven: opened by DockPointer, with the dock's own pointer (arrow) at px, py of a screen width by height. */
    private boolean driven;
    private Display display;
    private CursorOverlay arrow;
    private float px, py;
    private int width, height;

    private final Runnable open = () -> open(0);
    /** The push that calls the dock over an Android app, from what the injector reports. */
    private final EdgePush rule = new EdgePush();
    private final Runnable close = this::close;
    private final Runnable openFromEdge = this::openFromEdge;
    /** Where the receiver's pointer rests across the screen, for the dock's arrow when its rest opens the dock. */
    private float restX;
    private final Runnable openDrivenFromRest = () -> {
        if (panel == null && windows != null) openDriven(restX);
    };

    interface ImeProbe {
        /** Where the input method begins on this display, -1 while it is not showing. May take a while. */
        int top(int display);
    }

    /** @param shown after the dock appears, for what must stay above it */
    ExternalDock(Context c, Runnable shown, ImeProbe ime) {
        context = c.getApplicationContext();
        this.shown = shown;
        this.ime = ime;
    }

    /**
     * The pointer's place at the edge, known from the phone (the strip may be under the input method and hear
     * nothing). A pointer that can be pushed opens the dock by the push alone (pushed); for one that is placed
     * (a receiver's, a browser's) resting here is all there is.
     */
    @Override public void atEdge(int edge, boolean at, boolean pushable) {
        if (edge != EdgePush.BOTTOM) return;  // the top and right edges are the notification centre's corner
        atEdge = at;
        if (!at) {
            rule.forget();
            stopDwell();
            dwellHeld = false;
        }
        if (windows == null) return;
        if (at) {
            ui.removeCallbacks(close);
            // Over an Android app the pointer cannot go past the edge, and the injector's pushes came too seldom
            // for a push's bar to show (17 November): there resting at the edge opens it, by time, with the bar
            // growing as it does. A placed pointer (a receiver's, a browser's) the same, sooner, as before
            if (panel == null && !dwellHeld) startDwell(pushable ? ANDROID_DWELL_MS : EDGE_DWELL_MS);
        } else {
            ui.removeCallbacks(openFromEdge);
            if (panel != null && lift > 0) later(close, LONG_WAY_MS);
        }
    }

    /** Resting at the bottom edge over an Android app: how long until the dock opens. */
    private static final long ANDROID_DWELL_MS = 500, DWELL_FRAME_MS = 16;
    private long dwellStart, dwellMs;
    /** A button was held at the edge (a drag): no rest counts until the pointer leaves the edge. */
    private boolean dwellHeld;
    private final Runnable dwellTick = new Runnable() {
        @Override public void run() {
            if (dwellStart == 0) return;
            if (panel != null || windows == null || !atEdge) {
                stopDwell();
                return;
            }
            float f = (float) (android.os.SystemClock.uptimeMillis() - dwellStart) / dwellMs;
            if (f >= 1) {
                stopDwell();
                openFromEdge();
                return;
            }
            pushProgress(f);
            ui.postDelayed(this, DWELL_FRAME_MS);
        }
    };

    private void startDwell(long ms) {
        if (dwellStart != 0) return;
        dwellStart = android.os.SystemClock.uptimeMillis();
        dwellMs = ms;
        ui.removeCallbacks(dwellTick);
        ui.post(dwellTick);
    }

    private void stopDwell() {
        dwellStart = 0;
        ui.removeCallbacks(dwellTick);
        pushProgress(0);
    }

    /**
     * Over an Android app: a move the injector was asked for with the pointer at the bottom edge already. The same
     * rule as over X and a Mac, with a single window's thresholds (an app has no panel of its own to tell apart).
     * With the input method up the dock opens above it, as from the edge before.
     */
    @Override public void pushed(int edge, int dx, int dy, boolean held) {
        if (edge != EdgePush.BOTTOM) {
            rule.forget();
            return;
        }
        // The rest at the edge opens it (atEdge, dwellTick); a push only keeps it there. A button held is a drag
        // reaching the edge: nothing opens until the pointer has left the edge
        if (held) {
            dwellHeld = true;
            stopDwell();
            return;
        }
        if (windows != null && panel == null && atEdge && !dwellHeld) startDwell(ANDROID_DWELL_MS);
    }

    /**
     * How far a push at the bottom edge is towards opening the dock (EdgePush.progress), shown as a bar growing from
     * the middle of the edge, so a push is seen to count and how much is left. 0 hides it; it also goes by itself
     * when no push comes for a while.
     */
    void pushProgress(float f) {
        if (strip instanceof PushBar) ((PushBar) strip).set(f);
    }

    /** The edge strip, drawing the push's progress (pushProgress). */
    private final class PushBar extends View {
        private static final long FADE_MS = 300;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private final Runnable fade = () -> set(0);

        PushBar(Context c) {
            super(c);
            paint.setColor(0xccffffff);
        }

        void set(float f) {
            removeCallbacks(fade);
            if (f > 0) postDelayed(fade, FADE_MS);
            if (f == progress) return;
            progress = f;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            if (progress <= 0) return;
            float full = Math.min(getWidth() * 0.4f, dp(240)), w = Math.max(getHeight(), full * progress);
            float x = (getWidth() - w) / 2f, h = getHeight();
            c.drawRoundRect(x, 0, x + w, h, h / 2f, h / 2f, paint);
        }
    }

    @Override public boolean wantsPlace() {
        return showing;
    }

    @Override public void placed(int x, int y) {
        Panel p = panel;
        if (p != null && !closing) p.placed(x, y);
    }

    private void openFromEdge() {
        if (panel != null || windows == null) return;
        final int asked = displayId;
        new Thread(() -> {
            final int top = ime.top(asked);
            ui.post(() -> {
                if (asked != displayId || panel != null || windows == null) return;
                int height = windows.getCurrentWindowMetrics().getBounds().height();
                open(top > 0 && top < height ? height - top : 0);
            });
        }, "xdesk-dock-ime").start();
    }

    // --- driven by XDesk's own pointer (over X, a Mac) ---

    /** On a display, so openDriven can work. */
    boolean attached() {
        return windows != null;
    }

    /** Open with its own pointer, which takes the moves and clicks. */
    boolean driven() {
        return driven && panel != null && !closing;
    }

    /**
     * @param x where the content's pointer is across the screen, 0 to 1: the dock's pointer starts under it
     * @return false when it did not open: nothing to show, no window, or it is open (or still going) already
     */
    boolean openDriven(float x) {
        if (windows == null || display == null || panel != null) return false;
        open(0);
        if (panel == null) return false;
        android.graphics.Rect bounds = windows.getCurrentWindowMetrics().getBounds();
        width = bounds.width();
        height = bounds.height();
        px = Math.max(0f, Math.min(1f, x)) * (width - 1);
        // Among the icons, not under them at the edge where only the arrow's tip would show
        py = height - 1 - dp(ROOM_BELOW_DP + PAD_DP + ICON_DP * 0.55f);
        driven = true;
        if (arrow == null) {
            arrow = new CursorOverlay(context);
            arrow.attach(display);
        }
        arrow.moveTo(Math.round(px), Math.round(py));
        arrow.raise();  // the panel was added after an arrow kept from an earlier opening: the arrow over it
        return true;
    }

    /** The pointer moves; up out of the dock closes it. Beside the bar it stays: the way to an icon often starts there. */
    void moveBy(int dx, int dy) {
        Panel p = panel;
        if (!driven() || p == null) return;
        px = Math.max(0f, Math.min(width - 1, px + dx));
        py = Math.max(0f, Math.min(height - 1, py + dy));
        int x = Math.round(px), y = Math.round(py);
        if (p.above(y)) {
            close();
            return;
        }
        arrow.moveTo(x, y);
        p.point(x, y);
    }

    /**
     * A click of the dock's pointer: anywhere on the bar's row it opens the nearest item (and the dock goes),
     * as the Android pointer's click in a gap does; anywhere else the dock just goes.
     */
    void clickDriven() {
        Panel p = panel;
        if (!driven() || p == null) return;
        View item = p.itemFor(Math.round(px), Math.round(py));
        if (item != null) item.performClick();
        else close();
    }

    /**
     * The secondary button of the dock's pointer: on an icon its card opens on the phone (ItemCard) and the dock stays,
     * saying so; beside the icons nothing.
     */
    void secondaryDriven() {
        Panel p = panel;
        if (!driven() || p == null) return;
        View item = p.itemAt(Math.round(px), Math.round(py));
        if (item != null) item.performContextClick();
    }

    /** No-op unless driven: over Android apps the dock closes by where Android's pointer is. */
    void closeDriven() {
        if (driven()) close();
    }

    /**
     * The receiver's placed pointer rests on the bottom edge over X or a Mac: after the same dwell as atBottom's
     * rest over an Android app (EDGE_DWELL_MS), the dock opens with its own pointer under the content's (openDriven).
     * @param x the pointer's place across the screen, 0 to 1: the dock's arrow starts under it
     */
    void restDriven(boolean at, float x) {
        atEdge = at;
        if (windows == null) return;
        if (at) {
            restX = x;
            ui.removeCallbacks(close);
            if (panel == null) later(openDrivenFromRest, EDGE_DWELL_MS);
        } else {
            ui.removeCallbacks(openDrivenFromRest);
            if (panel != null) later(close, LEAVE_MS);
        }
    }

    /**
     * A receiver placed its pointer here (0 to 1 of the screen) while the dock has the pointer: the dock's arrow
     * follows it, and up out of the dock closes it, as the relative moves do (moveBy). The content's pointer is not
     * moved (DISPLAY.md §7: it stays where it was).
     */
    void placeDriven(float x, float y) {
        Panel p = panel;
        if (!driven() || p == null) return;
        px = Math.max(0f, Math.min(width - 1, x * (width - 1)));
        py = Math.max(0f, Math.min(height - 1, y * (height - 1)));
        if (p.above(Math.round(py))) {
            close();
            return;
        }
        arrow.moveTo(Math.round(px), Math.round(py));
        p.point(Math.round(px), Math.round(py));
    }

    /** The keys and mouse went to the phone (Ctrl+Alt+M, its button): nothing moves the Dock any more, so it goes. */
    void closeForPhone() {
        close();
    }

    private void endDriven() {
        driven = false;
        if (arrow != null) arrow.hide();
    }

    /** Pixels per dp on the display: the shell's factor (Sizing.shell, §4.1), not the display's density override. */
    private float density = 1f;

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Null while there are no Android apps on an external display. */
    void setDisplay(Display d) {
        int id = d != null ? d.getDisplayId() : Display.INVALID_DISPLAY;
        if (id == displayId) return;
        detach();
        displayId = id;
        display = d;
        if (d == null) return;
        dc = context.createDisplayContext(d);
        density = Sizing.shell(context, d).density;
        windows = dc.getSystemService(WindowManager.class);
        strip = new PushBar(dc);
        strip.setOnHoverListener((v, e) -> {
            int action = e.getActionMasked();
            // What XDesk injects comes from no input device; that pointer opens the dock by its push (pushed). A
            // real mouse here is one the phone handles itself: its resting is the only sign there is
            android.view.InputDevice from = e.getDevice();
            boolean own = from == null || from.isVirtual();
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                ui.removeCallbacks(open);
                if (panel != null) later(close, LEAVE_MS);
            } else if (panel == null) {
                if (!own && !ui.hasCallbacks(open)) ui.postDelayed(open, DWELL_MS);
            } else {
                ui.removeCallbacks(close);
            }
            return true;
        });
        WindowManager.LayoutParams p = params(WindowManager.LayoutParams.MATCH_PARENT, Math.max(MIN_EDGE_PX, dp(EDGE_DP)), "XDesk dock edge");
        fastMode = fastestMode(d);
        fast = false;
        stripParams = p;
        try {
            windows.addView(strip, p);
        } catch (RuntimeException e) {
            Log.w(TAG, "no dock edge", e);
            strip = null;
        }
    }

    /** How long after the pointer's last move or scroll the fast mode is still asked for. */
    private static final long FAST_HOLD_MS = 2000;

    /**
     * The pointer moved or scrolled over the external display (any thread). Android starts a monitor in the mode its
     * EDID prefers, often 60 Hz on one that can do 120 or 144: while the pointer is in use the dock's edge, there as
     * long as Android apps are on that display, asks for the fastest mode, so the arrow and what it drags or scrolls
     * move smoothly. FAST_HOLD_MS after the last use it asks for nothing again: a video, reading, typing get the mode
     * the system and the app choose, and the phone does not draw and send frames at the higher rate for nothing.
     */
    void pointerUsed() {
        pointerUsedAt = android.os.SystemClock.uptimeMillis();
        if (fastWanted) return;
        fastWanted = true;
        ui.post(() -> setFast(true));
    }

    private final Runnable maybeSlow = new Runnable() {
        @Override public void run() {
            long idle = android.os.SystemClock.uptimeMillis() - pointerUsedAt;
            if (idle < FAST_HOLD_MS) {
                ui.postDelayed(this, FAST_HOLD_MS - idle);
                return;
            }
            fastWanted = false;
            setFast(false);
        }
    };

    private void setFast(boolean on) {
        if (on) {
            ui.removeCallbacks(maybeSlow);
            ui.postDelayed(maybeSlow, FAST_HOLD_MS);
        }
        if (on == fast || strip == null || stripParams == null || display == null) return;
        // Worked out again each time: the display may have changed its resolution since, and the old fastest mode
        // would switch it back
        if (on && (fastMode = fastestMode(display)) == 0) return;
        fast = on;
        stripParams.preferredDisplayModeId = on ? fastMode : 0;
        try {
            windows.updateViewLayout(strip, stripParams);
        } catch (RuntimeException e) {
            Log.w(TAG, "dock edge: no refresh change", e);
        }
    }

    /** The external display's fastest mode at the resolution it has, 0 when it has no faster one. */
    static int fastestMode(Display d) {
        Display.Mode now = d.getMode(), best = null;
        for (Display.Mode m : d.getSupportedModes())
            if (m.getPhysicalWidth() == now.getPhysicalWidth() && m.getPhysicalHeight() == now.getPhysicalHeight()
                    && (best == null || m.getRefreshRate() > best.getRefreshRate()))
                best = m;
        if (best == null || best.getRefreshRate() <= now.getRefreshRate() + 1) return 0;
        Log.i(TAG, "external display " + d.getDisplayId() + ": " + Math.round(now.getRefreshRate()) + " Hz, asking for "
            + Math.round(best.getRefreshRate()) + " Hz while the pointer is in use");
        return best.getModeId();
    }

    private void later(Runnable r, long ms) {
        ui.removeCallbacks(r);
        ui.postDelayed(r, ms);
    }

    private WindowManager.LayoutParams params(int w, int h, String title) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setFitInsetsTypes(0);
        p.windowAnimations = 0;
        p.setTitle(title);
        return p;
    }

    private void open(int above) {
        if (panel != null || windows == null) return;
        Switchboard board = Switchboard.get(context);
        if (board.host() == null) return;  // the phone's window is gone: nothing could be opened
        Panel fresh = new Panel(board);
        if (fresh.empty()) return;
        try {
            WindowManager.LayoutParams p = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, "XDesk dock");
            p.y = above;
            windows.addView(fresh, p);
        } catch (RuntimeException e) {
            Log.w(TAG, "no dock", e);
            return;
        }
        panel = fresh;
        showing = true;
        lift = above;
        closing = false;
        fresh.slideIn();
        shown.run();
    }

    private void close() {
        final Panel p = panel;
        if (p == null || closing) return;
        closing = true;
        endDriven();
        p.slideOut(() -> {
            remove(p);
            if (panel == p) {
                panel = null;
                showing = false;
            }
            closing = false;
        });
    }

    private void remove(View v) {
        try {
            if (windows != null) windows.removeViewImmediate(v);
        } catch (RuntimeException ignored) { }
    }

    private void detach() {
        ui.removeCallbacksAndMessages(null);
        rule.forget();
        if (panel != null) remove(panel);
        panel = null;
        showing = false;
        closing = false;
        driven = false;
        if (arrow != null) arrow.remove();
        arrow = null;
        if (strip != null) remove(strip);
        strip = null;
        stripParams = null;
        fast = fastWanted = false;
        fastMode = 0;
        windows = null;
    }

    /** Space for the name above it, the rounded bar, and room below that still counts as being on the dock. */
    private final class Panel extends LinearLayout {
        /** The icons shrink to this much of ICON_DP at most, the gaps with them, when not all of the strip fits. */
        private static final float MIN_SCALE = 0.7f;
        private final LinearLayout bar;
        /** The name bubble's fixed box: the bar's width at most, one line high, whatever the text (see the ctor). */
        private final FrameLayout bubble;
        private final TextView name;
        /** The icon size as a part of ICON_DP (the gaps shrink the same): worked out in fill. */
        private float scale = 1f;
        private int items;
        private View over;
        private boolean visited;
        /** The click's start, in the Panel's coordinates, for a click between the boxes (the bar itself is clicked). */
        private float downX, downY;
        private final int[] place = new int[2];

        Panel(Switchboard board) {
            super(dc);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            // The zoom and a device badge draw past the icons' bounds: no cutting at any of these levels
            setClipChildren(false);
            setClipToPadding(false);
            setPadding(dp(16), dp(4), dp(16), dp(ROOM_BELOW_DP));
            // The bubble's box is a fixed size (its width becomes the bar's, at most 220dp, in onLayout): a new
            // label must never re-measure the Panel or the window, which is what shifted the bar. The bubble itself
            // is the wrap-content text inside it, centred and ellipsized; the box around it stays invisible
            bubble = new FrameLayout(dc);
            name = Style.label(dc, "", Style.TEXT, 13);
            // The shell's text factor (b·u, no font scale): sp from the display context would follow the density override
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.round(13 * density));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable back = new GradientDrawable();
            back.setColor(BUBBLE);
            back.setCornerRadius(dp(8));
            name.setBackground(back);
            bubble.addView(name, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            bubble.setAlpha(0f);
            LayoutParams nameAt = new LayoutParams(0, Math.round(13 * density * 1.4f) + dp(8));
            nameAt.bottomMargin = dp(8);
            addView(bubble, nameAt);
            bar = new LinearLayout(dc);
            bar.setGravity(Gravity.BOTTOM);
            bar.setClipChildren(false);
            bar.setClipToPadding(false);
            bar.setPadding(dp(PAD_DP), dp(PAD_DP), dp(PAD_DP), dp(4));
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(PANEL);
            surface.setCornerRadius(dp(RADIUS_DP));
            surface.setStroke(Math.max(1, dp(0.5f)), EDGE);
            bar.setBackground(surface);
            // A click between the boxes (their margins, the divider) lands on the bar itself: it opens the nearest box
            bar.setClickable(true);
            bar.setFocusable(false);
            bar.setOnClickListener(v -> {
                getLocationOnScreen(place);
                View near = itemFor(place[0] + Math.round(downX), place[1] + Math.round(downY));
                if (near != null) near.performClick();
            });
            addView(bar, new LayoutParams(-2, -2));
            fill(board);
        }

        boolean empty() {
            return items == 0;
        }

        /**
         * The strip as the model has it, in its order, the last tile ("All") always. The icons (and their gaps)
         * shrink so that all of it fits, to 70% at most; if even that is not enough, the end of the favourites
         * drops and a "+N" tile before "All" says how many.
         */
        private void fill(Switchboard board) {
            List<Switchboard.Item> all = board.strip(true);
            int curPrev = 0;
            boolean hasFav = false;
            for (Switchboard.Item it : all) {
                if (it.strip == Switchboard.STRIP_FAVOURITE) hasFav = true;
                else if (it.strip != Switchboard.STRIP_ALL) curPrev++;
            }
            float avail = dc.getResources().getDisplayMetrics().widthPixels * WIDTH_USED - dp(2 * PAD_DP + 32);
            float each = dp(ICON_DP + ITEM_GAP_DP);  // one tile, icon and gaps, at the full size
            float divExtra = dp(ITEM_GAP_DP + 4);    // the divider's margins at the full size (its hairline stays)
            float hair = Math.max(1, dp(1));
            boolean divider = hasFav && curPrev > 0;
            // The scale where all of it fits; 1 when it does at the full size
            float s = divider ? (avail - hair) / (each * all.size() + divExtra) : avail / (each * all.size());
            int kept = all.size() - 1;  // everything before "All"
            int more = 0;
            if (s > 1f) {
                s = 1f;
            } else if (s < MIN_SCALE) {
                // Not even at 70%: as many as fit minus one (the "+N" tile takes a slot too)
                s = MIN_SCALE;
                float e = dp((ICON_DP + ITEM_GAP_DP) * s);
                int fit = Math.max(1, (int) ((avail - (divider ? hair + dp((ITEM_GAP_DP + 4) * s) : 0)) / e));
                kept = Math.max(0, fit - 2);
                // The divider only shows with a favourite after it; where none is shown its room is a tile's,
                // but where its room frees one, the divider is drawn after all and the smaller count is right
                if (divider && kept <= curPrev) {
                    int without = Math.max(0, (int) (avail / e) - 2);
                    kept = without > curPrev ? kept : without;
                }
                if (kept > all.size() - 1) kept = all.size() - 1;
                more = all.size() - 1 - kept;
            }
            scale = s;
            boolean left = false, line = false;
            for (int i = 0; i < kept; i++) {
                Switchboard.Item it = all.get(i);
                if (it.strip != Switchboard.STRIP_FAVOURITE) left = true;
                else if (left && !line) {
                    line = true;
                    bar.addView(divider());
                }
                bar.addView(item(board, it));
            }
            if (more > 0) bar.addView(moreTile(board, more));
            bar.addView(item(board, all.get(all.size() - 1)));
        }

        /** The hairline between what is on the screen and the favourites; its margins shrink with the icons. */
        private View divider() {
            View divider = new View(dc);
            divider.setBackgroundColor(EDGE);
            LayoutParams at = new LayoutParams(Math.max(1, dp(1)), dp(ICON_DP * 0.72f * scale));
            at.leftMargin = at.rightMargin = dp((ITEM_GAP_DP / 2 + 2) * scale);
            at.bottomMargin = dp(12 * scale);
            divider.setLayoutParams(at);
            return divider;
        }

        /** The "+N" tile before "All": the favourites that did not fit are in the Launchpad's favourites tab. */
        private View moreTile(final Switchboard board, int n) {
            Switchboard.Item it = new Switchboard.Item();
            it.kind = Switchboard.ITEM;
            it.key = "more";  // no real item: only the tile's own listeners below ever see it
            it.label = "+" + n;  // the tile, drawn whole, unlike a letter tile's first letter
            it.title = dc.getString(R.string.xd_dock_more, n);  // the name bubble
            ImageView tile = new ImageView(dc);
            tile.setImageDrawable(Style.wordIcon(dc, it.label, density));
            View box = item(board, it, tile);
            box.setOnClickListener(v -> {
                board.setTab(Switchboard.TAB_FAVOURITES);
                board.open(ExternalLauncher.SWITCHER);
                close();
            });
            // No card for it; the press is just taken, or it would count as a click
            box.setOnContextClickListener(v -> true);
            return box;
        }

        private View item(final Switchboard board, Switchboard.Item it) {
            return item(board, it, ExternalLauncher.icon(dc, it, PANEL_OPAQUE, false, density));
        }

        /** {@code icon} made by the caller for a tile of the dock's own (the "+N" tile), instead of the item's. */
        private View item(final Switchboard board, Switchboard.Item it, final View icon) {
            final String key = it.key;
            boolean running = it.strip == Switchboard.STRIP_CURRENT;
            items++;
            LinearLayout box = new LinearLayout(dc);
            box.setOrientation(VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setClickable(true);
            box.setFocusable(false);
            box.setClipChildren(false);
            box.setClipToPadding(false);
            box.setTag(icon);
            box.addView(icon, new LayoutParams(dp(ICON_DP * scale), dp(ICON_DP * scale)));
            // The zoom goes around the icon's middle, bottom-fixed, whatever the scale: it never changes the layout
            icon.setPivotX(dp(ICON_DP * scale) / 2f);
            icon.setPivotY(dp(ICON_DP * scale));
            View dot = new View(dc);
            GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            round.setColor(DOT);
            dot.setBackground(round);
            dot.setVisibility(running ? VISIBLE : INVISIBLE);
            LayoutParams dotAt = new LayoutParams(dp(4), dp(4));
            dotAt.topMargin = dp(4);
            dotAt.bottomMargin = dp(2);
            box.addView(dot, dotAt);
            // The name as the lists have it: a whole desktop says whose, not its session's name
            box.setContentDescription(it.title);
            // No hover listener here: dispatchHoverEvent picks the item from the pointer's x, so the row has no gaps
            box.setOnClickListener(v -> {
                board.open(key);
                close();
            });
            // The secondary button (Android's own mouse, or secondaryDriven): the item's card, which is on the phone.
            // Taken also when there is none (the Launchpad's tile), or it would count as a click
            box.setOnContextClickListener(v -> {
                if (board.card(key)) showName(dc.getString(R.string.xd_card_on_phone), v);
                return true;
            });
            LayoutParams at = new LayoutParams(-2, -2);
            at.leftMargin = at.rightMargin = dp(ITEM_GAP_DP * scale / 2);
            box.setLayoutParams(at);
            return box;
        }

        private void setOver(View v) {
            if (v == over) return;
            if (over != null) ((View) over.getTag()).animate().scaleX(1f).scaleY(1f).setDuration(ZOOM_MS).start();
            over = v;
            if (v == null) {
                bubble.animate().alpha(0f).setDuration(ZOOM_MS).start();
                return;
            }
            ((View) v.getTag()).animate().scaleX(HOVER_ZOOM).scaleY(HOVER_ZOOM).setDuration(ZOOM_MS).start();
            CharSequence label = v.getContentDescription();
            showName(label != null ? label.toString() : "", v);
        }

        private boolean holds(View v, int x, int y) {
            v.getLocationOnScreen(place);
            return x >= place[0] && x < place[0] + v.getWidth() && y >= place[1] && y < place[1] + v.getHeight();
        }

        /**
         * The item whose part of the bar's row holds this place of the screen: from the midpoint to its neighbour on
         * one side to the midpoint to the next on the other, and from the bar's edges at the ends, so the row over
         * the bar has no dead gaps (a box holds half of each margin beside it, the divider's place included). Null
         * above or below the row, or beside the bar.
         */
        View itemFor(int x, int y) {
            bar.getLocationOnScreen(place);
            int left = place[0], right = left + bar.getWidth();
            if (y < place[1] || y >= place[1] + bar.getHeight() || x < left || x >= right) return null;
            View prev = null;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getTag() == null) continue;  // the divider
                if (prev == null) {
                    if (x < left + child.getLeft()) return child;  // before the first box: it is the nearest
                } else if (x < left + (prev.getRight() + child.getLeft()) / 2) {
                    return prev;
                }
                prev = child;
            }
            return prev;  // from the last midpoint to the bar's right edge: the last box
        }

        /** The pointer's place when no hover events tell it. */
        void placed(int x, int y) {
            if (!holds(this, x, y)) {
                setOver(null);
                if (!atEdge && !ui.hasCallbacks(close)) later(close, lift > 0 && !visited ? LONG_WAY_MS : LEAVE_MS);
                return;
            }
            visited = true;
            ui.removeCallbacks(close);
            setOver(itemFor(x, y));
        }

        /** The item's box at this place of the screen, null beside them: for the secondary button, which takes none in a gap. */
        View itemAt(int x, int y) {
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getTag() != null && holds(child, x, y)) return child;
            }
            return null;
        }

        /** The dock's own pointer (driven) is here. */
        void point(int x, int y) {
            setOver(itemFor(x, y));
        }

        /** Higher on the screen than the dock with the room for its names. Not before it is laid out. */
        boolean above(int y) {
            if (getHeight() == 0) return false;
            getLocationOnScreen(place);
            return y < place[1];
        }

        private void showName(String label, View over) {
            name.setText(label);
            name.post(() -> {
                float centre = bar.getLeft() + over.getLeft() + over.getWidth() / 2f;
                float half = name.getWidth() / 2f;  // the visible bubble: the text, not its fixed box
                float clamped = Math.max(half, Math.min(getWidth() - half, centre));
                bubble.setTranslationX(clamped - getWidth() / 2f);
                bubble.animate().alpha(1f).setDuration(ZOOM_MS).start();
            });
        }

        // The hovered item is picked from the pointer's x here, for the Android pointer and (through point/placed)
        // for the driven one: a box holds half of each margin beside it, so crossing between two boxes changes
        // nothing at the moment the pointer is over the divider between their extents... see itemFor
        @Override public boolean dispatchHoverEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                setOver(null);  // left the dock: nothing is hovered any more, whatever the strip's listener decides
                later(close, LEAVE_MS);
                return super.dispatchHoverEvent(e);
            }
            ui.removeCallbacks(close);
            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
                getLocationOnScreen(place);
                setOver(itemFor(place[0] + (int) e.getX(), place[1] + (int) e.getY()));
            }
            return super.dispatchHoverEvent(e);
        }

        // The click's start, for the bar's own click: where the pointer was when it went down between the boxes
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
            }
            return super.dispatchTouchEvent(e);
        }

        // The bubble's box becomes as wide as the bar (at most 220dp): fixed for good then, so a new label never
        // re-measures the Panel or the window (which is what shifted the bar when the name changed)
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            LayoutParams at = (LayoutParams) bubble.getLayoutParams();
            int w = Math.min(dp(220), bar.getWidth());
            if (at.width != w) {
                at.width = w;
                bubble.requestLayout();
            }
        }

        void slideIn() {
            bar.setTranslationY(dp(ICON_DP + 30));
            setAlpha(0f);
            animate().alpha(1f).setDuration(SLIDE_MS).start();
            bar.animate().translationY(0f).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator()).start();
        }

        void slideOut(Runnable done) {
            animate().alpha(0f).setDuration(SLIDE_MS).withEndAction(done).start();
            bar.animate().translationY(dp(ICON_DP + 30)).setDuration(SLIDE_MS).start();
        }
    }
}
