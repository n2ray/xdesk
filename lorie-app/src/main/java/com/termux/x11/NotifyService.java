package com.termux.x11;

import android.app.Notification;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * NOTIFY.md §2: where the phone's notifications come from. The system binds this when the user grants the notification
 * access; everything else works without it. It hands the notifications to NotifyCenter, and keeps it posted about the
 * interruption filter, the battery and the keyguard. Only package names and events are logged, never content.
 *
 * Bound is not a given (NOTIFY.md §2.1): HyperOS refuses to bind it again after XDesk's process went (an update, the
 * system killing it, a reboot) unless XDesk may start itself. So it asks for a rebind when it is let go, NotifyCenter
 * asks again while XDesk is in front, and on every connection the centre is made the same as what the phone shows.
 */
public class NotifyService extends NotificationListenerService {
    private static final String TAG = "XDeskNotify";
    /** Ranking changes come in bursts (one per post): the centre is made over once they settle. */
    private static final long RESYNC_MS = 800;
    /** The battery and unlock receivers are up (they go with the connection, once). */
    private boolean receiversUp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable resync = this::resync;

    /** What the centre needs of one notification, the ranking read once. */
    static final class Posted {
        final StatusBarNotification sbn;
        final boolean silent, ongoing, summary, call, matchesDnd;

        Posted(StatusBarNotification sbn, boolean silent, boolean ongoing, boolean summary, boolean call, boolean matchesDnd) {
            this.sbn = sbn;
            this.silent = silent;
            this.ongoing = ongoing;
            this.summary = summary;
            this.call = call;
            this.matchesDnd = matchesDnd;
        }
    }

    private Posted describe(StatusBarNotification sbn, RankingMap map) {
        Notification n = sbn.getNotification();
        Ranking ranking = new Ranking();
        boolean ranked = map != null && map.getRanking(sbn.getKey(), ranking);
        // Unknown importance (no ranking, or UNSPECIFIED) is not silence: shown rather than lost
        int importance = ranked ? ranking.getImportance() : NotificationManager.IMPORTANCE_DEFAULT;
        boolean silent = importance != NotificationManager.IMPORTANCE_UNSPECIFIED && importance <= NotificationManager.IMPORTANCE_LOW;
        boolean matches = !ranked || ranking.matchesInterruptionFilter();
        boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        return new Posted(sbn, silent, sbn.isOngoing(), summary, call, matches);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        NotifyCenter center = NotifyCenter.get(this);
        center.interruptionFilter(getCurrentInterruptionFilter());
        Posted p = describe(sbn, rankingMap != null ? rankingMap : getCurrentRanking());
        Log.i(TAG, "posted " + sbn.getPackageName() + " silent=" + p.silent + " ongoing=" + p.ongoing
            + " summary=" + p.summary + " call=" + p.call + " matches=" + p.matchesDnd);
        center.posted(p, false);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        onNotificationPosted(sbn, null);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        NotifyCenter.get(this).removed(sbn.getKey());
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        NotifyCenter.get(this).removed(sbn.getKey());
    }

    /** A channel's importance or the filter's match changed: what shows may change, so the centre is made over. */
    @Override public void onNotificationRankingUpdate(RankingMap rankingMap) {
        ui.removeCallbacks(resync);
        ui.postDelayed(resync, RESYNC_MS);
    }

    @Override public void onListenerConnected() {
        NotifyCenter center = NotifyCenter.get(this);
        center.interruptionFilter(getCurrentInterruptionFilter());
        if (!receiversUp) {
            receiversUp = true;
            center.registerReceivers(this);
        }
        center.attach(this);
        resync();
    }

    /**
     * The centre made the same as the phone's notifications now: what went while the service was not bound leaves,
     * what came enters without banners. Also after a ranking change and when an app is shown again.
     */
    void resync() {
        ui.removeCallbacks(resync);
        StatusBarNotification[] all;
        try {
            all = getActiveNotifications();
        } catch (RuntimeException e) {
            // Not bound (any more): nothing to compare with; the next connection syncs
            Log.w(TAG, "no active notifications: " + e);
            return;
        }
        if (all == null) {
            Log.w(TAG, "active notifications unknown");
            return;
        }
        RankingMap map = getCurrentRanking();
        List<Posted> now = new ArrayList<>(all.length);
        for (StatusBarNotification sbn : all) now.add(describe(sbn, map));
        Log.i(TAG, "synced with " + all.length + " notifications");
        NotifyCenter.get(this).sync(now);
    }

    @Override public void onListenerDisconnected() {
        Log.i(TAG, "disconnected");
        ui.removeCallbacks(resync);
        NotifyCenter center = NotifyCenter.get(this);
        if (receiversUp) {
            receiversUp = false;
            center.unregisterReceivers(this);
        }
        center.detach(this);
        // The documented way back; HyperOS may still refuse it while XDesk may not start itself (NotifyCenter.rebind)
        NotifyCenter.requestRebind(this);
    }

    @Override public void onDestroy() {
        ui.removeCallbacks(resync);
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
