/*
 * XDesk's glue between the Mac session's sound and AudioMute. The synced playout (Sall's, SyncedAudio)
 * and the fallback MacAudio both honour the same local mute.
 */
package com.termux.x11.mac;

/** What AudioMute holds for a session: the synced playout, or MacAudio after it failed. */
interface SoundPlayer {
    void setMuted(boolean muted);
}
