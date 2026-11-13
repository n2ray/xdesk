package com.termux.x11;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * The window exchange's registry (DISPLAY.md §0, §1.5): every ContentSource other than Android apps
 * and Termux sessions (those keep their own paths in XDeskActivity) registers here, and the
 * chooser lists them. Phase 1: one content full screen on the target display.
 *
 * UI thread only, except register(), which may come from any thread.
 */
final class Contents {

    /** What a source may ask of XDeskActivity. Implemented there; all calls on the UI thread. */
    interface Host {
        /** The display contents go to now (TargetDisplay), or Display.INVALID_DISPLAY. */
        int targetDisplayId();

        /**
         * Put the X desktop on the target display, starting the default (or the "apps only")
         * session if none runs, then run {@code then} once X is connected. Runs {@code failed} with
         * a reason if it can't.
         */
        void needDesktop(Runnable then, ContentSource.Result failed, String key);

        /**
         * Free the target display for a content that draws itself (a Mac stream): the Android app
         * goes behind, the desktop is left running. The source then starts its own activity there.
         */
        void freeDisplay();

        /** Something about this content changed that the phone UI shows (title, running state). */
        void contentChanged();
    }

    private static final ArrayList<ContentSource> sources = new ArrayList<>();
    private static Host host;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Contents() { }

    static void register(final ContentSource s) {
        ui.post(() -> {
            for (ContentSource o : sources) if (o.prefix().equals(s.prefix())) return;
            sources.add(s);
            s.setListener(() -> { if (host != null) host.contentChanged(); });
            if (host != null) host.contentChanged();
        });
    }

    static void setHost(Host h) {
        host = h;
    }

    static Host host() {
        return host;
    }

    static List<ContentSource> sources() {
        return sources;
    }

    /** The source a key belongs to, or null (Android "a:" and session "t:" keys are not here). */
    static ContentSource sourceOf(String key) {
        if (key == null) return null;
        for (ContentSource s : sources) if (key.startsWith(s.prefix())) return s;
        return null;
    }

    static ContentSource.Entry find(String key) {
        ContentSource s = sourceOf(key);
        if (s == null) return null;
        for (ContentSource.Entry e : s.windows()) if (e.key.equals(key)) return e;
        for (ContentSource.Entry e : s.apps()) if (e.key.equals(key)) return e;
        return null;
    }
}
