package com.termux.x11;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;

import com.termux.x11.app.R;
import com.termux.x11.mac.MacClient;
import com.termux.x11.mac.MacMenuState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Mac menu's windows on the external display (MACMENU.md §3, §4): the ≡ corner button at the top-left and
 * the menu bar overlay, the same kind of overlay windows as the notification centre's, on the same display — a
 * wireless receiver's the same way. Shown only while the external screen's content is a single Mac window or
 * app (never the Mac's whole desktop, whose own menu bar is in the picture; never the car).
 *
 * Three ways to open the bar (§3), all here: the pointer pushed out through the top-left corner's 48 shell dp
 * (EdgePush, LEFT or TOP, only over a Mac — DockPointer feeds it), the ≡ button, and Ctrl+Alt+Space
 * (XDeskActivity.physicalKey calls hotkey). While the bar or the button is under the pointer over a Mac, the
 * pointer is theirs: DockPointer asks grab() on every move and routes moves and clicks here, the arrow is
 * XDesk's own, with the same NotifyGrab hand-off as the notification centre so it never traps. The phone's
 * keys reach the bar through key() (physicalKey): ←→ ↑↓ Enter → Esc (MACMENU.md §4); Ctrl+Alt+M and Esc close
 * it like the centre.
 *
 * One instance per process, as Notify is. Main thread.
 */
final class MacMenus {
    private static final String TAG = "XDeskMacMenus";
    /** MACMENU.md §3: the corner is 48 shell dp of the left edge's top and the top edge's left. */
    static final float CORNER_DP = 48;
    /** How long a fetch's answer is waited for (Sall's menuTimeoutMs without a round trip). */
    static final long MENU_WAIT_MS = 4000;
    private static final long LEAVE_MS = 1000;
    /** What the driven pointer is over. */
    private static final int SURFACE_BAR = 1, SURFACE_BUTTON = 2;

    private static MacMenus instance;

    static synchronized MacMenus get(Context c) {
        if (instance == null) instance = new MacMenus(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final EdgePush cornerRule = new EdgePush();
    /** The hover-grab's trap-avoidance, the notification centre's own rule (NotifyGrab). */
    private final NotifyGrab grab = new NotifyGrab();
    private final Set<Integer> takenKeys = new HashSet<>();
    private final Rect rect = new Rect();

    // The display the windows are on; null while there is none (nothing to show then)
    private Display display;
    private int displayId = Display.INVALID_DISPLAY;
    private Context dc;
    private WindowManager windows;
    private float density;
    private int width, height;

    /** The Mac whose window or app is the content; null when the button and the bar are not for this screen. */
    private MacClient client;
    private CornerButton button;
    private TextView hint;
    private WindowManager.LayoutParams hintParams;
    private MacMenuBar bar;
    private final Runnable barLeave = this::closeBar;

    /** The driven pointer: where the arrow is, and whose window it is over. */
    private boolean driven;
    private float px, py;
    private int over;
    private CursorOverlay arrow;
    private boolean swallowSpace;

    /** The client's menus: the bar follows the top level and its requests' answers. UI thread. */
    private final MacClient.MenuListener clientListener = (top, done) -> {
        for (MacMenuState.Done d : done)
            if (d.pressed != null && d.refusal != null) flash(d.refusal);  // a press refused: said over the picture
        MacMenuBar b = bar;
        if (b == null || b.closing()) return;
        b.setTop(top);
        for (MacMenuState.Done d : done) b.onDone(d);
    };

    private MacMenus(Context c) {
        context = c;
    }

    // --- the display (Notify's setDisplay, for the menu's windows) ---

    void setDisplay(Display d) {
        int id = d != null ? d.getDisplayId() : Display.INVALID_DISPLAY;
        if (id == displayId) {
            // The same display, but its size may have changed: the corner and the fade follow it
            if (d != null && windows != null) {
                Rect b = windows.getCurrentWindowMetrics().getBounds();
                if (b.width() != width || b.height() != height) {
                    width = b.width();
                    height = b.height();
                    Log.i(TAG, "display " + id + " is now " + width + "x" + height);
                }
            }
            return;
        }
        detach();
        displayId = id;
        display = d;
        if (d == null) return;
        dc = context.createDisplayContext(d);
        density = Sizing.shell(context, d).density;
        windows = dc.getSystemService(WindowManager.class);
        Rect b = windows.getCurrentWindowMetrics().getBounds();
        width = b.width();
        height = b.height();
        Log.i(TAG, "menu on display " + id + " (" + width + "x" + height + ", shell " + density + ")");
        if (client != null) showButton();
    }

    /**
     * The content on the external screen changed: the menu is for a single Mac window or app only. The same Mac
     * again is nothing; another one (or none) swaps the listener, the button and the bar.
     */
    void update(MacClient mac) {
        if (mac == client) return;
        if (client != null) client.removeMenuListener(clientListener);
        client = mac;
        detachWindows();
        if (mac != null) {
            mac.addMenuListener(clientListener);
            mac.menuSubscribe();  // the bar wants the top level pushed as it changes
            if (windows != null) showButton();
        }
    }

    private void showButton() {
        if (button != null || windows == null || client == null) return;
        button = new CornerButton(dc, density, CornerButton.LEFT, this::drawButton, context.getString(R.string.xd_mac_menu),
            this::toggle);
        try {
            windows.addView(button.view(), button.params());
        } catch (RuntimeException e) {
            Log.w(TAG, "no menu button", e);
            button = null;
        }
    }

    private void hideButton() {
        CornerButton b = button;
        button = null;
        if (b != null) {
            try {
                windows.removeViewImmediate(b.view());
            } catch (RuntimeException ignored) { }
        }
    }

    /** The ≡: three lines, the bell's stroke (MACMENU.md §3). */
    private void drawButton(android.graphics.Canvas c, float w, float h, float s, android.graphics.Paint paint) {
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.6f * s));
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        c.drawLine(w * 0.16f, h * 0.3f, w * 0.84f, h * 0.3f, paint);
        c.drawLine(w * 0.16f, h * 0.5f, w * 0.84f, h * 0.5f, paint);
        c.drawLine(w * 0.16f, h * 0.7f, w * 0.84f, h * 0.7f, paint);
        paint.setStyle(android.graphics.Paint.Style.FILL);
    }

    /** The button stays above the bar: it is the switch that closes it, as the bell is the centre's. */
    private void raiseButton() {
        if (button == null || windows == null) return;
        try {
            windows.removeViewImmediate(button.view());
            windows.addView(button.view(), button.params());
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot raise the menu button", e);
        }
    }

    private void detachWindows() {
        endDriven();
        ui.removeCallbacks(barLeave);
        cornerRule.forget();
        closeBar();
        hideHint();
        hideButton();
    }

    private void detach() {
        detachWindows();
        windows = null;
        dc = null;
    }

    private void endDriven() {
        driven = false;
        over = 0;
        if (arrow != null) arrow.hide();
        hideHint();
    }

    /** Whether the menu's windows are on a display: the corner rule needs them (Notify's attached()). */
    boolean attached() {
        return windows != null;
    }

    // --- the three ways to open the bar (MACMENU.md §3) ---

    /** The corner the pointer is pushed out through: LEFT on the left edge's top 48 dp, TOP on the top edge's left. */
    int cornerEdge(float fx, float fy) {
        if (width <= 0 || height <= 0 || button == null) return 0;
        int corner = Math.max(1, Math.round(CORNER_DP * density));
        return NotifyCorner.edge(Math.round(fx * (width - 1)), Math.round(fy * (height - 1)), width, height, corner, true);
    }

    /** The ≡ button, and the hotkey: the bar opens or closes. */
    void toggle() {
        if (bar != null && !bar.closing()) closeBar();
        else openBar();
    }

    private void openBar() {
        if (windows == null || client == null) return;
        if (bar != null) return;
        bar = new MacMenuBar(dc, density, barHost, windows);
        bar.open();
        if (bar.closing()) {
            bar = null;
            return;
        }
        bar.setTop(client.menuTop());
        raiseButton();
        if (arrow != null) arrow.raise();  // the bar's window came after the arrow's
        ui.removeCallbacks(barLeave);
    }

    void closeBar() {
        ui.removeCallbacks(barLeave);
        MacMenuBar b = bar;
        bar = null;
        if (b != null) b.close(null);
        if (driven && over == SURFACE_BAR) endDriven();
    }

    /** The keys and mouse went to the phone: nothing moves the bar any more, so it goes (the centre's closeForPhone). */
    void closeForPhone() {
        closeBar();
    }

    private final MacMenuBar.Host barHost = new MacMenuBar.Host() {
        @Override public MacMenuState.Sent fetch(String id, String title, int version) {
            MacClient c = client;
            return c != null ? c.menuFetch(id, title, version) : null;
        }
        @Override public MacMenuState.Sent press(MacMenuState.Row row) {
            MacClient c = client;
            return c != null ? c.menuPress(row) : null;
        }
        @Override public void close() {
            closeBar();
        }
        @Override public void hover(boolean inside) {
            ui.removeCallbacks(barLeave);
            if (!inside) ui.postDelayed(barLeave, LEAVE_MS);  // left the bar: it goes in a second
        }
        @Override public void dropdownShown() {
            if (arrow != null) arrow.raise();  // the arrow over the dropdown, not under it
        }
    };

    // --- the driven pointer (over a Mac; DockPointer calls these, as it calls Notify's) ---

    /** The corner push opened the bar with its own pointer, which takes the moves and clicks. */
    boolean openDriven(float fx, float fy) {
        if (windows == null || display == null) return false;
        if (bar != null) return false;  // already open: grab() takes the pointer while it is over the bar
        openBar();
        if (bar == null || bar.closing()) return false;
        px = Math.max(0f, Math.min(1f, fx)) * (width - 1);
        py = Math.max(0f, Math.min(1f, fy)) * (height - 1);
        driven = true;
        over = SURFACE_BAR;
        grab.grabbed(Math.round(px), Math.round(py));
        arrowAt();
        return true;
    }

    private void arrowAt() {
        if (arrow == null) {
            arrow = new CursorOverlay(context);
            arrow.attach(display);
        }
        arrow.moveArrow(Math.round(px), Math.round(py));
    }

    /** True while the menu has the pointer (DockPointer.open). */
    boolean driven() {
        return driven;
    }

    /**
     * The pointer is the content's again (DockPointer.open): it left the ≡ or the bar, or the content changed. The
     * bar stays for LEAVE_MS, as the notification centre's panel does, so the pointer can go from the ≡ over the gap
     * to the bar (grab() takes it there and cancels the leave); a changed content closes it through update().
     */
    void drivenClosed() {
        endDriven();
        if (bar != null) {
            ui.removeCallbacks(barLeave);
            ui.postDelayed(barLeave, LEAVE_MS);
        }
    }

    /**
     * The content's pointer is at this place (0 to 1 of the screen): while the bar is open or the button is
     * there, the pointer is theirs (MACMENU.md §4, the notification centre's grab). Returns true when it took
     * the pointer. The outermost edge pixels never grab: they are the corner push's.
     */
    boolean grab(float fx, float fy) {
        if (windows == null) return false;
        int x = Math.round(fx * (width - 1)), y = Math.round(fy * (height - 1));
        if (x <= 0 || y <= 0) return false;
        if (!grab.mayGrab(x, y)) return false;
        // The button sits above the bar's corner: it is its own thing to click
        if (button != null && buttonHolds(x, y)) return setOver(SURFACE_BUTTON, x, y);
        if (bar != null && !bar.closing() && bar.holds(x, y)) {
            ui.removeCallbacks(barLeave);
            return setOver(SURFACE_BAR, x, y);
        }
        return false;
    }

    private boolean buttonHolds(int x, int y) {
        button.bounds(rect);
        return x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom;
    }

    private boolean setOver(int surface, int x, int y) {
        if (surface == over && driven) {
            // The arrow follows within the window it is already over
            px = x;
            py = y;
            arrowAt();
            return true;
        }
        over = surface;
        driven = true;
        px = x;
        py = y;
        grab.grabbed(x, y);  // the arrow starts under the content's pointer
        arrowAt();
        showHint(surface == SURFACE_BUTTON);
        return true;
    }

    /** The app name while the pointer rests on the ≡ (MACMENU.md §3). */
    private void showHint(boolean on) {
        if (!on) {
            hideHint();
            return;
        }
        String name = appName();
        if (name.isEmpty()) return;
        if (hint == null) {
            hint = new TextView(dc);
            hint.setTextColor(Style.TEXT);
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(12 * density));
            hint.setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable back = new GradientDrawable();
            back.setColor(0xF61B1E24);
            back.setCornerRadius(dp(6));
            hint.setBackground(back);
            hintParams = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            hintParams.gravity = Gravity.TOP | Gravity.START;
            hintParams.x = dp(CornerButton.SIZE_DP + 8) - 1;
            hintParams.y = dp(6);
            hintParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            hintParams.setFitInsetsTypes(0);
            hintParams.windowAnimations = 0;
            hintParams.setTitle("XDesk Mac menu app");
        }
        hint.setText(name);
        try {
            if (hint.getParent() == null) windows.addView(hint, hintParams);
        } catch (RuntimeException e) {
            Log.w(TAG, "no menu hint", e);
        }
    }

    private void hideHint() {
        if (hint == null) return;
        try {
            windows.removeViewImmediate(hint);
        } catch (RuntimeException ignored) { }
        hint = null;
    }

    private String appName() {
        MacClient c = client;
        if (c == null) return "";
        String app = c.menuTop().app;
        return app != null && !app.isEmpty() ? app : c.name();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /**
     * The pointer moves while the bar or the button has it. Out of them, the grab ends: the arrow is hidden and
     * what the arrow moved by comes back — the caller sends it to the content, whose pointer then ends where the
     * arrow left, outside the window. Null while the move stays the menu's.
     */
    int[] moveBy(int dx, int dy) {
        if (!driven || windows == null) return null;
        px = Math.max(0f, Math.min(width - 1, px + dx));
        py = Math.max(0f, Math.min(height - 1, py + dy));
        int x = Math.round(px), y = Math.round(py);
        arrow.moveArrow(x, y);
        // The ≡, the bar and its dropdown are one surface (they touch): the arrow goes from one to the other and
        // stays the menu's; only leaving all of them gives the pointer back to the Mac's picture
        boolean onBar = bar != null && !bar.closing() && bar.holds(x, y);
        if (over == SURFACE_BUTTON && !buttonHolds(x, y) && onBar) {
            over = SURFACE_BAR;
            hideHint();
            ui.removeCallbacks(barLeave);
        } else if (over == SURFACE_BAR && button != null && buttonHolds(x, y)) {
            over = SURFACE_BUTTON;
            return null;
        }
        if (over == SURFACE_BAR) {
            if (onBar) {
                bar.pointerAt(x, y);  // the row under the arrow is highlighted, as on the Mac
                return null;
            }
            if (bar == null) {  // closed under the arrow (the hotkey, a press): the pointer is the picture's again
                endDriven();
                return null;
            }
            bar.rect(rect);
            int[] left = grab.release(rect.left, rect.top, rect.right, rect.bottom, x, y);
            endDriven();
            ui.postDelayed(barLeave, LEAVE_MS);  // left the bar: it goes in a second
            return left;
        }
        if (over == SURFACE_BUTTON) {
            if (button == null) {  // the ≡ went under the arrow: the pointer is the picture's again (see Notify)
                endDriven();
                return null;
            }
            if (buttonHolds(x, y)) return null;
            button.bounds(rect);
            int[] left = grab.release(rect.left, rect.top, rect.right, rect.bottom, x, y);
            endDriven();
            hideHint();
            return left;
        }
        return null;
    }

    /** A click of the menu's own pointer, as the Android pointer's click would land. */
    void clickDriven() {
        if (!driven) return;
        int x = Math.round(px), y = Math.round(py);
        if (over == SURFACE_BUTTON) toggle();
        else if (over == SURFACE_BAR) bar.clickAt(x, y);
    }

    /** Esc: the bar closes (its own Esc goes back a level first in key()). */
    void closeDriven() {
        endDriven();
        closeBar();
    }

    /** Whether Esc is the menu's now: the bar is open. Else Esc is the content's. */
    boolean takesEsc() {
        return bar != null && !bar.closing() && bar.takesEsc();
    }

    /** Where the content's pointer is, for the button's fade and the grab's bookkeeping. */
    void pointerAt(float fx, float fy) {
        if (width > 0) grab.moved(Math.round(fx * (width - 1)), Math.round(fy * (height - 1)));
        if (button != null) button.pointerAt(fx, fy, width, height);
    }

    /** A receiver's or a browser's pointer was placed here (0 to 1 of the screen): the button's fade follows it. */
    void placedAt(float fx, float fy) {
        if (driven && over == SURFACE_BAR) {
            px = Math.max(0f, Math.min(1f, fx)) * (width - 1);
            py = Math.max(0f, Math.min(1f, fy)) * (height - 1);
            arrow.moveArrow(Math.round(px), Math.round(py));
            moveBy(0, 0);
            return;
        }
        pointerAt(fx, fy);
    }

    // --- the hotkey and the keys (XDeskActivity.physicalKey) ---

    /** Ctrl+Alt+Space with a Mac window or app on the screen: the bar opens or closes, the Space's release is
     *  swallowed, as N's and Q's are (MACMENU.md §3). Without the button it is just a key. */
    boolean hotkey(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_SPACE) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && swallowSpace) {
            swallowSpace = false;
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0 || !e.isCtrlPressed() || !e.isAltPressed())
            return false;
        if (windows == null || button == null) return false;
        swallowSpace = true;
        toggle();
        return true;
    }

    /**
     * The phone's physical keys while the bar is open (MACMENU.md §4): ←→ along the bar, ↑↓ pick an item, Enter
     * presses, → opens a submenu, Esc goes back a level or closes. True when the key was taken.
     */
    boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            // The release of a key that went down here, also when its down already closed the bar
            return takenKeys.remove(code);
        }
        MacMenuBar b = bar;
        if (b == null || b.closing()) return false;
        if (e.getRepeatCount() != 0) return false;  // repeats of what the bar does not take are the content's
        boolean use = true;
        switch (code) {
        case KeyEvent.KEYCODE_ESCAPE:
            b.esc();
            break;
        case KeyEvent.KEYCODE_DPAD_LEFT:
            b.left();
            break;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            b.right();
            break;
        case KeyEvent.KEYCODE_DPAD_UP:
            b.up();
            break;
        case KeyEvent.KEYCODE_DPAD_DOWN:
            b.down();
            break;
        case KeyEvent.KEYCODE_ENTER:
            b.enter();
            break;
        default:
            use = false;
        }
        if (use) {
            takenKeys.add(code);
            return true;
        }
        return false;
    }

    // --- the words the bar and the flashes say ---

    /** The host's refusal, in this side's words where they are known (MACMENU.md §4), else the host's own. */
    static String refusalText(Context c, MacMenuState.Refusal refusal) {
        if (refusal == null) return c.getString(R.string.xd_mac_menu_no_answer);
        switch (refusal.kind) {
        case CHANGED:
            return c.getString(R.string.xd_mac_menu_changed);
        case NOT_ANSWERING:
            return c.getString(R.string.xd_mac_menu_no_answer);
        case NOT_TRUSTED:
            return c.getString(R.string.xd_mac_menu_no_access);
        default:
            return refusal.note;
        }
    }

    /** A pressed item's refusal, said over the Mac's picture (MACMENU.md §4: in the bar or on the screen). */
    private void flash(MacMenuState.Refusal refusal) {
        MacClient c = client;
        if (c == null) return;
        c.flash(refusalText(context, refusal));
    }
}
