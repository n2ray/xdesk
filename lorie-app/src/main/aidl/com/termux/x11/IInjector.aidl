package com.termux.x11;

import com.termux.x11.IInjectorListener;

/** The privileged input process. Runs as the shell user through Shizuku, see InjectorService. */
interface IInjector {
    /** Called by Shizuku when the service is removed. The id is fixed by Shizuku's API. */
    void destroy() = 16777114;

    /** Returns null when ready, otherwise why injection cannot work. */
    String configure(IInjectorListener listener, int displayId, int width, int height, String packageName, float mouseSpeed) = 1;
    oneway void setDisplay(int displayId, int width, int height) = 2;
    /** Grab the physical mouse and keyboard, or give them back to the phone. */
    oneway void setForwarding(boolean on) = 3;
    oneway void setMouseSpeed(float speed) = 4;
    /** The phone screen was touched, which moves focus away from the external display. */
    oneway void phoneTouched() = 5;

    // The phone UI: touchpad, shortcut row and soft keyboard. Buttons are X numbered (1 left, 2 middle, 3 right).
    oneway void move(int dx, int dy) = 6;
    oneway void button(int button, boolean down) = 7;
    oneway void scroll(int dy, int dx) = 8;
    oneway void key(int scan, boolean down) = 9;
    oneway void text(String text) = 10;
    oneway void tapKey(int scan) = 14;

    /** Returns null on success, otherwise the error. */
    String startApp(String component, int displayId) = 11;
    /** The window of another app presenting on this display, or null. Best effort. */
    String findPresentation(int displayId) = 12;
    /** Release everything that is held. Returns when that is done. */
    void shutdown() = 13;

    /** Another app is now on the display: release what is held, change the input method and refocus. */
    oneway void setApp(String packageName) = 15;
    oneway void setWatchSession(boolean watch) = 16;
    /** False while a Termux desktop is on the display: the hotkey must not grab anything. */
    oneway void setEnabled(boolean enabled) = 17;
    /** Left button as a finger, for this app only. Ignored for apps with their own mouse method. */
    oneway void setTouchMode(boolean on) = 20;
    /** Prefer this device for media on the whole system, type < 0 clears it. Returns null or the error. */
    String setAudioOutput(int type, String address) = 18;
    /** Connect without an app, for the sound route alone. Reads no input devices. */
    void attach(IInjectorListener listener) = 19;
}
