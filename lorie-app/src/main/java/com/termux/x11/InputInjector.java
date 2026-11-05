package com.termux.x11;

import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Turns mouse and key input into real Android events on one display. Lives in the shell-user process and must be
 * called from a single thread, because button changes sleep on purpose. Knows nothing about where input comes from.
 *
 * The rules here were found by testing on a real phone. `adb shell input mouse` is no alternative: it sets no
 * buttonState, so clicks do not work. Events must be SOURCE_MOUSE, TOOL_TYPE_MOUSE, with the hidden setDisplayId.
 */
final class InputInjector {
    /** Called with the logical pointer position after every movement. */
    interface CursorListener {
        void onCursor(int x, int y);
    }

    private static final String TAG = "XDeskInjector";
    private static final int FOCUS_SETTLE_MS = 150;
    private static final HashMap<Integer, Integer> KEYS = new HashMap<>();
    /** Android button bits, in the order they are released. */
    private static final int[] ALL_BUTTONS = {MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY,
        MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD};
    private static final int KEY_LEFTCTRL = 29, KEY_LEFTALT = 56;

    private static void map(int... pairs) {
        for (int i = 0; i < pairs.length; i += 2) KEYS.put(pairs[i], pairs[i + 1]);
    }

    static {
        map(16, KeyEvent.KEYCODE_Q, 17, KeyEvent.KEYCODE_W, 18, KeyEvent.KEYCODE_E, 19, KeyEvent.KEYCODE_R, 20, KeyEvent.KEYCODE_T,
            21, KeyEvent.KEYCODE_Y, 22, KeyEvent.KEYCODE_U, 23, KeyEvent.KEYCODE_I, 24, KeyEvent.KEYCODE_O, 25, KeyEvent.KEYCODE_P,
            30, KeyEvent.KEYCODE_A, 31, KeyEvent.KEYCODE_S, 32, KeyEvent.KEYCODE_D, 33, KeyEvent.KEYCODE_F, 34, KeyEvent.KEYCODE_G,
            35, KeyEvent.KEYCODE_H, 36, KeyEvent.KEYCODE_J, 37, KeyEvent.KEYCODE_K, 38, KeyEvent.KEYCODE_L,
            44, KeyEvent.KEYCODE_Z, 45, KeyEvent.KEYCODE_X, 46, KeyEvent.KEYCODE_C, 47, KeyEvent.KEYCODE_V, 48, KeyEvent.KEYCODE_B,
            49, KeyEvent.KEYCODE_N, 50, KeyEvent.KEYCODE_M);
        for (int i = 1; i <= 9; i++) KEYS.put(i + 1, KeyEvent.KEYCODE_0 + i);
        map(11, KeyEvent.KEYCODE_0, 1, KeyEvent.KEYCODE_ESCAPE, 12, KeyEvent.KEYCODE_MINUS, 13, KeyEvent.KEYCODE_EQUALS,
            14, KeyEvent.KEYCODE_DEL, 15, KeyEvent.KEYCODE_TAB, 26, KeyEvent.KEYCODE_LEFT_BRACKET, 27, KeyEvent.KEYCODE_RIGHT_BRACKET,
            28, KeyEvent.KEYCODE_ENTER, KEY_LEFTCTRL, KeyEvent.KEYCODE_CTRL_LEFT, 39, KeyEvent.KEYCODE_SEMICOLON,
            40, KeyEvent.KEYCODE_APOSTROPHE, 41, KeyEvent.KEYCODE_GRAVE, 42, KeyEvent.KEYCODE_SHIFT_LEFT, 43, KeyEvent.KEYCODE_BACKSLASH,
            51, KeyEvent.KEYCODE_COMMA, 52, KeyEvent.KEYCODE_PERIOD, 53, KeyEvent.KEYCODE_SLASH, 54, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KEY_LEFTALT, KeyEvent.KEYCODE_ALT_LEFT, 57, KeyEvent.KEYCODE_SPACE, 58, KeyEvent.KEYCODE_CAPS_LOCK,
            97, KeyEvent.KEYCODE_CTRL_RIGHT, 100, KeyEvent.KEYCODE_ALT_RIGHT, 102, KeyEvent.KEYCODE_MOVE_HOME, 103, KeyEvent.KEYCODE_DPAD_UP,
            104, KeyEvent.KEYCODE_PAGE_UP, 105, KeyEvent.KEYCODE_DPAD_LEFT, 106, KeyEvent.KEYCODE_DPAD_RIGHT, 107, KeyEvent.KEYCODE_MOVE_END,
            108, KeyEvent.KEYCODE_DPAD_DOWN, 109, KeyEvent.KEYCODE_PAGE_DOWN, 110, KeyEvent.KEYCODE_INSERT, 111, KeyEvent.KEYCODE_FORWARD_DEL,
            125, KeyEvent.KEYCODE_META_LEFT, 126, KeyEvent.KEYCODE_META_RIGHT, 127, KeyEvent.KEYCODE_MENU);
        for (int i = 0; i < 10; i++) KEYS.put(59 + i, KeyEvent.KEYCODE_F1 + i);
        map(87, KeyEvent.KEYCODE_F11, 88, KeyEvent.KEYCODE_F12, 69, KeyEvent.KEYCODE_NUM_LOCK, 70, KeyEvent.KEYCODE_SCROLL_LOCK,
            99, KeyEvent.KEYCODE_SYSRQ, 119, KeyEvent.KEYCODE_BREAK,
            71, KeyEvent.KEYCODE_NUMPAD_7, 72, KeyEvent.KEYCODE_NUMPAD_8, 73, KeyEvent.KEYCODE_NUMPAD_9, 74, KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
            75, KeyEvent.KEYCODE_NUMPAD_4, 76, KeyEvent.KEYCODE_NUMPAD_5, 77, KeyEvent.KEYCODE_NUMPAD_6, 78, KeyEvent.KEYCODE_NUMPAD_ADD,
            79, KeyEvent.KEYCODE_NUMPAD_1, 80, KeyEvent.KEYCODE_NUMPAD_2, 81, KeyEvent.KEYCODE_NUMPAD_3, 82, KeyEvent.KEYCODE_NUMPAD_0,
            83, KeyEvent.KEYCODE_NUMPAD_DOT, 96, KeyEvent.KEYCODE_NUMPAD_ENTER, 98, KeyEvent.KEYCODE_NUMPAD_DIVIDE, 55, KeyEvent.KEYCODE_NUMPAD_MULTIPLY);
    }

    private static int metaOf(int code) {
        switch (code) {
            case 42: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case 54: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
            case KEY_LEFTCTRL: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case 97: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
            case KEY_LEFTALT: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case 100: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
            case 125: return KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
            case 126: return KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON;
            default: return 0;
        }
    }

    private final Object inputManager;
    private final Method inject, setDisplayMotion, setDisplayKey, setActionButton;
    private final CursorListener cursor;
    private final HashSet<Integer> held = new HashSet<>();
    private AppInputProfile profile = AppInputProfile.forPackage(null);
    private int display, width, height, buttons, meta;
    private float x, y, nudge;
    private long downTime;
    /** Hover method only: the press in progress goes to the app's own UI, see button(). */
    private boolean realPress, menuOpen;

    /** Hidden APIs: fine here, because a process started through app_process has no hidden API restrictions. */
    InputInjector(CursorListener cursor) throws ReflectiveOperationException {
        this.cursor = cursor;
        Class<?> manager;
        try {
            manager = Class.forName("android.hardware.input.InputManagerGlobal");  // Android 14 and newer
        } catch (ClassNotFoundException e) {
            manager = Class.forName("android.hardware.input.InputManager");
        }
        inputManager = manager.getMethod("getInstance").invoke(null);
        inject = manager.getMethod("injectInputEvent", InputEvent.class, int.class);
        setDisplayMotion = MotionEvent.class.getMethod("setDisplayId", int.class);
        setDisplayKey = KeyEvent.class.getMethod("setDisplayId", int.class);
        setActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
    }

    void setProfile(AppInputProfile p) {
        profile = p;
    }

    /** Use the logical size: a 2560x1440 monitor reports 2560x1508 on the phone this was tested on. */
    void setDisplay(int id, int w, int h) {
        boolean first = width == 0;
        display = id;
        width = w;
        height = h;
        x = first ? w / 2f : Math.min(x, w - 1);
        y = first ? h / 2f : Math.min(y, h - 1);
    }

    int width() {
        return width;
    }

    private void send(InputEvent e) throws ReflectiveOperationException {
        inject.invoke(inputManager, e, 0);
    }

    private void mouse(int action, int actionButton, float vScroll, float hScroll) throws ReflectiveOperationException {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x + nudge;
        c.y = y;
        c.size = 1f;
        c.pressure = buttons != 0 ? 1f : 0f;
        if (action == MotionEvent.ACTION_SCROLL) {
            c.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
            c.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        }
        long now = SystemClock.uptimeMillis();
        boolean inGesture = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_MOVE
            || action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE;
        MotionEvent e = MotionEvent.obtain(inGesture ? downTime : now, now, action, 1,
            new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
            meta, buttons, 1f, 1f, -1, 0, InputDevice.SOURCE_MOUSE, 0);
        setDisplayMotion.invoke(e, display);
        if (actionButton != 0) setActionButton.invoke(e, actionButton);
        send(e);
        e.recycle();
    }

    private boolean inPress() {
        return buttons != 0 && (!profile.hoverButtons || realPress);
    }

    /** Pixels on the display, already scaled by the caller. */
    void moveBy(float dx, float dy) throws ReflectiveOperationException {
        x = Math.max(0, Math.min(width - 1, x + dx));
        y = Math.max(0, Math.min(height - 1, y + dy));
        mouse(inPress() ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
        cursor.onCursor((int) x, (int) y);
    }

    void scroll(float vertical, float horizontal) throws ReflectiveOperationException {
        mouse(MotionEvent.ACTION_SCROLL, 0, vertical, horizontal);
    }

    /** @param bit a MotionEvent.BUTTON_* value */
    void button(int bit, boolean down) throws ReflectiveOperationException, InterruptedException {
        if (down == ((buttons & bit) != 0)) return;
        if (profile.hoverButtons) hoverMethodButton(bit, down);
        else normalButton(bit, down);
    }

    /** What Android itself sends for a mouse. Generic apps expect this. */
    private void normalButton(int bit, boolean down) throws ReflectiveOperationException {
        if (down) {
            boolean first = buttons == 0;
            if (first) {
                mouse(MotionEvent.ACTION_HOVER_EXIT, 0, 0, 0);
                downTime = SystemClock.uptimeMillis();
            }
            buttons |= bit;
            if (first) mouse(MotionEvent.ACTION_DOWN, 0, 0, 0);
            mouse(MotionEvent.ACTION_BUTTON_PRESS, bit, 0, 0);
        } else {
            buttons &= ~bit;
            mouse(MotionEvent.ACTION_BUTTON_RELEASE, bit, 0, 0);
            if (buttons == 0) {
                mouse(MotionEvent.ACTION_UP, 0, 0, 0);
                mouse(MotionEvent.ACTION_HOVER_ENTER, 0, 0, 0);
                mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
            }
        }
    }

    /**
     * RustDesk's remote picture. An Android ACTION_DOWN becomes both a mouse and a touch pointer in Flutter. The touch
     * sets RustDesk's isPhysicalMouse to false and every move during the press is lost, so dragging fails. Changing the
     * device id or adding HOVER_EXIT does not help. So everything, button changes included, goes as ACTION_HOVER_MOVE
     * with buttonState set: RustDesk's _getMouseEvent treats a change of buttons as a press or release.
     * ACTION_BUTTON_PRESS/RELEASE reach Flutter as hovers and break the press, so they are not sent on this path.
     *
     * RustDesk's own UI (toolbar and its menus) is the exception: it needs real DOWN/UP and hover clicks do nothing.
     * Real presses go to the bottom part of the display, and to the click right after a toolbar click, because a menu
     * is open then and covers the remote picture. This is a heuristic. Known weakness: a toolbar click that opens no
     * menu makes the next click go to RustDesk instead of the remote machine.
     */
    private void hoverMethodButton(int bit, boolean down) throws ReflectiveOperationException, InterruptedException {
        float toolbarTop = height * (1f - profile.toolbarFraction);
        if (down && buttons == 0) realPress = y >= toolbarTop || menuOpen;
        if (realPress) {
            if (down) {
                boolean first = buttons == 0;
                if (first) {
                    downTime = SystemClock.uptimeMillis();
                    nudge = 0f;
                }
                buttons |= bit;
                mouse(first ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE, 0, 0, 0);
            } else {
                buttons &= ~bit;
                if (buttons == 0) {
                    mouse(MotionEvent.ACTION_UP, 0, 0, 0);
                    menuOpen = y >= toolbarTop;
                    realPress = false;
                    Thread.sleep(30);
                    mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
                } else {
                    mouse(MotionEvent.ACTION_MOVE, 0, 0, 0);
                }
            }
            return;
        }
        // Flutter's LocationNeedsUpdate drops a hover whose position has not changed, along with its button change,
        // so a click without movement would vanish: move one pixel on each change. Android also merges hovers within
        // a frame and keeps the first one's buttons, so each change gets its own frame.
        Thread.sleep(20);
        if (down) buttons |= bit;
        else buttons &= ~bit;
        nudge = nudge == 0f ? (x >= width - 2 ? -1f : 1f) : 0f;
        mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
        Thread.sleep(40);
    }

    /** @param value 1 down, 0 up, 2 repeat, as in evdev. Keys only reach the display that has focus, see focus(). */
    void key(int linuxCode, int value) throws ReflectiveOperationException {
        Integer code = KEYS.get(linuxCode);
        if (code == null) {
            Log.i(TAG, "unmapped key " + linuxCode);
            return;
        }
        int m = metaOf(linuxCode);
        if (value == 1) {
            meta |= m;
            held.add(linuxCode);
        } else if (value == 0) {
            meta &= ~m;
            held.remove(linuxCode);
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent e = new KeyEvent(now, now, value == 0 ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN, code, value == 2 ? 1 : 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, linuxCode, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        setDisplayKey.invoke(e, display);
        send(e);
    }

    /** Text from the phone's input method. Returns how many characters have no key on a keyboard and were dropped. */
    int text(String s) throws ReflectiveOperationException {
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        int dropped = 0;
        for (int i = 0; i < s.length(); i++) {
            KeyEvent[] events = map.getEvents(new char[]{s.charAt(i)});
            if (events == null) {
                dropped++;
                continue;
            }
            for (KeyEvent k : events) {
                KeyEvent e = new KeyEvent(k.getDownTime(), k.getEventTime(), k.getAction(), k.getKeyCode(), k.getRepeatCount(),
                    k.getMetaState() | meta, k.getDeviceId(), k.getScanCode(), k.getFlags(), k.getSource());
                setDisplayKey.invoke(e, display);
                send(e);
            }
        }
        return dropped;
    }

    /**
     * Injected keys only reach a display with a focused window, and touching the phone takes focus away.
     * Failure is not fatal: mouse input is delivered by position and keeps working.
     */
    void focus(int displayId) {
        try {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "activity_task");
            Object atm = Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            atm.getClass().getMethod("focusTopTask", int.class).invoke(atm, displayId);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "focus failed", e);
        }
    }

    int displayId() {
        return display;
    }

    /** Input was just taken for this display. */
    void take() throws ReflectiveOperationException, InterruptedException {
        focus(display);
        Thread.sleep(FOCUS_SETTLE_MS);
        if (!profile.hoverButtons) mouse(MotionEvent.ACTION_HOVER_ENTER, 0, 0, 0);
        mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
        cursor.onCursor((int) x, (int) y);
    }

    /** Release every held key and button. Runs when physical input goes back to the phone and when stopping. */
    void releaseAll() throws ReflectiveOperationException, InterruptedException {
        for (Integer code : new ArrayList<>(held)) key(code, 0);
        for (int bit : ALL_BUTTONS) button(bit, false);
    }

    void hoverExit() throws ReflectiveOperationException {
        if (!profile.hoverButtons) mouse(MotionEvent.ACTION_HOVER_EXIT, 0, 0, 0);
    }

    /**
     * RustDesk caches Ctrl, Alt and so on and adds them to every mouse event, and on a Mac Ctrl plus left click is a
     * right click. A key-up alone does not clear the cache: it takes a full press and release of each, sent once both
     * are physically up. Only for apps that need it: Alt on its own opens menus elsewhere.
     */
    void clearModifiers() throws ReflectiveOperationException, InterruptedException {
        if (!profile.clearModifiers) return;
        meta = 0;
        held.clear();
        for (int code : new int[]{KEY_LEFTCTRL, KEY_LEFTALT}) {
            key(code, 1);
            Thread.sleep(20);
            key(code, 0);
            Thread.sleep(20);
        }
    }
}
