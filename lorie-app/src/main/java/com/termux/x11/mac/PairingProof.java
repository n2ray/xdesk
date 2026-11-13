/*
 * Derived from Sill (https://github.com/Saffsanity/sill, Sources/StreamProtocol/Pairing.swift) and its Android
 * client Sall (https://github.com/n2ray/sall, PairingProof.java). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Sill pairing protocol, derived from Sources/StreamProtocol/Pairing.swift (Apache-2.0). */
final class PairingProof {
    private static final byte[] LABEL = "sill-pair-v1".getBytes(StandardCharsets.UTF_8);
    private static final int[][] DAMM = {
        {0,3,1,7,5,9,8,6,4,2}, {7,0,9,2,1,5,4,8,6,3}, {4,2,0,6,8,7,1,3,5,9},
        {1,7,5,0,9,8,3,4,2,6}, {6,1,2,3,0,4,5,9,7,8}, {3,6,7,4,2,0,9,5,8,1},
        {5,8,6,9,7,2,0,1,3,4}, {8,9,4,5,3,6,2,0,1,7}, {9,4,3,8,6,1,7,2,0,5},
        {2,5,8,1,4,3,6,7,9,0}
    };

    static String normalizeCode(String input) {
        String code = input.replace(" ", "").replace("-", "").replace("\u00a0", "");
        if (code.length() != 12) throw new IllegalArgumentException("A code has 12 digits");
        int check = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = code.charAt(i) - '0';
            if (digit < 0 || digit > 9) throw new IllegalArgumentException("A code has 12 digits");
            check = DAMM[check][digit];
        }
        if (check != 0) throw new IllegalArgumentException("The code has a typo");
        return code;
    }

    static byte[] codeKey(String code, byte[] macFingerprint) throws GeneralSecurityException {
        code = normalizeCode(code);
        if (macFingerprint.length != 32) throw new IllegalArgumentException("Invalid Mac fingerprint");
        byte[] salt = new byte[LABEL.length + macFingerprint.length];
        System.arraycopy(LABEL, 0, salt, 0, LABEL.length);
        System.arraycopy(macFingerprint, 0, salt, LABEL.length, macFingerprint.length);
        PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, 600_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    static byte[] proof(byte[] key, byte[] macFingerprint, byte[] deviceFingerprint, boolean device)
            throws GeneralSecurityException {
        if (macFingerprint.length != 32 || deviceFingerprint.length != 32)
            throw new IllegalArgumentException("Invalid fingerprint");
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(key, "HmacSHA256"));
        hmac.update((device ? "sill-pair-v1 device" : "sill-pair-v1 mac").getBytes(StandardCharsets.UTF_8));
        hmac.update((byte) 0);
        hmac.update(device ? deviceFingerprint : macFingerprint);
        hmac.update(device ? macFingerprint : deviceFingerprint);
        return hmac.doFinal();
    }

    static boolean verifyMac(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
