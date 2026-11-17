package com.termux.x11;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the devices XDesk joins share (INPUT.md §8, SHELF.md §0): one clipboard for now, later the shelf's files on the
 * same ground. The phone's clipboard is the clipboard; every device that takes part is an Endpoint: the Linux desktop,
 * each Mac, each receiver. A copy on any of them comes here (put) and goes to the phone and to all the others, a copy on
 * the phone (an Android app, or XDesk itself) comes here through the injector's watch (phone) and goes to all of them,
 * and a device that joins gets what is there. One switch for all, the existing clipboard_sync.
 *
 * Android lets only the focused app read the clipboard: the injector, the shell user, may read it in the background
 * and tells of each change (IInjector.watchClipboard). Without an injector XDesk hears of the phone's copies only while
 * it has the focus, through its own listener, as before.
 */
public final class ShareHub {
    private static final String TAG = "XDeskShare";
    /** As the receiver protocol's CLIPBOARD (PROTOCOL.md) and Sill's: a longer text is not shared. */
    public static final int MAX_TEXT = 256 * 1024;

    /** A device that takes part. Called on the hub's own thread, never for what came from it. */
    public interface Endpoint {
        void clipboard(String text);
    }

    private static ShareHub instance;

    public static synchronized ShareHub get(Context c) {
        if (instance == null) instance = new ShareHub(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "share-hub"));
    private final CopyOnWriteArrayList<Endpoint> endpoints = new CopyOnWriteArrayList<>();
    private volatile String current;
    /** Where the current text came from; null for the phone. */
    private volatile Endpoint from;
    private volatile boolean listening;

    /** The Linux desktop: X asks LorieView when a program pastes, which answers with this when it cannot read Android's. */
    private final Endpoint x = text -> ui.post(() -> {
        LorieView v = DesktopInput.view();
        if (v != null) v.setComputerClipboard(text);
    });

    private ShareHub(Context c) {
        context = c;
        endpoints.add(x);
        // X copied: LorieView has put it on the phone already
        LorieView.onClipboardFromX = text -> take(text, x, false);
        ui.post(this::listen);
    }

    /** The clipboard_sync switch (Settings › receivers), for every device. */
    public boolean on() {
        return context.getSharedPreferences("wireless", Context.MODE_PRIVATE).getBoolean("clipboard_sync", true);
    }

    /** What the clipboard holds as far as the hub knows, null before anything. */
    public String current() {
        return current;
    }

    /** The Linux desktop came on the screen: it pastes what is there, which it may not have been told while it was away. */
    public void desktopShown() {
        final String text = current;
        if (text != null && from != x && on()) x.clipboard(text);
    }

    /** A device takes part from now on: it gets what is there, unless that came from it. */
    public void join(final Endpoint e) {
        if (endpoints.addIfAbsent(e)) {
            final String text = current;
            if (text != null && from != e && on()) worker.execute(() -> e.clipboard(text));
        }
    }

    public void leave(Endpoint e) {
        endpoints.remove(e);
        if (from == e) from = null;
    }

    /** A device copied this: to the phone and to every other device. */
    public void put(String text, Endpoint origin) {
        take(text, origin, true);
    }

    /** The phone's clipboard changed (the injector's watch, or XDesk's own listener while focused). */
    void phone(String text) {
        take(text, null, false);
    }

    private void take(String text, Endpoint origin, boolean toPhone) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT || !on()) return;
        // The same text again: the phone's echo of what a device put there, or a device sending it back
        if (text.equals(current)) return;
        current = text;
        from = origin;
        if (toPhone) writePhone(text);
        worker.execute(() -> {
            for (Endpoint e : endpoints) {
                if (e == origin) continue;
                try {
                    e.clipboard(text);
                } catch (RuntimeException ex) {
                    Log.w(TAG, "clipboard to a device", ex);
                }
            }
        });
    }

    /** Through the injector when there is one (as the receivers did), else as XDesk itself, which Android lets write. */
    private void writePhone(final String text) {
        worker.execute(() -> {
            if (AppSession.get(context).setClipboardText(text)) return;
            ui.post(() -> {
                try {
                    context.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("XDesk", text));
                } catch (RuntimeException e) {
                    Log.w(TAG, "clipboard", e);
                }
            });
        });
    }

    /** XDesk's own listener: Android calls it only while XDesk has the focus, and then XDesk may read the clipboard. */
    private void listen() {
        if (listening) return;
        listening = true;
        final ClipboardManager cm = context.getSystemService(ClipboardManager.class);
        cm.addPrimaryClipChangedListener(() -> {
            try {
                ClipData d = cm.getPrimaryClip();
                if (d == null || d.getItemCount() == 0) return;
                CharSequence t = d.getItemAt(0).getText();
                if (t != null) phone(t.toString());
            } catch (RuntimeException e) {
                Log.d(TAG, "clipboard", e);
            }
        });
    }
}
