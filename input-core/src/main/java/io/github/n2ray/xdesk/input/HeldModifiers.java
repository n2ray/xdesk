package io.github.n2ray.xdesk.input;

/**
 * The physical keyboard's modifiers that went to the target as held, checked against what Android says is held.
 *
 * <p>Keys reach XDesk after the phone's input method (INPUT.md §5.1), which may keep a modifier's release for itself
 * (a Shift tap switches Chinese and English in many of them) or the window may lose focus before it comes. The
 * target would then hold Shift or Ctrl for ever. Every later key carries Android's meta state: a modifier this sent
 * as down that the state no longer has is let go of first.
 */
public final class HeldModifiers {
    // Linux key codes
    public static final int LEFT_CTRL = 29, LEFT_SHIFT = 42, LEFT_ALT = 56, RIGHT_SHIFT = 54, RIGHT_CTRL = 97,
        RIGHT_ALT = 100, LEFT_META = 125, RIGHT_META = 126;
    // android.view.KeyEvent meta state bits
    public static final int META_ALT_LEFT_ON = 0x10, META_ALT_RIGHT_ON = 0x20, META_SHIFT_LEFT_ON = 0x40,
        META_SHIFT_RIGHT_ON = 0x80, META_CTRL_LEFT_ON = 0x2000, META_CTRL_RIGHT_ON = 0x4000,
        META_META_LEFT_ON = 0x20000, META_META_RIGHT_ON = 0x40000;

    private static final int[] SCANS = {LEFT_CTRL, RIGHT_CTRL, LEFT_SHIFT, RIGHT_SHIFT, LEFT_ALT, RIGHT_ALT, LEFT_META, RIGHT_META};
    private static final int[] METAS = {META_CTRL_LEFT_ON, META_CTRL_RIGHT_ON, META_SHIFT_LEFT_ON, META_SHIFT_RIGHT_ON,
        META_ALT_LEFT_ON, META_ALT_RIGHT_ON, META_META_LEFT_ON, META_META_RIGHT_ON};

    public interface Out {
        void release(int scan);
    }

    /** Bits of SCANS sent as down. */
    private int held;

    private static int index(int scan) {
        for (int i = 0; i < SCANS.length; i++) if (SCANS[i] == scan) return i;
        return -1;
    }

    public static boolean isModifier(int scan) {
        return index(scan) >= 0;
    }

    /** A key went to the target. */
    public void sent(int scan, boolean down) {
        int i = index(scan);
        if (i < 0) return;
        if (down) held |= 1 << i;
        else held &= ~(1 << i);
    }

    /**
     * Before a key that is not a modifier goes on: the modifiers sent as held that metaState does not have go up
     * (through out, which must also send the release).
     */
    public void check(int metaState, Out out) {
        if (held == 0) return;
        for (int i = 0; i < SCANS.length; i++) {
            if ((held & 1 << i) == 0 || (metaState & METAS[i]) != 0) continue;
            held &= ~(1 << i);
            out.release(SCANS[i]);
        }
    }

    public void clear() {
        held = 0;
    }
}
