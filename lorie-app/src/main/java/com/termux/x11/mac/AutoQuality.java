package com.termux.x11.mac;

/**
 * XDesk's quick quality modes (Sharp / Balanced / Smooth) and the machine that lowers the quality on its own
 * when the Mac's link report says the connection is behind, and steps back up when it has kept up for a while
 * (SALL-SYNC.md "XDesk's own additions on top of Sall"). Plain Java, no Android imports: the test in /tmp
 * compiles and runs this class alone.
 *
 * The modes are triples of the Mac's menu values (HostSettings.QUALITY_RATES' presets, the capture scale, and
 * prioritizeSpeed): Sharp is the High preset at Retina without encoding speed; Balanced
 * the middle preset (Balanced) at Retina without speed; Smooth the lowest preset (Low — what Sill suggests most
 * often for a weak link) at the standard resolution with speed. modeOf() says which mode the Mac's settings
 * match, CUSTOM when they match none.
 *
 * The machine never reads the clock itself: every event carries now (SystemClock.uptimeMillis on the device),
 * so a test drives it with its own time. Behind continuously for BEHIND_MS lowers (the Mac's suggestion, or one
 * step down); keeping up for the current raiseWait raises one step, never above the user's mode. A raise that
 * goes behind again within FAIL_MS doubles the wait before the next raise (at most RAISE_MAX_MS); a manual pick
 * cancels everything pending and resets the wait.
 */
public final class AutoQuality {
    public static final int SHARP = 0, BALANCED = 1, SMOOTH = 2, MODES = 3, CUSTOM = -1;
    /** lower()/keep() answers: nothing to send, or apply the Mac's suggestion instead of a mode. */
    public static final int NO = -1, SUGGEST = -2;

    /** The preset bits per second of each mode: HostSettings.QUALITY_RATES' High (3), Balanced (2) and Low (0).
     *  High, not Extreme: over Wi-Fi or Tailscale even High is often more than the link carries (owner, 2026-11-15) */
    private static final int[] PRESET = {25_000_000, 15_000_000, 4_000_000};
    /** Each mode's index in HostSettings.QUALITY_RATES: the quality tier for stepping up and down. */
    private static final int[] TIER = {3, 2, 0};

    static final long BEHIND_MS = 5_000, RAISE_BASE_MS = 2 * 60_000, FAIL_MS = 2 * 60_000, RAISE_MAX_MS = 16 * 60_000;

    public static int preset(int mode) { return PRESET[mode]; }
    /** Retina (2) for Sharp and Balanced, the standard resolution (1) for Smooth. */
    public static double scale(int mode) { return mode == SMOOTH ? 1 : 2; }
    /** Encoding speed first only for Smooth (the mode for a weak link). */
    public static boolean speed(int mode) { return mode == SMOOTH; }

    /** One field to send (kind 17): the field's name, its absolute value, and the resolution sent with a bitrate. */
    public static final class Change {
        public final String field;
        public final Object value;
        public final Double captureScale;

        public Change(String field, Object value, Double captureScale) {
            this.field = field;
            this.value = value;
            this.captureScale = captureScale;
        }
    }

    /** Which mode the settings match (all three fields known and equal), or CUSTOM. */
    public static int modeOf(Integer bitrate, Double captureScale, Boolean prioritizeSpeed) {
        for (int m = 0; m < MODES; m++)
            if (bitrate != null && bitrate == PRESET[m] && captureScale != null && captureScale == scale(m)
                    && prioritizeSpeed != null && prioritizeSpeed == speed(m)) return m;
        return CUSTOM;
    }

    /** What the user wants now (a mode, or CUSTOM when the Mac's own settings match none of the three). */
    private int chosen = CUSTOM;
    /** The mode the Mac's settings matched last, and the tier of its bitrate (its preset index, -1 for a custom one). */
    private int mode = CUSTOM, tier = -1;
    /** The Mac is below what the user chose by the machine's own doing: waiting for the link to keep up. */
    private boolean lowered;
    /** When the machine last raised a step (keep()): a behind within FAIL_MS of it doubles the next wait. */
    private long lastRaise = -1;
    private long behindSince = -1, keepSince = -1;
    private long raiseWait = RAISE_BASE_MS;

    /** The user picked a mode (the quick row, Ctrl+Alt+Q): nothing pending of the machine's any more. */
    public void userChose(int mode, int tier, long now) {
        chosen = mode;
        this.mode = mode;
        this.tier = mode >= 0 ? TIER[mode] : tier;
        lowered = false;
        behindSince = keepSince = lastRaise = -1;
        raiseWait = RAISE_BASE_MS;
    }

    /** The machine's change was applied: the Mac's answer (or the timeout) shows what its settings are now. */
    public void applied(int mode, int tier, long now) {
        this.mode = mode;
        this.tier = tier;
        lowered = chosen != CUSTOM && mode != chosen;
    }

    /** The Mac's settings changed without XDesk asking (its own menu, another device): follow them, don't fight. */
    public void external(int mode, int tier, long now) {
        if (mode == this.mode && tier == this.tier) return;
        chosen = mode;
        this.mode = mode;
        this.tier = tier;
        lowered = false;
        keepSince = lastRaise = -1;
    }

    /** The link is behind now: SUGGEST when the Mac's suggestion should be applied, the mode to step down to, or NO. */
    public int lower(long now, boolean suggest) {
        keepSince = -1;
        if (behindSince < 0) {
            behindSince = now;
            return NO;
        }
        if (now - behindSince < BEHIND_MS) return NO;
        behindSince = now;                       // continuous behind lowers again after another BEHIND_MS
        if (lastRaise >= 0) {
            // The last raise went behind again: twice the wait before the next. A raise that held for FAIL_MS was fine.
            raiseWait = now - lastRaise < FAIL_MS ? Math.min(2 * raiseWait, RAISE_MAX_MS) : RAISE_BASE_MS;
            lastRaise = -1;
        }
        if (suggest) return SUGGEST;
        return stepDown();
    }

    /** The link is keeping up: the mode to step back up to (never above the user's), or NO. */
    public int keep(long now) {
        behindSince = -1;                        // the behind run broke: a new one starts from zero
        if (!lowered) return NO;
        if (keepSince < 0) {
            keepSince = now;
            return NO;
        }
        if (now - keepSince < raiseWait) return NO;
        keepSince = now;                         // another step needs another full wait
        int up = stepUp();
        if (up >= 0) lastRaise = now;
        return up;
    }

    /** One step down from the applied tier: High and above to Balanced, Balanced and Efficient to Smooth. */
    private int stepDown() {
        if (tier < 0) return NO;
        if (tier > TIER[BALANCED]) return BALANCED;
        if (tier > TIER[SMOOTH]) return SMOOTH;
        return NO;
    }

    /** One step up from the applied tier, capped at the user's mode. */
    private int stepUp() {
        if (chosen == CUSTOM || tier < 0) return NO;
        int target = tier < TIER[BALANCED] ? BALANCED : tier < TIER[SHARP] ? SHARP : NO;
        return target != NO && TIER[target] <= TIER[chosen] ? target : NO;
    }
}
