package com.termux.x11;

/** Events from the injector process back to the app. */
oneway interface IInjectorListener {
    /** The pointer moved to these logical coordinates on the external display. */
    void onCursor(int x, int y) = 1;
    /** Physical devices were grabbed or released, from a phone button or the hotkey. */
    void onForwarding(boolean on, int deviceCount) = 2;
    void onMessage(String message) = 3;
    /** Whether the app looks like it is in a session where it draws its own cursor, see AppInputProfile. */
    void onAppInSession(boolean inSession) = 4;
    /** A mouse button was released. */
    void onButtonUp() = 5;
    /** What Back left in front on the display: a package, or empty when no normal app is showing. */
    void onTopApp(String packageName) = 6;
    /** 触控 mode: where the fingers are on the display, in pixels. Empty when none are down. */
    void onFingers(in float[] x, in float[] y) = 7;
    /**
     * A relative move (a grabbed mouse, the phone's touchpad) asked for while the pointer was already at an edge of
     * the display, in pixels: what clamping takes away, and what the Dock (BOTTOM) and the notification centre (TOP,
     * RIGHT, NOTIFY.md §4.1) open by (EdgePush). edge is EdgePush.BOTTOM, TOP or RIGHT. x, y are the pointer's place
     * at that edge (only the centre's corner counts, so ordinary tab bars and scrollbars along the rest of an edge
     * are left alone). left: the pointer has left the edge, an explicit signal instead of the old (0, -1) that meant
     * an outward move for TOP. held: a button or a finger is down.
     */
    void onEdgePush(int edge, int dx, int dy, boolean held, int x, int y, boolean left) = 8;
    /** Game passthrough went on or off (GAMING.md): by Ctrl+Alt+G, by setGameMode, or left by a touch on the phone. */
    void onGameMode(boolean on) = 9;
}
