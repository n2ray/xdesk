/*
 * Derived from Sall (https://github.com/n2ray/sall, RemoteTextState.java), the Android client of Sill
 * (https://github.com/Saffsanity/sill). Copyright 2026 Noah Saffer. Licensed under the Apache License, Version 2.0.
 * Modified for XDesk (Java 9, public for MacInput).
 */
package com.termux.x11.mac;
import java.text.BreakIterator;
import java.util.Locale;

/**
 * The text bridge (INPUT.md §5.3): the suffix typed since the last action that may have moved the Mac's caret.
 * A change to it becomes "delete n characters, then type this"; a newline or tab is a fence nothing goes back
 * over; a click, an arrow key or another window clears it.
 */
public final class RemoteTextState {
    public static final class Edit {
        public final int backspaces;
        public final String text;

        Edit(int backspaces, String text) {
            this.backspaces = backspaces;
            this.text = text;
        }
    }
    private String sent = "";
    private int fence;
    private String retype = "";
    private String composingRetype = "";

    public Edit reconcile(String next) { return reconcile(next, false); }

    public Edit reconcile(String next, boolean backspaceFinal) {
        if (next.equals(sent)) return new Edit(0, "");
        retype = "";
        BreakIterator previous = BreakIterator.getCharacterInstance(Locale.ROOT);
        previous.setText(sent);
        int last = previous.preceding(sent.length());
        boolean deleteFinal = backspaceFinal && last != BreakIterator.DONE
                && next.equals(sent.substring(0, last));
        if (!deleteFinal && (next.length() < fence || !next.regionMatches(0, sent, 0, fence))) return null;
        int common = 0, limit = Math.min(sent.length(), next.length());
        while (common < limit && sent.charAt(common) == next.charAt(common)) common++;
        BreakIterator current = BreakIterator.getCharacterInstance(Locale.ROOT);
        current.setText(next);
        int minimum = deleteFinal ? 0 : fence;
        while (common > minimum && (!previous.isBoundary(common) || !current.isBoundary(common))) common--;
        int count = 0;
        if (common < sent.length())
            for (int at = previous.following(common); at != BreakIterator.DONE; at = previous.next()) count++;
        Edit edit = new Edit(count, next.substring(common));
        sent = next;
        fence = Math.min(fence, sent.length());
        for (int i = common; i < sent.length(); i++) {
            char c = sent.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') fence = i + 1;
        }
        return edit;
    }

    public String text() { return sent; }
    int fence() { return fence; }
    boolean hasRetype() { return !retype.isEmpty(); }
    boolean hasComposingRetype() { return !composingRetype.isEmpty(); }
    String filterComposing(String text) {
        if (text.isEmpty()) return text;
        if (composingRetype.isEmpty() && !retype.isEmpty()) {
            composingRetype = retype;
            retype = "";
        }
        if (composingRetype.isEmpty()) return text;
        if (text.startsWith(composingRetype)) return text.substring(composingRetype.length());
        if (composingRetype.startsWith(text)) return "";
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\t') >= 0) return null;
        return text;
    }
    String filterComposingCommit(String text) {
        String prefix = composingRetype;
        composingRetype = "";
        if (prefix.isEmpty()) return text;
        if (text.startsWith(prefix)) return text.substring(prefix.length());
        if (prefix.startsWith(text)) return "";
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\t') >= 0 ? null : text;
    }
    void clearComposingRetype() { composingRetype = ""; }
    int[] selection(int start, int end) {
        int left = Math.min(start, end), right = Math.max(start, end);
        if (left == right || right <= fence) return new int[]{sent.length(), sent.length()};
        return new int[]{left, right};
    }
    boolean crossesFence(int start, int end) {
        return start >= 0 && start < fence && end > fence && end <= sent.length()
                && !splitsSurrogate(sent, start) && !splitsSurrogate(sent, end);
    }
    Edit deleteCrossingSelection(int start, int end) {
        if (!crossesFence(start, end)) return null;
        String kept = sent.substring(start, fence);
        Edit edit = reconcile(sent.substring(0, fence) + sent.substring(end));
        if (edit != null) retype = kept;
        return edit;
    }
    String consumeRetype(String text) {
        String pending = retype;
        retype = "";
        if (pending.isEmpty()) return text;
        if (text.startsWith(pending)) return text.substring(pending.length());
        if (pending.startsWith(text)) { retype = pending.substring(text.length()); return ""; }
        return null;
    }
    void clearRetype() { retype = ""; }

    boolean wantsTrim() {
        if (sent.length() <= 4000) return false;
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(sent);
        int count = 0;
        for (int at = it.first(); (at = it.next()) != BreakIterator.DONE; )
            if (++count > 4000) return true;
        return false;
    }
    public boolean trim() {
        if (!wantsTrim()) return false;
        BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
        it.setText(sent);
        int cut = it.last();
        for (int count = 0; count < 1000; count++) cut = it.previous();
        sent = sent.substring(cut);
        fence = Math.max(0, fence - cut);
        retype = "";
        return true;
    }
    static boolean splitsSurrogate(CharSequence text, int at) {
        return at > 0 && at < text.length() && Character.isHighSurrogate(text.charAt(at - 1))
                && Character.isLowSurrogate(text.charAt(at));
    }
    static int deletionStart(CharSequence text, int at) { return splitsSurrogate(text, at) ? at - 1 : at; }
    static int deletionEnd(CharSequence text, int at) { return splitsSurrogate(text, at) ? at + 1 : at; }
    public void clear() { sent = ""; fence = 0; retype = ""; composingRetype = ""; }
}
