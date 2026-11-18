/*
 * Derived from Sall (https://github.com/n2ray/sall, RecognitionTag.java) and Sill
 * (https://github.com/Saffsanity/sill, Sources/StreamProtocol/Pairing.swift). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sill's Bonjour TXT record key "r": a hint that a discovered Mac is one this device paired with, whatever its
 * Bonjour name. base64url of 12 bytes, a fresh 6-byte nonce and HMAC-SHA256(recognitionKey, "sill-tag-v1" | nonce)
 * truncated to 6 bytes. Replayable, so recognition is never authentication: the home door is still dialled with
 * the saved pin, and only that handshake decides.
 */
final class RecognitionTag {
    private static final String LABEL = "sill-tag-v1";

    private RecognitionTag() { }

    static boolean matches(String tag, byte[] key) {
        if (tag == null || tag.length() != 16 || key == null || key.length != 32) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(tag);
            if (decoded.length != 12 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(tag))
                return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(LABEL.getBytes(StandardCharsets.UTF_8));
            mac.update(decoded, 0, 6);
            byte[] expected = java.util.Arrays.copyOf(mac.doFinal(), 6);
            return MessageDigest.isEqual(expected, java.util.Arrays.copyOfRange(decoded, 6, 12));
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            return false;
        }
    }

    /** A TXT value as a tag: 16 base64url characters, or null. */
    static String fromTxt(byte[] bytes) {
        if (bytes == null || bytes.length != 16) return null;
        for (byte b : bytes) {
            int c = b & 255;
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-' && c != '_')
                return null;
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
