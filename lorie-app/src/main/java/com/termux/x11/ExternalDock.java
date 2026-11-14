package com.termux.x11;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.termux.x11.app.R;

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
 * rule, and moves and clicks a pointer the dock draws itself, until that pointer leaves upwards, something is opened,
 * or closeDriven.
 *
 * The one case left where resting at the edge opens it: a pointer XDesk does not move. That is a mouse the phone
 * handles itself over an Android app (the keys and mouse are "on the phone": only its hover over a strip a few pixels
 * high is heard, never how far it was pushed), and a pointer placed from a receiver or a browser (it stops at the
 * edge of its own picture). Nothing beyond the edge can be known there.
 */
final class ExternalDock implements AppSession.BottomEdgeListener {
    private static final String TAG = "XDesk";
    private static final long DWELL_MS = 250, LEAVE_MS = 450, SLIDE_MS = 180;
    /** No hover heard at the edge: the pointer's place is the only sign, and over the input method it has far to go to the dock. */
    private static final long EDGE_DWELL_MS = 350, LONG_WAY_MS = 1800;
    private static final float EDGE_DP = 3, ICON_DP = 54, ITEM_GAP_DP = 8, PAD_DP = 10, ROOM_BELOW_DP = 10, RADIUS_DP = 24;
    private static final float HOVER_ZOOM = 1.16f, WIDTH_USED = 0.9f;
    private static final long ZOOM_MS = 110;
    private static final int MIN_EDGE_PX = 4;
    private static final int PANEL = 0xEB1B1E24, EDGE = 0x33FFFFFF, BUBBLE = 0xF02A2E36, DOT = 0xFFFFFFFF;

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
    @Override public void atBottom(boolean at, boolean pushable) {
        atEdge = at;
        if (!at) rule.forget();
        if (windows == null) return;
        if (at) {
            ui.removeCallbacks(close);
            if (panel == null && !pushable) later(openFromEdge, EDGE_DWELL_MS);
        } else {
            ui.removeCallbacks(openFromEdge);
            if (panel != null && lift > 0) later(close, LONG_WAY_MS);
        }
    }

    /**
     * Over an Android app: a move the injector was asked for with the pointer at the bottom edge already. The same
     * rule as over X and a Mac, with a single window's thresholds (an app has no panel of its own to tell apart).
     * With the input method up the dock opens above it, as from the edge before.
     */
    @Override public void pushed(int dx, int dy, boolean held) {
        if (windows == null || panel != null || held) {
            rule.forget();
            return;
        }
        if (rule.pushed(dx, dy, false)) openFromEdge();
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

    /** A click of the dock's pointer: on an icon it opens that (and the dock goes), anywhere else the dock just goes. */
    void clickDriven() {
        Panel p = panel;
        if (!driven() || p == null) return;
        View item = p.itemAt(Math.round(px), Math.round(py));
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

    private void endDriven() {
        driven = false;
        if (arrow != null) arrow.hide();
    }

    private int dp(float v) {
        return Style.dp(dc, v);
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
        windows = dc.getSystemService(WindowManager.class);
        strip = new View(dc);
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
        try {
            windows.addView(strip, p);
        } catch (RuntimeException e) {
            Log.w(TAG, "no dock edge", e);
            strip = null;
        }
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
        windows = null;
    }

    /** Space for the name above it, the rounded bar, and room below that still counts as being on the dock. */
    private final class Panel extends LinearLayout {
        private final LinearLayout bar;
        private final TextView name;
        private int items;
        private View over;
        private boolean visited;
        private final int[] place = new int[2];

        Panel(Switchboard board) {
            super(dc);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setClipChildren(false);
            setClipToPadding(false);
            setPadding(dp(16), dp(4), dp(16), dp(ROOM_BELOW_DP));
            name = Style.label(dc, "", Style.TEXT, 13);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setMaxWidth(dp(220));
            name.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable bubble = new GradientDrawable();
            bubble.setColor(BUBBLE);
            bubble.setCornerRadius(dp(8));
            name.setBackground(bubble);
            name.setAlpha(0f);
            LayoutParams nameAt = new LayoutParams(-2, -2);
            nameAt.bottomMargin = dp(8);
            addView(name, nameAt);
            bar = new LinearLayout(dc);
            bar.setGravity(Gravity.BOTTOM);
            bar.setClipChildren(false);
            bar.setClipToPadding(false);
            bar.setPadding(dp(PAD_DP), dp(PAD_DP), dp(PAD_DP), dp(4));
            GradientDrawable back = new GradientDrawable();
            back.setColor(PANEL);
            back.setCornerRadius(dp(RADIUS_DP));
            back.setStroke(Math.max(1, dp(0.5f)), EDGE);
            bar.setBackground(back);
            addView(bar, new LayoutParams(-2, -2));
            fill(board);
        }

        boolean empty() {
            return items == 0;
        }

        /** The strip as the model has it; as many as fit, the last tile ("All") always. */
        private void fill(Switchboard board) {
            int room = (int) (dc.getResources().getDisplayMetrics().widthPixels * WIDTH_USED - dp(2 * PAD_DP + 32));
            int each = dp(ICON_DP + ITEM_GAP_DP);
            int most = Math.max(1, room / each);
            boolean left = false, line = false;
            for (Switchboard.Item it : board.strip(true)) {
                if (it.strip == Switchboard.STRIP_ALL) {
                    bar.addView(item(board, it));
                    continue;
                }
                if (items >= most) continue;
                if (it.strip != Switchboard.STRIP_FAVOURITE) {
                    left = true;
                } else if (left && !line) {
                    line = true;
                    View divider = new View(dc);
                    divider.setBackgroundColor(EDGE);
                    LayoutParams at = new LayoutParams(Math.max(1, dp(1)), dp(ICON_DP * 0.72f));
                    at.leftMargin = at.rightMargin = dp(ITEM_GAP_DP / 2 + 2);
                    at.bottomMargin = dp(12);
                    bar.addView(divider, at);
                }
                bar.addView(item(board, it));
            }
        }

        private View item(final Switchboard board, Switchboard.Item it) {
            final String key = it.key;
            boolean running = it.strip == Switchboard.STRIP_CURRENT;
            items++;
            LinearLayout box = new LinearLayout(dc);
            box.setOrientation(VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setClickable(true);
            box.setFocusable(false);
            box.setClipChildren(false);
            final View icon = ExternalLauncher.icon(dc, key, dp(ICON_DP));
            box.setTag(icon);
            box.addView(icon, new LayoutParams(dp(ICON_DP), dp(ICON_DP)));
            icon.setPivotX(dp(ICON_DP) / 2f);
            icon.setPivotY(dp(ICON_DP));
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
            box.setOnHoverListener((v, e) -> {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_ENTER) setOver(v);
                else if (action == MotionEvent.ACTION_HOVER_EXIT && over == v) setOver(null);
                return false;
            });
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
            at.leftMargin = at.rightMargin = dp(ITEM_GAP_DP / 2);
            box.setLayoutParams(at);
            return box;
        }

        private void setOver(View v) {
            if (v == over) return;
            if (over != null) ((View) over.getTag()).animate().scaleX(1f).scaleY(1f).setDuration(ZOOM_MS).start();
            over = v;
            if (v == null) {
                name.animate().alpha(0f).setDuration(ZOOM_MS).start();
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

        /** The pointer's place when no hover events tell it. */
        void placed(int x, int y) {
            if (!holds(this, x, y)) {
                setOver(null);
                if (!atEdge && !ui.hasCallbacks(close)) later(close, lift > 0 && !visited ? LONG_WAY_MS : LEAVE_MS);
                return;
            }
            visited = true;
            ui.removeCallbacks(close);
            setOver(itemAt(x, y));
        }

        /** The icon at this place of the screen, null beside them. */
        View itemAt(int x, int y) {
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child.getTag() != null && holds(child, x, y)) return child;
            }
            return null;
        }

        /** The dock's own pointer (driven) is here. */
        void point(int x, int y) {
            setOver(itemAt(x, y));
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
                float half = name.getWidth() / 2f;
                float clamped = Math.max(half, Math.min(getWidth() - half, centre));
                name.setTranslationX(clamped - getWidth() / 2f);
                name.animate().alpha(1f).setDuration(ZOOM_MS).start();
            });
        }

        // The children's own enter and exit are handled below this: here is only whether the pointer is on the dock at all
        @Override public boolean dispatchHoverEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_EXIT) later(close, LEAVE_MS);
            else ui.removeCallbacks(close);
            return super.dispatchHoverEvent(e);
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
