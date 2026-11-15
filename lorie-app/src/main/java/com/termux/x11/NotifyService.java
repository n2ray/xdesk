package com.termux.x11;

import android.app.Notification;
import android.app.NotificationManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * NOTIFY.md §2: where the phone's notifications come from. The system binds this when the user grants the notification
 * access; everything else works without it. It hands the notifications to NotifyCenter after the filter, and keeps it
 * posted about the interruption filter, the battery and the keyguard. Only package names and events are logged, never
 * content.
 */
public class NotifyService extends NotificationListenerService {
    private static final String TAG = "XDeskNotify";
    /** The battery and unlock receivers are up (they go with the connection, once). */
    private boolean receiversUp;

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        NotifyCenter center = NotifyCenter.get(this);
        center.interruptionFilter(getCurrentInterruptionFilter());
        boolean silent = importance(sbn) <= NotificationManager.IMPORTANCE_LOW;
        boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        Log.i(TAG, "posted " + sbn.getPackageName() + " silent=" + silent + " ongoing=" + sbn.isOngoing()
            + " summary=" + summary + " matches=" + matchesInterruption(sbn));
        center.posted(sbn, silent, sbn.isOngoing(), summary, matchesInterruption(sbn), false);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        NotifyCenter.get(this).removed(sbn.getKey());
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        NotifyCenter.get(this).removed(sbn.getKey());
    }

    @Override public void onListenerConnected() {
        NotifyCenter center = NotifyCenter.get(this);
        center.attach(this);
        center.interruptionFilter(getCurrentInterruptionFilter());
        if (!receiversUp) {
            receiversUp = true;
            center.registerReceivers(this);
        }
        // What is already up enters the centre silently: no banners for things that arrived before the access
        StatusBarNotification[] all = getActiveNotifications();
        Log.i(TAG, "connected, seeding " + (all != null ? all.length : 0) + " notifications");
        if (all == null) return;
        for (StatusBarNotification sbn : all) {
            Notification n = sbn.getNotification();
            center.posted(sbn, importance(sbn) <= NotificationManager.IMPORTANCE_LOW, sbn.isOngoing(),
                (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0, matchesInterruption(sbn), true);
        }
    }

    /**
     * The channel's importance from the listener's ranking — the NotificationManager's getNotificationChannel only
     * knows XDesk's own channels. DEFAULT where the ranking cannot say: then it is not silent.
     */
    private int importance(StatusBarNotification sbn) {
        RankingMap map = getCurrentRanking();
        if (map == null) return NotificationManager.IMPORTANCE_DEFAULT;
        Ranking ranking = new Ranking();
        if (!map.getRanking(sbn.getKey(), ranking)) return NotificationManager.IMPORTANCE_DEFAULT;
        return ranking.getImportance();
    }

    /** Whether it matches the interruption filter now, NOTIFY.md §2's ranking check. */
    private boolean matchesInterruption(StatusBarNotification sbn) {
        RankingMap map = getCurrentRanking();
        if (map == null) return true;  // nothing is ranked: treat it as matching
        Ranking ranking = new Ranking();
        map.getRanking(sbn.getKey(), ranking);
        return ranking.matchesInterruptionFilter();
    }

    @Override public void onListenerDisconnected() {
        NotifyCenter center = NotifyCenter.get(this);
        if (receiversUp) {
            receiversUp = false;
            center.unregisterReceivers(this);
        }
        center.detach(this);
    }

    @Override public void onDestroy() {
        NotifyCenter center = NotifyCenter.get(this);
        if (receiversUp) {
            receiversUp = false;
            center.unregisterReceivers(this);
        }
        center.detach(this);
        super.onDestroy();
    }

    @Override public void onInterruptionFilterChanged(int filter) {
        NotifyCenter.get(this).interruptionFilter(filter);
    }
}
