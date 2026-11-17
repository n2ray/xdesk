package com.termux.x11;

import com.termux.x11.input.InputStub;
import io.github.n2ray.xdesk.input.Caps;
import java.nio.charset.StandardCharsets;

/** 把输入直接交给外屏 MainActivity 里的 LorieView。桌面没连上时丢弃。 */
final class DesktopInput implements InputSink {
    private static final float WHEEL_STEP = 60f;
    private static final long RESAY_HIDDEN_MS = 1000;
    private boolean cursorHidden;
    private LorieView cursorView;
    private long cursorSaidAt;

    /** 桌面窗口已连上 X 时返回它的 LorieView，否则返回 null。 */
    static LorieView view() {
        MainActivity m = MainActivity.getInstance("");
        LorieView v = m != null ? m.getLorieView() : null;
        return v != null && v.connected() ? v : null;
    }

    /** The browser's pointer is on top: the X cursor stays hidden until another pointer moves, or it leaves. */
    private void setCursorHidden(LorieView v, boolean hidden) {
        if (v == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        // The same view may be connected to a new X server by now (an app update restarts it), which shows its
        // cursor again: a hidden one is said again once a second while placements go on (MACAPP.md §1)
        if (hidden == cursorHidden && v == cursorView && (!hidden || now - cursorSaidAt < RESAY_HIDDEN_MS)) return;
        cursorHidden = hidden;
        cursorView = v;
        cursorSaidAt = now;
        v.setCursorHidden(hidden);
    }

    @Override public void pointerGone() {
        setCursorHidden(view(), false);
    }

    @Override public void move(int dx, int dy) {
        LorieView v = view();
        setCursorHidden(v, false);
        if (v != null) v.sendMouseEvent(dx, dy, InputStub.BUTTON_UNDEFINED, false, true);
    }

    @Override public void moveToFraction(float x, float y) {
        LorieView v = view();
        if (v == null) return;
        setCursorHidden(v, true);
        android.graphics.Point s = v.screenSize();
        if (s.x > 0 && s.y > 0) v.sendMouseEvent(x * (s.x - 1), y * (s.y - 1), InputStub.BUTTON_UNDEFINED, false, false);
    }

    /** Placed as moveToFraction, but X's cursor stays shown: the user's own pointer moved there. */
    @Override public void warpTo(float x, float y) {
        LorieView v = view();
        if (v == null) return;
        setCursorHidden(v, false);
        android.graphics.Point s = v.screenSize();
        if (s.x > 0 && s.y > 0) v.sendMouseEvent(x * (s.x - 1), y * (s.y - 1), InputStub.BUTTON_UNDEFINED, false, false);
    }

    /** The X server's own pointer, as the renderer last had it: a move just sent may not be in it yet. */
    @Override public boolean pointerPlace(float[] out) {
        LorieView v = view();
        if (v == null) return false;
        android.graphics.Point s = v.screenSize();
        if (s.x < 2 || s.y < 2) return false;
        android.graphics.PointF at = v.getRealCursorPosition();
        out[0] = Math.max(0f, Math.min(1f, at.x / (s.x - 1)));
        out[1] = at.y >= s.y - 1 ? 1f : Math.max(0f, at.y / (s.y - 1));
        return true;
    }

    @Override public void button(int button, boolean down) {
        LorieView v = view();
        if (v != null) v.sendMouseEvent(0, 0, button, down, true);
    }

    @Override public void click(int button) {
        button(button, true);
        button(button, false);
    }

    @Override public void scroll(int dy, int dx) {
        LorieView v = view();
        if (v != null) v.sendMouseWheelEvent(dx * WHEEL_STEP, dy * WHEEL_STEP);
    }

    @Override public Caps caps() {
        return Caps.X11_DESKTOP;
    }

    /** X has smooth scroll valuators: fractions of a step scroll a fraction. */
    @Override public boolean scrollFine(float dy, float dx, int phase) {
        if (dx == 0 && dy == 0) return true;
        LorieView v = view();
        if (v != null) v.sendMouseWheelEvent(dx * WHEEL_STEP, dy * WHEEL_STEP);
        return true;
    }

    @Override public void key(int scan, boolean down) {
        LorieView v = view();
        if (v != null) v.sendKeyEvent(scan, 0, down);
    }

    @Override public void tapKey(int scan) {
        key(scan, true);
        key(scan, false);
    }

    @Override public void text(String s) {
        LorieView v = view();
        if (v != null) v.sendTextEvent(s.getBytes(StandardCharsets.UTF_8));
    }
}
