package com.termux.x11;

/** From the X server to XDesk: the windows the window manager manages, see XWindows and xwindows.c. */
oneway interface IXWindowListener {
    /**
     * Every window that is an app, one line each, UTF-8: id, _NET_WM_PID, pid of its X client, flags (1 fullscreen,
     * 2 active, 4 minimized), WM_CLASS instance, WM_CLASS class, _NET_STARTUP_ID, title; separated by tabs.
     */
    void windows(in byte[] snapshot) = 1;
    /** A window's icon as ARGB, nearest to 48 px; width 0 when it has none. Sent for new windows and when one changes. */
    void icon(int window, int width, int height, in int[] argb) = 2;
}
