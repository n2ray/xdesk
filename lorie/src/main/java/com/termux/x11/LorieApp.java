package com.termux.x11;

import static android.os.Build.VERSION.SDK_INT;
import static com.termux.x11.CmdEntryPoint.ACTION_START;
import static com.termux.x11.LoriePreferences.ACTION_PREFERENCES_CHANGED;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build.VERSION_CODES;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import com.termux.x11.input.TouchInputHandler;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class LorieApp extends Application {
    public static final int NOTIFICATION_ID = 7892;

    /**
     * Connections waiting for a window: one per "-tag", a handful at most. Any app that can send ACTION_START
     * on an older Android (before the sender uid check in Receiver) would otherwise grow this without bound.
     */
    private static final int MAX_PENDING_CONNECTIONS = 8, MAX_TAG_LENGTH = 256;

    public final Prefs builtInPrefs = new Prefs();
    public final Prefs secondaryPrefs = new Prefs();
    public NotificationManager notificationManager;
    public final Map<String, IBinder> pendingConnections = new ConcurrentHashMap<>();
    /** X servers whose socket MainActivity has checked. Only these keep another ACTION_START out, see onBroadcastReceive. */
    public final Set<IBinder> verifiedServers = Collections.newSetFromMap(new WeakHashMap<>());

    private Notification baseNotification;
    /** Only one rejected Binder is answered at a time: one that never returns must not pile up threads. */
    private final AtomicBoolean reportingFatalError = new AtomicBoolean();

    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener = (__, key) -> {
        for (MainActivity activity : MainActivity.getInstances())
            activity.onPreferencesChanged(key);
    };

    @Override
    @SuppressLint("ObsoleteSdkInt")
    public void onCreate() {
        super.onCreate();

        // A platform-supplied Context can identify as the host app's package in sharedUid builds.
        Context prefsCtx = this;
        if (!BuildConfig.APPLICATION_ID.equals(getPackageName())) {
            try {
                prefsCtx = createPackageContext(BuildConfig.APPLICATION_ID, 0);
            } catch (PackageManager.NameNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        builtInPrefs.attach(prefsCtx, PreferenceManager.getDefaultSharedPreferences(prefsCtx));
        secondaryPrefs.attach(prefsCtx, prefsCtx.getSharedPreferences("secondary", Context.MODE_PRIVATE));

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        builtInPrefs.get().registerOnSharedPreferenceChangeListener(preferencesChangedListener);
        secondaryPrefs.get().registerOnSharedPreferenceChangeListener(preferencesChangedListener);

        String channelId = getResources().getString(R.string.lorie_app_name);
        if (SDK_INT >= VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_HIGH);
            channel.setImportance(NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            if (SDK_INT >= VERSION_CODES.Q)
                channel.setAllowBubbles(false);
            notificationManager.createNotificationChannel(channel);
        }

        // The parts of the notification that don't depend on preferences, built once and reused as a template.
        baseNotification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Termux:X11")
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentText(getResources().getText(R.string.lorie_notification_content_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setSilent(true)
                .setShowWhen(false)
                .setColor(0xFF607D8B)
                .build();
    }

    /** Picks builtInPrefs or secondaryPrefs depending on which display ctx's window is on. */
    public Prefs getPrefs(Context ctx) {
        boolean isSecondaryDisplay = ((WindowManager) ctx.getSystemService(WINDOW_SERVICE))
                .getDefaultDisplay().getDisplayId() != Display.DEFAULT_DISPLAY;
        return (isSecondaryDisplay && builtInPrefs.storeSecondaryDisplayPreferencesSeparately.get())
                ? secondaryPrefs : builtInPrefs;
    }

    public void onActivityResumed(MainActivity activity) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(activity));
    }

    public void onActivityPaused() {
        notificationManager.cancel(NOTIFICATION_ID);
    }

    void refreshNotificationIfShown() {
        MainActivity activity = MainActivity.getFocusedOrAnyInstance();
        if (activity == null)
            return;
        for (StatusBarNotification notification : notificationManager.getActiveNotifications())
            if (notification.getId() == NOTIFICATION_ID) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(activity));
                return;
            }
    }

    private Notification buildNotification(MainActivity activity) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, baseNotification).setSilent(true);
        return TouchInputHandler.setupNotification(activity, activity.prefs, builder).build();
    }

    public void onBroadcastReceive(Intent intent) {
        String action = intent.getAction();
        String extraTag = ACTION_START.equals(action) || MainActivity.ACTION_STOP.equals(action) || MainActivity.ACTION_CUSTOM.equals(action)
                ? intent.getStringExtra(CmdEntryPoint.EXTRA_DOCUMENT_TAG) : null;
        String tag = extraTag == null ? "" : extraTag;
        MainActivity activity = MainActivity.getInstance(tag);
        if (activity != null)
            activity.prefs = getPrefs(activity);

        switch (action == null ? "" : action) {
            case ACTION_START: {
                Bundle bundle = intent.getBundleExtra(null);
                IBinder binder = bundle == null ? null : bundle.getBinder(null);
                if (binder == null)
                    break;

                IBinder activeService = activity != null && activity.service != null ? activity.service.asBinder() : null;
                IBinder pendingConnection = pendingConnections.get(tag);
                boolean sameConnection = binder.equals(activeService) || binder.equals(pendingConnection);
                // Any app can send this broadcast, so a connection not checked yet must not get a real server told to quit.
                // Off the main thread: a Binder that never returns would freeze the window.
                if (!sameConnection && activeService != null && activeService.isBinderAlive() && verifiedServers.contains(activeService)) {
                    reportFatalErrorAsync(binder);
                    break;
                }

                // No window to take it: keep it for the window that opens later, within bounds. Tracked binders
                // are dropped before linkToDeath so a flood of ignored ones leaves nothing on our side.
                if (activity == null && (tag.length() > MAX_TAG_LENGTH
                        || (!pendingConnections.containsKey(tag) && pendingConnections.size() >= MAX_PENDING_CONNECTIONS))) {
                    Log.w("LorieApp", "Ignoring a connection: a tag is too long or too many are pending");
                    break;
                }

                try {
                    if (!sameConnection) {
                        WeakReference<IBinder> binderRef = new WeakReference<>(binder);
                        binder.linkToDeath(() -> {
                            IBinder deadBinder = binderRef.get();
                            if (deadBinder != null)
                                onConnectionDied(tag, deadBinder);
                        }, 0);
                    }
                } catch (RemoteException ignored) {}

                if (activity != null) {
                    try {
                        Log.v("LorieApp", "Got new ACTION_START intent");
                        activity.connectToService(binder);
                    } catch (Exception e) {
                        Log.e("LorieApp", "Something went wrong while we extracted connection details from binder.", e);
                    }
                } else {
                    pendingConnections.put(tag, binder);
                }
                break;
            }
            case MainActivity.ACTION_STOP:
                if (activity != null)
                    activity.finishAffinity();
                break;
            case ACTION_PREFERENCES_CHANGED:
                for (MainActivity a : MainActivity.getInstances()) {
                    a.prefs = getPrefs(a);
                    a.onPreferencesChanged(intent.getStringExtra("key"));
                }
                break;
            case MainActivity.ACTION_CUSTOM:
                Log.d("ACTION_CUSTOM", "action " + intent.getStringExtra("what"));
                if (activity != null)
                    activity.mInputHandler.extractUserActionFromPreferences(activity.prefs, intent.getStringExtra("what")).accept(0, true);
                break;
        }

        if (activity == null && !ACTION_START.equals(action) && !ACTION_PREFERENCES_CHANGED.equals(action))
            Log.w("LorieApp", "Got " + action + " but no MainActivity instance in this process");
    }

    private void onConnectionDied(String tag, IBinder binder) {
        pendingConnections.remove(tag, binder);

        MainActivity activity = MainActivity.getInstance(tag);
        if (activity != null && activity.service != null && activity.service.asBinder() == binder)
            activity.disconnectService();
    }

    /**
     * Tells a sender that another X server is active, without waiting for it. The Binder comes from any app that can
     * send ACTION_START; one that blocks its caller for ever leaves only this thread, and later ones are not started.
     */
    private void reportFatalErrorAsync(IBinder binder) {
        if (!reportingFatalError.compareAndSet(false, true))
            return;
        Thread thread = new Thread(() -> {
            try {
                ICmdEntryInterface remote = ICmdEntryInterface.Stub.asInterface(binder);
                if (remote != null)
                    remote.reportFatalError("Termux:X11 already has an active X server connection.");
            } catch (RemoteException | RuntimeException e) {
                Log.v("LorieApp", "The rejected sender did not answer: " + e);
            } finally {
                reportingFatalError.set(false);
            }
        }, "xdesk-reject");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Exported, because the X server's ACTION_START comes from Termux's app_process. Anything else would let any app
     * close the desktop window (ACTION_STOP) or run a user action (ACTION_CUSTOM), even when named explicitly, so it
     * is dropped here. ACTION_START is checked against the X server's socket, see MainActivity.tryConnect.
     */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_START.equals(intent.getAction())) {
                Log.w("LorieApp", "Ignored " + intent.getAction() + " from outside the app");
                return;
            }
            // API 34 says who sent the broadcast. The X server runs in Termux (or as root or the shell, like
            // MainActivity.madeByXServer allows); anything else carrying a Binder here is not the server. Older
            // Androids cannot tell, but MainActivity.tryConnect still checks the socket's SO_PEERCRED.
            if (SDK_INT >= 34) {
                int uid = getSentFromUid();
                if (!senderAllowed(context, uid)) {
                    Log.w("LorieApp", "Ignored ACTION_START from uid " + uid);
                    return;
                }
            }
            ((LorieApp) context.getApplicationContext()).onBroadcastReceive(intent);
        }

        private static boolean senderAllowed(Context context, int uid) {
            if (uid == Process.INVALID_UID)  // not known: the socket check still has to pass
                return true;
            if (uid == Process.myUid() || uid == Process.ROOT_UID || uid == Process.SHELL_UID)
                return true;
            try {
                return uid == context.getPackageManager().getPackageUid("com.termux", 0);
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
    }

    /**
     * Not exported: only this app's own broadcasts reach it, ACTION_PREFERENCES_CHANGED from the preferences and
     * ACTION_CUSTOM from the notification's PendingIntents. ACTION_STOP has no sender here, so no filter lists it.
     */
    public static class LocalReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_START.equals(intent.getAction()))  // the exported Receiver checks those
                return;
            ((LorieApp) context.getApplicationContext()).onBroadcastReceive(intent);
        }
    }
}
