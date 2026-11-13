package com.termux.x11;

import android.graphics.Bitmap;

import java.util.List;

/**
 * One system whose apps and windows XDesk can put on a screen: the window exchange of DISPLAY.md
 * (§0, §1.5). Phase 1: one content full screen per screen.
 *
 * Every source follows the same model (taken from Sill's app list and pendingLaunch):
 * opening an entry that already has a window switches to its most recent window; opening one that
 * is not running starts it, waits for its first window and puts that window on the screen.
 *
 * Keys are stable strings with a per-source prefix, so the chooser, the dock and the recents
 * store can keep them: "a:" Android (AppCatalog, existing), "t:" Termux sessions (existing),
 * "l:" Linux programs and X windows, "m:" Mac apps, windows and desktops.
 */
interface ContentSource {

    /** Key prefix, e.g. "l:". Unique per source. */
    String prefix();

    /** Section title in the chooser, e.g. "Linux", "Mac「M4」". */
    String title();

    /** Apps that can be opened, running or not. May be served from a cache; never blocks. */
    List<Entry> apps();

    /** Windows that are open now (for the switcher's "running" part). Never blocks. */
    List<Entry> windows();

    /**
     * Open an app or a window on the display with this id (the target display, see
     * TargetDisplay). Runs off the UI thread if it has to wait; reports through the callback on
     * the UI thread.
     */
    void open(String key, int displayId, Result result);

    /** Close a window or quit an app (the explicit "quit", not taking it off a screen). */
    void close(String key);

    /** Called on the UI thread whenever apps() or windows() changed. */
    void setListener(Runnable changed);

    interface Result {
        /** The content is on the screen. The input sink to attach for it (INPUT.md Target). */
        void shown(String key, InputSink input);

        /** It could not be opened. A short user-facing reason. */
        void failed(String key, String reason);
    }

    final class Entry {
        final String key;
        final String label;
        /** May be null: the chooser draws a letter icon. */
        final Bitmap icon;
        /** For a window: the window title, else null. */
        final String detail;
        final boolean running;

        Entry(String key, String label, Bitmap icon, String detail, boolean running) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.detail = detail;
            this.running = running;
        }
    }
}
