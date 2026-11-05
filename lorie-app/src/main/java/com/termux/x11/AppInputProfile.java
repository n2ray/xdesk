package com.termux.x11;

/**
 * How to deliver a mouse to one Android app. Most apps take normal DOWN/MOVE/UP, RustDesk needs its own method.
 * Add a line to forPackage for another app that misbehaves.
 */
final class AppInputProfile {
    /** Toolbar height of RustDesk's remote view: 68 pixels measured on a 1508 high display. */
    private static final float RUSTDESK_TOOLBAR_FRACTION = 68f / 1508f;

    private static final AppInputProfile NORMAL = new AppInputProfile(false, 0f, false);
    private static final AppInputProfile RUSTDESK = new AppInputProfile(true, RUSTDESK_TOOLBAR_FRACTION, true);

    /** Send button changes as hovers with buttonState, see InputInjector.hoverButton. */
    final boolean hoverButtons;
    /** With hoverButtons: the bottom part of the display that belongs to the app's own UI and needs real presses. */
    final float toolbarFraction;
    /** The app caches modifiers and only a full press and release clears them, see InputInjector.clearModifiers. */
    final boolean clearModifiers;

    private AppInputProfile(boolean hoverButtons, float toolbarFraction, boolean clearModifiers) {
        this.hoverButtons = hoverButtons;
        this.toolbarFraction = toolbarFraction;
        this.clearModifiers = clearModifiers;
    }

    static AppInputProfile forPackage(String pkg) {
        if ("com.carriez.flutter_hbb".equals(pkg)) return RUSTDESK;
        return NORMAL;
    }
}
