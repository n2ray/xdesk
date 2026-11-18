/*
 * Derived from Sall (https://github.com/n2ray/sall, HomeDiscoveryMetadata.java) and Sill
 * (https://github.com/Saffsanity/sill, Sources/StreamProtocol/Pairing.swift: HomeDoorTXT, RecognitionTag).
 * Copyright 2026 Noah Saffer. Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import java.util.Map;

/**
 * A Sill Mac's Bonjour TXT record: how its home door speaks ("p") and the recognition tag ("r"). Untrusted:
 * neither a matching tag nor an advertised door verifies a Mac, so a saved one is always dialled with its pin.
 */
final class HomeDiscoveryMetadata {
    enum Door { UNKNOWN, LEGACY_PLAIN, TLS_OPEN, TLS_PAIRING_REQUIRED }

    private final Door door;
    private final String recognitionHint;

    private HomeDiscoveryMetadata(Door door, String recognitionHint) {
        this.door = door;
        this.recognitionHint = recognitionHint;
    }

    Door door() { return door; }
    String recognitionHint() { return recognitionHint; }

    /** Null metadata means unavailable; an observed record without "r" has no hint. */
    static HomeDiscoveryMetadata parse(Map<String, byte[]> txt) {
        if (txt == null || txt.size() > 64) return new HomeDiscoveryMetadata(Door.UNKNOWN, null);
        boolean hasDoor = false, hasHint = false;
        byte[] doorValue = null, hintValue = null;
        for (Map.Entry<String, byte[]> entry : txt.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty() || key.length() > 255) return new HomeDiscoveryMetadata(Door.UNKNOWN, null);
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (c < 0x20 || c > 0x7e || c == '=') return new HomeDiscoveryMetadata(Door.UNKNOWN, null);
            }
            if (key.equals("p") || key.equals("P")) {
                if (hasDoor) return new HomeDiscoveryMetadata(Door.UNKNOWN, null);
                hasDoor = true;
                doorValue = entry.getValue();
            } else if (key.equals("r") || key.equals("R")) {
                if (hasHint) return new HomeDiscoveryMetadata(Door.UNKNOWN, null);
                hasHint = true;
                hintValue = entry.getValue();
            }
        }
        // No "p": a plain door (an old Sill, SillHost without --pairing). "0": TLS, any device; anything else:
        // TLS, paired devices only. Sill's HomeDoorTXT reads any value but "0" as the safe side.
        Door door = !hasDoor ? Door.LEGACY_PLAIN
                : doorValue != null && doorValue.length == 1 && doorValue[0] == '0'
                    ? Door.TLS_OPEN : Door.TLS_PAIRING_REQUIRED;
        return new HomeDiscoveryMetadata(door, RecognitionTag.fromTxt(hintValue));
    }
}
