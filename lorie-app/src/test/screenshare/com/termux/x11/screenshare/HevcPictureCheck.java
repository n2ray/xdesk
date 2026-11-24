// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class HevcPictureCheck {
    public static void main(String[] args) throws Exception {
        HevcPicture h = new HevcPicture();
        byte[] ap = {96,1,0,3,64,1,9,0,5,66,1,1,1,8,0,3,68,1,7};
        byte[] start = {98,1,(byte)148,9,8}, end = {98,1,84,7,6};
        if (!h.assemble(List.of(ap,start,end)) || !h.key || !h.slice)
            throw new AssertionError("AP + fragmented IDR");
        byte[] expected={0,0,0,1,64,1,9,0,0,0,1,66,1,1,1,8,0,0,0,1,68,1,7,0,0,0,1,40,1,9,8,7,6};
        if (!Arrays.equals(expected,Arrays.copyOf(h.bytes(),h.size())))
            throw new AssertionError("Annex B");
        if (h.assemble(List.of(start)) || h.assemble(List.of(end)))
            throw new AssertionError("incomplete fragment");
        if (!h.assemble(List.of(new byte[]{2,1,0,3})) || h.key || !h.slice)
            throw new AssertionError("single P slice");
        try {
            h.assemble(List.of(new byte[]{66,1,1,4,8}));
            throw new AssertionError("RExt SPS accepted");
        } catch (IOException expectedRejection) { }
    }
}
