package com.termux.x11;

import android.os.DeadObjectException;
import android.util.Log;

/**
 * Whether the X server runs this app's build. The server is started through the loader from the APK installed then;
 * an update replaces the APK but a running server keeps the old code (its maps show "base.apk (deleted)"), and the
 * two don't work together: the desktop stays black. The server says its build (serverBuild, CmdEntryPoint.buildId);
 * one too old to have the call answers null, which counts as another build.
 */
final class XServerBuild {
    private static final String TAG = "XDeskXBuild";

    private XServerBuild() { }

    /** Binder call: run off the main thread. False when the server is gone (nothing to restart) or is this build. */
    static boolean differs(ICmdEntryInterface s) {
        String mine = CmdEntryPoint.buildId(), theirs;
        try {
            theirs = s.serverBuild();
        } catch (DeadObjectException e) {
            return false;
        } catch (Exception e) {
            theirs = null;
        }
        if (mine.equals(theirs)) return false;
        Log.i(TAG, "X server build " + theirs + ", app " + mine);
        return true;
    }
}
