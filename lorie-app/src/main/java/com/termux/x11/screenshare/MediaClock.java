// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** RFC 3550 sender reports relate independent RTP clocks; audio presentation anchors local time. */
final class MediaClock {
    static final class Reports {
        private final SecretKeySpec key;
        private final byte[] salt;
        private final Mac auth;
        private int newest = -1;

        Reports(byte[] material) throws GeneralSecurityException {
            SecretKeySpec master = new SecretKeySpec(material, 0, 32, "AES");
            byte[] base = Arrays.copyOfRange(material, 32, 48);
            key = new SecretKeySpec(SrtpStream.expand(master, base, 3, 32), "AES");
            auth = Mac.getInstance("HmacSHA1");
            auth.init(new SecretKeySpec(SrtpStream.expand(master, base, 4, 20), "HmacSHA1"));
            salt = Arrays.copyOf(SrtpStream.expand(master, base, 5, 14), 16);
        }

        void accept(byte[] data, int length, Mapping target) throws GeneralSecurityException {
            if (length < 42 || (data[0] & 0xc0) != 0x80 || (data[1] & 255) != 200) return;
            ByteBuffer bytes = ByteBuffer.wrap(data, 0, length);
            int firstLength = 4 * (1 + Short.toUnsignedInt(bytes.getShort(2)));
            int end = length - 14;
            if (firstLength < 28 || firstLength > end) return;
            int trailer = bytes.getInt(end);
            int index = trailer & Integer.MAX_VALUE;
            if (index <= newest) return;
            auth.update(data, 0, length - 10);
            if (!MessageDigest.isEqual(Arrays.copyOf(auth.doFinal(), 10),
                    Arrays.copyOfRange(data, length - 10, length))) return;
            int ssrc = bytes.getInt(4);
            byte[] timing = Arrays.copyOfRange(data, 8, 20);
            if (trailer < 0) {
                ByteBuffer nonce = ByteBuffer.wrap(salt.clone());
                nonce.putInt(4, nonce.getInt(4) ^ ssrc);
                nonce.putInt(10, nonce.getInt(10) ^ index);
                Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce.array()));
                timing = cipher.doFinal(timing);
            }
            newest = index;
            ByteBuffer report = ByteBuffer.wrap(timing);
            target.update(ssrc, report.getLong(), report.getInt());
        }
    }

    static final class Mapping {
        private long ntp;
        private int source, rtp;
        private double rate;

        synchronized void update(int ssrc, long nextNtp, int nextRtp) {
            if (ntp != 0 && source == ssrc) {
                double seconds = (nextNtp - ntp) * 0x1.0p-32;
                if (seconds > 0 && seconds < 30) {
                    double measured = (nextRtp - rtp) / seconds;
                    if (measured > 1000 && measured < 200000) rate = measured;
                }
            } else rate = 0;
            source = ssrc;
            ntp = nextNtp;
            rtp = nextRtp;
        }

        synchronized int rateHz() { return (int)Math.round(rate); }

        synchronized long time(int ssrc, int stamp, int fallbackRate) {
            double hz = rate > 0 ? rate : fallbackRate;
            if (ntp == 0 || source != ssrc || hz == 0) return 0;
            return ntp + (long)((stamp - rtp) * 0x1.0p32 / hz);
        }
    }

    final Mapping audio = new Mapping(), video = new Mapping();
    private long audioNtp, speakerNs, observedNs;
    volatile long lastVideoWaitMs;

    synchronized void played(int ssrc, int stamp, long presentationNs) {
        audioNtp = audio.time(ssrc, stamp, 48000);
        speakerNs = presentationNs;
        observedNs = System.nanoTime();
    }

    synchronized long presentAt(int ssrc, int stamp, long now) {
        long imageNtp = video.time(ssrc, stamp, 0);
        if (audioNtp == 0 || imageNtp == 0 || now - observedNs > 1_000_000_000L) return now;
        long delta = (long)((imageNtp - audioNtp) * (1e9 * 0x1.0p-32));
        long target = Math.max(now, Math.min(now + 500_000_000L, speakerNs + delta));
        lastVideoWaitMs = (target - now) / 1000000;
        return target;
    }
}
