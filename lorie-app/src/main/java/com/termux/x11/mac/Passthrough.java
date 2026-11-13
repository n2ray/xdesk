package com.termux.x11.mac;

/**
 * A Mac's video forwarded as it comes, still compressed, to where the screen's picture goes: a wireless receiver
 * that decodes HEVC itself (DISPLAY.md §3.0, §3.4). The phone then decodes nothing and encodes nothing for this
 * screen. Frames and parameter sets are Annex B (start codes). Called on the connection's reader thread, except
 * end(), which comes from the UI thread.
 */
public interface Passthrough {
    interface Provider {
        /** A passthrough to the receiver of this display, or null when there is none that takes HEVC this way. */
        Passthrough open(int displayId, Runnable askKeyframe);
    }

    /** The stream (re)starts with these VPS/SPS/PPS. False: this stream can't go through (too big): decode it. */
    boolean parameters(byte[] annexB, int width, int height);

    /** False: the picture waits for a keyframe (the caller asks the Mac for one). */
    boolean frame(byte[] annexB, int length, long ptsUs, boolean keyframe);

    /** The cursor's PNG; sizes and hot spot in fractions of the picture's width. */
    void cursorShape(byte[] png, int offset, int length, float width, float height, float hotX, float hotY);

    /** Where the Mac's pointer is, in fractions of the picture. */
    void cursorPosition(float x, float y, boolean inside);

    /** False once the receiver went away or can't take it any more: the caller decodes again. */
    boolean alive();

    /** The Mac leaves this display: the phone's own stream comes back, from a keyframe. */
    void end();
}
