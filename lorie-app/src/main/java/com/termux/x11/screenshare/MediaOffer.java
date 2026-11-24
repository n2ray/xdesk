// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11.screenshare;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Encode the editable M4 profile. All per-connection fields are assigned during construction. */
final class MediaOffer {
    private final JSONObject profile;
    /** HEVC Main 4:2:0 instead of H.264 (ScreenShareSession picks it): payload 100, two formats, parameters in band. */
    boolean hevc;
    private static final byte[] HEVC_OPTIONS =
            "FLS;LF:-1;POS:5;EOD:1;HTS:2;RR:3;POSE:4;AR:16/9,5/8;XR:16/9,5/8;"
                    .getBytes(StandardCharsets.US_ASCII);

    MediaOffer(byte[] json) throws Exception {
        profile = new JSONObject(new String(json, StandardCharsets.UTF_8));
    }

    private static class Message extends ByteArrayOutputStream {
        void varint(long value) {
            while ((value & ~127L) != 0) {
                write((int)(value & 127) | 128);
                value >>>= 7;
            }
            write((int)value);
        }
        Message number(int field, long value) {
            varint(field << 3);
            varint(value);
            return this;
        }
        Message data(int field, byte[] data) {
            varint(field << 3 | 2);
            varint(data.length);
            write(data, 0, data.length);
            return this;
        }
        Message nested(int field, Message value) {
            return data(field, value.toByteArray());
        }
    }

    private Message video(JSONObject cfg, long ssrc, int width, int height) throws Exception {
        Message codec = new Message().number(1, hevc ? 100 : cfg.getInt("payloadType"));
        JSONArray formats = cfg.getJSONArray("formats");
        for (int i = 0; i < (hevc ? 2 : formats.length()); i++) {
            Message format = new Message().number(1, 1).number(2, formats.getInt(i))
                    .number(3, cfg.getInt("formatField3")).number(4, 0);
            codec.nested(2, format);
        }
        StringBuilder options = new StringBuilder();
        JSONArray tokens = cfg.getJSONArray("codecOptions");
        for (int i = 0; i < tokens.length(); i++) options.append(tokens.getString(i)).append(';');
        // 14 asks for VPS/SPS/PPS in band; H.264 takes an image description (avcC)
        codec.data(3, hevc ? HEVC_OPTIONS : options.toString().getBytes(StandardCharsets.US_ASCII))
                .number(4, hevc ? 14 : 1);
        return new Message().number(1, ssrc).number(2, 0).nested(3, codec)
                .number(4, width).number(5, height).number(6, cfg.getInt("tiles"))
                .number(7, 1).number(8, hevc ? 1 : cfg.getInt("field8")).number(9, 1).number(12, 1);
    }

    byte[] create(boolean picture, long ssrc, int width, int height) throws Exception {
        JSONObject cfg = profile.getJSONObject(picture ? "video" : "audio");
        Message desc = picture ? video(cfg, ssrc, width, height)
                : new Message().number(1, ssrc).number(2, 0).number(3, 0)
                    .number(4, cfg.getInt("field4")).number(5, 0).number(6, 0);
        Message offer = new Message().number(1, 1).number(2, 1).nested(picture ? 5 : 3, desc)
                .data(6, profile.getString("negotiatorVersion").getBytes(StandardCharsets.UTF_8))
                .number(8, 0);
        JSONObject modes = profile.getJSONObject("bandwidthModes");
        JSONArray order = profile.getJSONArray(picture ? "videoModeOrder" : "audioModeOrder");
        for (int i = 0; i < order.length(); i++) {
            JSONArray values = modes.getJSONArray(order.getString(i));
            Message tier = new Message();
            for (int j = 0; j < values.length(); j++) tier.number(j + 1, values.getLong(j));
            offer.nested(9, tier);
        }
        offer.number(13, System.currentTimeMillis()*1000000L).number(14, 2).number(16, 0);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(compressed)) { offer.writeTo(z); }
        Message endpoint = new Message().number(1, 0).number(2, 1)
                .data(3, "Android-arm64".getBytes(StandardCharsets.US_ASCII))
                .data(4, "1.0.0".getBytes(StandardCharsets.US_ASCII)).data(5, new byte[]{48});
        OfferDictionary dict = new OfferDictionary();
        dict.number("avcMediaStreamNegotiatorMode", cfg.getInt("mode"));
        dict.text("avcMediaStreamOptionCallID", UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT));
        dict.bytes("avcMediaStreamOptionRemoteEndpointInfo", endpoint.toByteArray());
        dict.bytes("avcMediaStreamNegotiatorMediaBlob", compressed.toByteArray());
        return dict.serialize();
    }

    /** Binary plist with one root dictionary and ASCII, data or integer values. */
    private static final class OfferDictionary {
        final List<byte[]> objects = new ArrayList<>();
        private static byte[] scalar(int kind, byte[] value) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(body);
            out.writeByte(kind | Math.min(value.length, 15));
            if (value.length >= 15) {
                out.writeByte(0x12);
                out.writeInt(value.length);
            }
            out.write(value);
            return body.toByteArray();
        }
        void entry(String key, byte[] value) throws IOException {
            objects.add(scalar(0x50, key.getBytes(StandardCharsets.US_ASCII)));
            objects.add(value);
        }
        void number(String key, int value) throws IOException { entry(key, new byte[]{0x10, (byte)value}); }
        void bytes(String key, byte[] value) throws IOException { entry(key, scalar(0x40, value)); }
        void text(String key, String value) throws IOException {
            entry(key, scalar(0x50, value.getBytes(StandardCharsets.US_ASCII)));
        }
        byte[] serialize() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeBytes("bplist00");
            int[] offsets = new int[objects.size()+1];
            offsets[0] = buffer.size();
            out.writeByte(0xd0 | objects.size()/2);
            for (int ref = 1; ref <= objects.size(); ref += 2) out.writeByte(ref);
            for (int ref = 2; ref <= objects.size(); ref += 2) out.writeByte(ref);
            for (int i=0; i<objects.size(); i++) {
                offsets[i+1] = buffer.size();
                out.write(objects.get(i));
            }
            int table = buffer.size();
            for (int offset : offsets) out.writeInt(offset);
            out.write(new byte[6]);
            out.writeByte(4);
            out.writeByte(1);
            out.writeLong(offsets.length);
            out.writeLong(0);
            out.writeLong(table);
            return buffer.toByteArray();
        }
    }
}
