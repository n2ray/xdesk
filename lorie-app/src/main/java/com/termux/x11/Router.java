package com.termux.x11;

import io.github.n2ray.xdesk.input.Caps;
import io.github.n2ray.xdesk.input.Input;
import io.github.n2ray.xdesk.input.WheelSteps;

/**
 * Every input device's way to the current target (INPUT.md §9 Router, phase 1: one content per screen).
 *
 * <ul>
 * <li>The target is whatever the host says at the moment of each event: never cached, so the switcher or a new
 * content takes over at once.</li>
 * <li>A press binds its device to the target it went to until everything that device holds is up (§0.5, §10 case
 * 19): a drag that started on an app ends on that app even if the switcher opened meanwhile.</li>
 * <li>Held keys and buttons are counted per device (§2), so cancel lets go of exactly what that device holds.</li>
 * <li>Semantic actions (§3.4) become keys for a whole desktop, and go to XDesk for a single app or window.</li>
 * </ul>
 *
 * No allocation per event. A device is used from one thread at a time (the phone's UI thread, or the network thread
 * of a receiver).
 */
final class Router {
    interface Host {
        /** The current target, null without a session. */
        InputSink current();
        /** An action XDesk does itself (Android apps, single windows, the switcher). The target it was meant for. */
        void hostAction(InputSink target, int action, int arg);
    }

    /** Devices, §2's source device ids. */
    static final int PHONE_PAD = 0, PHONE_KEYS = 1, PHONE_MOUSE = 2, REMOTE = 3;
    private static final int DEVICES = 4, MAX_KEYS = 8;

    // Linux key codes used for actions on desktops
    static final int KEY_ESC = 1, KEY_MINUS = 12, KEY_EQUAL = 13, KEY_TAB = 15, KEY_W = 17, KEY_LEFTBRACE = 26,
        KEY_RIGHTBRACE = 27, KEY_LEFTCTRL = 29, KEY_D = 32, KEY_LEFTSHIFT = 42, KEY_LEFTALT = 56, KEY_F4 = 62,
        KEY_F11 = 87, KEY_UP = 103, KEY_LEFT = 105, KEY_RIGHT = 106, KEY_LEFTMETA = 125;

    private final Host host;
    private final Device[] devices = new Device[DEVICES];

    Router(Host host) {
        this.host = host;
        for (int i = 0; i < DEVICES; i++) devices[i] = new Device(i);
    }

    Device device(int id) {
        return devices[id];
    }

    /** Everything every device holds goes up: the session ended, the phone locked, a call came (§2 cancel). */
    void cancelAll() {
        for (Device d : devices) d.cancel();
    }

    /** One device's input. It is an InputSink, so code that sent to the target sends to this instead. */
    final class Device implements InputSink {
        final int id;
        /** Where everything goes while something is held; null when nothing is. */
        private InputSink bound;
        private int buttons;
        private final int[] keys = new int[MAX_KEYS];
        private int keyCount;
        /** Held app switching on a desktop: the modifier that is down, 0 when none. */
        private int switchModifier;
        private final WheelSteps wheel = new WheelSteps();

        Device(int id) {
            this.id = id;
        }

        private boolean holding() {
            return buttons != 0 || keyCount > 0 || switchModifier != 0;
        }

        /** The target for this event. */
        private InputSink target() {
            InputSink b = bound;
            return b != null ? b : host.current();
        }

        private InputSink bindTo() {
            if (bound == null) bound = host.current();
            return bound;
        }

        private void unbindIfFree() {
            if (!holding()) bound = null;
        }

        @Override public Caps caps() {
            InputSink t = target();
            return t != null ? t.caps() : null;
        }

        @Override public void move(int dx, int dy) {
            InputSink t = target();
            if (t != null) t.move(dx, dy);
        }

        @Override public void moveToFraction(float x, float y) {
            InputSink t = target();
            if (t != null) t.moveToFraction(x, y);
        }

        @Override public void pointerGone() {
            InputSink t = target();
            if (t != null) t.pointerGone();
        }

        @Override public void browserLayout(String layout) {
            InputSink t = target();
            if (t != null) t.browserLayout(layout);
        }

        @Override public void chooseApp(String key) {
            InputSink t = target();
            if (t != null) t.chooseApp(key);
        }

        @Override public void androidButton(int button) {
            if (button == Input.BACK || button == Input.HOME) action(button, 0);
            else {
                InputSink t = target();
                if (t != null) t.androidButton(button);
            }
        }

        @Override public void button(int button, boolean down) {
            int bit = button > 0 && button < 31 ? 1 << button : 0;
            InputSink t;
            if (down) {
                t = bindTo();
                buttons |= bit;
            } else {
                t = target();
                buttons &= ~bit;
            }
            if (t != null) t.button(button, down);
            if (!down) unbindIfFree();
        }

        @Override public void click(int button) {
            if (holding()) {
                // Bound: both halves go where the held things go
                InputSink t = bound;
                if (t != null) t.click(button);
                return;
            }
            InputSink t = host.current();
            if (t != null) t.click(button);
        }

        @Override public void scroll(int dy, int dx) {
            InputSink t = target();
            if (t != null) t.scroll(dy, dx);
        }

        /** Fine scrolling from a gesture; whole steps for a target that takes only those. */
        @Override public boolean scrollFine(float dy, float dx, int phase) {
            InputSink t = target();
            if (t == null) return true;
            if (phase == Input.BEGIN) wheel.clear();
            if (t.scrollFine(dy, dx, phase)) return true;
            if ((dx != 0 || dy != 0) && wheel.add(dx, dy)) t.scroll(wheel.y(), wheel.x());
            return true;
        }

        @Override public void key(int scan, boolean down) {
            InputSink t;
            if (down) {
                t = bindTo();
                addKey(scan);
            } else {
                t = target();
                removeKey(scan);
            }
            if (t != null) t.key(scan, down);
            if (!down) unbindIfFree();
        }

        private void addKey(int scan) {
            for (int i = 0; i < keyCount; i++) if (keys[i] == scan) return;
            if (keyCount < MAX_KEYS) keys[keyCount++] = scan;
        }

        private void removeKey(int scan) {
            for (int i = 0; i < keyCount; i++) {
                if (keys[i] != scan) continue;
                keys[i] = keys[--keyCount];
                return;
            }
        }

        @Override public void tapKey(int scan) {
            InputSink t = target();
            if (t != null) t.tapKey(scan);
        }

        @Override public void text(String s) {
            InputSink t = target();
            if (t != null) t.text(s);
        }

        @Override public boolean anyThread() {
            InputSink t = target();
            return t != null && t.anyThread();
        }

        /** §2 cancel: what this device holds goes up where it went down. A drag stops where it is. */
        void cancel() {
            InputSink t = bound;
            if (t != null) {
                for (int b = 1; b < 31; b++) if ((buttons & 1 << b) != 0) t.button(b, false);
                for (int i = keyCount - 1; i >= 0; i--) t.key(keys[i], false);
                if (switchModifier != 0) t.key(switchModifier, false);
            }
            buttons = 0;
            keyCount = 0;
            switchModifier = 0;
            bound = null;
        }

        /** A semantic action (§3.4): keys for a whole desktop, XDesk's own work for an app or a window. */
        void action(int action, int arg) {
            InputSink t = action == Input.SWITCH_APP && switchModifier != 0 ? bound : target();
            Caps c = t != null ? t.caps() : null;
            if (t == null || c == null || c.scope != Caps.DESKTOP || !desktopAction(t, c, action, arg)) {
                host.hostAction(t, action, arg);
            }
        }

        private boolean desktopAction(InputSink t, Caps c, int action, int arg) {
            boolean mac = c.macKeys;
            switch (action) {
                case Input.BACK:
                    combo(t, mac ? KEY_LEFTMETA : KEY_LEFTALT, mac ? KEY_LEFTBRACE : KEY_LEFT);
                    return true;
                case Input.FORWARD:
                    combo(t, mac ? KEY_LEFTMETA : KEY_LEFTALT, mac ? KEY_RIGHTBRACE : KEY_RIGHT);
                    return true;
                case Input.HOME:
                    // Show the desktop: XFCE's default Ctrl+Alt+D; a Mac's F11
                    if (mac) t.tapKey(KEY_F11);
                    else {
                        t.key(KEY_LEFTCTRL, true);
                        combo(t, KEY_LEFTALT, KEY_D);
                        t.key(KEY_LEFTCTRL, false);
                    }
                    return true;
                case Input.OVERVIEW:
                    // Mission Control on a Mac (Ctrl+Up). The X desktop's overview is XDesk's switcher (DISPLAY.md)
                    if (!mac) return false;
                    combo(t, KEY_LEFTCTRL, KEY_UP);
                    return true;
                case Input.SWITCH_APP:
                    switchApp(t, mac ? KEY_LEFTMETA : KEY_LEFTALT, arg);
                    return true;
                case Input.ZOOM:
                    if (mac) combo(t, KEY_LEFTMETA, arg > 0 ? KEY_EQUAL : KEY_MINUS);
                    else {
                        t.key(KEY_LEFTCTRL, true);
                        t.scroll(arg > 0 ? -1 : 1, 0);
                        t.key(KEY_LEFTCTRL, false);
                    }
                    return true;
                case Input.CLOSE_WINDOW:
                    combo(t, mac ? KEY_LEFTMETA : KEY_LEFTALT, mac ? KEY_W : KEY_F4);
                    return true;
                case Input.FULLSCREEN:
                    t.tapKey(KEY_F11);
                    return true;
                default:
                    return false;
            }
        }

        private void combo(InputSink t, int modifier, int key) {
            t.key(modifier, true);
            t.tapKey(key);
            t.key(modifier, false);
        }

        /**
         * "Held" switching (§3.4): the modifier stays down while the fingers do, each step is a Tab (Shift+Tab back),
         * lifting lets go. arg 0 is the end.
         */
        private void switchApp(InputSink t, int modifier, int arg) {
            if (arg == 0) {
                if (switchModifier != 0) t.key(switchModifier, false);
                switchModifier = 0;
                unbindIfFree();
                return;
            }
            if (switchModifier == 0) {
                bindTo();
                switchModifier = modifier;
                t.key(modifier, true);
            }
            if (arg < 0) t.key(KEY_LEFTSHIFT, true);
            t.tapKey(KEY_TAB);
            if (arg < 0) t.key(KEY_LEFTSHIFT, false);
        }
    }
}
