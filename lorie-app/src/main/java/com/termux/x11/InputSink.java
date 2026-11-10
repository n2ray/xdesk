package com.termux.x11;

/** Where the phone's touchpad and keyboard go: a Termux desktop (DesktopInput) or an Android app (AppSession). */
interface InputSink {
    void move(int dx, int dy);
    /** To this place on the display, 0 to 1 across and down. Only for pointers that know where they are, as a browser's. */
    default void moveToFraction(float x, float y) { }
    /** That pointer is gone: the display's own cursor comes back. */
    default void pointerGone() { }
    /** A browser is here, with a keyboard of this layout: de, fr, it, es, gb or us. Again when it finds out better. */
    default void browserLayout(String layout) { }
    /** 1 Back, 2 Home, 3 the list of apps. */
    default void androidButton(int button) { }
    /** One of the keys sent with the list of apps. */
    default void chooseApp(String key) { }
    /** Whether input may be handed over straight from the network thread, for the least delay. */
    default boolean anyThread() { return false; }
    /** X numbered: 1 left, 2 middle, 3 right. */
    void button(int button, boolean down);
    void click(int button);
    /** dy > 0 is down, dx > 0 is right, in wheel steps. */
    void scroll(int dy, int dx);
    /** Linux key code. */
    void key(int scan, boolean down);
    void tapKey(int scan);
    void text(String s);
}
