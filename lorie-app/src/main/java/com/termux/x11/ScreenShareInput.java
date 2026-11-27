// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import com.termux.x11.screenshare.ScreenShareSession;

import java.util.HashMap;

/**
 * XDesk's touchpad and keyboard for a Mac reached through macOS Screen Sharing (ScreenShareSource). Screen Sharing
 * takes an absolute pointer: the touchpad's moves are added up here, in pixels of the Mac's virtual screen, and
 * the buttons and wheel go as the VNC button mask. The Mac receives layout-resolved X keysyms, with a
 * US mapping for sources that only supply scan codes. With XDesk's modifier translation on, Ctrl
 * and Command trade places so the PC keyboard's Ctrl+C is the Mac's ⌘C. Text from the phone's keyboard goes as the
 * characters' keysyms. Keys held when the input leaves are let go (releaseAll).
 */
public final class ScreenShareInput implements InputSink {
    private final String hostId;
    private int x = -1, y = -1, buttons;
    private final HashMap<Integer, Integer> deviceButtons = new HashMap<>();
    /** Device and scan code identify the original press, even after the layout changes. */
    private final HashMap<Long, Integer> down = new HashMap<>();

    public ScreenShareInput(String hostId) {
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

    /** Fractions of a frame-buffer pixel left over from the moves (a screen pixel is less than one at 适度放大). */
    private float restX, restY;

    /**
     * The touchpad's moves are screen pixels: the frame buffer may be smaller than the screen (适度放大, or a limit),
     * so they are scaled to it and the pointer goes as fast on the screen at every 桌面缩放.
     */
    @Override public void move(int dx, int dy) {
        ScreenShareSession s = session();
        if (s == null) return;
        float k = s.sizing.screenW > 0 ? (float) s.width / s.sizing.screenW : 1f;
        restX += dx * k;
        restY += dy * k;
        int mx = Math.round(restX), my = Math.round(restY);
        restX -= mx;
        restY -= my;
        x = Math.max(0, Math.min(s.width - 1, x + mx));
        y = Math.max(0, Math.min(s.height - 1, y + my));
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
        // Apple high-performance pointer record: left, right, middle.
        switch (button) {
            case 1: return 1;
            case 2: return 4;
            case 3: return 2;
            default: return 0;
        }
    }

    @Override public void button(int button, boolean isDown) {
        deviceButton(-1, button, isDown);
    }

    public void deviceButton(int device, int button, boolean isDown) {
        int b = bit(button);
        if (b == 0) return;
        int held = deviceButtons.getOrDefault(device, 0);
        int next = isDown ? held | b : held & ~b;
        if (held == next) return;
        if (next == 0) deviceButtons.remove(device); else deviceButtons.put(device, next);
        updateButtons();
    }

    private void updateButtons() {
        int mask = 0;
        for (int held : deviceButtons.values()) mask |= held;
        if (mask == buttons) return;
        buttons = mask;
        send();
    }

    @Override public void click(int button) {
        button(button, true);
        button(button, false);
    }

    // A wheel button pulse arrives in AppKit as one precise scrolling point.
    private static final int POINTS_PER_NOTCH = 36;
    private final io.github.n2ray.xdesk.input.WheelSteps wheelPoints = new io.github.n2ray.xdesk.input.WheelSteps();

    @Override public boolean scrollFine(float dy, float dx, int phase) {
        if (phase != io.github.n2ray.xdesk.input.Input.WHEEL) return false;
        if (wheelPoints.add(dx * POINTS_PER_NOTCH, dy * POINTS_PER_NOTCH))
            scrollPoints(wheelPoints.y(), wheelPoints.x());
        return true;
    }

    // Touchpad gestures still use the router's whole-step fallback.
    @Override public void scroll(int dy, int dx) {
        scrollPoints(dy * 3, dx * 3);
    }

    private void scrollPoints(int dy, int dx) {
        ScreenShareSession s = session();
        if (s == null) return;
        if (dy != 0) s.wheel(buttons, dy > 0 ? 16 : 8, x, y, Math.abs(dy));
        if (dx != 0) s.wheel(buttons, dx > 0 ? 64 : 32, x, y, Math.abs(dx));
    }

    /** Says what arrives (lengths and codes, never the text): the phone's keyboard reaching a Mac or not. */
    private static void log(String what) {
        android.util.Log.i(com.termux.x11.screenshare.ScreenShareSession.TAG, "input " + what);
    }

    @Override public void key(int scan, boolean isDown) {
        keyWithLayout(scan, 0, isDown);
    }

    /** Preserve the actual layout on press and release the same keysym even after a layout switch. */
    public boolean keyWithLayout(int scan, int layoutSym, boolean isDown) {
        return deviceKey(-1, scan, layoutSym, isDown);
    }

    public boolean deviceKey(int device, int scan, int layoutSym, boolean isDown) {
        long key = ((long) device << 32) | (scan & 0xffffffffL);
        int sym = layoutSym != 0 ? translateModifier(layoutSym) : keysym(scan);
        if (sym == 0 && !down.containsKey(key)) return false;
        if (isDown && sym == 0xff09) {
            // Keep Command down across repeated Tab presses until the physical switcher modifier lifts.
            for (java.util.Map.Entry<Long,Integer> entry : down.entrySet()) {
                int modifier = entry.getKey().intValue();
                if (modifier != 56 && modifier != 100 && modifier != 125 && modifier != 126) continue;
                Integer original = entry.getValue();
                if (original == null || original == 0xffeb || original == 0xffec) continue;
                int command = modifier == 100 || modifier == 126 ? 0xffec : 0xffeb;
                entry.setValue(command);
                if (!down.containsValue(original)) keysym(false, original);
                if (java.util.Collections.frequency(down.values(), command) == 1) keysym(true, command);
            }
        }
        if (isDown) {
            if (down.containsKey(key)) return true;
            down.put(key, sym);
            if (java.util.Collections.frequency(down.values(), sym) == 1) keysym(true, sym);
        } else {
            Integer held = down.remove(key);
            if (held != null && !down.containsValue(held)) keysym(false, held);
        }
        return true;
    }

    public void releaseDevice(int device) {
        if (deviceButtons.remove(device) != null) updateButtons();
        java.util.Iterator<java.util.Map.Entry<Long,Integer>> it = down.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Long,Integer> entry = it.next();
            if ((int)(entry.getKey() >> 32) != device) continue;
            int symbol = entry.getValue();
            it.remove();
            if (!down.containsValue(symbol)) keysym(false, symbol);
        }
    }

    private static int translateModifier(int sym) {
        if (!MacInput.translateModifiers) return sym;
        switch (sym) {
            case 0xffe3: return 0xffeb;
            case 0xffe4: return 0xffec;
            case 0xffeb: return 0xffe3;
            case 0xffec: return 0xffe4;
            default: return sym;
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
            int sym = c == '\n' ? 0xff0d : c == '\t' ? 0xff09 : characterKeysym(c);
            if (sym == 0) continue;
            keysym(true, sym);
            keysym(false, sym);
        }
    }

    /** Android marks the next key for composition by setting the high bit on a spacing accent. */
    public static int hardwareCharacterKeysym(int ch) {
        if (ch >= 0) return characterKeysym(ch);
        switch (ch & Integer.MAX_VALUE) {
            case 0x0060: return 0xfe50; // dead_grave
            case 0x00b4: return 0xfe51; // dead_acute
            case 0x005e: return 0xfe52; // dead_circumflex
            case 0x007e: return 0xfe53; // dead_tilde
            case 0x00af: return 0xfe54; // dead_macron
            case 0x02d8: return 0xfe55; // dead_breve
            case 0x02d9: return 0xfe56; // dead_abovedot
            case 0x00a8: return 0xfe57; // dead_diaeresis
            case 0x02da: return 0xfe58; // dead_abovering
            case 0x02dd: return 0xfe59; // dead_doubleacute
            case 0x02c7: return 0xfe5a; // dead_caron
            case 0x00b8: return 0xfe5b; // dead_cedilla
            case 0x02db: return 0xfe5c; // dead_ogonek
            default: return characterKeysym(ch & Integer.MAX_VALUE);
        }
    }

    private static int characterKeysym(int codePoint) {
        if (Character.isISOControl(codePoint) || !Character.isValidCodePoint(codePoint)) return 0;
        return codePoint <= 255 ? codePoint : 0x01000000 | codePoint;
    }

    private void keysym(boolean isDown, int sym) {
        ScreenShareSession s = session();
        if (s != null) s.key(isDown, sym);
        else if (isDown) log("keysym dropped: no session for " + hostId);
    }

    /** Lets go of every key and button held (the input leaves, the screen goes). */
    public void releaseAll() {
        wheelPoints.clear();
        deviceButtons.clear();
        for (int sym : new java.util.HashSet<>(down.values())) keysym(false, sym);
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
