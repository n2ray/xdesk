package com.termux.x11;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** 不可见的焦点视图：接输入法的文字（含中文）和抓取到的物理鼠标，转给 Sink。 */
final class KeyInputView extends View {
    interface Sink {
        void text(String s);
        void tapKey(int scan);
        void mouseMove(float dx, float dy);
        void mouseButton(int button, boolean down);
        void mouseScroll(float vertical, float horizontal);
        void captureChanged(boolean has);
    }

    static final int SCAN_ESC = 1, SCAN_BACKSPACE = 14, SCAN_TAB = 15, SCAN_ENTER = 28, SCAN_CTRL = 29, SCAN_SHIFT = 42,
        SCAN_ALT = 56, SCAN_UP = 103, SCAN_LEFT = 105, SCAN_RIGHT = 106, SCAN_DOWN = 108, SCAN_DELETE = 111, SCAN_SUPER = 125;

    private final Sink sink;

    KeyInputView(Context c, Sink sink) {
        super(c);
        this.sink = sink;
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

    @Override public InputConnection onCreateInputConnection(EditorInfo o) {
        o.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        o.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        return new Connection();
    }

    private static int scanFor(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_DEL: return SCAN_BACKSPACE;
        case KeyEvent.KEYCODE_FORWARD_DEL: return SCAN_DELETE;
        case KeyEvent.KEYCODE_ENTER: return SCAN_ENTER;
        case KeyEvent.KEYCODE_TAB: return SCAN_TAB;
        case KeyEvent.KEYCODE_ESCAPE: return SCAN_ESC;
        case KeyEvent.KEYCODE_DPAD_UP: return SCAN_UP;
        case KeyEvent.KEYCODE_DPAD_DOWN: return SCAN_DOWN;
        case KeyEvent.KEYCODE_DPAD_LEFT: return SCAN_LEFT;
        case KeyEvent.KEYCODE_DPAD_RIGHT: return SCAN_RIGHT;
        default: return 0;
        }
    }

    /** 输入法不会回读内容：这里没有真正的文本框，文字一提交就进 XFCE。 */
    private final class Connection extends BaseInputConnection {
        private CharSequence composing = "";

        Connection() {
            super(KeyInputView.this, false);
        }

        @Override public boolean commitText(CharSequence t, int newCursor) {
            composing = "";
            if (t.length() > 0) sink.text(t.toString());
            return true;
        }

        @Override public boolean setComposingText(CharSequence t, int newCursor) {
            composing = t;
            return true;
        }

        @Override public boolean finishComposingText() {
            if (composing.length() > 0) sink.text(composing.toString());
            composing = "";
            return true;
        }

        @Override public boolean deleteSurroundingText(int before, int after) {
            for (int i = 0; i < before; i++) sink.tapKey(SCAN_BACKSPACE);
            for (int i = 0; i < after; i++) sink.tapKey(SCAN_DELETE);
            return true;
        }

        @Override public boolean performEditorAction(int action) {
            sink.tapKey(SCAN_ENTER);
            return true;
        }

        @Override public boolean sendKeyEvent(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            int scan = scanFor(e.getKeyCode());
            if (scan != 0) {
                sink.tapKey(scan);
            } else {
                int u = e.getUnicodeChar();
                if (u > 0) sink.text(new String(Character.toChars(u)));
            }
            return true;
        }
    }
}
