// SPDX-License-Identifier: GPL-3.0-only
package io.github.n2ray.xdesk.input;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Several physical keyboards feed one logical X (or remote) keyboard. */
public final class CapturedKeys {
    private final HashMap<Integer, HashSet<Integer>> devices = new HashMap<>();
    private final HashMap<Integer, String> origins = new HashMap<>();

    public List<Integer> heldBy(int fd) {
        HashSet<Integer> keys = devices.get(fd);
        return keys == null ? new ArrayList<>() : new ArrayList<>(keys);
    }

    /** The Android layout of the first press owns the matching release. */
    public String update(int fd, String name, int scan, int value) {
        HashSet<Integer> keys = devices.get(fd);
        if (value == 1) {
            if (keys == null) {
                keys = new HashSet<>();
                devices.put(fd, keys);
            }
            if (!keys.add(scan) || origins.putIfAbsent(scan, name) != null) return null;
            return name;
        }
        if (keys == null || !keys.contains(scan)) return null;
        if (value == 2) return origins.get(scan);
        if (value != 0) return null;
        keys.remove(scan);
        if (keys.isEmpty()) devices.remove(fd);
        for (HashSet<Integer> other : devices.values()) if (other.contains(scan)) return null;
        return origins.remove(scan);
    }
}
