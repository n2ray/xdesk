package com.termux.x11;

import com.termux.x11.input.InputStub;
import java.nio.charset.StandardCharsets;

/** 把输入直接交给外屏 MainActivity 里的 LorieView。桌面没连上时丢弃。 */
final class DesktopInput {
    private static final float WHEEL_STEP = 60f;

    /** 桌面窗口已连上 X 时返回它的 LorieView，否则返回 null。 */
    static LorieView view() {
        MainActivity m = MainActivity.getInstance("");
        LorieView v = m != null ? m.getLorieView() : null;
        return v != null && v.connected() ? v : null;
    }

    void move(int dx, int dy) {
        LorieView v = view();
        if (v != null) v.sendMouseEvent(dx, dy, InputStub.BUTTON_UNDEFINED, false, true);
    }

    void button(int button, boolean down) {
        LorieView v = view();
        if (v != null) v.sendMouseEvent(0, 0, button, down, true);
    }

    void click(int button) {
        button(button, true);
        button(button, false);
    }

    /** dy > 0 向下，dx > 0 向右，单位是滚轮格。 */
    void scroll(int dy, int dx) {
        LorieView v = view();
        if (v != null) v.sendMouseWheelEvent(dx * WHEEL_STEP, dy * WHEEL_STEP);
    }

    void key(int scan, boolean down) {
        LorieView v = view();
        if (v != null) v.sendKeyEvent(scan, 0, down);
    }

    void tapKey(int scan) {
        key(scan, true);
        key(scan, false);
    }

    void text(String s) {
        LorieView v = view();
        if (v != null) v.sendTextEvent(s.getBytes(StandardCharsets.UTF_8));
    }
}
