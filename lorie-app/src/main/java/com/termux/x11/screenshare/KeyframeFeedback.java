// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Reduced-size RTCP FIR, protected with the negotiated viewer-to-host SRTCP key. */
final class KeyframeFeedback {
    private final int sender;
    private final MacMediaPort socket;
    private final InetAddress host;
    private final SecretKeySpec key;
    private final Mac mac;
    private final byte[] salt;
    private int index;

    KeyframeFeedback(byte[] seed, int sender, MacMediaPort socket, InetAddress host) throws Exception {
        this.sender = sender;
        this.socket = socket;
        this.host = host;
        SecretKeySpec master = new SecretKeySpec(seed, 0, 32, "AES");
        byte[] base = Arrays.copyOfRange(seed, 32, 48);
        key = new SecretKeySpec(SrtpStream.expand(master, base, 3, 32), "AES");
        mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(SrtpStream.expand(master, base, 4, 20), "HmacSHA1"));
        salt = Arrays.copyOf(SrtpStream.expand(master, base, 5, 14),16);
    }

    void request(int mediaSsrc) throws Exception {
        int id = ++index;
        ByteBuffer iv = ByteBuffer.wrap(salt.clone());
        iv.putInt(4, iv.getInt(4) ^ sender);
        iv.putInt(10, iv.getInt(10) ^ id);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv.array()));
        byte[] report = ByteBuffer.allocate(12).putInt(0).putInt(mediaSsrc).putInt(id << 24).array();
        ByteBuffer packet = ByteBuffer.allocate(34);
        packet.putShort((short)0x84ce).putShort((short)4).putInt(sender);
        packet.put(cipher.doFinal(report)).putInt(id | 0x80000000);
        mac.update(packet.array(), 0, packet.position());
        packet.put(mac.doFinal(), 0, 10);
        socket.send(new DatagramPacket(packet.array(), packet.capacity(), host, 5901));
    }
}
