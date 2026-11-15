package com.termux.x11;

/**
 * The corners' push zone, as pure geometry: whether the pointer is on an edge inside a top corner's square, whose
 * side is {@code corner} pixels (48 shell dp). At the top-right corner: on the right edge inside it the push goes
 * right, on the top edge inside it the push goes up (NOTIFY.md §4.1). At the top-left: left and up (MACMENU.md
 * §3). Anywhere else along those edges is the content's own (a tab bar, a scrollbar, a window's traffic lights),
 * and the centre or the menu must not open there. AppSession and Notify share the right corner, DockPointer and
 * MacMenus the left one, so the injector's pushes and the driven pointer judge each corner the same way.
 */
final class NotifyCorner {
    private NotifyCorner() { }

    /**
     * The corner's push edge: LEFT on the left edge's corner part, TOP on the top edge's, 0 anywhere else; the
     * top-right corner's mirror (EdgePush.RIGHT, EdgePush.TOP) with {@code left} false.
     */
    static int edge(int x, int y, int width, int height, int corner, boolean left) {
        if (left) {
            if (x >= corner || y > corner) return 0;
            if (x <= 0) return EdgePush.LEFT;
            if (y <= 0) return EdgePush.TOP;
            return 0;
        }
        if (x < width - corner || y > corner) return 0;
        if (x >= width - 1) return EdgePush.RIGHT;
        if (y <= 0) return EdgePush.TOP;
        return 0;
    }
}
