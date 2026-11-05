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
}
