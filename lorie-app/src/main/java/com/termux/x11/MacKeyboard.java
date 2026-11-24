// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.util.HashMap;

/** Shell-side exclusive access, limited to external keyboard event nodes. Closing the fd returns it to Android. */
final class MacKeyboard extends Thread {
    private final IMacKeyboard target;
    private final HashMap<String, Integer> devices = new HashMap<>();
    private final HashMap<Integer, String> names = new HashMap<>();
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
        try {
            while (active) {
                long now = SystemClock.uptimeMillis();
                if (now >= nextScan) {
                    scan();
                    nextScan = now + (devices.isEmpty() ? 250 : 2000);
                }
                Integer[] open = devices.values().toArray(new Integer[0]);
                int[] fds = new int[open.length];
                for (int i = 0; i < open.length; i++) fds[i] = open[i];
                int count = Evdev.poll(fds, fds.length, events, 100);
                if (count < 0) break;
                for (int i = 0; active && i < count; i++) {
                    int offset = i * Evdev.STRIDE, fd = fds[events[offset]];
                    int type = events[offset + 1], code = events[offset + 2];
                    if (type == Evdev.GONE || type == Evdev.EV_SYN && code == 3) {
                        devices.values().remove(fd);
                        names.remove(fd);
                        Evdev.close(fd);
                        target.reset();
                        break;
                    }
                    if (type == Evdev.EV_KEY) target.key(names.get(fd), code, events[offset + 3]);
                }
            }
        } catch (RemoteException | LinkageError e) {
            Log.w("XDeskInjector", "Mac keyboard capture ended", e);
        } finally {
            for (int fd : devices.values()) Evdev.close(fd);
        }
    }
}
