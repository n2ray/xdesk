package io.github.n2ray.xdesk.receiver;

import android.os.Handler;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.nio.charset.StandardCharsets;

/**
 * This device's touch screen, mouse and keyboard as the phone's input, in the messages of the web display (see
 * PROTOCOL.md). Touch works on the picture directly: a tap clicks where it lands, a drag drags, a long press or a
 * two-finger tap is a right click and two fingers scroll. A mouse points where it is on the picture.
 */
final class Control {
    static final int BUTTON = 21, SCROLL = 22, KEY = 23, TEXT = 24, POSITION = 25, ANDROID = 27;
    static final int LEFT = 1, RIGHT = 3;
    static final int ANDROID_BACK = 1, ANDROID_HOME = 2;
    static final int SCAN_ESC = 1, SCAN_BACKSPACE = 14, SCAN_TAB = 15, SCAN_ENTER = 28, SCAN_UP = 103, SCAN_LEFT = 105,
            SCAN_RIGHT = 106, SCAN_DOWN = 108, SCAN_DELETE = 111;
    private static final int MAX_TEXT = 4096;

    private final Receiver r;
    private final View picture;
    private final Handler ui;
    private final int slop, longPress, scrollStep;

    // Touch
    private float downX, downY, midX, midY, accX, accY;
    private long downAt;
    private boolean dragging, longPressed, multi, scrolled, newMid;
    // Mouse
    private int mouseButtons;
    private float wheelX, wheelY;

    private final Runnable longPressed_ = new Runnable() {
        @Override
        public void run() {
            longPressed = true;
            picture.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            position(downX, downY);
            click(RIGHT);
        }
    };

    Control(Receiver r, View picture, Handler ui) {
        this.r = r;
        this.picture = picture;
        this.ui = ui;
        ViewConfiguration c = ViewConfiguration.get(picture.getContext());
        slop = c.getScaledTouchSlop();
        longPress = ViewConfiguration.getLongPressTimeout();
        scrollStep = Math.round(28 * picture.getResources().getDisplayMetrics().density);
    }

    /** A touch on the screen, or a mouse with a button down. */
    boolean onTouch(MotionEvent e) {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return onMouse(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downAt = e.getEventTime();
                dragging = longPressed = multi = scrolled = false;
                ui.postDelayed(longPressed_, longPress);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                ui.removeCallbacks(longPressed_);
                if (dragging) {
                    button(LEFT, false);
                    dragging = false;
                }
                multi = true;
                newMid = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (multi) {
                    if (e.getPointerCount() >= 2) twoFingers(e);
                } else if (!longPressed) {
                    if (!dragging && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
                        ui.removeCallbacks(longPressed_);
                        dragging = true;
                        position(downX, downY);
                        button(LEFT, true);
                    }
                    if (dragging) position(e.getX(), e.getY());
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // The middle of the fingers that stay is somewhere else
                newMid = true;
                break;
            case MotionEvent.ACTION_UP:
                ui.removeCallbacks(longPressed_);
                if (multi) {
                    if (!scrolled && e.getEventTime() - downAt < longPress) {
                        position(downX, downY);
                        click(RIGHT);
                    }
                } else if (dragging) {
                    position(e.getX(), e.getY());
                    button(LEFT, false);
                } else if (!longPressed) {
                    position(downX, downY);
                    click(LEFT);
                }
                dragging = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPressed_);
                if (dragging) button(LEFT, false);
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }

    private void twoFingers(MotionEvent e) {
        float x = (e.getX(0) + e.getX(1)) / 2, y = (e.getY(0) + e.getY(1)) / 2;
        if (newMid) {
            newMid = false;
        } else {
            // Fingers up move the page up, as on a touch screen: the wheel turns down
            accX += midX - x;
            accY += midY - y;
            int sx = (int) (accX / scrollStep), sy = (int) (accY / scrollStep);
            if (sx != 0 || sy != 0) {
                scrolled = true;
                accX -= sx * scrollStep;
                accY -= sy * scrollStep;
                scroll(sy, sx);
            }
        }
        midX = x;
        midY = y;
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
        ui.removeCallbacks(longPressed_);
        if (dragging) button(LEFT, false);
        dragging = false;
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
