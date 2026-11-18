package com.termux.x11;

import android.util.Log;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The phone's on-screen gamepad as a real one (GAMEPAD.md §4, §5): a uhid device that the kernel's hid-generic turns into
 * an evdev gamepad, which Android hands to the focused app like any plugged-in pad. It says it is a wired Xbox 360 pad
 * (as scrcpy's does), so games and Android's Vendor_045e_Product_028e.kl know its buttons and axes.
 *
 * Runs in the shell-user process, which may write to /dev/uhid. Everything happens on one thread of its own, in order:
 * the injector's worker sleeps between keys, which would make sticks stutter. Closing the session's device is the
 * caller's job: in ShellServer the process stays.
 */
final class GamepadHid {
    static final String NAME = "XDesk Gamepad";

    private static final String TAG = "XDeskInjector";
    private static final int UHID_DESTROY = 1, UHID_CREATE2 = 11, UHID_INPUT2 = 12, EVENT_SIZE = 4380, BUS_USB = 3;
    private static final int VENDOR = 0x045e, PRODUCT = 0x028e, REPORT_SIZE = 13, CLOSE_WAIT_MS = 500;
    /** No direction on the hat. */
    static final int HAT_NONE = 8;
    /**
     * One Game Pad collection, no report id: buttons 1-15 (and a padding bit), the hat (and four), the sticks X Y Rx Ry
     * as signed 16 bit, the triggers Z Rz as 0-255. hid-input makes the buttons BTN_SOUTH .. BTN_THUMBR in that order.
     */
    private static final int[] DESCRIPTOR = {
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0F, 0x81, 0x02,
        0x75, 0x01, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03, 0x65, 0x00, 0x45, 0x00,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34, 0x16, 0x00, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02,
        0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0xC0,
    };

    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "xdesk-gamepad");
        t.setDaemon(true);
        return t;
    });
    /** On the thread only. Null while there is no device. */
    private FileOutputStream out;
    private final ByteBuffer report = ByteBuffer.allocate(4 + 2 + REPORT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private int buttons, hat = HAT_NONE, lx, ly, rx, ry, lt, rt;
    private boolean closed;

    private void post(Runnable r) {
        try {
            thread.execute(r);
        } catch (RejectedExecutionException ignored) {
            // closed
        }
    }

    /** Makes the device if there is none, and says nothing is pressed. */
    void open() {
        post(() -> {
            if (closed) return;
            if (out == null) create();
            neutral();
        });
    }

    /** A whole state. Buttons by number (GAMEPAD.md §4): button n is bit n-1. Sticks -32768..32767, triggers 0..255. */
    void state(int buttons, int hat, int lx, int ly, int rx, int ry, int lt, int rt) {
        post(() -> {
            if (closed) return;
            if (out == null) create();
            this.buttons = buttons;
            this.hat = hat;
            this.lx = lx;
            this.ly = ly;
            this.rx = rx;
            this.ry = ry;
            this.lt = lt;
            this.rt = rt;
            write();
        });
    }

    /** Lets go of everything, and the device stays: a game would say the pad went away and pause. */
    void end() {
        post(() -> {
            if (out != null) neutral();
        });
    }

    /** Lets go, takes the device away and stops the thread. For the session's end. */
    void close() {
        post(() -> {
            closed = true;
            if (out == null) return;
            neutral();
            try {
                ByteBuffer b = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(UHID_DESTROY);
                out.write(b.array());
            } catch (IOException e) {
                Log.w(TAG, "gamepad not destroyed", e);
            }
            closeFile();
            Log.i(TAG, "gamepad gone");
        });
        thread.shutdown();
        try {
            thread.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) { }
    }

    private void neutral() {
        buttons = 0;
        hat = HAT_NONE;
        lx = ly = rx = ry = lt = rt = 0;
        write();
    }

    private static void string(ByteBuffer b, String s, int size) {
        byte[] t = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.put(t);
        b.position(b.position() + size - t.length);
    }

    private void create() {
        FileOutputStream f = null;
        try {
            // Write only: hid-generic asks the device nothing
            f = new FileOutputStream("/dev/uhid");
            ByteBuffer b = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(UHID_CREATE2);
            string(b, NAME, 128);
            string(b, "xdesk/gamepad", 64);
            // The same every time: Android and games keep what they learned about this pad
            string(b, "xdesk-gamepad", 64);
            b.putShort((short) DESCRIPTOR.length);
            b.putShort((short) BUS_USB);
            b.putInt(VENDOR);
            b.putInt(PRODUCT);
            b.putInt(1);
            b.putInt(0);
            for (int v : DESCRIPTOR) b.put((byte) v);
            f.write(b.array());
            out = f;
            Log.i(TAG, "gamepad made");
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "no gamepad", e);
            if (f != null) try {
                f.close();
            } catch (IOException ignored) { }
        }
    }

    private void write() {
        if (out == null) return;
        ByteBuffer b = report;
        b.clear();
        b.putInt(UHID_INPUT2);
        b.putShort((short) REPORT_SIZE);
        b.putShort((short) (buttons & 0x7fff));
        b.put((byte) (hat & 0x0f));
        b.putShort((short) clamp(lx, -32768, 32767));
        b.putShort((short) clamp(ly, -32768, 32767));
        b.putShort((short) clamp(rx, -32768, 32767));
        b.putShort((short) clamp(ry, -32768, 32767));
        b.put((byte) clamp(lt, 0, 255));
        b.put((byte) clamp(rt, 0, 255));
        try {
            out.write(b.array(), 0, b.position());
        } catch (IOException e) {
            // The device is gone: made again with the next state
            Log.w(TAG, "gamepad report", e);
            closeFile();
        }
    }

    private void closeFile() {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) { }
        out = null;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
