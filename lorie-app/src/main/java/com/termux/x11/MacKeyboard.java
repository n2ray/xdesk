// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import io.github.n2ray.xdesk.input.CapturedKeys;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;

/** Shell-side exclusive access, limited to external keyboard event nodes. Closing the fd returns it to Android. */
final class MacKeyboard extends Thread {
    private final IMacKeyboard target;
    private final HashMap<String, Integer> devices = new HashMap<>();
    private final HashMap<Integer, String> names = new HashMap<>();
    private final HashSet<Integer> dropped = new HashSet<>();
    private final CapturedKeys held = new CapturedKeys();
    private volatile boolean active = true;

    MacKeyboard(IMacKeyboard target) {
        super("xdesk-mac-keyboard");
        this.target = target;
    }

    void finish() {
        active = false;
        interrupt();
        try { join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void scan() {
        String[] nodes = new File("/dev/input").list();
        if (nodes == null) return;
        for (String node : nodes) {
            if (!node.startsWith("event") || devices.containsKey(node) || devices.size() >= 32) continue;
            int fd = Evdev.open("/dev/input/" + node);
            if (fd < 0) continue;
            String name = Evdev.name(fd);
            // Never take a mouse or a touchscreen away from its existing pointer path.
            if (Evdev.capabilities(fd) == Evdev.CAP_KEYBOARD && !UhidKeyboard.NAME.equals(name)
                    && Evdev.grabIdleKeyboard(fd)) {
                devices.put(node, fd);
                names.put(fd, name);
            } else Evdev.close(fd);
        }
    }

    @Override public void run() {
        int[] events = new int[256 * Evdev.STRIDE];
        long nextScan = 0;
        int watch = -1;
        try {
            watch = Evdev.watchDevices();
            while (active) {
                long now = SystemClock.uptimeMillis();
                if (now >= nextScan) {
                    scan();
                    nextScan = now + (devices.isEmpty() ? 250 : 2000);
                }
                Integer[] open = devices.values().toArray(new Integer[0]);
                int[] fds = new int[open.length];
                for (int i = 0; i < open.length; i++) fds[i] = open[i];
                int count = Evdev.poll(fds, fds.length, events, 100, watch);
                if (count < 0) break;
                for (int i = 0; active && i < count; i++) {
                    int offset = i * Evdev.STRIDE;
                    if (events[offset + 1] == Evdev.DEVICES_CHANGED) { nextScan = 0; continue; }
                    int fd = fds[events[offset]];
                    if (!names.containsKey(fd)) continue;
                    int type = events[offset + 1], code = events[offset + 2];
                    if (type == Evdev.GONE) {
                        devices.values().remove(fd);
                        Evdev.close(fd);
                        for (int scan : held.heldBy(fd)) sendKey(fd, scan, 0);
                        names.remove(fd);
                        dropped.remove(fd);
                        nextScan = 0;
                        continue;
                    }
                    if (type == Evdev.EV_SYN && code == 3) dropped.add(fd); // SYN_DROPPED
                    if (dropped.contains(fd)) {
                        if (type == Evdev.EV_SYN && code == 0) { // first complete SYN_REPORT
                            int[] down = Evdev.pressedKeys(fd);
                            // An unreadable node cannot safely keep keys held. Its next report or removal recovers it.
                            for (int scan : held.heldBy(fd)) {
                                if (down == null || java.util.Arrays.binarySearch(down, scan) < 0) sendKey(fd, scan, 0);
                            }
                            if (down != null) for (int scan : down) sendKey(fd, scan, 1);
                            dropped.remove(fd);
                        }
                        continue;
                    }
                    if (type == Evdev.EV_KEY) sendKey(fd, code, events[offset + 3]);
                }
            }
        } catch (RemoteException | LinkageError e) {
            Log.w("XDeskInjector", "Mac keyboard capture ended", e);
        } finally {
            Evdev.close(watch);
            for (int fd : devices.values()) Evdev.close(fd);
            try { target.reset(); } catch (RemoteException ignored) { }
        }
    }

    private void sendKey(int fd, int scan, int value) throws RemoteException {
        String device = held.update(fd, names.get(fd), scan, value);
        if (device != null) target.key(device, scan, value);
    }
}
