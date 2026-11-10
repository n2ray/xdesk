package com.termux.x11;

import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A keyboard made in the kernel while a browser has taken over: Android then treats the session as one with a real
 * keyboard, so the input method shows candidates and no soft keyboard, and characters are looked up in a layout of the
 * right language. Keys are typed on it rather than injected: an injected key is read with the virtual keyboard's US layout,
 * and system shortcuts such as Ctrl+Space ignore it. Runs in the shell-user process, which may write to /dev/uhid. It goes
 * away when closed or when the process ends.
 */
final class UhidKeyboard {
    /** EvdevReader and InputInjector tell it from a keyboard that is really plugged in by this name. */
    static final String NAME = "XDesk Web Keyboard";

    private static final String TAG = "XDeskInjector";
    private static final int UHID_DESTROY = 1, UHID_CREATE2 = 11, UHID_INPUT2 = 12, EVENT_SIZE = 4380, BUS_USB = 3, REPORT_SIZE = 8;
    private static final int APPEAR_TRIES = 40, APPEAR_STEP_MS = 50;
    private static final int VENDOR = 0x1209, PRODUCT_BASE = 0x7d00;
    /** A standard boot keyboard. */
    private static final int[] DESCRIPTOR = {
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08,
        0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x01, 0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x91, 0x01, 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65,
        0x81, 0x00, 0xC0,
    };

    private final FileOutputStream out;
    private final String layout;

    private UhidKeyboard(FileOutputStream out, String layout) {
        this.out = out;
        this.layout = layout;
    }

    String layout() {
        return layout;
    }

    /** HID country code, from which Android picks the layout. */
    private static int country(String layout) {
        switch (layout) {
            case "de": return 9;
            case "fr": return 8;
            case "it": return 14;
            case "es": return 25;
            case "gb": return 32;
            default: return 33;
        }
    }

    private static void string(ByteBuffer b, String s, int size) {
        byte[] t = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.put(t);
        b.position(b.position() + size - t.length);
    }

    /** Null when the system does not allow it: keys still work, with the virtual keyboard's layout. */
    static UhidKeyboard create(String layout) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream("/dev/uhid");
            int country = country(layout);
            ByteBuffer b = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(UHID_CREATE2);
            string(b, NAME, 128);
            string(b, "xdesk/web", 64);
            string(b, "xdesk-web-" + layout, 64);
            b.putShort((short) DESCRIPTOR.length);
            b.putShort((short) BUS_USB);
            b.putInt(VENDOR);
            // Android remembers a layout per product, and this one must not carry over to another language
            b.putInt(PRODUCT_BASE + country);
            b.putInt(1);
            b.putInt(country);
            for (int v : DESCRIPTOR) b.put((byte) v);
            out.write(b.array());
            Log.i(TAG, "web keyboard " + layout);
            // What is typed next needs it, and it takes Android a moment
            for (int i = 0; i < APPEAR_TRIES && InputInjector.physicalKeyboard() == null; i++) Thread.sleep(APPEAR_STEP_MS);
            Thread.sleep(APPEAR_STEP_MS);
            return new UhidKeyboard(out, layout);
        } catch (IOException | SecurityException | InterruptedException e) {
            Log.w(TAG, "no web keyboard", e);
            if (out != null) try {
                out.close();
            } catch (IOException ignored) { }
            return null;
        }
    }

    /** Linux key code → HID usage. */
    private static final int[] USAGE = new int[128];
    private static final HashMap<Integer, Integer> MODIFIER_OF_SCAN = new HashMap<>(), MODIFIER_OF_KEYCODE = new HashMap<>();

    private static void usage(int... pairs) {
        for (int i = 0; i < pairs.length; i += 2) USAGE[pairs[i]] = pairs[i + 1];
    }

    static {
        usage(30, 4, 48, 5, 46, 6, 32, 7, 18, 8, 33, 9, 34, 10, 35, 11, 23, 12, 36, 13, 37, 14, 38, 15, 50, 16, 49, 17, 24, 18, 25, 19,
            16, 20, 19, 21, 31, 22, 20, 23, 22, 24, 47, 25, 17, 26, 45, 27, 21, 28, 44, 29);
        for (int i = 2; i <= 11; i++) USAGE[i] = 0x1E + i - 2;
        usage(28, 0x28, 1, 0x29, 14, 0x2A, 15, 0x2B, 57, 0x2C, 12, 0x2D, 13, 0x2E, 26, 0x2F, 27, 0x30, 43, 0x32, 39, 0x33, 40, 0x34,
            41, 0x35, 51, 0x36, 52, 0x37, 53, 0x38, 58, 0x39, 87, 0x44, 88, 0x45, 99, 0x46, 70, 0x47, 119, 0x48, 110, 0x49, 102, 0x4A,
            104, 0x4B, 111, 0x4C, 107, 0x4D, 109, 0x4E, 106, 0x4F, 105, 0x50, 108, 0x51, 103, 0x52, 69, 0x53, 98, 0x54, 55, 0x55,
            74, 0x56, 78, 0x57, 96, 0x58, 79, 0x59, 80, 0x5A, 81, 0x5B, 75, 0x5C, 76, 0x5D, 77, 0x5E, 71, 0x5F, 72, 0x60, 73, 0x61,
            82, 0x62, 83, 0x63, 86, 0x64, 127, 0x65);
        for (int i = 0; i < 10; i++) USAGE[59 + i] = 0x3A + i;
        int[][] modifiers = {
            {29, KeyEvent.KEYCODE_CTRL_LEFT, 0x01}, {42, KeyEvent.KEYCODE_SHIFT_LEFT, 0x02}, {56, KeyEvent.KEYCODE_ALT_LEFT, 0x04},
            {125, KeyEvent.KEYCODE_META_LEFT, 0x08}, {97, KeyEvent.KEYCODE_CTRL_RIGHT, 0x10}, {54, KeyEvent.KEYCODE_SHIFT_RIGHT, 0x20},
            {100, KeyEvent.KEYCODE_ALT_RIGHT, 0x40}, {126, KeyEvent.KEYCODE_META_RIGHT, 0x80},
        };
        for (int[] m : modifiers) {
            MODIFIER_OF_SCAN.put(m[0], m[2]);
            MODIFIER_OF_KEYCODE.put(m[1], m[2]);
        }
    }

    private int modifiers;
    private final ArrayList<Integer> pressed = new ArrayList<>();
    private final HashMap<Integer, Integer> scanOfKeyCode = new HashMap<>();
    private int mappedDevice = -1;

    private void report(int mods, List<Integer> keys) {
        ByteBuffer b = ByteBuffer.allocate(4 + 2 + REPORT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(UHID_INPUT2);
        b.putShort((short) REPORT_SIZE);
        b.put((byte) mods);
        b.put((byte) 0);
        for (int i = 0; i < Math.min(keys.size(), 6); i++) b.put((byte) (int) keys.get(i));
        try {
            out.write(b.array());
        } catch (IOException e) {
            Log.w(TAG, "web keyboard report", e);
        }
    }

    /** Pressed on this keyboard, as if by hand: Android handles it as any key of a real keyboard. */
    void key(int scan, boolean down) {
        Integer bit = MODIFIER_OF_SCAN.get(scan);
        if (bit != null) {
            modifiers = down ? modifiers | bit : modifiers & ~bit;
        } else {
            int usage = scan >= 0 && scan < USAGE.length ? USAGE[scan] : 0;
            if (usage == 0) return;
            pressed.remove((Integer) usage);
            if (down) pressed.add(usage);
        }
        report(modifiers, pressed);
    }

    /** What each key code comes from on this keyboard's layout, which may have moved it, as German does with Z. */
    private void mapKeys(InputDevice keyboard) {
        if (mappedDevice == keyboard.getId()) return;
        mappedDevice = keyboard.getId();
        scanOfKeyCode.clear();
        for (Map.Entry<Integer, Integer> e : InputInjector.KEYS.entrySet()) {
            int produced = keyboard.getKeyCodeForKeyLocation(e.getValue());
            scanOfKeyCode.put(produced != KeyEvent.KEYCODE_UNKNOWN ? produced : e.getValue(), e.getKey());
        }
        // Two keys share the place BACKSLASH on a US keyboard, and the system answers for just the other one
        scanOfKeyCode.put(KeyEvent.KEYCODE_BACKSLASH, 43);
    }

    /** Types the keys that make this text in the layout. Returns how many characters it has no key for. */
    int text(String s, InputDevice keyboard) {
        if (keyboard == null) return s.length();
        mapKeys(keyboard);
        KeyCharacterMap map = keyboard.getKeyCharacterMap();
        int dropped = 0;
        for (int i = 0; i < s.length(); i++) {
            KeyEvent[] events = map.getEvents(new char[]{s.charAt(i)});
            int mods = 0, usage = 0;
            if (events != null) for (KeyEvent k : events) {
                if (k.getAction() != KeyEvent.ACTION_DOWN) continue;
                Integer bit = MODIFIER_OF_KEYCODE.get(k.getKeyCode());
                Integer scan = scanOfKeyCode.get(k.getKeyCode());
                if (bit != null) mods |= bit;
                else if (scan != null && scan < USAGE.length) usage = USAGE[scan];
            }
            if (usage == 0) {
                dropped++;
                continue;
            }
            report(mods, Collections.singletonList(usage));
            report(mods, Collections.emptyList());
            report(modifiers, pressed);
        }
        return dropped;
    }

    void close() {
        try {
            ByteBuffer b = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(UHID_DESTROY);
            out.write(b.array());
        } catch (IOException e) {
            Log.w(TAG, "web keyboard not destroyed", e);
        }
        try {
            out.close();
        } catch (IOException ignored) { }
    }
}
