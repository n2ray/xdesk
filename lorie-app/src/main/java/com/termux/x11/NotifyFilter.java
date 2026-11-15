package com.termux.x11;

/**
 * NOTIFY.md §2: which notifications the external screen shows at all. The service takes the values from a
 * StatusBarNotification and its channel, and hands them here; pure arithmetic so the rule is testable without a
 * device. Nothing of the notification's content is kept or logged.
 */
final class NotifyFilter {
    private NotifyFilter() { }

    /**
     * @param ongoing the notification is ongoing (music, downloads, XDesk's own service)
     * @param silent its channel's importance is MIN or LOW
     * @param groupSummary it is a group's summary itself: only the group's messages show
     * @param ownPackage it is XDesk's own
     */
    static boolean shows(boolean ongoing, boolean silent, boolean groupSummary, boolean ownPackage) {
        return !ongoing && !silent && !groupSummary && !ownPackage;
    }

    /**
     * Whether a notification whose app has {@code appMode} may raise a banner, given the global content choice
     * {@code content} (NotifyCenter.CONTENT_ALL, NAME or NONE). NOTIFY.md §6: an app set to the centre only or to
     * nothing gets no banners, whatever the global choice.
     */
    static boolean banners(int appMode, int content) {
        return appMode == NotifyCenter.APP_NORMAL && content != NotifyCenter.CONTENT_NONE;
    }

    /**
     * Whether a posted notification is a new one, not an update of a key already in the centre (NOTIFY.md §2): only
     * new ones count unread and raise a banner. Updates — FLAG_ONLY_ALERT_ONCE re-posts included — just replace.
     */
    static boolean fresh(boolean existed) {
        return !existed;
    }
}
