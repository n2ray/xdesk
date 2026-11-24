// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * HEVC Main access units from the RTP of one stream, one tile: the M4 sends RFC 7798 AP and FU without DONL.
 * From n2ray/xdesk-mac-screen-sharing de55ac3 (android-lite).
 */
final class HevcPicture {
    private byte[] vps, sps, pps;
    private static final byte[] START = {0,0,0,1};
    private static final int LIMIT = 4 << 20;
    private static final class Bytes extends ByteArrayOutputStream {
        byte[] bytes() { return buf; }
    }
    private final Bytes frame = new Bytes(), fragment = new Bytes();
    boolean key, slice;
    private boolean fragmented;

    byte[] bytes() { return frame.bytes(); }
    int size() { return frame.size(); }

    boolean assemble(Iterable<byte[]> packets) throws IOException {
        frame.reset();
        key = slice = fragmented = false;
        for (byte[] p : packets) {
            if (p.length < 2) return false;
            int type = (p[0] >> 1) & 63;
            if (fragmented && type != 49) return false;
            if (type == 48) {
                int at = 2;
                while (at < p.length) {
                    if (at + 2 > p.length) return false;
                    int n = (p[at] & 255) * 256 + (p[at + 1] & 255);
                    at += 2;
                    if (n < 2 || n > p.length - at || !nal(p, at, n)) return false;
                    at += n;
                }
            } else if (type == 49) {
                if (p.length < 4) return false;
                int flags = p[2] & 255;
                if ((flags & 128) != 0) {
                    if (fragmented) return false;
                    fragmented = true;
                    fragment.reset();
                    fragment.write((p[0] & 129) | ((flags & 63) << 1));
                    fragment.write(p[1]);
                }
                if (!fragmented || fragment.size() + p.length - 3 > LIMIT) return false;
                fragment.write(p, 3, p.length - 3);
                if ((flags & 64) != 0) {
                    if (!nal(fragment.bytes(),0,fragment.size())) return false;
                    fragmented = false;
                }
            } else if (!nal(p,0,p.length)) return false;
        }
        return !fragmented;
    }

    private boolean nal(byte[] p, int at, int n) throws IOException {
        int type = (p[at] >> 1) & 63;
        if (type >= 48 || (p[at+1] & 7) == 0) return false;
        if (type == 32) vps = Arrays.copyOfRange(p,at,at+n);
        else if (type == 33) {
            // general_profile_idc in the M4's SPS profile_tier_level; reject RExt/Main10.
            if (n < 5 || (p[at+3] & 31) != 1) throw new IOException("Mac 未协商到 HEVC Main");
            sps = Arrays.copyOfRange(p,at,at+n);
        } else if (type == 34) pps = Arrays.copyOfRange(p,at,at+n);
        else if (type <= 31) {
            if (type >= 16 && type <= 21 && !key) {
                if (vps == null || sps == null || pps == null) return false;
                for (byte[] param : new byte[][]{vps,sps,pps}) append(param,0,param.length);
                key = true;
            }
            if (n + 4 > LIMIT - frame.size()) return false;
            append(p,at,n);
            slice = true;
        }
        return true;
    }
    private void append(byte[] p,int at,int size) {
        frame.write(START,0,4);
        frame.write(p,at,size);
    }
}
