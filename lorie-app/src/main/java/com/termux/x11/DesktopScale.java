package com.termux.x11;

import android.app.Activity;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.widget.LinearLayout;

import com.termux.x11.app.R;

/**
 * The X desktop's size on its screen, DISPLAY.md §4.1 (glue for XDeskActivity, main thread):
 * <ul>
 *   <li>the X screen's DPI, in the display's Prefs next to its resolution, so LorieView sends both together;</li>
 *   <li>XFCE's font DPI, panel, icons and cursor: xdesk scale in the X server's process (ICmdEntryInterface.setScale),
 *       once the desktop is connected and whenever the DPI changes, never while a size is dragged (XDeskActivity
 *       commits a size only once it has settled);</li>
 *   <li>the screen's kind and size u in the display settings, and the question a new wired screen gets once.</li>
 * </ul>
 */
final class DesktopScale {
    private static final String TAG = "XDeskScale";
    private static final long RETRY_MS = 2000;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    /** The DPI XFCE should have, and the X server that was last told it. */
    private static int wanted;
    private static IBinder told;
    private static int toldDpi;

    private DesktopScale() { }

    /** The width of the screen's own pixels, as XDeskActivity.desktopSize takes it (landscape). */
    static int nativeWidth(Display d) {
        Point p = new Point();
        d.getRealSize(p);
        if (p.y > p.x) return d.getMode().getPhysicalWidth();
        return p.x;
    }

    /** With the size XDeskActivity.applySize puts in p: the X DPI for the desktop drawn width pixels wide on d. */
    static void apply(Activity a, Prefs p, Display d, int width) {
        int dpi = Sizing.desktopDpi(a, d, width, nativeWidth(d));
        // Unchanged values change nothing: SharedPreferences only tells listeners about real changes
        p.putInt("xdeskDpi", dpi);
        wanted = dpi;
        tellXfce();
    }

    /**
     * The desktop connected. A new X server gets the DPI; the same one already has it, and xdesk itself sizes a session
     * it starts later (xdesk switch) with the last DPI it was given.
     */
    static void connected() {
        tellXfce();
    }

    private static final Runnable retry = DesktopScale::tellXfce;

    private static void tellXfce() {
        ui.removeCallbacks(retry);
        if (wanted <= 0) return;
        ICmdEntryInterface s = MainActivity.verifiedServer;
        if (s == null || !s.asBinder().isBinderAlive()) {
            // The desktop is connected before its X server is verified, now and then
            ui.postDelayed(retry, RETRY_MS);
            return;
        }
        if (s.asBinder() == told && toldDpi == wanted) return;
        try {
            s.setScale(wanted);
            told = s.asBinder();
            toldDpi = wanted;
        } catch (RemoteException | RuntimeException e) {
            // An X server from before setScale: it has no xdesk scale either
            Log.i(TAG, "setScale: " + e);
        }
    }

    // --- settings ---

    private static final int[] KINDS = {Sizing.MONITOR, Sizing.TV, Sizing.PROJECTOR, Sizing.HANDHELD};
    private static final int[] KIND_LABELS = {R.string.xd_kind_monitor, R.string.xd_kind_tv, R.string.xd_kind_projector,
        R.string.xd_kind_handheld};
    private static final int[] SIZE_LABELS = {R.string.xd_size_small, R.string.xd_size_medium, R.string.xd_size_large,
        R.string.xd_size_xlarge};

    private static String[] strings(Activity a, int[] ids, int n) {
        String[] s = new String[n];
        for (int i = 0; i < n; i++) s[i] = a.getString(ids[i]);
        return s;
    }

    private static int kindIndex(int kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i] == kind) return i;
        return 0;
    }

    /** The kind and size of the screen d (null: none connected) on the display settings page. */
    static void addSettings(Activity a, SettingsUi s, LinearLayout page, Display d, Runnable changed) {
        LinearLayout card = s.section(page, a.getString(R.string.xd_section_this_screen));
        if (d == null) {
            s.infoRow(card, a.getString(R.string.xd_screen_size), a.getString(R.string.xd_screen_none), null);
            return;
        }
        final ScreenProfiles profiles = ScreenProfiles.get(a);
        final ScreenProfiles.Profile p = profiles.of(a, d);
        // The car is seen from one place; a wired screen is never held in the hand
        if (!TargetDisplay.isCar(d)) {
            boolean wired = d.getDisplayId() != WirelessDisplay.displayId();
            int n = wired ? 3 : 4;
            s.segmentedRow(card, a.getString(R.string.xd_screen_kind), a.getString(R.string.xd_screen_kind_note),
                strings(a, KIND_LABELS, n), p.kind == Sizing.KIND_UNSET && !wired ? -1 : kindIndex(p.kind), which -> {
                    p.kind = KINDS[which];
                    profiles.save(p);
                    changed.run();
                });
        }
        s.segmentedRow(card, a.getString(R.string.xd_screen_size), a.getString(R.string.xd_screen_size_note),
            strings(a, SIZE_LABELS, SIZE_LABELS.length), p.size, which -> {
                p.size = which;
                profiles.save(p);
                changed.run();
            });
    }

    /** DISPLAY.md §4.1: a wired screen seen for the first time is asked once what it is. */
    static void askKindOnce(Activity a, SettingsUi s, Display d, Runnable changed) {
        if (d == null || TargetDisplay.isCar(d) || d.getDisplayId() == WirelessDisplay.displayId() || a.isFinishing()) return;
        final ScreenProfiles profiles = ScreenProfiles.get(a);
        String key = ScreenProfiles.wiredKey(d);
        // A virtual display (the wireless one before it is known as such) is not a screen to ask about
        if (key.startsWith("w:virtual:") || profiles.known(key)) return;
        final ScreenProfiles.Profile p = profiles.of(key);
        // Saved now as a monitor: closing the question without an answer keeps that, and it is not asked again
        p.kind = Sizing.MONITOR;
        profiles.save(p);
        try {
            s.choice(a.getString(R.string.xd_screen_ask_title), a.getString(R.string.xd_screen_ask_note),
                strings(a, KIND_LABELS, 3), 0, which -> {
                    if (KINDS[which] == p.kind) return;
                    p.kind = KINDS[which];
                    profiles.save(p);
                    changed.run();
                });
        } catch (RuntimeException e) {
            Log.i(TAG, "cannot ask: " + e);
        }
    }
}
