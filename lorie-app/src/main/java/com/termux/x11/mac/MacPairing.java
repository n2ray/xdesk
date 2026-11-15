package com.termux.x11.mac;

import android.content.Context;
import android.os.Build;

import java.util.Collections;

/** Pairing with a Mac: by its address and the 12-digit code its Sill shows, by a pairing link (its QR code), or by asking it for a code. */
public final class MacPairing {
    private MacPairing() { }

    /** "host", "host:port", "[v6]:port" or a bare IPv6 address. Port 7455 (Sill's remote door) when none is given. */
    public static Object[] parse(String address) {
        String a = address.trim();
        String host = a;
        int port = MacClient.DEFAULT_PORT;
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("address");
            host = a.substring(1, end);
            if (a.length() > end + 1) {
                if (a.charAt(end + 1) != ':') throw new IllegalArgumentException("address");
                port = Integer.parseInt(a.substring(end + 2));
            }
        } else if (a.indexOf(':') == a.lastIndexOf(':') && a.indexOf(':') > 0) {
            host = a.substring(0, a.indexOf(':'));
            port = Integer.parseInt(a.substring(a.indexOf(':') + 1));
        }
        if (host.isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException("address");
        return new Object[] {host, port};
    }

    /** Checks the code's form (12 digits, Damm check digit) before any connection. Throws IllegalArgumentException. */
    public static void checkCode(String code) {
        PairingProof.normalizeCode(code);
    }

    /** Blocks (network and a 600 000-round key derivation): call off the UI thread. */
    public static MacStore.Mac pair(Context c, String address, String code) throws Exception {
        return pair(c, address, code, null);
    }

    /**
     * expectedPin: the Mac's key as an "ask" saw it, so the code goes to the same Mac; null pairs with whatever
     * Mac answers, as XDesk always did.
     */
    public static MacStore.Mac pair(Context c, String address, String code, byte[] expectedPin) throws Exception {
        Object[] hp = parse(address);
        String host = (String) hp[0];
        int port = (Integer) hp[1];
        String normalized = PairingProof.normalizeCode(code);
        try (SillTls t = SillTls.connect(host, port, SillTls.PAIRING, expectedPin, 10_000, 30_000)) {
            SillTls.Paired p = t.pair(normalized, "XDesk " + Build.MODEL, Build.DEVICE);
            MacStore.Mac m = MacStore.add(c, p, host, port);
            MacClient client = MacClients.get(c, m.id);
            if (client != null) client.paired();
            return m;
        }
    }

    /**
     * Pairs by a pairing link (scanned from Sill's QR code, or a tapped sill://pair link): dials its addresses in
     * the Mac's own order, pinned to the link's key, and proves the link's one-time secret. The saved Mac is the
     * same as the code path's. Blocks (network); call off the UI thread.
     */
    public static MacStore.Mac pairLink(Context c, PairLink link) throws Exception {
        byte[] secret = link.secret();
        Exception last = null;
        try {
            for (PairLink.Address a : link.candidates(Collections.emptyList())) {
                int port = a.port == 0 ? link.port : a.port;
                try (SillTls t = SillTls.connect(a.host, port, SillTls.PAIRING, link.fingerprint(), 10_000, 30_000)) {
                    SillTls.Paired p = t.pairQr(secret, "XDesk " + Build.MODEL, Build.DEVICE);
                    MacStore.Mac m = MacStore.add(c, p, a.host, port);
                    MacClient client = MacClients.get(c, m.id);
                    if (client != null) client.paired();
                    return m;
                } catch (Exception e) {
                    last = e;
                }
            }
            throw last != null ? last : new java.io.IOException("Pairing link has no address");
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
            link.clearSecret();
        }
    }

    /** The Mac's answer to an "ask" (kind 19 "ask"): its reason, and its key for the code pairing to pin. */
    public static final class Ask {
        /** "shown", "locked", "busy", "openOnMac", "closed"… */
        public final String reason;
        /** How long the Mac says to wait before asking again. */
        public final long retryMillis;
        /** The Mac's key as the ask saw it: pin the pairing that follows so the code goes to the same Mac. */
        public final byte[] pin;

        Ask(String reason, long retryMillis, byte[] pin) {
            this.reason = reason;
            this.retryMillis = retryMillis;
            this.pin = pin;
        }
    }

    /** Asks the Mac at `address` to show a pairing code. Blocks (network); call off the UI thread. */
    public static Ask ask(String address) throws Exception {
        Object[] hp = parse(address);
        try (SillTls t = SillTls.connect((String) hp[0], (Integer) hp[1], SillTls.PAIRING, null, 10_000, 30_000)) {
            SillTls.Asked asked = t.ask("XDesk " + Build.MODEL, Build.DEVICE);
            return new Ask(asked.reason, asked.retryMillis, t.peer());
        }
    }

    /** A refusal's reason from the Mac ("code", "closed", "busy"…), or null for another failure. */
    public static String refusal(Throwable e) {
        return e instanceof SillTls.PairRefused ? ((SillTls.PairRefused) e).reason : null;
    }
}
