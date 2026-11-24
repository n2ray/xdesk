package com.termux.x11.screenshare;

import android.graphics.Bitmap;
import android.util.LruCache;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** One cache per encrypted connection. Mac sends BGRX pixels followed by a separate alpha plane. */
final class CursorUpdates {
    static final class Shape {
        final Bitmap bitmap;
        final int hotX, hotY;
        Shape(Bitmap bitmap, int hotX, int hotY) {
            this.bitmap = bitmap;
            this.hotX = hotX;
            this.hotY = hotY;
        }
    }

    private final LruCache<Integer, Shape> shapes = new LruCache<Integer, Shape>(8 << 20) {
        @Override protected int sizeOf(Integer id, Shape s) { return 64 + s.bitmap.getByteCount(); }
    };

    void read(ControlReader records, Consumer<Shape> display) throws IOException {
        ByteBuffer b = records.next();
        if (b.remaining() < 4 || b.get() != 0) return;
        b.get();
        int count = b.getShort() & 65535;
        for (int i = 0; i < count; i++) {
            if (b.remaining() < 12) return;
            int x = b.getShort() & 65535, y = b.getShort() & 65535;
            int w = b.getShort() & 65535, h = b.getShort() & 65535;
            int encoding = b.getInt();
            if (encoding != 1104) {
                if (encoding != 1010 && encoding != 1011 && encoding != 1105
                        && encoding != 1107 && encoding != 1109 && encoding != 1110) return;
                if (b.remaining() < 2) return;
                int size = b.getShort() & 65535;
                if (size > b.remaining()) return;
                b.position(b.position() + size);
                continue;
            }
            if (b.remaining() < 8) return;
            int id = b.getInt(), size = b.getInt();
            if (size < 0 || size > (2 << 20)) throw new IOException("Invalid cursor payload size");
            if (size > 0) {
                byte[] compressed = new byte[size];
                int pos = 0;
                while (pos < size) {
                    if (!b.hasRemaining()) b = records.next();
                    int n = Math.min(size - pos, b.remaining());
                    b.get(compressed, pos, n);
                    pos += n;
                }
                shapes.remove(id);
                if (w > 0 && h > 0 && w <= 512 && h <= 512 && x < w && y < h) {
                    Shape shape = decode(w, h, x, y, compressed);
                    if (shape != null) shapes.put(id, shape);
                }
            }
            Shape shape = shapes.get(id);
            if (shape != null) display.accept(shape);
        }
    }

    private static Shape decode(int w, int h, int x, int y, byte[] compressed) {
        int pixels = w * h;
        byte[] raw = new byte[5 * pixels + 1];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int used = 0;
            while (used < raw.length) {
                int n = inflater.inflate(raw, used, raw.length - used);
                if (n == 0) break;
                used += n;
            }
            if (used != pixels * 5) return null;
        } catch (DataFormatException invalid) {
            return null;
        } finally {
            inflater.end();
        }
        int[] colors = new int[pixels];
        for (int i = 0; i < pixels; i++) {
            int p = 4 * i;
            colors[i] = (raw[4 * pixels + i] << 24) | ((raw[p + 2] & 255) << 16)
                    | ((raw[p + 1] & 255) << 8) | (raw[p] & 255);
        }
        return new Shape(Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888), x, y);
    }
}
