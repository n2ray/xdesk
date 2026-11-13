package com.termux.x11;

import com.termux.x11.IXWindowListener;

// This interface is used by utility on termux side.
interface ICmdEntryInterface {
    ParcelFileDescriptor getXConnection();
    ParcelFileDescriptor getLogcatOutput();
    void reportFatalError(String message);
    // A connection to the desktop sound (PCM), or null. Last, so an older X server just answers null.
    ParcelFileDescriptor getAudioConnection();

    // XDesk: programs as apps and windows as contents (DISPLAY.md §1.5), see XWindows. Added at the end, so an
    // older X server answers null, 0 or false.
    /** The app catalog made by xdesk apps (rebuilt first when programs changed), or null. Can take seconds. */
    ParcelFileDescriptor getAppCatalog();
    /** Runs xdesk launch for an app key "SCOPE/ID": the pid to follow as text, or "!" and why it failed. */
    String launchApp(String key, String startupId);
    /** Whether pid is ancestor or one of its descendants: the X server can read Termux's processes, the app cannot. */
    boolean isDescendant(int pid, int ancestor);
    /** Window snapshots and icons go to this listener from now on; null stops them. */
    void setWindowListener(IXWindowListener listener);
    /** 1 activate, 2 fullscreen, 3 not fullscreen, 4 close, see XWindows. */
    oneway void windowCommand(int window, int command);
}
