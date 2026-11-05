package com.termux.x11;

/**
 * How to deliver a mouse to one Android app. Most apps take normal DOWN/MOVE/UP, RustDesk needs its own method.
 * Add a line to forPackage for another app that misbehaves.
 */
final class AppInputProfile {
    private static final AppInputProfile NORMAL = new AppInputProfile(false, false);
    private static final AppInputProfile RUSTDESK = new AppInputProfile(true, true);

    /** Send button changes as hovers with buttonState, see InputInjector.hoverButton. */
    final boolean hoverButtons;
    /** The app caches modifiers and only a full press and release clears them, see InputInjector.clearModifiers. */
    final boolean clearModifiers;

    private AppInputProfile(boolean hoverButtons, boolean clearModifiers) {
        this.hoverButtons = hoverButtons;
        this.clearModifiers = clearModifiers;
    }

    static AppInputProfile forPackage(String pkg) {
        if ("com.carriez.flutter_hbb".equals(pkg)) return RUSTDESK;
        return NORMAL;
    }
}
