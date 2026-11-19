/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: AudioPcmWriter.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk: the package and the class name
 * (XDesk builds as Java 9). SALL-SYNC.md says which Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

final class SyncedAudioPcmWriter {
    private SyncedAudioPcmWriter() { }

    static boolean writeFully(ByteBuffer pcm, ToIntFunction<ByteBuffer> write, BooleanSupplier cancelled)
            throws InterruptedException {
        long lastProgress = System.nanoTime();
        while (pcm.hasRemaining()) {
            if (cancelled.getAsBoolean()) return false;
            int before = pcm.position();
            int written = write.applyAsInt(pcm);
            if (written < 0 || pcm.position() - before != written)
                throw new IllegalStateException("Audio output write failed: " + written);
            if (written > 0) lastProgress = System.nanoTime();
            else {
                // shortcut: a stalled track fails after 500 ms; use playback callbacks when implementing timed audio.
                if (System.nanoTime() - lastProgress >= 500_000_000L)
                    throw new IllegalStateException("Audio output stalled");
                Thread.sleep(2);
            }
        }
        return !cancelled.getAsBoolean();
    }
}
