package com.termux.x11;

import android.os.SystemClock;

/**
 * When the external Dock, the notification centre or the Mac menu is called, the same for every kind of content
 * (DISPLAY.md §7, NOTIFY.md §4.1, MACMENU.md §3): the pointer is at the edge of the screen and is pushed further
 * out. Only what goes beyond the edge counts, so each move is given here as it was asked for, not as it was clamped.
 * The push has to last (DWELL_MS) and
 * to be far enough (PUSH), without a pause longer than GAP_MS and more sideways than outwards. Resting on the edge
 * does not open anything, nor does a flick that lands there: a taskbar, a tab or a navigation bar at the bottom of
 * the picture is clicked exactly so.
 *
 * A whole desktop (XFCE, a Mac's screen) has things of its own at that edge, a panel, the Mac's Dock that shows itself
 * when the pointer reaches the edge: there the push is DESKTOP_DWELL_MS and DESKTOP_PUSH, long after the Mac's Dock
 * has come, so a touch of the edge is the Mac's and a push kept up is XDesk's.
 *
 * The one implementation of the rule: DockPointer feeds it over X and a Mac (the phone's own moves, the content
 * draws the pointer), ExternalDock and Notify over an Android app (the injector's moves, reported from its process).
 * Whether the pointer is at the edge and whether a button is held is the caller's to know: it calls forget() when
 * not. Main thread.
 */
final class EdgePush {
    /** The edges the rule is told apart by. BOTTOM pushes down (the Dock), TOP up and RIGHT right (the centre),
     *  LEFT left (the Mac menu's corner). Never 0: everywhere an edge is passed around, 0 means "at no edge"
     *  (InputInjector.reportPush, NotifyCorner, DockPointer). BOTTOM was 0 once, and the injector then never reported
     *  a push at the bottom: no Dock over Android apps (2026-11-15). */
    static final int NONE = 0, BOTTOM = 4, TOP = 1, RIGHT = 2, LEFT = 3;

    private static final long DWELL_MS = 350, DESKTOP_DWELL_MS = 700, GAP_MS = 250;
    /** In units of pointer movement, about pixels of the content. */
    private static final int PUSH = 60, DESKTOP_PUSH = 240, SIDE_SLACK = 12;

    private long edgeSince, lastPush;
    private int push, side;

    /** The pointer left the edge, moved away, a button is held: whatever was counted is void. */
    void forget() {
        edgeSince = 0;
        push = side = 0;
    }

    /** The Dock's rule, unchanged: {@code pushed(BOTTOM, dx, dy, desktop)}. */
    boolean pushed(int dx, int dy, boolean desktop) {
        return pushed(BOTTOM, dx, dy, desktop);
    }

    /**
     * A move asked for while the pointer is at the edge with no button held.
     * @param edge BOTTOM, TOP or RIGHT: what "outwards" means for the moves given
     * @param desktop over a whole desktop, which has its own things at the edge: the longer push
     * @return true when this completes the push: open. It starts over then.
     */
    boolean pushed(int edge, int dx, int dy, boolean desktop) {
        // The move's part along the edge's outward direction, and its sideways part
        int along = edge == LEFT ? -dx : edge == BOTTOM ? dy : edge == TOP ? -dy : dx;
        int sideways = edge == RIGHT || edge == LEFT ? dy : dx;
        if (along < 0) {
            forget();
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (edgeSince == 0 || now - lastPush > GAP_MS) {
            edgeSince = lastPush = now;
            push = side = 0;
        }
        if (along > 0) lastPush = now;
        push += along;
        side += Math.abs(sideways);
        if (side > push + SIDE_SLACK) {
            // Along the edge, not against it: start again
            edgeSince = now;
            push = side = 0;
            return false;
        }
        if (now - edgeSince < (desktop ? DESKTOP_DWELL_MS : DWELL_MS) || push < (desktop ? DESKTOP_PUSH : PUSH)) return false;
        forget();
        return true;
    }
}
