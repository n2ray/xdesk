/*
 * The input connection is derived from Sall (https://github.com/n2ray/sall, RemoteKeyboard.java), the Android client
 * of Sill (https://github.com/Saffsanity/sill). Copyright 2026 Noah Saffer. Licensed under the Apache License, Version
 * 2.0. Modified for XDesk: Java 9, one bridge for every target that is not Android, physical keys typed into it.
 */
package com.termux.x11;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.termux.x11.mac.RemoteTextState;

/**
 * The text bridge (INPUT.md §5.3) for a target that is not Android: an X desktop or window, a Mac. The phone's input
 * method edits a real (invisible) text here; what changed in it since the last time goes out as "this many
 * backspaces, then this text". So composing stays on the phone, and an input method that corrects a word it already
 * committed (autocorrect, a picked suggestion) becomes backspaces and the word typed again on the target.
 *
 * <p>Only the text typed since the caret last may have moved is kept (RemoteTextState): a click, an arrow, another
 * target or window forget it; Enter and Tab are a fence nothing goes back over.
 *
 * <p>UI thread only. Nothing here runs per pointer event: a click only marks the text as forgotten (forgetSoon).
 */
final class TextBridge {
    interface Out {
        void text(String s);
        /** A key pressed and let go (Linux key code): Backspace, Enter, Tab, Esc, arrows. */
        void tapKey(int scan);
        /** Whatever text and keys go to now. A different one starts afresh. */
        Object target();
        /** A modifier is latched on XDesk's key row: a letter is a shortcut now, sent at once, never composed. */
        boolean immediate();
    }

    private static final int SCAN_ESC = 1, SCAN_BACKSPACE = 14, SCAN_TAB = 15, SCAN_ENTER = 28, SCAN_UP = 103,
        SCAN_LEFT = 105, SCAN_RIGHT = 106, SCAN_DOWN = 108, SCAN_HOME = 102, SCAN_END = 107, SCAN_PAGE_UP = 104,
        SCAN_PAGE_DOWN = 109, SCAN_DELETE = 111;

    private final View view;
    private final Out out;
    private final RemoteTextState state = new RemoteTextState();
    private final java.util.HashSet<Integer> keysDown = new java.util.HashSet<>();
    private BaseInputConnection active;
    private long generation, activeGeneration;
    private boolean mutating, preserveOnCreate, trimPosted;
    private Object lastTarget;
    /** Input methods may correct what they committed. Off: a plain text field without suggestions, as before. */
    private boolean corrections = true;
    /** Set from any thread when the caret may have moved (a click): forgotten at the next edit. */
    private volatile boolean stale;

    TextBridge(View view, Out out) {
        this.view = view;
        this.out = out;
    }

    void setCorrections(boolean on) {
        if (on == corrections) return;
        corrections = on;
        restart();
    }

    /** The text or a composition is not empty: only then is there anything to forget. */
    private volatile boolean holding;
    private final Runnable forgetNow = () -> {
        // A word still being composed stays on the phone; the text before it is dropped at the next edit
        if (!composing()) forget();
    };

    /**
     * Something may have moved the caret on the target (a click, a key that is not text). Any thread; nothing happens
     * unless text is held, and then one message to the UI thread, so a pointer path is not slowed.
     */
    void forgetSoon() {
        if (!holding || stale) return;
        stale = true;
        view.post(forgetNow);
    }

    private void updateHolding() {
        holding = !state.text().isEmpty() || composing();
    }

    private boolean composing() {
        return active != null && BaseInputConnection.getComposingSpanStart(active.getEditable()) >= 0;
    }

    /** Before an edit: a new target, or a click since the last edit, starts with an empty text. */
    private void checkFresh() {
        Object t = out.target();
        boolean moved = stale && !composing();
        if (t == lastTarget && !moved) return;
        lastTarget = t;
        stale = false;
        if (active != null) {
            Editable e = active.getEditable();
            // A word being composed stays: it is still only on the phone
            int start = BaseInputConnection.getComposingSpanStart(e);
            if (start > 0) e.delete(0, start);
            else if (start < 0) e.clear();
        }
        state.clear();
        updateHolding();
    }

    InputConnection create(EditorInfo o) {
        releaseKeys();
        boolean preserve = preserveOnCreate;
        preserveOnCreate = false;
        if (!preserve) finishCurrent();
        final long mine = ++generation;
        activeGeneration = mine;
        o.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | (corrections ? 0 : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        o.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
        active = new Connection(mine);
        if (preserve && !state.text().isEmpty()) {
            Editable e = active.getEditable();
            e.replace(0, e.length(), state.text());
            Selection.setSelection(e, e.length());
        }
        o.initialSelStart = o.initialSelEnd = active.getEditable().length();
        return active;
    }

    /**
     * Text from a physical key the input method let through (INPUT.md §5.1): typed into the same text, so the input
     * method's later corrections still line up.
     */
    void type(String s) {
        if (active == null || generation != activeGeneration || !view.hasFocus()) {
            out.text(s);
            return;
        }
        checkFresh();
        Editable e = active.getEditable();
        if (composing()) active.finishComposingText();
        Selection.setSelection(e, e.length());
        active.commitText(s, 1);
        InputMethodManager m = imm();
        if (m != null) m.updateSelection(view, e.length(), e.length(), -1, -1);
    }

    /** Clicks, arrows, another window: the text so far is no longer where the caret is. */
    void forget() {
        stale = false;
        preserveOnCreate = false;
        releaseKeys();
        finishCurrent();
        restart();
    }

    private InputMethodManager imm() {
        return (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private void restart() {
        generation++;
        if (view.hasFocus()) {
            InputMethodManager m = imm();
            if (m != null) m.restartInput(view);
        }
    }

    private void restartPreserving() {
        preserveOnCreate = true;
        restart();
    }

    private void finishCurrent() {
        if (active != null) {
            active.finishComposingText();
            active.getEditable().clear();
        }
        state.clear();
        updateHolding();
    }

    private void releaseKeys() {
        keysDown.clear();
    }

    /** New text for the target. A line break or a tab in it is the key: targets type those reliably only as keys. */
    private void emit(String t) {
        int from = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '\n' && c != '\r' && c != '\t') continue;
            if (i > from) out.text(t.substring(from, i));
            if (c == '\t') out.tapKey(SCAN_TAB);
            else if (c == '\r' || i == 0 || t.charAt(i - 1) != '\r') out.tapKey(SCAN_ENTER);
            from = i + 1;
        }
        if (from == 0) {
            if (!t.isEmpty()) out.text(t);
        } else if (from < t.length()) {
            out.text(t.substring(from));
        }
    }

    private void backspaces(int n) {
        for (int i = 0; i < n; i++) out.tapKey(SCAN_BACKSPACE);
    }

    private boolean deleteCrossingSelection(Editable e, int start, int end) {
        if (!e.toString().equals(state.text())) return false;
        RemoteTextState.Edit edit = state.deleteCrossingSelection(start, end);
        if (edit == null) return false;
        e.replace(0, e.length(), state.text());
        Selection.setSelection(e, e.length());
        backspaces(edit.backspaces);
        emit(edit.text);
        return true;
    }

    private boolean reconcile(Editable e, boolean finalBackspace) {
        int previousFence = state.fence();
        RemoteTextState.Edit edit = state.reconcile(e.toString(), finalBackspace);
        if (edit == null) {
            // It would reach back over a fence: the target keeps what it has, the text here follows it
            e.replace(0, e.length(), state.text());
            Selection.setSelection(e, e.length());
            return false;
        }
        backspaces(edit.backspaces);
        emit(edit.text);
        updateHolding();
        if (finalBackspace && state.fence() < previousFence) {
            mutating = true;
            try { forget(); } finally { mutating = false; }
        } else {
            scheduleTrim();
        }
        return true;
    }

    private void scheduleTrim() {
        if (trimPosted || state.hasRetype() || state.hasComposingRetype() || !state.wantsTrim() || composing()) return;
        trimPosted = true;
        final BaseInputConnection expected = active;
        final long expectedGeneration = activeGeneration;
        view.post(() -> {
            trimPosted = false;
            if (active != expected || generation != expectedGeneration || composing() || state.hasRetype()
                || state.hasComposingRetype() || !state.trim()) return;
            Editable e = expected.getEditable();
            e.replace(0, e.length(), state.text());
            Selection.setSelection(e, e.length());
            restartPreserving();
        });
    }

    private static int scanFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return SCAN_BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return SCAN_DELETE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return SCAN_ENTER;
            case KeyEvent.KEYCODE_TAB: return SCAN_TAB;
            case KeyEvent.KEYCODE_ESCAPE: return SCAN_ESC;
            case KeyEvent.KEYCODE_DPAD_UP: return SCAN_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return SCAN_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return SCAN_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return SCAN_RIGHT;
            case KeyEvent.KEYCODE_MOVE_HOME: return SCAN_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return SCAN_END;
            case KeyEvent.KEYCODE_PAGE_UP: return SCAN_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return SCAN_PAGE_DOWN;
            default: return 0;
        }
    }

    private final class Connection extends BaseInputConnection {
        private final long mine;

        Connection(long mine) {
            super(view, true);
            this.mine = mine;
        }

        private boolean valid() {
            if (generation != mine) return false;
            checkFresh();
            return true;
        }

        @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (!valid()) return false;
            Editable e = getEditable();
            boolean marked = getComposingSpanStart(e) >= 0;
            if (!marked) {
                int start = Selection.getSelectionStart(e), end = Selection.getSelectionEnd(e);
                if (start >= 0 && end >= 0) deleteCrossingSelection(e, Math.min(start, end), Math.max(start, end));
            }
            if (out.immediate()) return commitText(text, newCursorPosition);
            String t = state.filterComposing(text.toString());
            if (t == null) return true;
            if (t.isEmpty() && !marked) return true;
            boolean result = super.setComposingText(t, newCursorPosition);
            updateHolding();
            return result;
        }

        @Override public boolean setComposingRegion(int start, int end) {
            return valid() && super.setComposingRegion(start, end);
        }

        @Override public boolean setSelection(int start, int end) {
            if (!valid()) return false;
            Editable e = getEditable();
            if (getComposingSpanStart(e) < 0 && start >= 0 && end >= 0 && start <= e.length() && end <= e.length()
                && e.toString().equals(state.text())) {
                int[] s = state.selection(start, end);
                start = s[0];
                end = s[1];
            }
            if (start != end) {
                state.clearRetype();
                if (getComposingSpanStart(e) < 0) state.clearComposingRetype();
            }
            return super.setSelection(start, end);
        }

        @Override public boolean commitText(CharSequence text, int newCursorPosition) {
            if (!valid()) return false;
            Editable e = getEditable();
            boolean retiring = state.hasComposingRetype();
            if (retiring) {
                String rest = state.filterComposingCommit(text.toString());
                if (rest == null) {
                    restartPreserving();
                    return true;
                }
                text = rest;
            }
            if (getComposingSpanStart(e) < 0) {
                int start = Selection.getSelectionStart(e), end = Selection.getSelectionEnd(e);
                int left = Math.min(start, end), right = Math.max(start, end);
                if (start >= 0 && end >= 0 && text.length() == 0 && deleteCrossingSelection(e, left, right)) return true;
                if (start == e.length() && end == e.length()) {
                    String rest = state.consumeRetype(text.toString());
                    if (rest == null || rest.isEmpty()) {
                        if (retiring) restartPreserving();
                        return true;
                    }
                    text = rest;
                }
            }
            mutating = true;
            boolean result;
            try {
                result = super.commitText(text, newCursorPosition);
            } finally {
                mutating = false;
            }
            if (result) reconcile(getEditable(), false);
            if (retiring) restartPreserving();
            return result;
        }

        @Override public boolean finishComposingText() {
            if (generation != mine) return false;
            boolean retiring = !mutating && state.hasComposingRetype() && getComposingSpanStart(getEditable()) >= 0;
            boolean result = super.finishComposingText();
            if (result && !mutating) reconcile(getEditable(), false);
            if (result && retiring) {
                state.clearComposingRetype();
                restartPreserving();
            }
            return result;
        }

        @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (!valid() || beforeLength < 0 || afterLength < 0) return false;
            Editable e = getEditable();
            if (getComposingSpanStart(e) >= 0) return super.deleteSurroundingText(beforeLength, afterLength);
            int start = Selection.getSelectionStart(e), end = Selection.getSelectionEnd(e);
            if (start < 0 || end < 0) return false;
            int left = Math.min(start, end), right = Math.max(start, end);
            if (RemoteTextState.splitsSurrogate(e, left) || RemoteTextState.splitsSurrogate(e, right)) return false;
            int before = Math.min(beforeLength, 64), after = Math.min(afterLength, 64);
            int beyond = Math.max(0, before - left);
            // Deleting before what is known only works as a plain backspace at the end
            if (after > e.length() - right
                || (beyond > 0 && (before != 1 || left != right || right != e.length() || after != 0))) return false;
            boolean finalBackspace = left == right && right == e.length() && before == 1 && after == 0;
            if (before <= left) before = left - RemoteTextState.deletionStart(e, left - before);
            after = RemoteTextState.deletionEnd(e, right + after) - right;
            boolean result = super.deleteSurroundingText(Math.min(before, left), after);
            if (result && reconcile(e, finalBackspace) && beyond > 0) backspaces(beyond);
            return result;
        }

        @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            if (!valid() || beforeLength < 0 || afterLength < 0) return false;
            Editable e = getEditable();
            if (getComposingSpanStart(e) >= 0) return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
            int selStart = Selection.getSelectionStart(e), selEnd = Selection.getSelectionEnd(e);
            if (selStart < 0 || selEnd < 0) return false;
            int left = Math.min(selStart, selEnd), right = Math.max(selStart, selEnd);
            if (RemoteTextState.splitsSurrogate(e, left) || RemoteTextState.splitsSurrogate(e, right)) return false;
            int before = Math.min(beforeLength, 64), after = Math.min(afterLength, 64);
            int known = Character.codePointCount(e, 0, left);
            int knownAfter = Character.codePointCount(e, right, e.length());
            if (after > knownAfter
                || (before > known && (before != 1 || left != right || right != e.length() || after != 0))) return false;
            int start = Character.offsetByCodePoints(e, left, -Math.min(before, known));
            int end = Character.offsetByCodePoints(e, right, after);
            boolean finalBackspace = left == right && right == e.length() && before == 1 && after == 0;
            boolean result = super.deleteSurroundingText(left - start, end - right);
            if (result && reconcile(e, finalBackspace) && before > known) backspaces(before - known);
            return result;
        }

        @Override public boolean performEditorAction(int action) {
            if (generation != mine) return false;
            finishCurrent();
            out.tapKey(SCAN_ENTER);
            return true;
        }

        @Override public boolean sendKeyEvent(KeyEvent event) {
            if (!valid()) return false;
            int scan = scanFor(event.getKeyCode());
            if (scan == 0) {
                // A character key from a soft keyboard that sends keys: typed like committed text
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int u = event.getUnicodeChar();
                    if (u > 0) commitText(new String(Character.toChars(u)), 1);
                } else if (event.getAction() == KeyEvent.ACTION_MULTIPLE && event.getCharacters() != null) {
                    commitText(event.getCharacters(), 1);
                }
                return true;
            }
            if (scan == SCAN_BACKSPACE && getComposingSpanStart(getEditable()) >= 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) super.deleteSurroundingText(1, 0);
                return true;
            }
            if (scan == SCAN_BACKSPACE && event.getAction() == KeyEvent.ACTION_DOWN && getEditable().length() > 0) {
                // A backspace the input method sends as a key while it knows the text: the same as deleting it
                deleteSurroundingText(1, 0);
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (scan != SCAN_BACKSPACE) finishCurrent();
                if (keysDown.add(scan) || event.getRepeatCount() > 0) out.tapKey(scan);
            } else if (event.getAction() == KeyEvent.ACTION_UP && keysDown.remove(scan)) {
                if (scan != SCAN_BACKSPACE && scan != SCAN_ENTER && scan != SCAN_TAB) forget();
            }
            return true;
        }
    }
}
