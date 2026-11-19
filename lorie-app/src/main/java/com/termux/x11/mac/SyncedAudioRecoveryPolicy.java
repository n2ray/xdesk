/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioRecoveryPolicy.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name
 * (XDesk builds as Java 9). SALL-SYNC.md says which Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

/** Bounds automatic recovery from a broken Android presentation clock. */
final class SyncedAudioRecoveryPolicy {
    private final java.util.ArrayDeque<Long> attempts = new java.util.ArrayDeque<>();
    boolean retry(Throwable error, long now) {
        // An underrun is a gap heard, not a broken clock: the session starts again the same way (XDesk; Sall ends
        // it). On the fast output a busy moment emptied its 20 ms and the sound went back to the unsynced playout
        // for the rest of the connection (2026-11-19)
        String m = error instanceof IllegalStateException ? error.getMessage() : null;
        if (m == null || !(m.startsWith("presentation timestamp unavailable or discontinuous") || m.startsWith("audio underrun during")))
            return false;
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= 30_000_000_000L) attempts.removeFirst();
        if (attempts.size() >= 2) return false;
        attempts.addLast(now);
        return true;
    }
}
