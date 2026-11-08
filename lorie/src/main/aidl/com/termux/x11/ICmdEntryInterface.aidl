package com.termux.x11;

// This interface is used by utility on termux side.
interface ICmdEntryInterface {
    ParcelFileDescriptor getXConnection();
    ParcelFileDescriptor getLogcatOutput();
    void reportFatalError(String message);
    // A connection to the desktop sound (PCM), or null. Last, so an older X server just answers null.
    ParcelFileDescriptor getAudioConnection();
}