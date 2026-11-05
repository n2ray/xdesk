package com.termux.x11;

/** Where the phone's touchpad and keyboard go: a Termux desktop (DesktopInput) or an Android app (AppSession). */
interface InputSink {
    void move(int dx, int dy);
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
