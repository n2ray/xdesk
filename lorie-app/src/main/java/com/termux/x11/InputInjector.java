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
    /** How far apart the right button's two fingers are, as a part of the display height: 60 pixels on 1508. */
    private static final float TWO_FINGER_GAP_OF_HEIGHT = 60f / 1508f;
    /** One wheel tick in touch mode, as a part of the display height. Raise it to scroll further per tick. */
    private static final float SWIPE_STEP_FRACTION = 1f / 16f;
    private static final int SWIPE_MOVES_PER_TICK = 3;
    private static final int SWIPE_MOVE_MS = 6;
    /** Ticks closer together than this are one gesture. Long enough that the lift causes no fling. */
    static final long SWIPE_IDLE_MS = 250;
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
    /** Hover method only: a button that is down but not sent yet, and where it went down, see hoverMethodButton(). */
    private int pendingButton;
    private float pendingX, pendingY;
    /** See setTouchMode. */
    private boolean touchMode;
    /** Touch mode: the button holding fingers down, 0 for none. Left is one finger, right is two. */
    private int pressBit;
    private int touchDevice;
    /** A synthetic one-finger swipe from the wheel or two-finger scroll is in progress, see scroll(). */
    private boolean swiping;
    private float swipeX, swipeY;
    private long lastSwipe;

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

    /**
     * For apps and pages that only listen to touch, such as a mobile site's video player: the left button is a finger at
     * the cursor. Moving without a press sends nothing to the app, the cursor only moves on our overlay, so hover effects
     * do not exist here. The wheel stays a mouse wheel. An app with its own mouse method (RustDesk) never uses this.
     */
    void setTouchMode(boolean on) throws ReflectiveOperationException, InterruptedException {
        releaseAll();
        hoverExit();
        touchMode = on;
    }

    private boolean touching() {
        return touchMode && !profile.hoverButtons;
    }

    /** Same device id choice as the system's `input touchscreen`, which is what showed that touch works. */
    private static int touchDeviceId() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && d.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) return id;
        }
        return 0;
    }

    private void finger(int action) throws ReflectiveOperationException {
        fingerAt(action, x, y);
    }

    private void fingerAt(int action, float fingerX, float fingerY) throws ReflectiveOperationException {
        if (action == MotionEvent.ACTION_DOWN) touchDevice = touchDeviceId();
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = fingerX;
        c.y = fingerY;
        c.size = 1f;
        c.pressure = 1f;
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(downTime, now, action, 1,
            new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
            0, 0, 1f, 1f, touchDevice, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        setDisplayMotion.invoke(e, display);
        send(e);
        e.recycle();
    }

    /**
     * Left is one finger: press, hold and drag follow the button, so a hold is a long press. Right is two fingers, see
     * twoFingers. Other buttons do nothing.
     */
    private void touchButton(int bit, boolean down) throws ReflectiveOperationException {
        endSwipe();
        if (bit != MotionEvent.BUTTON_PRIMARY && bit != MotionEvent.BUTTON_SECONDARY) return;
        if (down) {
            // A second button while one holds fingers is ignored, and its release too, so nothing can stay down
            if (pressBit != 0) return;
            pressBit = bit;
            downTime = SystemClock.uptimeMillis();
            if (bit == MotionEvent.BUTTON_PRIMARY) {
                finger(MotionEvent.ACTION_DOWN);
            } else {
                touchDevice = touchDeviceId();
                twoFingers(MotionEvent.ACTION_DOWN, 1);
                twoFingers(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
            }
        } else if (pressBit == bit) {
            pressBit = 0;
            if (bit == MotionEvent.BUTTON_PRIMARY) {
                finger(MotionEvent.ACTION_UP);
            } else {
                twoFingers(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
                twoFingers(MotionEvent.ACTION_UP, 1);
            }
        }
    }

    /**
     * The right button is two fingers side by side at the cursor, moving together, so one button gives a two-finger tap,
     * long press or drag by how it is used. What that does is up to the app: many do nothing with two fingers, or read
     * them as a pinch or a scroll. At the display's edge the pair stops as one, and the spacing never changes.
     *
     * @param count pointers in this event: 1 is only id 0, 2 is ids 0 and 1
     */
    private void twoFingers(int action, int count) throws ReflectiveOperationException {
        float half = height * TWO_FINGER_GAP_OF_HEIGHT / 2;
        float center = Math.max(half, Math.min(width - 1 - half, x));
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = i == 0 ? center - half : center + half;
            coords[i].y = y;
            coords[i].size = 1f;
            coords[i].pressure = 1f;
        }
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, props, coords,
            0, 0, 1f, 1f, touchDevice, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        setDisplayMotion.invoke(e, display);
        send(e);
        e.recycle();
    }

    /** Takes back any fingers a button holds, with no tap: a release would count as one. */
    void cancelTouch() throws ReflectiveOperationException {
        if (pressBit == 0) return;
        int bit = pressBit;
        pressBit = 0;
        if (bit == MotionEvent.BUTTON_PRIMARY) finger(MotionEvent.ACTION_CANCEL);
        else twoFingers(MotionEvent.ACTION_CANCEL, 2);
    }

    private boolean inPress() {
        return buttons != 0 && !profile.hoverButtons;
    }

    /** Pixels on the display, already scaled by the caller. */
    void moveBy(float dx, float dy) throws ReflectiveOperationException, InterruptedException {
        x = Math.max(0, Math.min(width - 1, x + dx));
        y = Math.max(0, Math.min(height - 1, y + dy));
        if (touching()) {
            if (pressBit == MotionEvent.BUTTON_PRIMARY) finger(MotionEvent.ACTION_MOVE);
            else if (pressBit == MotionEvent.BUTTON_SECONDARY) twoFingers(MotionEvent.ACTION_MOVE, 2);
            cursor.onCursor((int) x, (int) y);
            return;
        }
        if (pendingButton != 0) {
            float slop = width / 320f;
            if (Math.abs(x - pendingX) > slop || Math.abs(y - pendingY) > slop) startPendingDrag();
        }
        mouse(inPress() ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
        cursor.onCursor((int) x, (int) y);
    }

    /** @param vertical wheel steps, positive is up. @param horizontal wheel steps, positive is right. */
    void scroll(float vertical, float horizontal) throws ReflectiveOperationException, InterruptedException {
        if (touching()) swipe(vertical, horizontal);
        else mouse(MotionEvent.ACTION_SCROLL, 0, vertical, horizontal);
    }

    /**
     * Touch mode has no wheel, so scrolling is a finger: DOWN at the cursor, MOVEs, and UP after a short idle, with
     * following ticks continuing the same gesture. Content follows a finger, so scrolling up (content moves down) is a
     * finger moving down, and scrolling right (content moves left) is a finger moving left. For the phone's two-finger
     * scroll this means the page follows the fingers, see TouchpadView.scrollBy.
     */
    private void swipe(float vertical, float horizontal) throws ReflectiveOperationException, InterruptedException {
        if (pressBit != 0) return;  // a button is holding fingers down
        float step = height * SWIPE_STEP_FRACTION;
        float dx = -horizontal * step, dy = vertical * step;
        if (!swiping) startSwipe();
        float toX = Math.max(0, Math.min(width - 1, swipeX + dx)), toY = Math.max(0, Math.min(height - 1, swipeY + dy));
        boolean hitEdge = toX != swipeX + dx || toY != swipeY + dy;
        if (hitEdge && (swipeX != x || swipeY != y)) {
            // Out of display: lift and go on from the cursor again
            endSwipe();
            startSwipe();
            toX = Math.max(0, Math.min(width - 1, swipeX + dx));
            toY = Math.max(0, Math.min(height - 1, swipeY + dy));
        }
        float fromX = swipeX, fromY = swipeY;
        for (int i = 1; i <= SWIPE_MOVES_PER_TICK; i++) {
            swipeX = fromX + (toX - fromX) * i / SWIPE_MOVES_PER_TICK;
            swipeY = fromY + (toY - fromY) * i / SWIPE_MOVES_PER_TICK;
            fingerAt(MotionEvent.ACTION_MOVE, swipeX, swipeY);
            Thread.sleep(SWIPE_MOVE_MS);
        }
        lastSwipe = SystemClock.uptimeMillis();
    }

    private void startSwipe() throws ReflectiveOperationException {
        swiping = true;
        swipeX = x;
        swipeY = y;
        downTime = SystemClock.uptimeMillis();
        fingerAt(MotionEvent.ACTION_DOWN, swipeX, swipeY);
    }

    private void endSwipe() throws ReflectiveOperationException {
        if (!swiping) return;
        swiping = false;
        fingerAt(MotionEvent.ACTION_UP, swipeX, swipeY);
    }

    /** The scroll has stopped if nothing came for SWIPE_IDLE_MS. */
    void endSwipeIfIdle() throws ReflectiveOperationException {
        if (swiping && SystemClock.uptimeMillis() - lastSwipe >= SWIPE_IDLE_MS) endSwipe();
    }

    boolean isSwiping() {
        return swiping;
    }

    /** @param bit a MotionEvent.BUTTON_* value */
    void button(int bit, boolean down) throws ReflectiveOperationException, InterruptedException {
        if (touching()) {
            touchButton(bit, down);
            return;
        }
        if (profile.hoverButtons) {
            hoverMethodButton(bit, down);
            return;
        }
        if (down == ((buttons & bit) != 0)) return;
        normalButton(bit, down);
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
     * RustDesk. A press only goes out once it is clear what it is, because no single method works for everything:
     *
     * A real ACTION_DOWN becomes both a mouse and a touch pointer in Flutter. The touch sets RustDesk's isPhysicalMouse
     * to false and every move during the press is lost, so a real press cannot drag. Changing the device id or adding
     * HOVER_EXIT does not help. But hover clicks do nothing in RustDesk's own UI (its home page, toolbar and menus), and
     * only the remote picture understands them.
     *
     * So a press with no movement is sent as a real DOWN and UP on release, which both understand, and the next hover
     * turns physical mouse mode back on. A press that moves goes as ACTION_HOVER_MOVE with buttonState set, which
     * RustDesk's _getMouseEvent treats as press and release. Dragging only works on the remote picture, and a long press
     * without movement is not seen until release.
     */
    private void hoverMethodButton(int bit, boolean down) throws ReflectiveOperationException, InterruptedException {
        if (down) {
            if (pendingButton == bit || (buttons & bit) != 0) return;
            if (pendingButton == 0 && buttons == 0) {
                pendingButton = bit;
                pendingX = x;
                pendingY = y;
                return;
            }
            startPendingDrag();
            hoverButton(bit, true);
        } else if (pendingButton == bit) {
            pendingButton = 0;
            nudge = 0f;
            downTime = SystemClock.uptimeMillis();
            buttons |= bit;
            mouse(MotionEvent.ACTION_DOWN, 0, 0, 0);
            Thread.sleep(40);
            buttons &= ~bit;
            mouse(MotionEvent.ACTION_UP, 0, 0, 0);
            Thread.sleep(30);
            mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
        } else if ((buttons & bit) != 0) {
            hoverButton(bit, false);
        }
    }

    /** Send the waiting press where it happened, so a drag starts on what was under the cursor. */
    private void startPendingDrag() throws ReflectiveOperationException, InterruptedException {
        if (pendingButton == 0) return;
        int bit = pendingButton;
        pendingButton = 0;
        float nowX = x, nowY = y;
        x = pendingX;
        y = pendingY;
        hoverButton(bit, true);
        x = nowX;
        y = nowY;
    }

    /**
     * Flutter's LocationNeedsUpdate drops a hover whose position has not changed, along with its button change, so move
     * one pixel on each change. Android also merges hovers within a frame and keeps the first one's buttons, so each
     * change gets its own frame. ACTION_BUTTON_PRESS/RELEASE reach Flutter as hovers and break the press: not sent here.
     */
    private void hoverButton(int bit, boolean down) throws ReflectiveOperationException, InterruptedException {
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

    /** A key with no key code of its own on a keyboard, such as Back: down and up on the display. */
    void androidKey(int keyCode) throws ReflectiveOperationException {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent e = new KeyEvent(now, SystemClock.uptimeMillis(), action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            setDisplayKey.invoke(e, display);
            send(e);
        }
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
            cancelTouch();  // focus is moving: fingers must not stay down behind it
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
        if (!touching()) {
            if (!profile.hoverButtons) mouse(MotionEvent.ACTION_HOVER_ENTER, 0, 0, 0);
            mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
        }
        cursor.onCursor((int) x, (int) y);
    }

    /** Release every held key and button. Runs when physical input goes back to the phone and when stopping. */
    void releaseAll() throws ReflectiveOperationException, InterruptedException {
        pendingButton = 0;  // a click that never finished, do not fire it now
        endSwipe();
        cancelTouch();
        for (Integer code : new ArrayList<>(held)) key(code, 0);
        for (int bit : ALL_BUTTONS) button(bit, false);
    }

    void hoverExit() throws ReflectiveOperationException {
        if (!profile.hoverButtons && !touching()) mouse(MotionEvent.ACTION_HOVER_EXIT, 0, 0, 0);
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
