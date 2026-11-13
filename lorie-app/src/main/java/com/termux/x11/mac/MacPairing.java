package com.termux.x11.mac;

import android.content.Context;
import android.os.Build;

/** Pairing with a Mac by its address and the 12-digit code its Sill shows (Pair iPhone or iPad…). */
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
        Object[] hp = parse(address);
        String host = (String) hp[0];
        int port = (Integer) hp[1];
        String normalized = PairingProof.normalizeCode(code);
        try (SillTls t = SillTls.connect(host, port, SillTls.PAIRING, null, 10_000, 30_000)) {
            SillTls.Paired p = t.pair(normalized, "XDesk " + Build.MODEL, Build.DEVICE);
            return MacStore.add(c, p, host, port);
        }
    }

    /** A refusal's reason from the Mac ("code", "closed", "busy"…), or null for another failure. */
    public static String refusal(Throwable e) {
        return e instanceof SillTls.PairRefused ? ((SillTls.PairRefused) e).reason : null;
    }
}
