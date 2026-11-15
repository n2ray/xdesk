/*
 * Derived from Sill (https://github.com/Saffsanity/sill) and its Android client Sall
 * (https://github.com/n2ray/sall: LocalAudioControl.java, AudioMutePolicy.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk. SALL-SYNC.md says which
 * Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;

import java.util.function.Consumer;

/**
 * This device's mute of the Mac's sound (Sall's LocalAudioControl and AudioMutePolicy in one): the "mute this
 * device" switch, and Android's ACTION_AUDIO_BECOMING_NOISY for when the wired or Bluetooth headphones the sound
 * goes to are pulled. Muted, the packets still come and MacAudio decodes them but plays nothing, so the sound
 * does not jump to the loudspeaker. Local to this device; the Mac's own settings are not touched.
 *
 * Owned by a MacClient, from its first session with sound to shutdown. The mute holds across sessions. Callbacks
 * run on the UI thread (the switch and the receiver both live there).
 */
final class AudioMute {
    private final Context context;
    /** The mute changed (the switch, or the headphones went away while the sound played). UI thread. */
    private final Consumer<Boolean> changed;
    /** The headphones went away and the sound was muted because of it. UI thread. */
    private final Runnable headphonesOut;

    private MacAudio player;
    private boolean closed;
    // AudioMutePolicy's state. audible() = hasPlayer && watched && !muted
    private boolean muted, watched, hasPlayer, receivedAudio;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
            boolean muted;
            synchronized (AudioMute.this) {
                if (closed || !headphonesRemoved()) return;
                apply();
                muted = AudioMute.this.muted;
            }
            changed.accept(muted);
            headphonesOut.run();
        }
    };

    AudioMute(Context context, boolean muted, Consumer<Boolean> changed, Runnable headphonesOut) {
        this.context = context.getApplicationContext();
        this.muted = muted;
        this.changed = changed;
        this.headphonesOut = headphonesOut;
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else this.context.registerReceiver(receiver, filter);
    }

    /** The player that plays (or, muted, holds) this Mac's sound; null between sessions. A new one starts unheard. */
    synchronized void setPlayer(MacAudio player) {
        if (closed) return;
        this.player = player;
        hasPlayer = player != null;
        receivedAudio = false;
        apply();
    }

    /** Whether a screen shows the Mac: in XDesk sound only flows then, and only then is a pulled plug worth a mute. */
    synchronized void setWatched(boolean watched) {
        if (closed) return;
        this.watched = watched;
        apply();
    }

    /** Every kind 43 message, as in Sall: only sound packets (type 2) count as "sound played to the headphones". */
    synchronized void received(byte[] packet) {
        if (!closed && packet != null && packet.length > 0 && packet[0] == 2 && hasPlayer) receivedAudio = true;
    }

    /** The switch. Nothing if it does not change; the listener hears it, the player is applied at once. */
    void setMuted(boolean muted) {
        synchronized (this) {
            if (closed || this.muted == muted) return;
            this.muted = muted;
            apply();
        }
        changed.accept(muted);
    }

    private void apply() {
        MacAudio p = player;
        if (p != null) p.setMuted(!hasPlayer || !watched || muted);
    }

    /**
     * The wired or Bluetooth headphones Android was sending the media sound to went away: mute, but only when the
     * Mac's sound was actually playing to them (Sall's AudioMutePolicy.headphonesRemoved). Nothing had played, the
     * next sound may as well go where the system sends it.
     */
    private boolean headphonesRemoved() {
        if (!hasPlayer || !watched || muted || !receivedAudio) return false;
        muted = true;
        return true;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        player = null;
        hasPlayer = false;
        context.unregisterReceiver(receiver);
    }
}
