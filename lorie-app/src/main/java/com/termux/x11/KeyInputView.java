package com.termux.x11;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** 不可见的焦点视图：接输入法的文字（含中文，经 TextBridge）和抓取到的物理鼠标，转给 Sink。 */
final class KeyInputView extends View {
    interface Sink {
        void text(String s);
        void tapKey(int scan);
        void mouseMove(float dx, float dy);
        void mouseButton(int button, boolean down);
        void mouseScroll(float vertical, float horizontal);
        void captureChanged(boolean has);
        /** Where text goes now; a different one starts the text bridge afresh. */
        Object target();
        /** One of XDesk's modifier buttons is latched. */
        boolean latched();
    }

    static final int SCAN_ESC = 1, SCAN_BACKSPACE = 14, SCAN_TAB = 15, SCAN_ENTER = 28, SCAN_CTRL = 29, SCAN_SHIFT = 42,
        SCAN_ALT = 56, SCAN_UP = 103, SCAN_LEFT = 105, SCAN_RIGHT = 106, SCAN_DOWN = 108, SCAN_DELETE = 111, SCAN_SUPER = 125;

    private final Sink sink;
    private final TextBridge bridge;

    KeyInputView(Context c, Sink sink) {
        super(c);
        this.sink = sink;
        bridge = new TextBridge(this, new TextBridge.Out() {
            @Override public void text(String s) { sink.text(s); }
            @Override public void tapKey(int scan) { sink.tapKey(scan); }
            @Override public Object target() { return sink.target(); }
            @Override public boolean immediate() { return sink.latched(); }
        });
        setFocusable(true);
        setFocusableInTouchMode(true);
        setOnCapturedPointerListener(new OnCapturedPointerListener() {
            @Override public boolean onCapturedPointer(View v, MotionEvent e) {
                return onMouse(e);
            }
        });
    }

    /** What the captured mouse holds, sent once whichever way the device reports its buttons. */
    private final io.github.n2ray.xdesk.input.MouseButtons buttons = new io.github.n2ray.xdesk.input.MouseButtons();
    private final io.github.n2ray.xdesk.input.MouseButtons.Out buttonOut = new io.github.n2ray.xdesk.input.MouseButtons.Out() {
        @Override public void button(int b, boolean down) { sink.mouseButton(b, down); }
    };

    private boolean onMouse(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
        case MotionEvent.ACTION_MOVE:
        case MotionEvent.ACTION_HOVER_MOVE: {
            // Captured moves are relative, and Android batches several into one event: each sample is its own delta
            float dx = e.getX(), dy = e.getY();
            for (int i = 0, n = e.getHistorySize(); i < n; i++) {
                dx += e.getHistoricalX(i);
                dy += e.getHistoricalY(i);
            }
            buttons.event(action, 0, e.getButtonState(), buttonOut);
            if (dx != 0 || dy != 0) sink.mouseMove(dx, dy);
            break;
        }
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
        case MotionEvent.ACTION_BUTTON_PRESS:
        case MotionEvent.ACTION_BUTTON_RELEASE:
            buttons.event(action, e.getActionButton(), e.getButtonState(), buttonOut);
            break;
        case MotionEvent.ACTION_SCROLL:
            sink.mouseScroll(e.getAxisValue(MotionEvent.AXIS_VSCROLL), e.getAxisValue(MotionEvent.AXIS_HSCROLL));
            break;
        default:
            break;
        }
        return true;
    }

    /** Capture is gone: buttons still down go up, as their releases will not come here (INPUT.md §2 cancel). */
    void releaseMouse() {
        buttons.releaseAll(buttonOut);
    }

    /** The Router already let go of the mouse's buttons (the device went away): forget them without sending. */
    void clearMouse() {
        buttons.clear();
    }

    @Override public void onPointerCaptureChange(boolean has) {
        sink.captureChanged(has);
    }

    @Override public boolean onCheckIsTextEditor() {
        return true;
    }

    /**
     * Soft keyboard and input-method text for targets that are not Android: through the text bridge (INPUT.md §5.3),
     * so composing stays on the phone and corrections become backspaces and retyping on the target.
     */
    @Override public InputConnection onCreateInputConnection(EditorInfo o) {
        return bridge.create(o);
    }

    TextBridge bridge() {
        return bridge;
    }
}
