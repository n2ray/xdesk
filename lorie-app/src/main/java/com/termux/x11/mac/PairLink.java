/*
 * Derived from Sall (https://github.com/n2ray/sall, PairLink.java), the Android client of Sill
 * (https://github.com/Saffsanity/sill). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import java.io.ByteArrayOutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Untrusted pairing links (sill://pair?…), what Sill's pairing window shows in its QR code and what a camera app
 * hands over when it reads that code. Parsing never establishes trust or opens a connection: the link names the
 * Mac and carries its key (`fingerprint`) and the QR path's one-time secret, and MacPairing uses both.
 */
public final class PairLink {
    /** One address to try, in the Mac's dial order. port 0: use the link's own port. */
    public static final class Address {
        public final String host;
        public final int port;

        Address(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String display(int fallback) {
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + (port == 0 ? fallback : port);
        }

        /** By host and port, so a repeated address in the link is dialled once (Sall's is a record). */
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Address)) return false;
            Address a = (Address) o;
            return port == a.port && host.equals(a.host);
        }

        @Override public int hashCode() {
            return host.hashCode() * 31 + port;
        }
    }

    public final String name;
    public final int port;
    public final List<Address> addresses;
    private final byte[] fingerprint, secret;

    private PairLink(String name, int port, List<Address> addresses, byte[] fingerprint, byte[] secret) {
        this.name = name;
        this.port = port;
        this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
        this.fingerprint = fingerprint;
        this.secret = secret;
    }

    byte[] fingerprint() {
        return fingerprint.clone();
    }

    byte[] secret() {
        return secret.clone();
    }

    /** The secret is a credential: clear it once the link is not needed (paired, or the offer was declined). */
    public void clearSecret() {
        java.util.Arrays.fill(secret, (byte) 0);
    }

    /**
     * The addresses to dial, the Mac's own first, loopback last. XDesk has no discovery of local doors (Sall's
     * pairingDoors), so `local` is always empty and this is just the link's addresses.
     */
    public List<Address> candidates(List<Address> local) {
        List<Address> result = new ArrayList<>(localCandidates(local));
        List<Address> explicit = new ArrayList<>();
        List<Address> advertised = new ArrayList<>(addresses);
        advertised.sort(java.util.Comparator.comparingInt(a -> a.host.equals("127.0.0.1") || a.host.equals("::1") ? 1 : 0));
        for (Address a : advertised) {
            Address effective = new Address(a.host, a.port == 0 ? port : a.port);
            if (!explicit.contains(effective)) {
                explicit.add(effective);
                result.add(effective);
            }
        }
        return result;
    }

    private List<Address> localCandidates(List<Address> local) {
        List<Address> result = new ArrayList<>();
        int limit = addresses.isEmpty() ? 8 : 2;
        for (Address a : local) if (!result.contains(a) && result.size() < limit) result.add(a);
        return result;
    }

    public static PairLink parse(String text) {
        try {
            if (text == null || text.length() > 8192) throw new IllegalArgumentException();
            URI uri = new URI(text);
            if (!"sill".equalsIgnoreCase(uri.getScheme()) || !"pair".equalsIgnoreCase(uri.getRawAuthority())
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    || uri.getRawFragment() != null || uri.getRawQuery() == null) throw new IllegalArgumentException();
            Map<String, List<String>> fields = new HashMap<>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                int eq = part.indexOf('=');
                String key = decode(eq < 0 ? part : part.substring(0, eq));
                String value = decode(eq < 0 ? "" : part.substring(eq + 1));
                fields.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
            if (!one(fields, "v").equals("1")) throw new IllegalArgumentException();
            String mac = one(fields, "m");
            byte[] fingerprint = bytes(one(fields, "k"), 32), secret = bytes(one(fields, "s"), 16);
            if (!mac.equals(SillTls.macId(fingerprint))) throw new IllegalArgumentException();
            int port = port(one(fields, "p"));
            String name = label(fields.containsKey("n") ? fields.get("n").get(0) : "");
            if (name.isEmpty()) name = "Mac";
            List<Address> addresses = new ArrayList<>();
            for (String a : fields.containsKey("a") ? fields.get("a") : Collections.<String>emptyList()) {
                Address parsed = address(a);
                if (addresses.size() < 5) addresses.add(parsed);
            }
            return new PairLink(name, port, addresses, fingerprint, secret);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid pairing link");
        }
    }

    private static String one(Map<String, List<String>> f, String key) {
        List<String> v = f.get(key);
        if (v == null || v.size() != 1) throw new IllegalArgumentException();
        return v.get(0);
    }

    private static byte[] bytes(String text, int count) {
        if (!text.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        byte[] b = Base64.getUrlDecoder().decode(text);
        if (b.length != count || !Base64.getUrlEncoder().withoutPadding().encodeToString(b).equals(text))
            throw new IllegalArgumentException();
        return b;
    }

    private static String decode(String raw) throws Exception {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%') {
                if (i + 2 >= bytes.length) throw new IllegalArgumentException();
                int hi = Character.digit((char) bytes[++i], 16), lo = Character.digit((char) bytes[++i], 16);
                if (hi < 0 || lo < 0) throw new IllegalArgumentException();
                out.write((hi << 4) | lo);
            } else out.write(bytes[i]);
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();
    }

    private static int port(String value) {
        if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException();
        int p = Integer.parseInt(value);
        if (p < 1 || p > 65535) throw new IllegalArgumentException();
        return p;
    }

    static Address address(String raw) throws Exception {
        String host = raw;
        int p = 0;
        if (raw.startsWith("[")) {
            int end = raw.indexOf(']');
            if (end < 0) throw new IllegalArgumentException();
            host = raw.substring(1, end);
            String rest = raw.substring(end + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) throw new IllegalArgumentException();
                p = port(rest.substring(1));
            }
            if (!host.contains(":")) throw new IllegalArgumentException();
        } else if (raw.indexOf(':') >= 0 && raw.indexOf(':') == raw.lastIndexOf(':')) {
            int split = raw.indexOf(':');
            host = raw.substring(0, split);
            p = port(raw.substring(split + 1));
        }
        if (host.contains(":")) {
            if (!host.matches("[0-9a-fA-F:.]+") || !(InetAddress.getByName(host) instanceof Inet6Address))
                throw new IllegalArgumentException();
        } else if (host.matches("[0-9.]+")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException();
            for (String part : parts)
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) throw new IllegalArgumentException();
        } else {
            String domain = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
            if (domain.length() > 253 || domain.isEmpty()) throw new IllegalArgumentException();
            for (String part : domain.split("\\.", -1)) {
                if (part.length() > 63 || !part.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) throw new IllegalArgumentException();
                if (part.matches("(?i)0x[0-9a-f]+")) throw new IllegalArgumentException();
            }
        }
        return new Address(host, p);
    }

    /** The Mac's name as Sall's SafeText.label cleans it: 64 characters at most, control characters dropped. */
    private static String label(String value) {
        StringBuilder s = new StringBuilder();
        boolean space = false;
        int count = 0;
        for (int i = 0; i < value.length() && count < 64;) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                space = s.length() > 0;
                continue;
            }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.PRIVATE_USE || type == Character.UNASSIGNED) continue;
            if (space && count < 63) {
                s.append(' ');
                count++;
            }
            space = false;
            s.appendCodePoint(cp);
            count++;
        }
        return s.toString();
    }
}
