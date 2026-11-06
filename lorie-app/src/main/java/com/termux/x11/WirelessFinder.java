package com.termux.x11;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * XDesk Receivers announced on the local network (mDNS, see receiver/PROTOCOL.md), while the picker is open. mDNS does
 * not cross routers, so the picker also takes an address.
 */
final class WirelessFinder {
    private static final String TAG = "XDeskWireless";

    interface Listener {
        /** Main thread. The same receiver may come again with a new address. */
        void onFound(Receiver r);
    }

    static final class Receiver {
        final String name, id, host;
        final int port;

        Receiver(String name, String id, String host, int port) {
            this.name = name;
            this.id = id;
            this.host = host;
            this.port = port;
        }
    }

    private final NsdManager nsd;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Older systems resolve one service at a time and fail the rest. */
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private boolean resolving, running;
    private Listener listener;
    private NsdManager.DiscoveryListener discovery;

    WirelessFinder(Context c) {
        nsd = c.getSystemService(NsdManager.class);
    }

    void start(Listener l) {
        stop();
        listener = l;
        running = true;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo info) {
                ui.post(() -> {
                    if (!running) return;
                    pending.add(info);
                    resolveNext();
                });
            }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int error) {
                Log.w(TAG, "discovery failed: " + error);
            }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
        };
        try {
            nsd.discoverServices(WirelessDisplay.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            Log.w(TAG, "discovery", e);
            discovery = null;
        }
    }

    void stop() {
        running = false;
        listener = null;
        pending.clear();
        if (discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) { }
            discovery = null;
        }
    }

    @SuppressWarnings("deprecation")  // the replacement is Android 14 only, and this works there too
    private void resolveNext() {
        if (resolving || pending.isEmpty() || !running) return;
        resolving = true;
        nsd.resolveService(pending.poll(), new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                Log.i(TAG, "resolve failed: " + error);
                done();
            }
            @Override public void onServiceResolved(NsdServiceInfo info) {
                InetAddress a = info.getHost();
                final Receiver r = a == null ? null
                    : new Receiver(info.getServiceName(), attribute(info, "id"), a.getHostAddress(), info.getPort());
                ui.post(() -> {
                    if (r != null && running && listener != null) listener.onFound(r);
                });
                done();
            }
        });
    }

    private void done() {
        ui.post(() -> {
            resolving = false;
            resolveNext();
        });
    }

    private static String attribute(NsdServiceInfo info, String key) {
        Map<String, byte[]> m = info.getAttributes();
        byte[] v = m != null ? m.get(key) : null;
        return v != null ? new String(v, StandardCharsets.UTF_8) : null;
    }
}
