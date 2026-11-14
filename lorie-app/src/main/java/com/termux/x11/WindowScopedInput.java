package com.termux.x11;

import io.github.n2ray.xdesk.input.Caps;

/**
 * The X desktop's input, seen as one window (INPUT.md §1.1, §6): a single Linux program shown full screen is a window,
 * so Home, overview and app switching are XDesk's (the switcher), not xfwm4's show-desktop or Alt+Tab through every
 * X window. Everything else goes to the desktop sink unchanged.
 */
final class WindowScopedInput implements InputSink {
    private final InputSink desktop;

    WindowScopedInput(InputSink desktop) {
        this.desktop = desktop;
    }

    InputSink desktop() {
        return desktop;
    }

    @Override public Caps caps() { return Caps.LINUX_WINDOW; }
    @Override public boolean scrollFine(float dy, float dx, int phase) { return desktop.scrollFine(dy, dx, phase); }
    @Override public void move(int dx, int dy) { desktop.move(dx, dy); }
    @Override public void moveToFraction(float x, float y) { desktop.moveToFraction(x, y); }
    @Override public void pointerGone() { desktop.pointerGone(); }
    @Override public void browserLayout(String layout) { desktop.browserLayout(layout); }
    /** Back on a Linux window: Alt+← (Linux key codes 56, 105), as INPUT.md §3.4 says for X. */
    @Override public void androidButton(int button) {
        if (button != 1) { desktop.androidButton(button); return; }
        desktop.key(56, true);
        desktop.tapKey(105);
        desktop.key(56, false);
    }
    @Override public void chooseApp(String key) { desktop.chooseApp(key); }
    @Override public boolean anyThread() { return desktop.anyThread(); }
    @Override public void button(int button, boolean down) { desktop.button(button, down); }
    @Override public void click(int button) { desktop.click(button); }
    @Override public void scroll(int dy, int dx) { desktop.scroll(dy, dx); }
    @Override public void key(int scan, boolean down) { desktop.key(scan, down); }
    @Override public boolean takesRepeats() { return desktop.takesRepeats(); }
    @Override public void tapKey(int scan) { desktop.tapKey(scan); }
    @Override public void text(String s) { desktop.text(s); }
}
