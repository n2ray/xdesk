/*
 * Derived from Sall (https://github.com/n2ray/sall, MacDiscovery.java; DiscoveryEpoch.java by its use) and Sill
 * (https://github.com/Saffsanity/sill, Sources/SillHost/DoorPolicy.swift, StreamServer.swift). Copyright 2026
 * Noah Saffer. Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sill's Macs on the local network (_sill._tcp), to find the home door of a Mac this device already paired with.
 * Discovery runs while something needs it: XDesk's window, a Mac's screen, or a MacClient that wants a
 * connection; the last release stops it. It holds no multicast lock itself (the platform's NsdManager does its
 * own mDNS) and asks nothing of Wi-Fi, so a phone on Ethernet or without Wi-Fi keeps working, just without a home
 * door to find.
 *
 * The discovered direction of a saved Mac is a hint. A saved Mac is recognised by the TXT record's recognition
 * tag (RecognitionTag, Sill's "r"), and the home door is dialled with the saved pin like the remote one; only the
 * pinned TLS handshake decides who the Mac is.
 */
public final class MacDiscovery {
    private static final String TAG = "XDeskMac";
    /** Sill's Bonjour type (StreamServer.swift). */
    private static final String SERVICE = "_sill._tcp.";
    private static final int MAX_PENDING = 16;

    private static MacDiscovery instance;

    static synchronized MacDiscovery get(Context c) {
        if (instance == null) instance = new MacDiscovery(c.getApplicationContext());
        return instance;
    }

    /** One more thing needs local discovery; the first starts it. */
    public static void acquire(Context c) {
        get(c).acquire();
    }

    /** One fewer thing needs local discovery; the last stops it. */
    public static void release() {
        MacDiscovery d;
        synchronized (MacDiscovery.class) {
            d = instance;
        }
        if (d != null) d.releaseOne();
    }

    /** A resolved home door: where to dial, what Bonjour calls it, and how it speaks. */
    static final class Door {
        final String name, host, tag;
        final int port;
        final HomeDiscoveryMetadata.Door kind;

        Door(String name, String host, int port, HomeDiscoveryMetadata.Door kind, String tag) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.kind = kind;
            this.tag = tag;
        }

        boolean tls() {
            return kind == HomeDiscoveryMetadata.Door.TLS_OPEN
                    || kind == HomeDiscoveryMetadata.Door.TLS_PAIRING_REQUIRED;
        }

        String address() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }
    }

    private static final class Pending {
        final NsdServiceInfo service;

        Pending(NsdServiceInfo service) {
            this.service = service;
        }
    }

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    /** The services currently announced: a resolution that comes after its service was lost is dropped (Sall's
     *  DiscoveryEpoch ticket). Main thread. */
    private final java.util.HashSet<String> known = new java.util.HashSet<>();
    /** Resolved services by Bonjour name. Guarded by state; changed on the main thread. */
    private final LinkedHashMap<String, Door> doors = new LinkedHashMap<>();
    private final Object state = new Object();
    private volatile boolean active;
    private int users;
    private boolean resolving;
    private NsdManager.DiscoveryListener discovery;

    private MacDiscovery(Context c) {
        nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
    }

    private synchronized void acquire() {
        users++;
        if (!active) start();
    }

    private synchronized void releaseOne() {
        if (users == 0) return;
        users--;
        if (users == 0) stop();
    }

    private synchronized void start() {
        if (active || nsd == null) return;
        active = true;
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int error) {
                Log.w(TAG, "local discovery failed: " + error);
                main.post(() -> {
                    if (active) stop();
                });
            }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> found(info));
            }
            @Override public void onServiceLost(NsdServiceInfo info) {
                main.post(() -> lost(info));
            }
        };
        try {
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            Log.w(TAG, "local discovery could not start", e);
            active = false;
            discovery = null;
        }
    }

    private synchronized void stop() {
        if (!active) return;
        active = false;
        resolving = false;
        pending.clear();
        known.clear();
        NsdManager.DiscoveryListener d = discovery;
        discovery = null;
        synchronized (state) {
            doors.clear();
            state.notifyAll();
        }
        if (d != null) {
            try {
                nsd.stopServiceDiscovery(d);
            } catch (RuntimeException ignored) { }
        }
    }

    private void found(NsdServiceInfo info) {
        if (!active || info == null) return;
        String name = info.getServiceName();
        if (name == null || name.isEmpty()) return;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            if (name.equals(it.next().service.getServiceName())) it.remove();
        }
        if (pending.size() >= MAX_PENDING) pending.removeFirst();
        known.add(name);
        pending.add(new Pending(info));
        resolveNext();
    }

    private void lost(NsdServiceInfo info) {
        if (!active || info == null) return;
        String name = info.getServiceName();
        if (name == null) return;
        known.remove(name);
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            if (name.equals(it.next().service.getServiceName())) it.remove();
        }
        synchronized (state) {
            doors.remove(name);
            state.notifyAll();
        }
    }

    /** One resolve at a time: older systems fail a second, Sall's MacDiscovery does the same. */
    private void resolveNext() {
        if (!active || resolving || pending.isEmpty()) return;
        final Pending next = pending.removeFirst();
        resolving = true;
        try {
            nsd.resolveService(next.service, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                    main.post(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    main.post(() -> {
                        resolving = false;
                        resolved(next.service, info);
                        resolveNext();
                    });
                }
            });
        } catch (RuntimeException e) {
            resolving = false;
            main.post(this::resolveNext);
        }
    }

    private void resolved(NsdServiceInfo asked, NsdServiceInfo info) {
        if (!active || asked == null || info == null) return;
        String name = info.getServiceName();
        if (name == null || !name.equals(asked.getServiceName()) || !known.contains(name)) return;
        InetAddress host = info.getHost();
        int port = info.getPort();
        if (host == null || port < 1 || port > 65535) return;
        HomeDiscoveryMetadata metadata = HomeDiscoveryMetadata.parse(info.getAttributes());
        Door door = new Door(name, host.getHostAddress(), port, metadata.door(), metadata.recognitionHint());
        synchronized (state) {
            doors.put(name, door);
            state.notifyAll();
        }
    }

    /**
     * The home door of the Mac with this recognition key, waiting up to waitMs for discovery to resolve one.
     * Null when none was found, when the only match is a plain door (XDesk speaks TLS only), or when the key is
     * absent (a Mac paired before Sill sent recognition keys). Never blocks a connect longer than waitMs.
     */
    Door homeDoor(byte[] recognitionKey, long waitMs) {
        if (recognitionKey == null || recognitionKey.length != 32) return null;
        long until = SystemClock.uptimeMillis() + Math.max(0, waitMs);
        synchronized (state) {
            while (true) {
                Door door = matching(recognitionKey);
                if (door != null) return door;
                if (!active) return null;  // discovery stopped (no network, a failure): the saved address at once
                long left = until - SystemClock.uptimeMillis();
                if (left <= 0) return null;
                try {
                    state.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /** The first resolved TLS door whose tag matches; guarded by state. */
    private Door matching(byte[] recognitionKey) {
        for (Map.Entry<String, Door> e : doors.entrySet()) {
            Door d = e.getValue();
            if (d.tls() && RecognitionTag.matches(d.tag, recognitionKey)) return d;
        }
        return null;
    }
}
