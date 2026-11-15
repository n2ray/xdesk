package com.termux.x11;

/**
 * The driven pointer's hover-grab over the notification centre's windows (NOTIFY.md §3, §4.1), as pure geometry: the
 * part of the rule that keeps the pointer from being trapped. While a window is under the content's pointer, moves
 * are the window's (the arrow moves, the content's pointer stays where it was); when the arrow leaves the window the
 * grab ends, and what the arrow moved by goes back to the content, so the content's pointer ends where the arrow
 * left — outside the window, where a new grab cannot start. The window's place is then not grabbed again until the
 * content's pointer has left it; a window that appeared under a resting pointer never takes it either (Notify closes
 * its rect when the banner shows). Notify feeds it the moves and the arrow's place; rectangles are plain integers,
 * so this is testable without Android.
 */
final class NotifyGrab {
    /** The rect a grab ended from (or a window that appeared under the pointer); noLeft > noRight when there is none. */
    private int noLeft, noTop, noRight, noBottom;
    /** Where the content's pointer was when the window took it: the arrow's start. */
    private int grabX, grabY;

    /** The content's pointer moved to this place: the closed rect clears once it left it. */
    void moved(int x, int y) {
        if (hasRect() && (x < noLeft || x >= noRight || y < noTop || y >= noBottom)) clear();
    }

    /** Whether a window that holds this place may take the pointer. Never while the pointer rests in the closed rect. */
    boolean mayGrab(int x, int y) {
        return !hasRect() || x < noLeft || x >= noRight || y < noTop || y >= noBottom;
    }

    /** A window at this place took the pointer: the arrow starts under the content's pointer. */
    void grabbed(int x, int y) {
        grabX = x;
        grabY = y;
        clear();
    }

    /**
     * The arrow left the window whose rect is given: the grab ends, the rect closes (no new grab until the content's
     * pointer left it), and what the arrow moved by comes back — the content's pointer must move by it, so it ends
     * where the arrow left, outside the rect.
     */
    int[] release(int left, int top, int right, int bottom, int arrowX, int arrowY) {
        noLeft = left;
        noTop = top;
        noRight = right;
        noBottom = bottom;
        return new int[]{arrowX - grabX, arrowY - grabY};
    }

    /** A window appeared under the resting pointer: it never takes it, as if a grab had just ended on its rect. */
    void closed(int left, int top, int right, int bottom) {
        noLeft = left;
        noTop = top;
        noRight = right;
        noBottom = bottom;
    }

    private boolean hasRect() {
        return noLeft <= noRight;
    }

    private void clear() {
        noLeft = 1;
        noRight = 0;  // noLeft > noRight: no rect
    }
}
