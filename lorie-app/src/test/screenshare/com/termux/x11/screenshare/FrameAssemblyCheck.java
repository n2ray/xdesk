// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The exact Annex B access units AvcReceiver hands the decoder (XDesk's Sink: whole units, parameter sets before an
 * IDR), for H.264 and for HEVC, and a missing fragment dropping the unit. After android-lite's FrameAssemblyCheck
 * (n2ray/xdesk-mac-screen-sharing de55ac3).
 */
public final class FrameAssemblyCheck {
    public static void main(String[] args) throws Exception {
        avc();
        hevc();
        System.out.println("FrameAssemblyCheck ok");
    }

    private static void avc() throws Exception {
        AvcReceiver receiver = new AvcReceiver(new byte[46], new MediaClock());
        byte[][] expected = {
            {0,0,0,1,103,11,0,0,0,1,104,22,0,0,0,1,101,7,8,9},
            {0,0,0,1,65,4}
        };
        List<byte[]> out = new ArrayList<>();
        AvcReceiver.Sink sink = (data, ns, flags, due) -> out.add(data);
        receiver.parts.put(2L, new byte[]{124,69,9});
        receiver.parts.put(0L, new byte[]{24,0,2,103,11,0,2,104,22});
        receiver.parts.put(1L, new byte[]{124,(byte)133,7,8});
        receiver.flush(sink);
        receiver.parts.put(3L, new byte[]{65,4});
        receiver.flush(sink);
        receiver.parts.put(4L, new byte[]{124,(byte)129,1});
        receiver.parts.put(6L, new byte[]{124,65,3});
        receiver.flush(sink);
        if (out.size() != 2 || receiver.lost != 1) throw new AssertionError("H.264 missing-fragment handling");
        for (int i = 0; i < 2; i++)
            if (!Arrays.equals(expected[i], out.get(i))) throw new AssertionError("H.264 Annex B " + i);
    }

    private static void hevc() throws Exception {
        AvcReceiver receiver = new AvcReceiver(new byte[46], new MediaClock());
        receiver.hevc = new HevcPicture();
        List<byte[]> out = new ArrayList<>();
        List<Integer> flags = new ArrayList<>();
        AvcReceiver.Sink sink = (data, ns, f, due) -> { out.add(data); flags.add(f); };
        // A P slice before any keyframe: not sent (the decoder starts at an IDR)
        receiver.wantKeyframe = true;
        receiver.parts.put(0L, new byte[]{2,1,0,3});
        receiver.flush(sink);
        // AP (VPS, SPS, PPS) and a fragmented IDR, packets out of order
        receiver.parts.put(3L, new byte[]{98,1,84,7,6});
        receiver.parts.put(1L, new byte[]{96,1,0,3,64,1,9,0,5,66,1,1,1,8,0,3,68,1,7});
        receiver.parts.put(2L, new byte[]{98,1,(byte)148,9,8});
        receiver.flush(sink);
        receiver.parts.put(4L, new byte[]{2,1,0,3});
        receiver.flush(sink);
        // A fragment lost: the unit goes, a keyframe is wanted
        receiver.parts.put(5L, new byte[]{98,1,(byte)129,1});
        receiver.parts.put(7L, new byte[]{98,1,65,3});
        receiver.flush(sink);
        byte[] idr = {0,0,0,1,64,1,9,0,0,0,1,66,1,1,1,8,0,0,0,1,68,1,7,0,0,0,1,40,1,9,8,7,6};
        if (out.size() != 2) throw new AssertionError("HEVC units sent: " + out.size());
        if (!Arrays.equals(idr, out.get(0)) || flags.get(0) != 1) throw new AssertionError("HEVC IDR with VPS/SPS/PPS");
        if (!Arrays.equals(new byte[]{0,0,0,1,2,1,0,3}, out.get(1)) || flags.get(1) != 0) throw new AssertionError("HEVC P slice");
        if (receiver.lost != 1 || !receiver.wantKeyframe) throw new AssertionError("HEVC missing-fragment handling");
    }
}
