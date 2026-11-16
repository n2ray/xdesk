package com.termux.x11;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.termux.x11.app.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * The notification centre's windows on the external display (NOTIFY.md §7): the bell, the banners and the panel,
 * the same kind of overlay windows as the ExternalDock's, on the same display — a wireless receiver's the same way,
 * the display comes from XDeskActivity. This is what the model's changes become: a banner for a new notification,
 * the bell's count, the panel's rows, the low-battery banners.
 *
 * Three ways to open the panel (§4.1), all here: the pointer pushed out through the top-right corner's 48 shell dp
 * (EdgePush, TOP or RIGHT: over X and a Mac DockPointer feeds it, over an Android app the injector reports it and
 * AppSession passes it on), the bell, and Ctrl+Alt+N (XDeskActivity.physicalKey calls hotkey). While the panel or a
 * banner is under the pointer over X and a Mac, the pointer is theirs: DockPointer asks grab() on every move and
 * routes moves and clicks here, the arrow is XDesk's own, as the Dock's is. Over Android apps Android's own pointer
 * works the windows directly. The phone's keys reach the panel and an open reply through key() (physicalKey): up and
 * down select, Enter opens, Delete clears, R replies, Esc closes; text goes only to a focused reply field.
 *
 * One instance per process, as the model and the views around it. Main thread.
 */
final class Notify implements AppSession.EdgeListener, NotifyBanner.Host, NotifyPanel.Host {
    private static final String TAG = "XDeskNotify";
    /** NOTIFY.md §4.1: the corner is 48 shell dp of the right edge's top and the top edge's right. */
    static final float CORNER_DP = 48;
    private static final long EDGE_DWELL_MS = 350;
    private static final long OPEN_WAIT_MS = 600;
    /** What the driven pointer is over: 0..banners.size()-1 is a banner, the others are these. */
    private static final int SURFACE_PANEL = 10, SURFACE_BELL = 11;

    private static Notify instance;

    static synchronized Notify get(Context c) {
        if (instance == null) instance = new Notify(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final NotifyCenter center;
    private final EdgePush cornerRule = new EdgePush();
    /** The hover-grab's trap-avoidance (NOTIFY.md §3): where a grab ended and where the arrow is owed back. */
    private final NotifyGrab grab = new NotifyGrab();
    private final Set<Integer> takenKeys = new HashSet<>();
    private final Rect rect = new Rect();
    private volatile Activity activity;
    /** Where the content's pointer last was (fractions), when a move has said so: banners never grab a resting one. */
    private float lastFx = -1, lastFy = -1;
    private boolean havePlace;

    // The display the windows are on; null while there is none (nothing to show then)
    private Display display;
    private int displayId = Display.INVALID_DISPLAY;
    private Context dc;
    private WindowManager windows;
    private float density;
    private int width, height;

    private NotifyBell bell;
    private final ArrayList<NotifyBanner> banners = new ArrayList<>();
    private NotifyPanel panel;
    private CursorOverlay arrow;
    /** The cursor of an Android app session (AppSession.raiseCursor), drawn in a window of its own: set by the activity. */
    private Runnable raiseAppCursor;

    /** What raises the Android app's cursor: the panel, the bell and the banners are added after it (as for the Dock). */
    void setRaiseAppCursor(Runnable r) {
        raiseAppCursor = r;
    }

    /** Both pointers that can be over the centre's windows above them: XDesk's own (over X, a Mac) and an Android app's. */
    private void raiseArrows() {
        if (arrow != null) arrow.raise();
        Runnable r = raiseAppCursor;
        if (r != null) r.run();
    }
    /** The driven pointer: where the arrow is (a screen width by height), and whose window it is over. */
    private boolean driven;
    private float px, py;
    private int over;
    /** A reply field is taking the phone's keys (a banner's, or the panel's own). */
    private NotifyBanner replyBanner;

    /** The low-battery banner now up (NOTIFY.md §4), and the strongest threshold said this charge. */
    private NotifyBanner batteryBannerView;
    private boolean batteryUp;
    private int batteryDone;
    /** The "unlock the phone" banner (NOTIFY.md §5), up while it is. */
    private NotifyBanner unlockBanner;

    /** The hotkey's N went down here: its release is swallowed too. */
    private boolean swallowN;

    /** A placed pointer resting at the corner over an Android app: the panel opens, Android's own pointer works it. */
    private final Runnable openFromRest = () -> {
        if (panel == null && windows != null) openPanel();
    };
    /** The same rest over X and a Mac: the panel opens with XDesk's own pointer, as the Dock does. */
    private final Runnable openDrivenFromRest = () -> {
        if (panel == null && windows != null) openDriven(1f, 0f);
    };
    private final Runnable panelLeave = this::closePanel;

    private final Runnable modelChanged = this::onModelChanged;

    private Notify(Context c) {
        context = c;
        center = NotifyCenter.get(c);
        center.addListener(modelChanged);
    }

    /** The phone's window, for the dialogs that explain permissions; XDeskActivity sets it. */
    void attachActivity(Activity a) {
        activity = a;
    }

    // --- the display (the ExternalDock's setDisplay, for the centre's windows) ---

    void setDisplay(Display d) {
        int id = d != null ? d.getDisplayId() : Display.INVALID_DISPLAY;
        if (id == displayId) {
            // The same display, but its size may have changed (a resolution change): the corner and the fade follow it
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
        Log.i(TAG, "notifications on display " + id + " (" + width + "x" + height + ", shell " + density + ")");
        if (center.bellShown()) showBell();
        onModelChanged();
    }

    private void showBell() {
        if (bell != null || windows == null) return;
        bell = new NotifyBell(dc, density, this::toggle);
        try {
            windows.addView(bell.view(), bell.params());
            raiseArrows();  // the pointer stays above every window of the centre
            bell.setUnread(center.unread());
        } catch (RuntimeException e) {
            Log.w(TAG, "no notification bell", e);
            bell = null;
        }
    }

    private void hideBell() {
        NotifyBell b = bell;
        bell = null;
        if (b != null) {
            try {
                windows.removeViewImmediate(b.view());
            } catch (RuntimeException ignored) { }
        }
    }

    /** The bell stays above the panel's scrim, as the switch that closes it, and under the panel (NotifyPanel.open). */
    private void raiseBell() {
        if (bell == null || windows == null) return;
        try {
            windows.removeViewImmediate(bell.view());
            windows.addView(bell.view(), bell.params());
            raiseArrows();  // the pointer stays above every window of the centre
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot raise bell", e);
        }
    }

    private void detach() {
        endDriven();
        ui.removeCallbacks(openFromRest);
        ui.removeCallbacks(panelLeave);
        cornerRule.forget();
        NotifyPanel p = panel;
        panel = null;
        if (p != null) p.slideOut(null);
        for (NotifyBanner b : new ArrayList<>(banners)) b.remove();
        banners.clear();
        hideBell();
        if (arrow != null) arrow.remove();
        arrow = null;
        windows = null;
        dc = null;
    }

    private void endDriven() {
        driven = false;
        over = 0;
        if (arrow != null) arrow.hide();
    }

    // --- the model became something visible ---

    private void onModelChanged() {
        if (bell != null) bell.setUnread(center.unread());
        if (panel != null && !panel.closing()) {
            panel.fill();
            panel.refreshHeader();
        }
        NotifyCenter.Entry posted = center.lastPosted();
        if (posted != null) maybeBanner(posted);
        String removed = center.lastRemoved();
        if (removed != null) {
            for (NotifyBanner b : new ArrayList<>(banners)) {
                if (b.removeKey(removed)) closeBanner(b);
            }
        }
        String modeApp = center.lastModeApp();
        if (modeApp != null) {
            for (NotifyBanner b : new ArrayList<>(banners)) {
                if (modeApp.equals(b.packageName())) closeBanner(b);
            }
        }
        if (!center.notifyOn()) closeBanners();
        if (center.justUnlocked() && unlockBanner != null) {
            NotifyBanner u = unlockBanner;
            unlockBanner = null;
            if (banners.contains(u)) closeBanner(u);
        }
        battery();
    }

    /** NOTIFY.md §3: a new notification raises a banner, or merges into the app's, or only counts on the bell. */
    private void maybeBanner(NotifyCenter.Entry e) {
        if (windows == null || panel != null || !center.notifyOn()) return;
        if (!NotifyFilter.banners(center.appMode(e.pkg), center.bannerContent())) return;
        if (!center.bannersAllowed(e)) return;  // Do not disturb: the centre only (NOTIFY.md §2)
        boolean nameOnly = center.bannerContent() == NotifyCenter.CONTENT_NAME;
        if (!banners.isEmpty() && e.pkg.equals(banners.get(0).packageName())) {
            banners.get(0).merge(e, nameOnly);
            relayout();
            return;
        }
        if (banners.size() >= NotifyBanner.MAX) return;
        NotifyBanner b = new NotifyBanner(dc, density, this, windows, 0);
        banners.add(0, b);
        b.show(e, center.icon(e.pkg), center.appLabel(e.pkg), nameOnly, e.when);
        try {
            windows.addView(b.view(), b.params());
            raiseArrows();  // the pointer stays above every window of the centre
            b.shown();
        } catch (RuntimeException ex) {
            Log.w(TAG, "no notification banner", ex);
            banners.remove(b);
            return;
        }
        neverGrabResting(b);
        relayout();
    }

    /** A banner that appeared under a resting pointer never takes it: only one the pointer moves onto does. */
    private void neverGrabResting(NotifyBanner b) {
        ui.post(() -> {
            if (b.closing() || !havePlace || windows == null) return;
            int x = Math.round(lastFx * (width - 1)), y = Math.round(lastFy * (height - 1));
            if (b.holds(x, y)) {
                b.rect(rect);
                grab.closed(rect.left, rect.top, rect.right, rect.bottom);
            }
        });
    }

    /** The stack hangs below the bell, the newest on top, its y's from the heights (NOTIFY.md §3). */
    private void relayout() {
        ui.post(() -> {
            if (windows == null) return;
            int y = dp(NotifyBell.SIZE_DP + NotifyBanner.MARGIN_DP + 6);
            for (NotifyBanner b : banners) {
                b.params().y = y;
                try {
                    windows.updateViewLayout(b.view(), b.params());
                } catch (RuntimeException ignored) { }
                y += b.height() + dp(NotifyBanner.GAP_DP);
            }
        });
    }

    private void closeBanners() {
        for (NotifyBanner b : new ArrayList<>(banners)) closeBanner(b);
    }

    private void closeBanner(NotifyBanner b) {
        banners.remove(b);
        b.slideOut();
        relayout();
    }

    /** The low-battery banners at 15% and 5%, not while charging, each once per charge (NOTIFY.md §4). */
    private void battery() {
        int level = center.batteryLevel();
        boolean charging = center.batteryCharging();
        if (charging) batteryDone = 0;  // a new charge: both thresholds may speak again
        int want = !charging && center.lowBatteryBanners() && level >= 0 ? (level <= 5 ? 5 : level <= 15 ? 15 : 0) : 0;
        if (want == 0) {
            // Charging, or back above the threshold: the banner goes
            if (batteryUp) closeBattery();
            return;
        }
        if (want <= batteryDone) return;  // this threshold already said its piece (or its × went)
        if (batteryUp) closeBattery();  // 15%'s banner gives way to 5%'s
        // Only said once it really showed: a panel in the way must not spend the threshold
        if (showBatteryBanner(want)) batteryDone = want;
    }

    private void closeBattery() {
        batteryUp = false;
        for (NotifyBanner b : new ArrayList<>(banners)) if (b == batteryBannerView) closeBanner(b);
        batteryBannerView = null;
    }

    /** A banner without a notification behind it: clicking it opens the centre. */
    private boolean showBatteryBanner(int level) {
        if (windows == null || panel != null) return false;
        NotifyBanner b = new NotifyBanner(dc, density, this, windows, 0);
        banners.add(0, b);
        batteryBannerView = b;
        Drawable icon = Style.letterIcon(dc, context.getString(R.string.xd_notify_battery_short), density);
        b.showNotice(icon, context.getString(R.string.xd_notify_battery_app), context.getString(R.string.xd_notify_battery, level), System.currentTimeMillis());
        try {
            windows.addView(b.view(), b.params());
            raiseArrows();  // the pointer stays above every window of the centre
            b.shown();
        } catch (RuntimeException ex) {
            banners.remove(b);
            batteryBannerView = null;
            return false;
        }
        relayout();
        return true;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Whether the centre's windows are on a display: the corner rule needs them (the Dock's attached()). */
    boolean attached() {
        return windows != null;
    }

    // --- the three ways to open the panel (NOTIFY.md §4.1) ---

    /** The corner the pointer is pushed out through: TOP on the top edge's right 48 dp, RIGHT on the right edge's top. */
    int cornerEdge(float fx, float fy) {
        if (width <= 0 || height <= 0) return 0;
        int corner = Math.max(1, Math.round(CORNER_DP * density));
        return NotifyCorner.edge(Math.round(fx * (width - 1)), Math.round(fy * (height - 1)), width, height, corner, false);
    }

    /** The bell, and the hotkey: the panel opens or closes. */
    void toggle() {
        if (panel != null && !panel.closing()) closePanel();
        else openPanel();
    }

    /** Opens the panel; over Android apps Android's own pointer works it, over X and a Mac openDriven adds ours. */
    private void openPanel() {
        if (windows == null) return;
        closeBanners();  // the centre takes their place; the bell still counts
        if (panel != null) return;
        panel = new NotifyPanel(dc, density, this, windows);
        panel.open(this::raiseBell);
        raiseArrows();  // the panel came after the pointer: the pointer over it, not under
        ui.removeCallbacks(panelLeave);
    }

    void closePanel() {
        ui.removeCallbacks(openFromRest);
        ui.removeCallbacks(panelLeave);
        if (driven && over == SURFACE_PANEL) endDriven();
        NotifyPanel p = panel;
        panel = null;
        if (p != null) p.slideOut(null);
    }

    /** The keys and mouse went to the phone: nothing moves the centre any more, so it goes (the Dock's closeForPhone). */
    void closeForPhone() {
        closePanel();
    }

    // --- the corner push over an Android app (AppSession.EdgeListener) ---

    @Override public void atEdge(int edge, boolean at, boolean pushable) {
        if (edge == EdgePush.BOTTOM || windows == null) return;
        if (!at) {
            cornerRule.forget();
            ui.removeCallbacks(openFromRest);
        } else {
            // A pointer that can be pushed opens it by the push (pushed); a placed one (a receiver's, a browser's)
            // only rests here, as the Dock opens by the rest at the bottom
            if (panel == null && !pushable) later(openFromRest, EDGE_DWELL_MS);
            else ui.removeCallbacks(panelLeave);
        }
    }

    @Override public void pushed(int edge, int dx, int dy, boolean held) {
        if (edge == EdgePush.BOTTOM) return;
        if (windows == null || panel != null || held) {
            cornerRule.forget();
            return;
        }
        if (cornerRule.pushed(edge, dx, dy, false)) openPanel();
    }

    @Override public boolean wantsPlace() {
        return windows != null;
    }

    /** The pointer's place over an Android app: the bell's fade follows it. */
    @Override public void placed(int x, int y) {
        if (bell != null && width > 0) bell.pointerAt((float) x / (width - 1), (float) y / (height - 1), width, height);
    }

    private void later(Runnable r, long ms) {
        ui.removeCallbacks(r);
        ui.postDelayed(r, ms);
    }

    // --- the driven pointer (over X and a Mac; DockPointer calls these) ---

    /** The corner push over X and a Mac opened it with its own pointer, which takes the moves and clicks. */
    boolean openDriven(float fx, float fy) {
        if (windows == null || display == null) return false;
        if (panel != null) return false;  // already open: grab() takes the pointer while it is over the panel
        openPanel();
        if (panel == null || panel.closing()) return false;
        px = Math.max(0f, Math.min(1f, fx)) * (width - 1);
        py = Math.max(0f, Math.min(1f, fy)) * (height - 1);
        driven = true;
        over = SURFACE_PANEL;
        grab.grabbed(Math.round(px), Math.round(py));
        arrowAt();
        return true;
    }

    private void arrowAt() {
        if (arrow == null) {
            arrow = new CursorOverlay(context);
            arrow.attach(display);
        }
        arrow.moveTo(Math.round(px), Math.round(py));
    }

    /** True while the centre has the pointer (DockPointer.open). */
    boolean driven() {
        return driven;
    }

    /** The content under it ended or changed: the pointer is the content's again (DockPointer.open). */
    void drivenClosed() {
        endDriven();
        if (panel != null) later(panelLeave, NotifyPanel.LEAVE_MS);
    }

    /**
     * The content's pointer is at this place (0 to 1 of the screen): while the panel is open, a banner shows or the
     * bell is there, the pointer is theirs (NOTIFY.md §3, §4.1). Returns true when it took the pointer. The outermost
     * edge pixels never grab: they are the corner push's. And a place where a grab just ended does not grab again
     * until the content's pointer has left it (NotifyGrab).
     */
    boolean grab(float fx, float fy) {
        if (windows == null) return false;
        int x = Math.round(fx * (width - 1)), y = Math.round(fy * (height - 1));
        if (x >= width - 1 || y <= 0) return false;
        if (!grab.mayGrab(x, y)) return false;
        // The bell sits above the panel's corner: it is its own thing to click
        if (bell != null && bellHolds(x, y)) return setOver(SURFACE_BELL, x, y);
        if (panel != null && !panel.closing() && panel.holdsPanel(x, y)) {
            ui.removeCallbacks(panelLeave);
            return setOver(SURFACE_PANEL, x, y);
        }
        for (NotifyBanner b : banners) {
            if (!b.closing() && b.holds(x, y)) {
                b.hover(true);
                return setOver(banners.indexOf(b), x, y);
            }
        }
        return false;
    }

    private boolean bellHolds(int x, int y) {
        bell.bounds(rect);
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
        if (driven && over >= 0 && over < banners.size()) banners.get(over).hover(false);
        over = surface;
        driven = true;
        px = x;
        py = y;
        grab.grabbed(x, y);  // the arrow starts under the content's pointer
        arrowAt();
        return true;
    }

    /**
     * The pointer moves while a window of the centre's has it. Out of the panel, the banner or the bell, the grab
     * ends: the arrow is hidden, the window's place is not grabbed again until the content's pointer left it, and
     * what the arrow moved by comes back — the caller sends it to the content, whose pointer then ends where the
     * arrow left, outside the window. Null while the move stays the window's.
     */
    int[] moveBy(int dx, int dy) {
        if (!driven || windows == null) return null;
        px = Math.max(0f, Math.min(width - 1, px + dx));
        py = Math.max(0f, Math.min(height - 1, py + dy));
        int x = Math.round(px), y = Math.round(py);
        arrow.moveTo(x, y);
        if (over == SURFACE_PANEL) {
            if (panel.holdsPanel(x, y)) return null;
            panel.rect(rect);
            int[] left = grab.release(rect.left, rect.top, rect.right, rect.bottom, x, y);
            endDriven();
            later(panelLeave, NotifyPanel.LEAVE_MS);  // left the panel: it goes in a second (NOTIFY.md §4)
            return left;
        }
        if (over == SURFACE_BELL) {
            if (bellHolds(x, y)) return null;
            bell.bounds(rect);
            int[] left = grab.release(rect.left, rect.top, rect.right, rect.bottom, x, y);
            endDriven();
            return left;
        }
        if (over >= 0 && over < banners.size()) {
            NotifyBanner b = banners.get(over);
            if (b.holds(x, y)) return null;
            b.hover(false);
            b.rect(rect);
            int[] left = grab.release(rect.left, rect.top, rect.right, rect.bottom, x, y);
            endDriven();
            return left;
        }
        return null;
    }

    /** A click of the centre's own pointer, as the Android pointer's click would land. */
    void clickDriven() {
        if (!driven) return;
        int x = Math.round(px), y = Math.round(py);
        if (over == SURFACE_BELL) toggle();
        else if (over == SURFACE_PANEL) panel.clickAt(x, y);
        else if (over >= 0 && over < banners.size()) banners.get(over).clickAt(x, y);
    }

    /** The secondary button: on a row of the panel it clears that notification. */
    void secondaryDriven() {
        if (!driven || over != SURFACE_PANEL) return;
        panel.secondaryAt(Math.round(px), Math.round(py));
    }

    /** Esc: the panel closes (its reply is cancelled first in key()). */
    void closeDriven() {
        endDriven();
        closePanel();
    }

    /**
     * Whether Esc is the centre's now: the panel is open or a reply field has the keys. Else Esc is the content's —
     * a pointer merely resting over a banner or the bell does not make Esc theirs.
     */
    boolean takesEsc() {
        if (panel != null && !panel.closing()) return true;
        if (replyBanner != null && replyBanner.replyOpen()) return true;
        return panel != null && panel.replyOpen();
    }

    /** The wheel while the panel has the pointer: its list scrolls, in whole steps whatever the device. */
    void wheel(float dy, float dx) {
        if (panel != null && !panel.closing()) panel.wheel(dy != 0 ? dy : dx);
    }

    /** Where the content's pointer is, for the bell's fade (NOTIFY.md §4.1) and the grab's bookkeeping. */
    void pointerAt(float fx, float fy) {
        lastFx = fx;
        lastFy = fy;
        havePlace = true;
        if (width > 0) grab.moved(Math.round(fx * (width - 1)), Math.round(fy * (height - 1)));
        if (bell != null) bell.pointerAt(fx, fy, width, height);
    }

    /**
     * A receiver's or a browser's pointer was placed here (0 to 1 of the screen) over X or a Mac: the driven panel
     * follows it, resting in the corner opens the panel as the rest at the bottom opens the Dock, and the bell's
     * fade follows it.
     */
    void placedAt(float fx, float fy) {
        if (driven && over == SURFACE_PANEL) {
            px = Math.max(0f, Math.min(1f, fx)) * (width - 1);
            py = Math.max(0f, Math.min(1f, fy)) * (height - 1);
            arrow.moveTo(Math.round(px), Math.round(py));
            moveBy(0, 0);
            return;
        }
        if (windows == null) return;
        if (cornerEdge(fx, fy) != 0) {
            ui.removeCallbacks(panelLeave);
            if (panel == null) later(openDrivenFromRest, EDGE_DWELL_MS);
        } else {
            ui.removeCallbacks(openDrivenFromRest);
            if (panel != null) later(panelLeave, NotifyPanel.LEAVE_MS);
        }
        pointerAt(fx, fy);
    }

    // --- the hotkey and the keys (XDeskActivity.physicalKey) ---

    /** Ctrl+Alt+N: the centre opens or closes, the N's release is swallowed (INPUT.md §7, NOTIFY.md §4.1). */
    boolean hotkey(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_N) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && swallowN) {
            swallowN = false;
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0 || !e.isCtrlPressed() || !e.isAltPressed()) return false;
        if (windows == null) return false;  // nothing to open on: it is just a key
        swallowN = true;
        toggle();
        return true;
    }

    /**
     * The phone's physical keys while the centre is up (NOTIFY.md §4): up and down select, Enter opens, Delete
     * clears, R replies, Esc closes; text goes only to an open reply field (§3). True when the key was taken.
     */
    boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            // The release of a key that went down here, also when its down already closed the panel
            return takenKeys.remove(code);
        }
        NotifyPanel p = panel;
        boolean panelUp = p != null && !p.closing();
        NotifyBanner reply = replyBanner;
        boolean typing = reply != null && reply.replyOpen() || (p != null && p.replyOpen());
        if (!panelUp && !typing) return false;
        boolean use = false;
        if (typing) {
            if (code == KeyEvent.KEYCODE_ESCAPE) {
                cancelReply();
                use = true;
            } else if (code == KeyEvent.KEYCODE_ENTER) {
                sendReply();
                use = true;
            } else if (code == KeyEvent.KEYCODE_DEL) {
                if (reply != null && reply.replyOpen()) reply.backspace();
                else if (p != null) p.backspace();
                use = true;
            } else {
                int ch = e.getUnicodeChar(e.getMetaState());
                if (ch != 0 && !Character.isISOControl(ch)) {
                    if (reply != null && reply.replyOpen()) reply.type((char) ch);
                    else if (p != null) p.type((char) ch);
                    use = true;
                }
            }
        }
        if (!use && panelUp) {
            if (e.getRepeatCount() != 0) return false;  // repeats of what the panel does not take are the content's
            switch (code) {
                case KeyEvent.KEYCODE_ESCAPE:
                    closePanel();
                    use = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    p.select(-1);
                    use = true;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    p.select(1);
                    use = true;
                    break;
                case KeyEvent.KEYCODE_ENTER:
                    p.openSelected();
                    use = true;
                    break;
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    p.clearSelected();
                    use = true;
                    break;
                case KeyEvent.KEYCODE_R:
                    p.replySelected();
                    use = true;
                    break;
            }
        }
        if (use) {
            takenKeys.add(code);
            return true;
        }
        return false;
    }

    private void cancelReply() {
        if (replyBanner != null && replyBanner.replyOpen()) replyBanner.cancelReply();
        else if (panel != null) panel.cancelReply();
    }

    private void sendReply() {
        if (replyBanner != null && replyBanner.replyOpen()) replyBanner.sendReply();
        else if (panel != null) panel.sendReply();
    }

    // --- the banners' and the panel's host ---

    @Override public void bannerTap(NotifyBanner b) {
        NotifyCenter.Entry e = b.newest();
        if (e != null) openEntry(e);
        else openPanel();
    }

    @Override public void bannerClose(NotifyBanner b) {
        if (b == batteryBannerView) {
            batteryUp = false;  // its × went: this threshold said its piece (batteryDone already holds it)
            batteryBannerView = null;
        }
        closeBanner(b);
    }

    @Override public void bannerAction(NotifyBanner b, Notification.Action action, String replyText) {
        NotifyBanner.sendAction(context, action, replyText);
    }

    @Override public void replyFocus(NotifyBanner b, boolean on) {
        replyBanner = on ? b : null;
        relayout();  // the reply field made the banner taller
    }

    @Override public void bannerGone(NotifyBanner b) {
        bannerClose(b);
    }

    @Override public void open(NotifyCenter.Entry e) {
        openEntry(e);
    }

    @Override public void action(NotifyCenter.Entry e, Notification.Action a, String replyText) {
        NotifyBanner.sendAction(context, a, replyText);
    }

    @Override public void clear(NotifyCenter.Entry e) {
        center.cancel(e.key);
    }

    @Override public void clearAll() {
        center.clearAll();
    }

    @Override public void dnd(boolean on) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) {
            guideDndPolicy();
            return;
        }
        try {
            nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_NONE : NotificationManager.INTERRUPTION_FILTER_ALL);
        } catch (SecurityException e) {
            guideDndPolicy();  // the access went away between the check and the switch
            return;
        }
        center.interruptionFilter(nm.getCurrentInterruptionFilter());
    }

    @Override public void hover(boolean inside) {
        if (inside) ui.removeCallbacks(panelLeave);
    }

    @Override public void replyFocus(boolean on) {
        // The panel's own reply strip; the keys' routing asks the panel itself
    }

    @Override public void close() {
        closePanel();
    }

    /** The DND switch needs the notification-policy access: explain once, then the system page (NOTIFY.md §4). */
    private void guideDndPolicy() {
        if (center.policyAsked()) return;
        center.setPolicyAsked();
        Activity a = activity;
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        new SettingsUi(a).confirm(a.getString(R.string.xd_notify_dnd_title), a.getString(R.string.xd_notify_dnd_message),
            a.getString(R.string.xd_notify_dnd_open), () -> {
                try {
                    a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                } catch (RuntimeException e) {
                    Log.w(TAG, "no policy access settings", e);
                }
            });
    }

    // --- opening a notification (NOTIFY.md §5) ---

    private void openEntry(NotifyCenter.Entry e) {
        closePanel();
        Display d = display;
        if (d == null) return;
        PendingIntent pi = e.sbn.getNotification().contentIntent;
        if (pi != null) {
            try {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLaunchDisplayId(d.getDisplayId());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                }
                pi.send(context, 0, null, null, null, null, o.toBundle());
                Log.i(TAG, "opening " + e.pkg + " by its contentIntent");
            } catch (PendingIntent.CanceledException | RuntimeException ex) {
                Log.w(TAG, "contentIntent for " + e.pkg + ": " + ex);
            }
        }
        // Wait for its activity, then: already on the external display — just make it the content; landed on the
        // phone (the PendingIntent ignored the display) — the launcher's own path starts it there with the injector,
        // which moves its task; nowhere — say to unlock the phone
        ui.postDelayed(() -> choose(e), OPEN_WAIT_MS);
    }

    private void choose(NotifyCenter.Entry e) {
        if (onExternal(e.pkg)) {
            chooseKey(e.pkg);
            return;
        }
        if (locked() && !AppSession.get(context).injectorReady()) {
            // The panel went with the click: the notice is a banner that stays until the phone is unlocked
            showUnlockBanner();
            Log.i(TAG, "locked: " + e.pkg + " waits for the phone to be unlocked");
            return;
        }
        chooseKey(e.pkg);
    }

    /** "Unlock the phone" (NOTIFY.md §5): a synthetic banner, sticky until ACTION_USER_PRESENT takes it down. */
    private void showUnlockBanner() {
        if (windows == null || unlockBanner != null) return;
        NotifyBanner b = new NotifyBanner(dc, density, this, windows, 0);
        banners.add(0, b);
        unlockBanner = b;
        Drawable icon = Style.letterIcon(dc, context.getString(R.string.xd_notify_unlock_short), density);
        b.showNotice(icon, context.getString(R.string.xd_notify_unlock_app), context.getString(R.string.xd_notify_unlock),
            System.currentTimeMillis());
        try {
            windows.addView(b.view(), b.params());
            raiseArrows();  // the pointer stays above every window of the centre
            b.stay();
        } catch (RuntimeException ex) {
            banners.remove(b);
            unlockBanner = null;
        }
        neverGrabResting(b);
        relayout();
    }

    private boolean onExternal(String pkg) {
        AppSession apps = AppSession.get(context);
        if (!apps.isActive() || !apps.isStarted() || display == null) return false;
        String top = apps.topPackage(display);
        return pkg.equals(top);
    }

    private boolean locked() {
        KeyguardManager km = context.getSystemService(KeyguardManager.class);
        return km.isKeyguardLocked();
    }

    /** The launcher's way of choosing an app: it becomes the screen's content, the keys and mouse go with it. */
    private void chooseKey(String pkg) {
        AppCatalog.App a = AppCatalog.get(context).findPackage(pkg);
        if (a == null) {
            Log.w(TAG, "no launcher activity for " + pkg);
            return;
        }
        String key = ChooserView.recentKey(a);
        if (Switchboard.get(context).exists(key)) Switchboard.get(context).open(key);
    }
}
