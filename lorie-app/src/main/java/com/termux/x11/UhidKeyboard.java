package com.termux.x11;

import android.util.Log;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A keyboard made in the kernel while a browser has taken over: Android then treats the session as one with a real
 * keyboard, so the input method shows candidates and no soft keyboard, and characters are looked up in a layout of the
 * right language. It never sends a key itself, see InputInjector. Runs in the shell-user process, which may write to
 * /dev/uhid. It goes away when closed or when the process ends.
 */
final class UhidKeyboard {
    /** EvdevReader and InputInjector tell it from a keyboard that is really plugged in by this name. */
    static final String NAME = "XDesk Web Keyboard";

    private static final String TAG = "XDeskInjector";
    private static final int UHID_DESTROY = 1, UHID_CREATE2 = 11, EVENT_SIZE = 4380, BUS_USB = 3;
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
            return new UhidKeyboard(out, layout);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "no web keyboard", e);
            if (out != null) try {
                out.close();
            } catch (IOException ignored) { }
            return null;
        }
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
