package com.termux.x11;

/**
 * What differs per Android app: how to deliver a mouse to it, and when its own picture already has a cursor.
 * Most apps take normal DOWN/MOVE/UP, RustDesk needs its own method. Add a line to forPackage for another app that
 * misbehaves.
 */
final class AppInputProfile {
    /**
     * RustDesk's bottom toolbar: 68 pixels measured on a 1508 high display. This only decides where the cursor is drawn
     * (the toolbar has no remote cursor). Clicks do not depend on it, see InputInjector.hoverMethodButton.
     */
    private static final float RUSTDESK_TOOLBAR_FRACTION = 68f / 1508f;

    private static final AppInputProfile NORMAL = new AppInputProfile(false, false, false, 0f);
    private static final AppInputProfile RUSTDESK = new AppInputProfile(true, true, true, RUSTDESK_TOOLBAR_FRACTION);

    /** Send button changes as hovers with buttonState, see InputInjector.hoverButton. */
    final boolean hoverButtons;
    /** The app caches modifiers and only a full press and release clears them, see InputInjector.clearModifiers. */
    final boolean clearModifiers;
    /**
     * The app's window has KEEP_SCREEN_ON while it shows a remote machine, whose picture contains that machine's cursor.
     * RustDesk sets it during a session and not on its home page. This comes from a limited observation of one version
     * in `dumpsys window windows`. If it is wrong, the worst case is a cursor too many or too few, and there is a setting
     * to always show ours.
     */
    final boolean keepScreenOnMeansRemoteCursor;
    /** With keepScreenOnMeansRemoteCursor: the bottom part of the display that is the app's own UI. */
    final float toolbarFraction;

    private AppInputProfile(boolean hoverButtons, boolean clearModifiers, boolean keepScreenOnMeansRemoteCursor, float toolbarFraction) {
        this.hoverButtons = hoverButtons;
        this.clearModifiers = clearModifiers;
        this.keepScreenOnMeansRemoteCursor = keepScreenOnMeansRemoteCursor;
        this.toolbarFraction = toolbarFraction;
    }

    static AppInputProfile forPackage(String pkg) {
        if ("com.carriez.flutter_hbb".equals(pkg)) return RUSTDESK;
        return NORMAL;
    }
}
