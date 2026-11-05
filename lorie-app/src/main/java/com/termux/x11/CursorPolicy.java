package com.termux.x11;

/**
 * Which cursor XDesk draws. In an app that draws a remote machine's cursor, two full cursors would overlap, so ours is
 * faint over the remote picture. It never disappears: the session signal stays on in the app's own screens, such as
 * RustDesk's settings, which have no remote cursor, and nothing says which screen is showing. Over the app's toolbar,
 * which has no remote cursor, ours is the full one.
 *
 * A menu opened from the toolbar covers the picture and has no remote cursor either, and we cannot see the menu. So
 * after a click on the toolbar ours stays for MENU_MS, enough to pick something. The next click over the picture
 * usually closes the menu, so that shortens what is left to AFTER_MENU_MS, which still covers a submenu opening.
 */
final class CursorPolicy {
    static final long MENU_MS = 5000, AFTER_MENU_MS = 1500;

    private AppInputProfile profile = AppInputProfile.forPackage(null);
    private boolean alwaysShow, inSession;
    private int height, y;
    private long showUntil;

    void setProfile(AppInputProfile p) {
        profile = p;
        inSession = false;
        showUntil = 0;
    }

    void setAlwaysShow(boolean always) {
        alwaysShow = always;
    }

    void setInSession(boolean in) {
        inSession = in;
    }

    void setHeight(int h) {
        height = h;
    }

    void moved(int newY) {
        y = newY;
    }

    private boolean overToolbar() {
        return y >= height * (1f - profile.toolbarFraction);
    }

    void buttonUp(long now) {
        if (!hidingApplies()) return;
        if (overToolbar()) showUntil = now + MENU_MS;
        else if (showUntil > now) showUntil = Math.min(showUntil, now + AFTER_MENU_MS);
    }

    private boolean hidingApplies() {
        return !alwaysShow && inSession && profile.keepScreenOnMeansRemoteCursor;
    }

    /** The normal cursor, otherwise the faint one. */
    boolean fullCursor(long now) {
        return !hidingApplies() || overToolbar() || now < showUntil;
    }

    /** Milliseconds until fullCursor() can change with nothing else happening, or -1. */
    long nextChange(long now) {
        return hidingApplies() && !overToolbar() && now < showUntil ? showUntil - now : -1;
    }
}
