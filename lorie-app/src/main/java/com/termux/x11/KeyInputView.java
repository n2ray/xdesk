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

    private boolean onMouse(MotionEvent e) {
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_MOVE:
            sink.mouseMove(e.getX(), e.getY());
            break;
        case MotionEvent.ACTION_BUTTON_PRESS:
        case MotionEvent.ACTION_BUTTON_RELEASE:
            int b = e.getActionButton();
            int x = b == MotionEvent.BUTTON_PRIMARY ? 1 : b == MotionEvent.BUTTON_SECONDARY ? 3 : b == MotionEvent.BUTTON_TERTIARY ? 2 : 0;
            if (x != 0) sink.mouseButton(x, e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS);
            break;
        case MotionEvent.ACTION_SCROLL:
            sink.mouseScroll(e.getAxisValue(MotionEvent.AXIS_VSCROLL), e.getAxisValue(MotionEvent.AXIS_HSCROLL));
            break;
        default:
            break;
        }
        return true;
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
