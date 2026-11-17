package com.termux.x11;

import android.os.SystemClock;

/**
 * When the external Dock, the notification centre or the Mac menu is called, the same for every kind of content
 * (DISPLAY.md §7, NOTIFY.md §4.1, MACMENU.md §3): the pointer is at the edge of the screen and is pushed further
 * out. Only what goes beyond the edge counts, so each move is given here as it was asked for, not as it was clamped.
 * The push has to last (DWELL_MS) and
 * to be far enough (PUSH), without a pause longer than GAP_MS. Only the outward part of a move counts, and a move
 * more than SIDE_DEGREES from straight out counts for nothing: a push need not be straight (a finger on a phone's
 * slippery glass rarely is, 17 November: the Dock seemed to open only when pushed exactly down), and sliding along
 * the edge, along a taskbar, still opens nothing. Resting on the edge
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

    private static final long DWELL_MS = 350, DESKTOP_DWELL_MS = 500, GAP_MS = 250;
    /** In units of pointer movement, about pixels of the content. Over a desktop 240 and 700 ms until 17 November. */
    private static final int PUSH = 60, DESKTOP_PUSH = 150;
    /** tan 60°: a move whose sideways part is more than this times its outward part is along the edge. */
    private static final float SIDE_RATIO = 1.73f;

    private long edgeSince, lastPush;
    private int push;

    /** The pointer left the edge, moved away, a button is held: whatever was counted is void. */
    void forget() {
        edgeSince = 0;
        push = 0;
    }

    /** How far the push is towards opening, 0 to 1, for showing it (ExternalDock.pushProgress). */
    float progress(boolean desktop) {
        if (edgeSince == 0 || SystemClock.uptimeMillis() - lastPush > GAP_MS) return 0;
        float t = (float) (SystemClock.uptimeMillis() - edgeSince) / (desktop ? DESKTOP_DWELL_MS : DWELL_MS);
        float p = (float) push / (desktop ? DESKTOP_PUSH : PUSH);
        return Math.max(0, Math.min(1, Math.min(t, p)));
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
        // Along the edge more than against it: not part of a push, and no reason to forget one either
        if (Math.abs(sideways) > along * SIDE_RATIO) return false;
        long now = SystemClock.uptimeMillis();
        if (edgeSince == 0 || now - lastPush > GAP_MS) {
            edgeSince = lastPush = now;
            push = 0;
        }
        if (along > 0) lastPush = now;
        push += along;
        if (now - edgeSince < (desktop ? DESKTOP_DWELL_MS : DWELL_MS) || push < (desktop ? DESKTOP_PUSH : PUSH)) return false;
        forget();
        return true;
    }
}
