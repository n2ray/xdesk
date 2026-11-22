package com.termux.x11.screenshare;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Single inbound AES-256-CM/HMAC-SHA1-80 stream, RFC 3711 and RFC 6188. */
final class SrtpStream {
    private final SecretKeySpec encryptionKey;
    private final byte[] sessionSalt;
    private final Mac auth;
    private final Cipher ctr;
    private long newest = -1;
    private int streamId;
    int rejected;

    SrtpStream(byte[] material) throws GeneralSecurityException {
        if (material.length != 46) throw new IllegalArgumentException("expected 32+14 key bytes");
        ctr = Cipher.getInstance("AES/CTR/NoPadding");
        SecretKeySpec master = new SecretKeySpec(material, 0, 32, "AES");
        byte[] nonce = new byte[16];
        System.arraycopy(material, 32, nonce, 0, 14);
        encryptionKey = new SecretKeySpec(expand(master, nonce, 0, 32), "AES");
        auth = Mac.getInstance("HmacSHA1");
        auth.init(new SecretKeySpec(expand(master, nonce, 1, 20), "HmacSHA1"));
        sessionSalt = Arrays.copyOf(expand(master, nonce, 2, 14), 16);
    }

    static byte[] expand(SecretKeySpec master, byte[] salt, int label, int size)
            throws GeneralSecurityException {
        byte[] initial = salt.clone();
        initial[7] ^= (byte)label;
        Cipher derive = Cipher.getInstance("AES/CTR/NoPadding");
        derive.init(Cipher.ENCRYPT_MODE, master, new IvParameterSpec(initial));
        return derive.doFinal(new byte[size]);
    }

    static final class Payload {
        final long index;
        final byte[] data;
        Payload(long index, byte[] data) { this.index = index; this.data = data; }
    }

    Payload open(byte[] packet, int length) throws GeneralSecurityException {
        final int end = length - 10;
        if (length > packet.length || end <= 12 || (packet[0] & 0xc0) != 0x80) return null;
        ByteBuffer header = ByteBuffer.wrap(packet);
        int start = 12 + 4 * (packet[0] & 15);
        if ((packet[0] & 16) != 0) {
            if (start + 4 > end) return null;
            start += 4 * (1 + Short.toUnsignedInt(header.getShort(start + 2)));
        }
        if (start >= end) return null;
        int id = header.getInt(8);
        if (newest >= 0 && streamId != id) return null;
        int seq = Short.toUnsignedInt(header.getShort(2));
        long index = newest < 0 ? seq : (newest & ~65535L) + seq;
        if (index - newest > 32768 && newest >= 0) index -= 65536;
        if (newest - index > 32768) index += 65536;
        if (index < 0 || index >>> 48 != 0) return null;
        byte[] roc = ByteBuffer.allocate(4).putInt((int)(index >>> 16)).array();
        auth.update(packet, 0, end);
        byte[] tag = Arrays.copyOf(auth.doFinal(roc), 10);
        if (!MessageDigest.isEqual(tag, Arrays.copyOfRange(packet, end, length))) {
            rejected++;
            return null;
        }
        ByteBuffer nonce = ByteBuffer.allocate(16);
        nonce.putInt(4, id).putLong(8, index << 16);
        for (int i = 0; i < sessionSalt.length; i++) nonce.array()[i] ^= sessionSalt[i];
        ctr.init(Cipher.DECRYPT_MODE, encryptionKey, new IvParameterSpec(nonce.array()));
        byte[] data = ctr.doFinal(packet, start, end - start);
        if ((packet[0] & 32) != 0) {
            int padding = data[data.length - 1] & 255;
            if (padding == 0 || padding >= data.length) return null;
            data = Arrays.copyOf(data, data.length - padding);
        }
        newest = Math.max(newest, index);
        streamId = id;
        return new Payload(index, data);
    }
}
