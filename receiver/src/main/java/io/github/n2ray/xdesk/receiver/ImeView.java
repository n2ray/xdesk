package io.github.n2ray.xdesk.receiver;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

/**
 * Takes this device's input method; text goes to the phone as it is committed. It is also the strip on top of the
 * keyboard that shows what is being typed: the words still being composed, underlined, after the last ones sent. The
 * text box itself is on the phone and may be under the keyboard, so this is the only place the typing can be seen.
 */
final class ImeView extends TextView {
    /** How much of what was sent stays in the strip. */
    private static final int SHOWN = 60;

    private final Control control;
    private final StringBuilder sent = new StringBuilder();
    private CharSequence composing = "";

    ImeView(Context c, Control control) {
        super(c);
        this.control = control;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.START);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        setTextColor(Color.WHITE);
        setHintTextColor(0xFF8D97A3);
        setHint(R.string.ime_hint);
        setGravity(Gravity.CENTER_VERTICAL);
        float d = c.getResources().getDisplayMetrics().density;
        int pad = Math.round(14 * d);
        setPadding(pad, 0, pad, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01D1D1F);
        bg.setCornerRadius(12 * d);
        bg.setStroke(Math.max(1, Math.round(d)), 0x803D7EF5);
        setBackground(bg);
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

    /** What is still being composed goes to the phone: before Enter, and when the keyboard closes. */
    void flush() {
        if (composing.length() > 0) send(composing.toString());
        composing = "";
        show();
    }

    /** The keyboard opened again: start with an empty strip. */
    void clear() {
        sent.setLength(0);
        composing = "";
        show();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (!focused) flush();
    }

    private void send(String t) {
        control.paste(t);
        sent.append(t);
        if (sent.length() > SHOWN) sent.delete(0, sent.length() - SHOWN);
    }

    private void backspace() {
        control.tapKey(Control.SCAN_BACKSPACE);
        if (sent.length() > 0) sent.setLength(sent.length() - 1);
    }

    private void show() {
        SpannableStringBuilder s = new SpannableStringBuilder(sent);
        s.setSpan(new ForegroundColorSpan(0xFFB0B8C4), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = s.length();
        s.append(composing);
        s.setSpan(new UnderlineSpan(), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setText(s);
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
        Connection() {
            super(ImeView.this, false);
        }

        @Override
        public boolean commitText(CharSequence t, int newCursor) {
            composing = "";
            if (t.length() > 0) send(t.toString());
            show();
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence t, int newCursor) {
            composing = t;
            show();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            flush();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            for (int i = 0; i < before; i++) backspace();
            for (int i = 0; i < after; i++) control.tapKey(Control.SCAN_DELETE);
            show();
            return true;
        }

        @Override
        public boolean performEditorAction(int action) {
            flush();
            control.tapKey(Control.SCAN_ENTER);
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            int scan = scanFor(e.getKeyCode());
            if (scan == Control.SCAN_BACKSPACE && composing.length() == 0) {
                backspace();
            } else if (scan != 0) {
                flush();
                control.tapKey(scan);
            } else {
                int u = e.getUnicodeChar();
                if (u > 0) send(new String(Character.toChars(u)));
            }
            show();
            return true;
        }
    }
}
