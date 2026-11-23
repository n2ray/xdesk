package com.termux.x11;

/** For the checks only: the app's AudioRoute needs its resources (R). */
public final class AudioRoute {
    public static AudioRoute forScreenSharing(android.content.Context c) { return new AudioRoute(); }
    public android.media.AudioDeviceInfo screenSharingDevice() { return null; }
}
