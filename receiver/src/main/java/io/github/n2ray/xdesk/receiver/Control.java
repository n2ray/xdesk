package io.github.n2ray.xdesk.receiver;

import android.os.Handler;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import io.github.n2ray.xdesk.input.DirectTouch;
import io.github.n2ray.xdesk.input.GestureOutput;
import io.github.n2ray.xdesk.input.Input;
import io.github.n2ray.xdesk.input.Scheduler;
import io.github.n2ray.xdesk.input.Touches;
import io.github.n2ray.xdesk.input.Touchpad;
import io.github.n2ray.xdesk.input.Tuning;
import io.github.n2ray.xdesk.input.WheelSteps;

import java.nio.charset.StandardCharsets;

/**
 * This device's touch screen, mouse and keyboard as the phone's input, in the messages of the web display (see
 * PROTOCOL.md). Touch is input-core's touchpad or direct touch, the same gestures as on the phone (INPUT.md §3). A mouse
 * points where it is on the picture.
 */
final class Control {
    static final int MOVE = 20, BUTTON = 21, SCROLL = 22, KEY = 23, TEXT = 24, POSITION = 25, ANDROID = 27;
    static final int LEFT = 1, RIGHT = 3;
    static final int ANDROID_BACK = 1, ANDROID_HOME = 2;
    static final int SCAN_ESC = 1, SCAN_BACKSPACE = 14, SCAN_TAB = 15, SCAN_ENTER = 28, SCAN_UP = 103, SCAN_LEFT = 105,
            SCAN_RIGHT = 106, SCAN_DOWN = 108, SCAN_DELETE = 111;
    private static final int MAX_TEXT = 4096;

    private final Receiver r;
    private final View picture;
    private final Handler ui;
    // Gestures: input-core's engines, the same as on the phone (INPUT.md §9)
    private final Tuning tuning = new Tuning();
    private final Touches touches = new Touches();
    private final WheelSteps wheelSteps = new WheelSteps();
    private final Touchpad pad;
    private final DirectTouch direct;
    /** Fingers move the phone's cursor rather than touching where they are: in mouse mode and on the Linux desktop. */
    private boolean touchpad = true;
    // Mouse
    private int mouseButtons;
    private float wheelX, wheelY;

    /** What both engines make: the web display's messages. */
    private final GestureOutput out = new GestureOutput() {
        @Override public void move(int dx, int dy) { Control.this.move(dx, dy); }
        @Override public void position(float x, float y) { Control.this.position(x, y); }
        @Override public void button(int b, boolean down) { Control.this.button(b, down); }
        @Override public void scroll(int phase, float dx, float dy) {
            if (phase == Input.BEGIN) wheelSteps.clear();
            // Protocol v2 has whole wheel steps only
            if ((dx != 0 || dy != 0) && wheelSteps.add(dx, dy)) Control.this.scroll(wheelSteps.y(), wheelSteps.x());
        }
        @Override public void action(int action, int arg) {
            // The phone does Back and Home for whatever is on the picture; the rest waits for protocol v3
            if (action == Input.BACK) android(ANDROID_BACK);
            else if (action == Input.HOME) android(ANDROID_HOME);
        }
        @Override public void feedback(int kind) {
            if (kind != Input.FEEDBACK_CLICK) picture.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    };

    private final Scheduler scheduler = new Scheduler() {
        @Override public void post(Runnable task, long delayMs) { ui.postDelayed(task, delayMs); }
        @Override public void cancel(Runnable task) { ui.removeCallbacks(task); }
        @Override public long now() { return SystemClock.uptimeMillis(); }
    };

    Control(Receiver r, View picture, Handler ui) {
        this.r = r;
        this.picture = picture;
        this.ui = ui;
        tuning.density = picture.getResources().getDisplayMetrics().density;
        tuning.longPressMs = ViewConfiguration.getLongPressTimeout();
        tuning.doubleTapMs = ViewConfiguration.getDoubleTapTimeout();
        pad = new Touchpad(tuning, scheduler, out);
        direct = new DirectTouch(tuning, scheduler, out);
    }

    /** A touch on the screen, or a mouse with a button down. */
    boolean onTouch(MotionEvent e) {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return onMouse(e);
        // The phone's keyboard on the picture is pressed where the finger is, even when the rest is a touchpad
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) onKeys = onKeyboard(e.getY());
        if (onKeys) return onKeys(e);
        fill(e);
        if (touchpad) pad.onTouch(touches);
        else direct.onTouch(touches);
        return true;
    }

    /** MotionEvent into the engines' reused arrays. */
    private void fill(MotionEvent e) {
        Touches t = touches;
        int action = e.getActionMasked(), index = e.getActionIndex();
        if ((action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) && index >= Touches.MAX) {
            action = MotionEvent.ACTION_MOVE;
            index = 0;
        }
        t.action = action;
        t.index = action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP ? index : 0;
        t.time = e.getEventTime();
        t.tool = e.getToolType(0);
        int n = Math.min(e.getPointerCount(), Touches.MAX);
        t.count = n;
        for (int i = 0; i < n; i++) {
            t.id[i] = e.getPointerId(i);
            t.x[i] = e.getX(i);
            t.y[i] = e.getY(i);
        }
    }

    /** Where the phone's input method begins, 0 to 65535 down the picture; 65535 when it is not there. */
    private int imeTop = 0xFFFF;
    /** This gesture began on the phone's keyboard. */
    private boolean onKeys;

    void setImeTop(int top) {
        imeTop = top;
    }

    private boolean onKeyboard(float y) {
        int h = picture.getHeight();
        if (imeTop >= 0xFFFF || h <= 1) return false;
        return (y - picture.getTop()) / (h - 1) * 65535 >= imeTop;
    }

    /**
     * A finger on a key: down when it lands, up when it lifts, so that a held key repeats, a long press shows a key's
     * other characters and a swipe types. Only the first finger.
     */
    private boolean onKeys(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                position(e.getX(), e.getY());
                button(LEFT, true);
                break;
            case MotionEvent.ACTION_MOVE:
                position(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_UP:
                position(e.getX(), e.getY());
                button(LEFT, false);
                onKeys = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                button(LEFT, false);
                onKeys = false;
                break;
            default:
                break;
        }
        return true;
    }

    void setTouchpad(boolean on) {
        if (on == touchpad) return;
        release();
        touchpad = on;
    }

    boolean touchpad() {
        return touchpad;
    }

    /** The phone's app takes fingers (像平板, input mode 2): direct touch passes a finger, not a mouse. */
    void setFingers(boolean on) {
        direct.setFingers(on);
    }

    /** Scrolling direction, shared by every device (INPUT.md §12). */
    void setNaturalScroll(boolean on) {
        tuning.naturalScroll = on;
    }

    /** Hover, wheel and buttons of a mouse; touches with a mouse come here from onTouch too. */
    boolean onMouse(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_SCROLL) {
            wheelY -= e.getAxisValue(MotionEvent.AXIS_VSCROLL);
            wheelX += e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            int sy = (int) wheelY, sx = (int) wheelX;
            if (sx != 0 || sy != 0) {
                wheelX -= sx;
                wheelY -= sy;
                position(e.getX(), e.getY());
                scroll(sy, sx);
            }
            return true;
        }
        if (a == MotionEvent.ACTION_HOVER_EXIT) return true;
        position(e.getX(), e.getY());
        int state = e.getButtonState();
        // Some devices report a mouse press without its button
        if (state == 0 && (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE)) state = MotionEvent.BUTTON_PRIMARY;
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) state = 0;
        buttons(state);
        return true;
    }

    private void buttons(int state) {
        int changed = state ^ mouseButtons;
        mouseButtons = state;
        if ((changed & MotionEvent.BUTTON_PRIMARY) != 0) button(LEFT, (state & MotionEvent.BUTTON_PRIMARY) != 0);
        if ((changed & MotionEvent.BUTTON_TERTIARY) != 0) button(2, (state & MotionEvent.BUTTON_TERTIARY) != 0);
        if ((changed & MotionEvent.BUTTON_SECONDARY) != 0) button(RIGHT, (state & MotionEvent.BUTTON_SECONDARY) != 0);
    }

    /** Whatever is still held goes up, as when the session ends or the window loses focus. */
    void release() {
        pad.cancel();
        direct.cancel();
        buttons(0);
    }

    // Messages

    /** Where on the picture: 0 to 65535 across and down, what is outside it goes to its edge. */
    void position(float x, float y) {
        int w = picture.getWidth(), h = picture.getHeight();
        if (w <= 1 || h <= 1) return;
        float fx = Math.max(0, Math.min(1, (x - picture.getLeft()) / (w - 1)));
        float fy = Math.max(0, Math.min(1, (y - picture.getTop()) / (h - 1)));
        int px = Math.round(fx * 65535), py = Math.round(fy * 65535);
        r.send(POSITION, new byte[] { (byte) (px >> 8), (byte) px, (byte) (py >> 8), (byte) py });
    }

    /** Relative, in the phone display's pixels, as the phone's touchpad moves the cursor. */
    void move(int dx, int dy) {
        r.send(MOVE, new byte[] { (byte) (dx >> 8), (byte) dx, (byte) (dy >> 8), (byte) dy });
    }

    void button(int b, boolean down) {
        r.send(BUTTON, new byte[] { (byte) b, (byte) (down ? 1 : 0) });
    }

    void click(int b) {
        button(b, true);
        button(b, false);
    }

    void scroll(int dy, int dx) {
        r.send(SCROLL, new byte[] { (byte) (dy >> 8), (byte) dy, (byte) (dx >> 8), (byte) dx });
    }

    /** A Linux input key code. */
    void key(int scan, boolean down) {
        if (scan <= 0 || scan > 767) return;
        r.send(KEY, new byte[] { (byte) (scan >> 8), (byte) scan, (byte) (down ? 1 : 0) });
    }

    void tapKey(int scan) {
        key(scan, true);
        key(scan, false);
    }

    void text(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length == 0) return;
        if (b.length <= MAX_TEXT) {
            r.send(TEXT, b);
            return;
        }
        // Long pastes from an input method go in pieces, cut between characters
        int from = 0;
        while (from < s.length()) {
            int to = Math.min(s.length(), from + 1000);
            if (to < s.length() && Character.isHighSurrogate(s.charAt(to - 1))) to--;
            r.send(TEXT, s.substring(from, to).getBytes(StandardCharsets.UTF_8));
            from = to;
        }
    }

    void android(int which) {
        r.send(ANDROID, new byte[] { (byte) which });
    }
}
