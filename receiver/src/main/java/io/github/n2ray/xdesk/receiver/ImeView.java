package io.github.n2ray.xdesk.receiver;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** An invisible view that takes this device's input method: text goes to the phone as it is committed. */
final class ImeView extends View {
    private final Control control;

    ImeView(Context c, Control control) {
        super(c);
        this.control = control;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo o) {
        o.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        o.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        return new Connection();
    }

    private static int scanFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return Control.SCAN_BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return Control.SCAN_DELETE;
            case KeyEvent.KEYCODE_ENTER: return Control.SCAN_ENTER;
            case KeyEvent.KEYCODE_TAB: return Control.SCAN_TAB;
            case KeyEvent.KEYCODE_ESCAPE: return Control.SCAN_ESC;
            case KeyEvent.KEYCODE_DPAD_UP: return Control.SCAN_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Control.SCAN_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Control.SCAN_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Control.SCAN_RIGHT;
            default: return 0;
        }
    }

    /** There is no text box here: nothing is read back, and what the input method commits is sent at once. */
    private final class Connection extends BaseInputConnection {
        private CharSequence composing = "";

        Connection() {
            super(ImeView.this, false);
        }

        @Override
        public boolean commitText(CharSequence t, int newCursor) {
            composing = "";
            if (t.length() > 0) control.text(t.toString());
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence t, int newCursor) {
            composing = t;
            return true;
        }

        @Override
        public boolean finishComposingText() {
            if (composing.length() > 0) control.text(composing.toString());
            composing = "";
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            for (int i = 0; i < before; i++) control.tapKey(Control.SCAN_BACKSPACE);
            for (int i = 0; i < after; i++) control.tapKey(Control.SCAN_DELETE);
            return true;
        }

        @Override
        public boolean performEditorAction(int action) {
            control.tapKey(Control.SCAN_ENTER);
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            int scan = scanFor(e.getKeyCode());
            if (scan != 0) {
                control.tapKey(scan);
            } else {
                int u = e.getUnicodeChar();
                if (u > 0) control.text(new String(Character.toChars(u)));
            }
            return true;
        }
    }
}
