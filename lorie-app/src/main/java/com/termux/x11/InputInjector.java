package com.termux.x11;

import android.os.Build;
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
import java.util.List;

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
        void onFingers(float[] x, float[] y);
        /**
         * A relative move asked for while the pointer was at an edge already: what the clamping takes away, and what
         * the Dock (BOTTOM) and the notification centre (TOP, RIGHT, NOTIFY.md §4.1) open by (EdgePush).
         * @param edge EdgePush.BOTTOM, TOP or RIGHT
         * @param x, y the pointer's place at that edge, so only the centre's corner counts
         * @param left the pointer has left the edge, explicitly (never an outward move for TOP)
         * @param held a button or a finger is down: a drag to the edge
         */
        void onEdgePush(int edge, int dx, int dy, boolean held, int x, int y, boolean left);
    }

    private static final String TAG = "XDeskInjector";
    private static final int FOCUS_SETTLE_MS = 150;
    /** The right button's long press in touch mode: past Android's long-press timeout (400 to 500 ms) with room. */
    private static final long RIGHT_PRESS_MS = 650;
    /** One wheel tick in touch mode, as a part of the display height. Raise it to scroll further per tick. */
    private static final float SWIPE_STEP_FRACTION = 1f / 16f;
    private static final int SWIPE_MOVES_PER_TICK = 3;
    private static final int SWIPE_MOVE_MS = 6;
    /** Ticks closer together than this are one gesture. Long enough that the lift causes no fling. */
    static final long SWIPE_IDLE_MS = 250;
    /** Linux key code → the Android key code at that place on a US keyboard. */
    static final HashMap<Integer, Integer> KEYS = new HashMap<>();
    /** Android button bits, in the order they are released. */
    private static final int[] ALL_BUTTONS = {MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY,
        MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD};
    private static final int KEY_LEFTCTRL = 29, KEY_LEFTALT = 56, KEY_BACKSLASH = 43, KEY_102ND = 86;

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
            40, KeyEvent.KEYCODE_APOSTROPHE, 41, KeyEvent.KEYCODE_GRAVE, 42, KeyEvent.KEYCODE_SHIFT_LEFT, KEY_BACKSLASH, KeyEvent.KEYCODE_BACKSLASH,
            KEY_102ND, KeyEvent.KEYCODE_BACKSLASH,
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

    static int metaOf(int code) {
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
    /** See resolveFocus. Only the methods this Android version has are set. */
    private Object taskManager;
    private Method focusTopTask, rootTasksOnDisplay, setFocusedTask, stacksOnDisplay, setFocusedStack;
    private boolean focusResolved;
    private final CursorListener cursor;
    private static final class HeldKey {
        final int device, code;
        final long downTime;
        int repeats;
        HeldKey(int device, int code, long downTime) {
            this.device = device;
            this.code = code;
            this.downTime = downTime;
        }
    }
    private final HashMap<Integer,HeldKey> held = new HashMap<>();
    private AppInputProfile profile = AppInputProfile.forPackage(null);
    private int display, width, height, buttons, meta;
    private float x, y, nudge;
    private long downTime;
    /** Hover method only: a button that is down but not sent yet, and where it went down, see hoverMethodButton(). */
    private int pendingButton;
    private float pendingX, pendingY;
    /** See setTouchMode. */
    private boolean touchMode;
    /** 触控 mode: the phone's fingers are on the display. The last frame, in pixels, for cancelling. */
    private boolean directActive;
    private float anchorX, anchorY;
    private int[] directIds = new int[0];
    private float[] directX = new float[0], directY = new float[0];
    /** The car's fingers are down, see screenTouch. The last frame, for cancelling. */
    private boolean screenActive;
    private long screenDown;
    private int[] screenIds = new int[0];
    private float[] screenX = new float[0], screenY = new float[0];
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

    /** Each X client keeps its own buttons, held keys and cursor geometry while sharing Android's input service. */
    InputInjector forWindow() throws ReflectiveOperationException {
        return new InputInjector(cursor);
    }

    void setProfile(AppInputProfile p) {
        profile = p;
    }

    /**
     * Use the logical size: a 2560x1440 monitor reports 2560x1508 on the phone this was tested on. On another display
     * what is held goes up first, on the old one: it would otherwise stay down there.
     */
    void setDisplay(int id, int w, int h) throws ReflectiveOperationException, InterruptedException {
        boolean first = width == 0;
        if (!first && id != display) releaseAll();
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
     * 触控 mode, for apps and pages that only listen to touch, such as a mobile site's video player. The phone's fingers
     * come through touchFrame. A physical mouse adapts: the left button is a finger at
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
     * Left is one finger: press, hold and drag follow the button, so a hold is a long press. Right is a long press at
     * the cursor (owner, 2026-11-19): a quick right click holds the finger for RIGHT_PRESS_MS, the long press a
     * tablet's apps take for what a right click is to a computer (the context menu); held longer it stays down, and
     * drags as a held finger does. It was two fingers side by side, which many apps took for a plain tap. Other buttons
     * do nothing.
     */
    private void touchButton(int bit, boolean down) throws ReflectiveOperationException {
        endSwipe();
        if (bit != MotionEvent.BUTTON_PRIMARY && bit != MotionEvent.BUTTON_SECONDARY) return;
        if (down) {
            cancelDirect();  // the newest input wins
            // A second button while one holds fingers is ignored, and its release too, so nothing can stay down
            if (pressBit != 0) return;
            pressBit = bit;
            downTime = SystemClock.uptimeMillis();
            finger(MotionEvent.ACTION_DOWN);
        } else if (pressBit == bit) {
            if (bit == MotionEvent.BUTTON_SECONDARY) {
                // A quick click still becomes a long press: the finger stays until the apps' long press has passed.
                // The injector's worker waits here, as it does between button changes; input after it follows in order
                long left = downTime + RIGHT_PRESS_MS - SystemClock.uptimeMillis();
                if (left > 0) {
                    try {
                        Thread.sleep(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            pressBit = 0;
            finger(MotionEvent.ACTION_UP);
        }
    }

    private void directEvent(int action, int count) throws ReflectiveOperationException {
        fingers(action, downTime, count, directIds, directX, directY);
    }

    /** The first count pointers of the arrays, in pixels on the display. */
    private void fingers(int action, long down, int count, int[] ids, float[] xs, float[] ys) throws ReflectiveOperationException {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = ids[i];
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i];
            coords[i].y = ys[i];
            coords[i].size = 1f;
            coords[i].pressure = 1f;
        }
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, props, coords,
            0, 0, 1f, 1f, touchDevice, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        setDisplayMotion.invoke(e, display);
        send(e);
        e.recycle();
    }

    /** All current fingers go down, one per event. */
    private void directBegin() throws ReflectiveOperationException {
        directActive = true;
        downTime = SystemClock.uptimeMillis();
        touchDevice = touchDeviceId();
        directEvent(MotionEvent.ACTION_DOWN, 1);
        for (int i = 1; i < directIds.length; i++) {
            directEvent(MotionEvent.ACTION_POINTER_DOWN | (i << MotionEvent.ACTION_POINTER_INDEX_SHIFT), i + 1);
        }
    }

    /**
     * Two or more of the phone's fingers in 触控 mode, placed around where the cursor was when they went down. Fingers that
     * reach an edge stop there. The cursor stays put, only one finger moves it, and shows again when they lift. The newest input wins: a
     * finger going down on the phone cancels a mouse press or a wheel swipe, and a mouse press cancels the phone's fingers,
     * whose following frames are dropped until another finger goes down.
     */
    void touchFrame(int action, int[] ids, float[] offsetX, float[] offsetY) throws ReflectiveOperationException {
        if (!touching()) return;
        int masked = action & MotionEvent.ACTION_MASK;
        boolean starts = masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_POINTER_DOWN;
        if (!directActive && !starts) return;
        if (!directActive) {
            cancelTouch();
            endSwipe();
            anchorX = x;
            anchorY = y;
        }
        directIds = ids;
        directX = new float[ids.length];
        directY = new float[ids.length];
        for (int i = 0; i < ids.length; i++) {
            directX[i] = Math.max(0, Math.min(width - 1, anchorX + offsetX[i]));
            directY[i] = Math.max(0, Math.min(height - 1, anchorY + offsetY[i]));
        }
        if (!directActive) {
            directBegin();
        } else {
            directEvent(action, ids.length);
        }
        boolean over = masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL;
        if (over) {
            directActive = false;
            cursor.onFingers(new float[0], new float[0]);
            cursor.onCursor((int) x, (int) y);
        } else {
            cursor.onFingers(directX, directY);
        }
    }

    private void cancelDirect() throws ReflectiveOperationException {
        if (!directActive) return;
        directActive = false;
        directEvent(MotionEvent.ACTION_CANCEL, directIds.length);
        cursor.onFingers(new float[0], new float[0]);
    }

    /**
     * The car's touch screen, which shows this display: each finger where it is, already in pixels here, in either input
     * mode, because the user touches the picture itself. Any other press or the phone's fingers end it, as the newest input.
     */
    void screenTouch(int action, int[] ids, float[] xs, float[] ys) throws ReflectiveOperationException {
        int masked = action & MotionEvent.ACTION_MASK;
        if (masked == MotionEvent.ACTION_DOWN) {
            cancelTouch();
            endSwipe();
            touchDevice = touchDeviceId();
            screenDown = SystemClock.uptimeMillis();
            screenActive = true;
        }
        if (!screenActive) return;
        screenIds = ids;
        screenX = new float[ids.length];
        screenY = new float[ids.length];
        for (int i = 0; i < ids.length; i++) {
            screenX[i] = Math.max(0, Math.min(width - 1, xs[i]));
            screenY[i] = Math.max(0, Math.min(height - 1, ys[i]));
        }
        fingers(action, screenDown, ids.length, screenIds, screenX, screenY);
        if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) screenActive = false;
        if (masked == MotionEvent.ACTION_POINTER_UP) dropScreenFinger(action >> MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    /** A cancel must only name the fingers still down. */
    private void dropScreenFinger(int index) {
        int n = screenIds.length - 1;
        int[] ids = new int[n];
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0, j = 0; i <= n; i++) {
            if (i == index) continue;
            ids[j] = screenIds[i];
            xs[j] = screenX[i];
            ys[j++] = screenY[i];
        }
        screenIds = ids;
        screenX = xs;
        screenY = ys;
    }

    /** Takes back any fingers a button holds, with no tap: a release would count as one. */
    void cancelTouch() throws ReflectiveOperationException {
        cancelDirect();
        if (screenActive) {
            screenActive = false;
            fingers(MotionEvent.ACTION_CANCEL, screenDown, screenIds.length, screenIds, screenX, screenY);
        }
        if (pressBit == 0) return;
        int bit = pressBit;
        pressBit = 0;
        finger(MotionEvent.ACTION_CANCEL);
    }

    private boolean inPress() {
        return buttons != 0 && !profile.hoverButtons;
    }

    /** Pixels on the display, already scaled by the caller. */
    void moveBy(float dx, float dy) throws ReflectiveOperationException, InterruptedException {
        reportPush(dx, dy);
        x = Math.max(0, Math.min(width - 1, x + dx));
        y = Math.max(0, Math.min(height - 1, y + dy));
        if (touching()) {
            if (pressBit != 0) finger(MotionEvent.ACTION_MOVE);
            reportCursor();
            return;
        }
        if (pendingButton != 0) {
            float slop = width / 320f;
            if (Math.abs(x - pendingX) > slop || Math.abs(y - pendingY) > slop) startPendingDrag();
        }
        mouse(inPress() ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
        reportCursor();
    }

    /** A pointer placed from a computer has that computer's own cursor on it: only the others need ours. */
    private boolean placedFromComputer;
    /** The pointer's last move was a computer's placing: take() then shows no arrow of ours beside that computer's cursor. */
    private boolean lastFromComputer;

    /** The edge the pointer is being pushed against, 0 when at none; the pointer has not been told it left. */
    private int pushingEdge;
    /** What of a push is less than a whole pixel yet. */
    private float pushX, pushY;

    /**
     * Before a move is clamped: at an edge already, the whole of it is a push against (or along, or away from) the
     * edge, which only this process can know, as the pointer does not move for it. Placing from a computer is no
     * push: its pointer stops at the edge of its own picture. The bottom edge is the Dock's (DISPLAY.md §7), the
     * top and right edges the notification centre's corner (NOTIFY.md §4.1); the app keeps only the pushes inside
     * the corner. At a corner the bottom or top edge wins, the sideways part of the move is the rule's to judge.
     * Leaving an edge is said explicitly (left), once: it must not read as an outward move on another edge.
     */
    private void reportPush(float dx, float dy) {
        if (placedFromComputer) return;
        int edge = y >= height - 1 ? EdgePush.BOTTOM : y <= 0 ? EdgePush.TOP : x >= width - 1 ? EdgePush.RIGHT : 0;
        if (edge == 0) {
            if (pushingEdge != 0) cursor.onEdgePush(pushingEdge, 0, 0, false, (int) x, (int) y, true);
            pushingEdge = 0;
            pushX = pushY = 0;
            return;
        }
        if (pushingEdge != 0 && pushingEdge != edge) {
            cursor.onEdgePush(pushingEdge, 0, 0, false, (int) x, (int) y, true);
            pushX = pushY = 0;
        }
        pushingEdge = edge;
        pushX += dx;
        pushY += dy;
        int wholeX = (int) pushX, wholeY = (int) pushY;
        pushX -= wholeX;
        pushY -= wholeY;
        if (wholeX == 0 && wholeY == 0) return;
        cursor.onEdgePush(edge, wholeX, wholeY, buttons != 0 || pendingButton != 0 || pressBit != 0 || directActive || screenActive,
            (int) x, (int) y, false);
    }

    private void reportCursor() throws ReflectiveOperationException {
        if (placedFromComputer) return;
        lastFromComputer = false;
        cursor.onCursor((int) x, (int) y);
    }

    /** To this place, 0 to 1 across and down. */
    void moveTo(float fx, float fy) throws ReflectiveOperationException, InterruptedException {
        placedFromComputer = true;
        lastFromComputer = true;
        try {
            moveBy(fx * (width - 1) - x, fy * (height - 1) - y);
        } finally {
            placedFromComputer = false;
        }
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
        if (pressBit != 0 || directActive) return;  // fingers are already down
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

    /** The X client packs its active XKB locks above the scan so they survive a focus or device change. */
    void windowKey(int packet, boolean down, int deviceId) throws ReflectiveOperationException {
        int locks = packet >>> 16;
        int lockMeta = ((locks & 1) == 0 ? 0 : KeyEvent.META_CAPS_LOCK_ON)
            | ((locks & 2) == 0 ? 0 : KeyEvent.META_NUM_LOCK_ON)
            | ((locks & 4) == 0 ? 0 : KeyEvent.META_SCROLL_LOCK_ON);
        key(packet & 0xffff, down ? 1 : 0, lockMeta, deviceId);
    }

    /** @param value 1 down, 0 up, 2 repeat, as in evdev. Keys only reach the display that has focus, see focus(). */
    void key(int linuxCode, int value) throws ReflectiveOperationException {
        key(linuxCode,value,0,-1);
    }

    private void key(int linuxCode, int value, int lockMeta, int deviceId) throws ReflectiveOperationException {
        Integer code = KEYS.get(linuxCode);
        if (code == null) {
            Log.i(TAG, "unmapped key " + linuxCode);
            return;
        }
        long now = SystemClock.uptimeMillis();
        HeldKey press = held.get(linuxCode);
        if (press == null) {
            // Focus loss or a display transition may have already released this key.
            if (value != 1) return;
            InputDevice keyboard = deviceId < 0 ? physicalKeyboard() : InputDevice.getDevice(deviceId);
            if (keyboard != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && linuxCode != KEY_BACKSLASH) {
                int mapped = keyboard.getKeyCodeForKeyLocation(code);
                if (mapped != KeyEvent.KEYCODE_UNKNOWN) code = mapped;
            }
            press = new HeldKey(keyboard == null ? KeyCharacterMap.VIRTUAL_KEYBOARD : keyboard.getId(), code, now);
            held.put(linuxCode, press);
            meta |= metaOf(linuxCode);
        } else if (value == 0) {
            held.remove(linuxCode);
            if (metaOf(linuxCode) != 0) {
                meta = 0;
                for (Integer scan : held.keySet()) meta |= metaOf(scan);
            }
        } else {
            press.repeats++;
        }
        // Keep down, repeat and up paired even if a Bluetooth keyboard reconnects or its layout changes.
        KeyEvent e = new KeyEvent(press.downTime, now, value == 0 ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN,
            press.code, value == 0 ? 0 : press.repeats, meta | lockMeta, press.device, linuxCode,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        setDisplayKey.invoke(e, display);
        send(e);
    }

    /** The plugged-in keyboard whose layout applies, or null to keep the virtual keyboard's US layout. */
    static InputDevice physicalKeyboard() {
        InputDevice found = null;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || !d.isExternal() || d.isVirtual() || d.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) continue;
            // Keys that come through here are the browser's when it has its keyboard: one plugged in delivers its own
            if (UhidKeyboard.NAME.equals(d.getName())) return d;
            if (found == null) found = d;
        }
        return found;
    }

    /** Held down now, and not a modifier: what auto-repeat applies to. */
    boolean repeats(int linuxCode) {
        return held.containsKey(linuxCode) && metaOf(linuxCode) == 0 && KEYS.containsKey(linuxCode);
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

    /**
     * Text from an input method or a browser, typed on the keyboard whose layout has the most characters: a real one if there
     * is one. Returns how many characters have no key on a keyboard and were dropped.
     */
    int text(String s) throws ReflectiveOperationException {
        InputDevice keyboard = physicalKeyboard();
        KeyCharacterMap map = keyboard != null ? keyboard.getKeyCharacterMap() : KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        int device = keyboard != null ? keyboard.getId() : KeyCharacterMap.VIRTUAL_KEYBOARD;
        // The text already says upper case or AltGr: a Shift or Option held for it must not count twice
        int held = meta & ~(KeyEvent.META_SHIFT_MASK | KeyEvent.META_ALT_MASK);
        int dropped = 0;
        for (int i = 0; i < s.length(); i++) {
            KeyEvent[] events = map.getEvents(new char[]{s.charAt(i)});
            if (events == null) {
                dropped++;
                continue;
            }
            sendCharacters(events,device,held);
        }
        return dropped;
    }

    /** Refuse an unmappable IME commit as a unit so it cannot silently become different text. */
    boolean textCommit(String s) throws ReflectiveOperationException {
        InputDevice keyboard = physicalKeyboard();
        int device = keyboard == null ? KeyCharacterMap.VIRTUAL_KEYBOARD : keyboard.getId();
        KeyEvent[] events = KeyCharacterMap.load(device).getEvents(s.toCharArray());
        if (events == null) return false;
        sendCharacters(events,device,0);
        return true;
    }

    private void sendCharacters(KeyEvent[] events,int device,int additionalMeta) throws ReflectiveOperationException {
        for (KeyEvent k : events) {
            KeyEvent e = new KeyEvent(k.getDownTime(), k.getEventTime(), k.getAction(), k.getKeyCode(), k.getRepeatCount(),
                k.getMetaState() | additionalMeta, device, k.getScanCode(), k.getFlags(), k.getSource());
            setDisplayKey.invoke(e, display);
            send(e);
        }
    }

    /**
     * Injected keys only reach a display with a focused window, and touching the phone takes focus away.
     * Failure is not fatal: mouse input is delivered by position and keeps working.
     */
    void focus(int displayId) {
        if (!focusResolved) resolveFocus();
        try {
            cancelTouch();  // focus is moving: fingers must not stay down behind it
            if (focusTopTask != null) {
                focusTopTask.invoke(taskManager, displayId);
            } else if (rootTasksOnDisplay != null && setFocusedTask != null) {
                int id = topId((List<?>) rootTasksOnDisplay.invoke(taskManager, displayId), "taskId");
                if (id >= 0) setFocusedTask.invoke(taskManager, id);
            } else if (stacksOnDisplay != null && setFocusedStack != null) {
                int id = topId((List<?>) stacksOnDisplay.invoke(taskManager, displayId), "stackId");
                if (id >= 0) setFocusedStack.invoke(taskManager, id);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "focus failed", e);
        }
    }

    /**
     * Found once, because focus() runs before many keys. focusTopTask is the simple way. Where it is missing, the top
     * task on the display gets focus by id: setFocusedTask on root tasks, or setFocusedStack on the stacks of Android 11.
     */
    private void resolveFocus() {
        focusResolved = true;
        try {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "activity_task");
            taskManager = Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "no activity task manager: keys may not reach the external display", e);
            return;
        }
        Class<?> c = taskManager.getClass();
        focusTopTask = method(c, "focusTopTask");
        if (focusTopTask != null) return;
        rootTasksOnDisplay = method(c, "getAllRootTaskInfosOnDisplay");
        setFocusedTask = method(c, "setFocusedTask");
        if (rootTasksOnDisplay != null && setFocusedTask != null) {
            Log.i(TAG, "no focusTopTask, focusing the top task instead");
            return;
        }
        stacksOnDisplay = method(c, "getAllStackInfosOnDisplay");
        setFocusedStack = method(c, "setFocusedStack");
        if (stacksOnDisplay != null && setFocusedStack != null) Log.i(TAG, "no focusTopTask, focusing the top stack instead");
        else Log.w(TAG, "no way found to focus a display: keys may not reach the external display");
    }

    /** A method taking one int, or null when this Android version has none of that name. */
    private static Method method(Class<?> c, String name) {
        try {
            return c.getMethod(name, int.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** The first visible one, the system lists them top first. -1 when there is none. */
    private static int topId(List<?> infos, String idField) throws ReflectiveOperationException {
        for (Object info : infos) {
            if (TaskMover.visible(info)) return info.getClass().getField(idField).getInt(info);
        }
        return -1;
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
        if (!lastFromComputer) cursor.onCursor((int) x, (int) y);
    }

    /** Release every held key and button. Runs when physical input goes back to the phone and when stopping. */
    void releaseAll() throws ReflectiveOperationException, InterruptedException {
        pendingButton = 0;  // a click that never finished, do not fire it now
        endSwipe();
        cancelTouch();
        for (Integer code : new ArrayList<>(held.keySet())) key(code, 0);
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
