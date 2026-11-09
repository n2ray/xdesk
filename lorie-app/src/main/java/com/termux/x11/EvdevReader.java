package com.termux.x11;

import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Reads physical mice and keyboards from /dev/input, found by capability, with hotplug. Devices are opened all the
 * time so the hotkey works, but events are only passed on while grabbed. Nothing is grabbed until asked for.
 */
final class EvdevReader extends Thread {
    interface Sink {
        void onForwarding(boolean on, int deviceCount);
        /** A device came or went while grabbed. */
        void onDeviceCount(int deviceCount, boolean removed);
        /** Physical Ctrl and Alt are up after a grab, see InputInjector.clearModifiers. */
        void onModifiersUp();
        void onMove(int dx, int dy);
        void onWheel(int vertical, int horizontal);
        void onButton(int code, boolean down);
        void onKey(int code, int value);
        /** A key went down on a keyboard, which Android delivers by itself to the display with the focus. */
        void onKeyboardUsed();
    }

    private static final String TAG = "XDeskInjector";
    private static final String DIR = "/dev/input";
    private static final long RESCAN_MS = 2000;
    private static final int POLL_MS = 200, MAX_DEVICES = 32, BUFFER_EVENTS = 256;
    private static final int REL_X = 0, REL_Y = 1, REL_HWHEEL = 6, REL_WHEEL = 8, SYN_REPORT = 0, SYN_DROPPED = 3;
    private static final int KEY_LEFTCTRL = 29, KEY_RIGHTCTRL = 97, KEY_LEFTALT = 56, KEY_RIGHTALT = 100, KEY_M = 50;
    private static final int FIRST_BUTTON = 256;

    private final Sink sink;
    private final HashMap<String, Integer> devices = new HashMap<>();
    private final HashSet<String> skipped = new HashSet<>();
    /**
     * Keyboards and nothing else. They are never grabbed: Android knows each keyboard's layout and gives its keys to the
     * display with the focus, input method included. A key sent on by hand could only guess at the layout.
     */
    private final HashSet<Integer> keyboards = new HashSet<>();
    /** Devices whose event buffer overflowed: their events are dropped up to the next SYN_REPORT. */
    private final HashSet<Integer> dropping = new HashSet<>();
    // Written under the lock (setGrabbed), read without it on this thread for every event.
    private volatile boolean grabbed;
    private volatile boolean running = true;
    /** False while a Termux desktop is on the display: it reads the same mouse and keyboard through Android. */
    private volatile boolean hotkeyEnabled = true;
    private long lastScan;
    private boolean leftCtrl, rightCtrl, leftAlt, rightAlt, swallowM, waitingForModifiers;
    private int dx, dy, wheel, hwheel;

    EvdevReader(Sink sink) {
        super("xdesk-evdev");
        this.sink = sink;
        setDaemon(true);
    }

    void setHotkeyEnabled(boolean on) {
        hotkeyEnabled = on;
    }

    boolean isGrabbed() {
        return grabbed;
    }

    void shutdown() {
        running = false;
    }

    /** Any thread: a phone button, or this thread for the hotkey. */
    synchronized void setGrabbed(boolean on) {
        // After shutdown the devices are closed, and nothing would ungrab them again
        if (!running || grabbed == on) return;
        grabbed = on;
        for (int fd : devices.values()) applyGrab(fd, on);
        sink.onForwarding(on, devices.size());
        if (on) {
            // The hotkey's own Ctrl and Alt must not reach the app, and modifiers are only cleared once both are up.
            waitingForModifiers = leftCtrl || rightCtrl || leftAlt || rightAlt;
            if (!waitingForModifiers) sink.onModifiersUp();
        } else {
            waitingForModifiers = false;
        }
    }

    private void applyGrab(int fd, boolean on) {
        if (keyboards.contains(fd)) return;
        int r = Evdev.grab(fd, on);
        if (r < 0) Log.w(TAG, "grab " + on + " failed: " + r);
    }

    private synchronized void scan() {
        String[] names = new File(DIR).list();
        if (names == null) {
            Log.w(TAG, "cannot list " + DIR);
            return;
        }
        HashSet<String> present = new HashSet<>(Arrays.asList(names));
        skipped.retainAll(present);
        for (String n : names) {
            if (!n.startsWith("event") || devices.containsKey(n) || skipped.contains(n) || devices.size() >= MAX_DEVICES) continue;
            int fd = Evdev.open(DIR + "/" + n);
            if (fd < 0) {
                skipped.add(n);
                continue;
            }
            // The phone's own touch screen, buttons and sensors have no capability that matches and stay untouched
            int capabilities = Evdev.capabilities(fd);
            if (capabilities == 0) {
                Evdev.close(fd);
                skipped.add(n);
                continue;
            }
            Log.i(TAG, "using " + n + ": " + Evdev.name(fd));
            devices.put(n, fd);
            if (capabilities == Evdev.CAP_KEYBOARD) keyboards.add(fd);
            if (grabbed) {
                applyGrab(fd, true);
                sink.onDeviceCount(devices.size(), false);
            }
        }
    }

    private synchronized void removeDevice(int fd) {
        String found = null;
        for (java.util.Map.Entry<String, Integer> e : devices.entrySet()) if (e.getValue() == fd) found = e.getKey();
        if (found != null) devices.remove(found);
        Evdev.close(fd);
        keyboards.remove(fd);
        dropping.remove(fd);
        Log.i(TAG, "device gone: " + found);
        // Its keys will never report going up
        leftCtrl = rightCtrl = leftAlt = rightAlt = swallowM = false;
        if (waitingForModifiers) {
            waitingForModifiers = false;
            sink.onModifiersUp();
        }
        if (grabbed) sink.onDeviceCount(devices.size(), true);
    }

    private synchronized int[] currentFds() {
        int[] fds = new int[devices.size()];
        int i = 0;
        for (int fd : devices.values()) fds[i++] = fd;
        return fds;
    }

    @Override public void run() {
        try {
            loop();
        } finally {
            closeAll();
        }
    }

    /** Ends the grab, so the devices go back to Android, and closes them. */
    private synchronized void closeAll() {
        running = false;
        for (int fd : devices.values()) {
            if (grabbed) applyGrab(fd, false);
            Evdev.close(fd);
        }
        devices.clear();
        keyboards.clear();
        dropping.clear();
        grabbed = false;
    }

    private void loop() {
        int[] out = new int[BUFFER_EVENTS * Evdev.STRIDE];
        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastScan >= RESCAN_MS) {
                lastScan = now;
                scan();
            }
            int[] fds = currentFds();
            if (fds.length == 0) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            int count = Evdev.poll(fds, fds.length, out, POLL_MS);
            ArrayList<Integer> gone = null;
            for (int i = 0; i < count; i++) {
                int at = i * Evdev.STRIDE, type = out[at + 1], code = out[at + 2], value = out[at + 3];
                if (type == Evdev.GONE) {
                    if (gone == null) gone = new ArrayList<>();
                    gone.add(fds[out[at]]);
                } else {
                    handle(fds[out[at]], type, code, value);
                }
            }
            if (gone != null) for (int fd : gone) removeDevice(fd);
        }
    }

    private boolean ctrl() {
        return leftCtrl || rightCtrl;
    }

    private boolean alt() {
        return leftAlt || rightAlt;
    }

    private void handle(int fd, int type, int code, int value) {
        if (type == Evdev.EV_SYN && code == SYN_DROPPED) {
            dropped(fd);
            return;
        }
        if (!dropping.isEmpty() && dropping.contains(fd)) {
            if (type == Evdev.EV_SYN && code == SYN_REPORT) dropping.remove(fd);
            return;
        }
        if (type == Evdev.EV_KEY) {
            handleKey(fd, code, value);
        } else if (!grabbed) {
            return;
        } else if (type == Evdev.EV_REL) {
            if (code == REL_X) dx += value;
            else if (code == REL_Y) dy += value;
            else if (code == REL_WHEEL) wheel += value;
            else if (code == REL_HWHEEL) hwheel += value;
        } else if (type == Evdev.EV_SYN && code == SYN_REPORT) {
            if (dx != 0 || dy != 0) sink.onMove(dx, dy);
            if (wheel != 0 || hwheel != 0) sink.onWheel(wheel, hwheel);
            dx = dy = wheel = hwheel = 0;
        }
    }

    /**
     * The kernel's buffer for a device overflowed and events were lost, among them maybe the release of a key or
     * button: release everything held, forget the modifiers and drop the rest of the broken report.
     */
    private synchronized void dropped(int fd) {
        Log.w(TAG, "events lost on a device, releasing all keys and buttons");
        dropping.add(fd);
        dx = dy = wheel = hwheel = 0;
        leftCtrl = rightCtrl = leftAlt = rightAlt = swallowM = false;
        if (waitingForModifiers) {
            waitingForModifiers = false;
            sink.onModifiersUp();
        }
        // Like a device that went away, which releases everything held
        if (grabbed) sink.onDeviceCount(devices.size(), true);
    }

    private void handleKey(int fd, int code, int value) {
        boolean down = value != 0;
        boolean modifier = true;
        switch (code) {
            case KEY_LEFTCTRL: leftCtrl = down; break;
            case KEY_RIGHTCTRL: rightCtrl = down; break;
            case KEY_LEFTALT: leftAlt = down; break;
            case KEY_RIGHTALT: rightAlt = down; break;
            default: modifier = false; break;
        }
        // Ctrl+Alt+M switches. While taking input nothing of it reaches the app. While giving input back, Ctrl and
        // Alt already went out before M showed what they were for: releaseAll lifts them, and the next take clears
        // what the app cached.
        if (hotkeyEnabled && code == KEY_M && value == 1 && ctrl() && alt()) {
            swallowM = true;
            setGrabbed(!grabbed);
            return;
        }
        if (code == KEY_M && swallowM) {
            if (!down) swallowM = false;
            return;
        }
        if (modifier && waitingForModifiers) {
            if (!ctrl() && !alt()) {
                waitingForModifiers = false;
                sink.onModifiersUp();
            }
            return;
        }
        if (!grabbed) return;
        if (keyboards.contains(fd)) {
            if (value == 1) sink.onKeyboardUsed();
            return;
        }
        if (code >= FIRST_BUTTON) {
            if (value != 2) sink.onButton(code, down);
        } else {
            sink.onKey(code, value);
        }
    }
}
