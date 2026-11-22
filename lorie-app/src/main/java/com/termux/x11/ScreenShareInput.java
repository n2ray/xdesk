// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import com.termux.x11.screenshare.ScreenShareSession;

import java.util.HashMap;

/**
 * XDesk's touchpad and keyboard for a Mac reached through macOS Screen Sharing (ScreenShareSource). Screen Sharing
 * takes an absolute pointer: the touchpad's moves are added up here, in pixels of the Mac's virtual screen, and
 * the buttons and wheel go as the VNC button mask. Linux key codes become X keysyms (US layout, as the Mac reads
 * keysyms by character); with XDesk's modifier translation on (MacInput.translateModifiers, as for Sill Macs) Ctrl
 * and Command trade places so the PC keyboard's Ctrl+C is the Mac's ⌘C. Text from the phone's keyboard goes as the
 * characters' keysyms. Keys held when the input leaves are let go (releaseAll).
 */
final class ScreenShareInput implements InputSink {
    private final String hostId;
    private int x = -1, y = -1, buttons;
    /** Linux key code -> the keysym sent down for it, to let go of the same one. */
    private final HashMap<Integer, Integer> down = new HashMap<>();

    ScreenShareInput(String hostId) {
        this.hostId = hostId;
    }

    /** This Mac's session now, or null (not connected yet, or ended): its input then goes nowhere. */
    private ScreenShareSession session() {
        ScreenShareSession s = ScreenShareSession.current();
        if (s == null || !s.host.id.equals(hostId)) return null;
        if (x < 0) {
            x = s.width / 2;
            y = s.height / 2;
        }
        return s;
    }

    private void send() {
        ScreenShareSession s = session();
        if (s != null) s.pointer(buttons, x, y);
    }

    /** The picture does not carry the Mac's cursor (fast mode): the screen draws XDesk's where it is. */
    private void shown() {
        com.termux.x11.screenshare.ScreenShareActivity.pointerAt(hostId, x, y);
    }

    @Override public void move(int dx, int dy) {
        ScreenShareSession s = session();
        if (s == null) return;
        x = Math.max(0, Math.min(s.width - 1, x + dx));
        y = Math.max(0, Math.min(s.height - 1, y + dy));
        s.pointer(buttons, x, y);
        shown();
    }

    @Override public void moveToFraction(float fx, float fy) {
        ScreenShareSession s = session();
        if (s == null) return;
        x = Math.max(0, Math.min(s.width - 1, Math.round(fx * s.width)));
        y = Math.max(0, Math.min(s.height - 1, Math.round(fy * s.height)));
        s.pointer(buttons, x, y);
        shown();
    }

    /** A Mac's whole screen: the Dock and the notification centre over it as over a Sill Mac's desktop. */
    @Override public io.github.n2ray.xdesk.input.Caps caps() {
        return io.github.n2ray.xdesk.input.Caps.MAC_DESKTOP;
    }

    /**
     * For the Dock and the notification centre (DockPointer): where the pointer is, 0 to 1 across, and down 1 at the
     * bottom row it stops at. The picture fills the screen it is shown on (the Mac's virtual screen is made its size).
     */
    @Override public boolean pointerPlace(float[] out) {
        ScreenShareSession s = session();
        if (s == null) return false;
        out[0] = (float) x / Math.max(1, s.width - 1);
        out[1] = y >= s.height - 2 ? 1f : (float) y / s.height;
        return true;
    }

    @Override public boolean screenPlace(float[] out) {
        ScreenShareSession s = session();
        if (s == null) return false;
        out[0] = (float) x / s.width;
        out[1] = (float) y / s.height;
        return true;
    }

    private static int bit(int button) {
        return button == 1 ? 1 : button == 2 ? 2 : button == 3 ? 4 : 0;
    }

    @Override public void button(int button, boolean isDown) {
        int b = bit(button);
        if (b == 0) return;
        buttons = isDown ? buttons | b : buttons & ~b;
        send();
    }

    @Override public void click(int button) {
        button(button, true);
        button(button, false);
    }

    @Override public void scroll(int dy, int dx) {
        step(dy > 0 ? 16 : 8, Math.abs(dy));
        step(dx > 0 ? 64 : 32, Math.abs(dx));
    }

    private void step(int wheel, int n) {
        for (int i = 0; i < Math.min(16, n); i++) {
            ScreenShareSession s = session();
            if (s == null) return;
            s.pointer(buttons | wheel, x, y);
            s.pointer(buttons, x, y);
        }
    }

    /** Says what arrives (lengths and codes, never the text): the phone's keyboard reaching a Mac or not. */
    private static void log(String what) {
        android.util.Log.i(com.termux.x11.screenshare.ScreenShareSession.TAG, "input " + what);
    }

    @Override public void key(int scan, boolean isDown) {
        if (isDown) log("key " + scan + " sym 0x" + Integer.toHexString(keysym(scan)) + (session() == null ? " (no session)" : ""));
        if (isDown) {
            int sym = keysym(scan);
            if (sym == 0 || down.containsKey(scan)) return;
            down.put(scan, sym);
            keysym(true, sym);
        } else {
            Integer sym = down.remove(scan);
            if (sym != null) keysym(false, sym);
        }
    }

    @Override public void tapKey(int scan) {
        key(scan, true);
        key(scan, false);
    }

    @Override public void text(String s) {
        log("text of " + s.codePointCount(0, s.length()) + " characters" + (session() == null ? " (no session)" : ""));
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            int sym = c == '\n' ? 0xff0d : c == '\t' ? 0xff09 : c < 32 ? 0 : c <= 255 ? c : 0x01000000 | c;
            if (sym == 0) continue;
            keysym(true, sym);
            keysym(false, sym);
        }
    }

    private void keysym(boolean isDown, int sym) {
        ScreenShareSession s = session();
        if (s != null) s.key(isDown, sym);
        else if (isDown) log("keysym dropped: no session for " + hostId);
    }

    /** Lets go of every key and button held (the input leaves, the screen goes). */
    void releaseAll() {
        for (int sym : down.values()) keysym(false, sym);
        down.clear();
        if (buttons != 0) {
            buttons = 0;
            send();
        }
    }

    // --- Linux key codes to X keysyms ---

    private static final String ROW1 = "1234567890-=", ROW2 = "qwertyuiop[]", ROW3 = "asdfghjkl;'`", ROW4 = "\\zxcvbnm,./";

    private static int keysym(int scan) {
        boolean swap = MacInput.translateModifiers;
        if (scan >= 2 && scan <= 13) return ROW1.charAt(scan - 2);
        if (scan >= 16 && scan <= 27) return ROW2.charAt(scan - 16);
        if (scan >= 30 && scan <= 41) return ROW3.charAt(scan - 30);
        if (scan >= 43 && scan <= 53) return ROW4.charAt(scan - 43);
        if (scan >= 59 && scan <= 68) return 0xffbe + scan - 59;   // F1..F10
        switch (scan) {
            case 1: return 0xff1b;     // Escape
            case 14: return 0xff08;    // BackSpace
            case 15: return 0xff09;    // Tab
            case 28: case 96: return 0xff0d;   // Return, KP Enter
            case 57: return ' ';
            case 58: return 0xffe5;    // Caps Lock
            case 87: return 0xffc8;    // F11
            case 88: return 0xffc9;    // F12
            case 42: return 0xffe1;    // Shift
            case 54: return 0xffe2;
            case 29: return swap ? 0xffeb : 0xffe3;   // Ctrl (Command when translated)
            case 97: return swap ? 0xffec : 0xffe4;
            case 125: return swap ? 0xffe3 : 0xffeb;  // Meta / Command (Ctrl when translated)
            case 126: return swap ? 0xffe4 : 0xffec;
            case 56: return 0xffe9;    // Alt = Option
            case 100: return 0xffea;
            case 102: return 0xff50;   // Home
            case 103: return 0xff52;   // Up
            case 104: return 0xff55;   // Page Up
            case 105: return 0xff51;   // Left
            case 106: return 0xff53;   // Right
            case 107: return 0xff57;   // End
            case 108: return 0xff54;   // Down
            case 109: return 0xff56;   // Page Down
            case 110: return 0xff63;   // Insert
            case 111: return 0xffff;   // Delete
            case 55: return 0xffaa;    // KP *
            case 74: return 0xffad;    // KP -
            case 78: return 0xffab;    // KP +
            case 98: return 0xffaf;    // KP /
            case 83: return 0xffae;    // KP .
            case 71: return 0xffb7; case 72: return 0xffb8; case 73: return 0xffb9;
            case 75: return 0xffb4; case 76: return 0xffb5; case 77: return 0xffb6;
            case 79: return 0xffb1; case 80: return 0xffb2; case 81: return 0xffb3; case 82: return 0xffb0;
            default: return 0;
        }
    }
}
