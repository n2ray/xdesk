package com.termux.x11;

/**
 * NOTIFY.md §4.1's corner, as pure geometry: whether the pointer is on an edge inside the top-right corner's square,
 * whose side is {@code corner} pixels (48 shell dp). On the right edge inside it the push goes right, on the top edge
 * inside it the push goes up; anywhere else along those edges is the content's own (a tab bar, a scrollbar), and the
 * notification centre must not open there. AppSession and Notify share this, so the injector's pushes and the
 * driven pointer judge the corner the same way.
 */
final class NotifyCorner {
    private NotifyCorner() { }

    /** EdgePush.RIGHT on the right edge's corner part, EdgePush.TOP on the top edge's, 0 anywhere else. */
    static int edge(int x, int y, int width, int height, int corner) {
        if (x < width - corner || y > corner) return 0;
        if (x >= width - 1) return EdgePush.RIGHT;
        if (y <= 0) return EdgePush.TOP;
        return 0;
    }
}
