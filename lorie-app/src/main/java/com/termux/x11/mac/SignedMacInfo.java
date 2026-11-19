/*
 * Derived from Sall (https://github.com/n2ray/sall, SignedMacInfo.java), the Android client of Sill
 * (https://github.com/Saffsanity/sill). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Kind 18: the Mac's signed address list. The signature covers the exact info bytes, never a re-encoding of the
 * fields, and the signing key must be the key this session is pinned to; the list is trusted only as a whole.
 */
final class SignedMacInfo {
    private static final byte[] P256_SPKI = {
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x02, 0x01,
            0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00};

    /** The checked contents of one list: enough to store it and to dial it. */
    static final class Info {
        final String macId;
        final int remotePort;
        final double issuedAt;
        final List<MacStore.Address> addresses;

        Info(String macId, int remotePort, double issuedAt, List<MacStore.Address> addresses) {
            this.macId = macId;
            this.remotePort = remotePort;
            this.issuedAt = issuedAt;
            this.addresses = addresses;
        }
    }

    private SignedMacInfo() { }

    /**
     * The envelope from a kind 18 payload, verified against the pin this session is pinned to and decoded. Anything
     * wrong, from the base64 to a host that is not a real address, throws: the caller ignores the whole message.
     */
    static Info parse(byte[] payload, byte[] pin) throws Exception {
        if (payload == null || payload.length > 98304) throw new IllegalArgumentException("Mac info too large");
        JSONObject envelope = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        byte[] bytes = verify(envelope.getString("info"), envelope.getString("key"),
                envelope.getString("sig"), pin);
        // Strict UTF-8 over the exact bytes the signature covers: a decoder that substitutes characters would
        // authenticate one byte sequence and read another.
        String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        JSONObject info = new JSONObject(json);
        String macId = info.getString("macID");
        if (!macId.equals(PairLink.macId(pin))) throw new IllegalArgumentException("Mac info for another Mac");
        double remote = number(info, "remotePort");
        int remotePort = (int) remote;
        if (remote != remotePort || remotePort < 1 || remotePort > 65535)
            throw new IllegalArgumentException("Invalid remote port");
        double issuedAt = number(info, "issuedAt");
        if (!(issuedAt > 0)) throw new IllegalArgumentException("Invalid Mac info time");
        JSONArray list = info.getJSONArray("addresses");
        ArrayList<MacStore.Address> addresses = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.getJSONObject(i);
            String kind = entry.getString("kind");
            if (!MacStore.Address.validKind(kind)) throw new IllegalArgumentException("Invalid address kind");
            int port;
            if (entry.isNull("port")) {
                port = remotePort;
            } else {
                Object value = entry.opt("port");
                if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid address port");
                double p = ((Number) value).doubleValue();
                port = (int) p;
                // Sall drops an entry whose port is not a whole port number, keeps the rest of the list.
                if (!Double.isFinite(p) || p != port || port < 1 || port > 65535) continue;
            }
            MacStore.Address address = address(entry.getString("host"), port, kind);
            if (address != null && !contains(addresses, address)) addresses.add(address);
        }
        return new Info(macId, remotePort, issuedAt, Collections.unmodifiableList(addresses));
    }

    /** Sall's SignedMacInfo.verify: the exact info bytes, signed by the key this session is pinned to. */
    static byte[] verify(String info, String key, String signature, byte[] sessionPin)
            throws GeneralSecurityException {
        if (sessionPin == null || sessionPin.length != 32 || info == null || info.length() > 65536
                || key == null || key.length() > 88 || signature == null || signature.length() > 100)
            throw new SignatureException("Invalid Mac info envelope");
        byte[] bytes = Base64.getDecoder().decode(info), point = Base64.getDecoder().decode(key);
        byte[] der = Base64.getDecoder().decode(signature);
        if (bytes.length == 0 || point.length != 65 || point[0] != 4 || der.length < 8 || der.length > 72)
            throw new SignatureException("Invalid Mac info encoding");
        byte[] spki = ByteBuffer.allocate(P256_SPKI.length + point.length).put(P256_SPKI).put(point).array();
        if (!MessageDigest.isEqual(sessionPin, MessageDigest.getInstance("SHA-256").digest(spki)))
            throw new SignatureException("Mac info signer differs from session");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki)));
        verifier.update(bytes);
        if (!verifier.verify(der)) throw new SignatureException("Invalid Mac info signature");
        return bytes;
    }

    /** Sall's SignedMacInfo.address: the host sanitized, or null when it is not dialable. */
    static MacStore.Address address(String host, int port) {
        return address(host, port, null);
    }

    /**
     * One advertised host, sanitized as Sall's SignedMacInfo.address: no scoped address, no loopback, link-local,
     * any-local or multicast address, no localhost; an IP is stored normalized. Null when it is not dialable.
     */
    static MacStore.Address address(String host, int port, String kind) {
        try {
            if (host == null || host.contains("%")) return null;
            PairLink.Address parsed = PairLink.address(host.contains(":") ? "[" + host + "]" : host);
            if (parsed.port != 0) return null;
            String normalized = parsed.host.toLowerCase(Locale.ROOT);
            if (normalized.equals("localhost") || normalized.equals("localhost.")) return null;
            if (normalized.contains(":") || normalized.matches("[0-9.]+")) {
                InetAddress ip = InetAddress.getByName(normalized);
                if (ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isAnyLocalAddress()
                        || ip.isMulticastAddress()) return null;
                normalized = ip.getHostAddress();
            }
            return new MacStore.Address(normalized, port, kind);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean contains(List<MacStore.Address> addresses, MacStore.Address address) {
        for (MacStore.Address a : addresses) if (a.key().equals(address.key())) return true;
        return false;
    }

    private static double number(JSONObject o, String name) {
        Object value = o.opt(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid Mac info " + name);
        double d = ((Number) value).doubleValue();
        if (!Double.isFinite(d)) throw new IllegalArgumentException("Invalid Mac info " + name);
        return d;
    }
}
