package com.termux.x11;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;

/**
 * A self-signed X.509 certificate for XDesk's adb key, written by hand so that a whole certificate library is not
 * shipped for this. adbd only looks at the public key in it. Far-off dates, because it lasts as long as the pairing.
 */
final class AdbCertificate {
    private static final int INTEGER = 0x02, BIT_STRING = 0x03, NULL = 0x05, OID = 0x06, UTF8 = 0x0C, UTC_TIME = 0x17;
    private static final int SEQUENCE = 0x30, SET = 0x31, VERSION = 0xA0;
    /** 1.2.840.113549.1.1.11 */
    private static final byte[] SHA256_WITH_RSA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    /** 2.5.4.3 */
    private static final byte[] COMMON_NAME = {0x55, 0x04, 0x03};

    private AdbCertificate() { }

    static byte[] selfSigned(KeyPair keys) throws GeneralSecurityException {
        byte[] algorithm = der(SEQUENCE, der(OID, SHA256_WITH_RSA), der(NULL));
        byte[] name = der(SEQUENCE, der(SET, der(SEQUENCE, der(OID, COMMON_NAME), der(UTF8, ascii("XDesk")))));
        byte[] validity = der(SEQUENCE, der(UTC_TIME, ascii("200101000000Z")), der(UTC_TIME, ascii("491231235959Z")));
        byte[] tbs = der(SEQUENCE,
            der(VERSION, der(INTEGER, new byte[]{2})),
            der(INTEGER, new byte[]{1}),
            algorithm, name, validity, name,
            keys.getPublic().getEncoded());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] bits = new byte[signature.length + 1];  // first byte: no unused bits
        System.arraycopy(signature, 0, bits, 1, signature.length);
        return der(SEQUENCE, tbs, algorithm, der(BIT_STRING, bits));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] der(int tag, byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) length += p.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + 4);
        out.write(tag);
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length);
        }
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}
