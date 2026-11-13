package com.termux.x11.mac;

/** Linux key codes (what XDesk's inputs carry) to USB HID keyboard usages (what Sill's key event takes). */
public final class HidKeys {
    public static final int LEFT_CTRL = 0xe0, LEFT_SHIFT = 0xe1, LEFT_ALT = 0xe2, LEFT_GUI = 0xe3;
    public static final int RIGHT_CTRL = 0xe4, RIGHT_SHIFT = 0xe5, RIGHT_ALT = 0xe6, RIGHT_GUI = 0xe7;
    public static final int TAB = 43, BACKSPACE = 42, ENTER = 40, LEFT_BRACKET = 47, RIGHT_BRACKET = 48;

    private static final int[] TABLE = new int[256];

    static {
        int[] pairs = {
            1, 41, 2, 30, 3, 31, 4, 32, 5, 33, 6, 34, 7, 35, 8, 36, 9, 37, 10, 38, 11, 39, 12, 45, 13, 46, 14, 42, 15, 43,
            16, 20, 17, 26, 18, 8, 19, 21, 20, 23, 21, 28, 22, 24, 23, 12, 24, 18, 25, 19, 26, 47, 27, 48, 28, 40,
            29, LEFT_CTRL, 30, 4, 31, 22, 32, 7, 33, 9, 34, 10, 35, 11, 36, 13, 37, 14, 38, 15, 39, 51, 40, 52, 41, 53,
            42, LEFT_SHIFT, 43, 49, 44, 29, 45, 27, 46, 6, 47, 25, 48, 5, 49, 17, 50, 16, 51, 54, 52, 55, 53, 56,
            54, RIGHT_SHIFT, 55, 85, 56, LEFT_ALT, 57, 44, 58, 57,
            59, 58, 60, 59, 61, 60, 62, 61, 63, 62, 64, 63, 65, 64, 66, 65, 67, 66, 68, 67,
            69, 83, 70, 71, 71, 95, 72, 96, 73, 97, 74, 86, 75, 92, 76, 93, 77, 94, 78, 87, 79, 89, 80, 90, 81, 91,
            82, 98, 83, 99, 86, 100, 87, 68, 88, 69, 96, 88, 97, RIGHT_CTRL, 98, 84, 99, 70, 100, RIGHT_ALT,
            102, 74, 103, 82, 104, 75, 105, 80, 106, 79, 107, 77, 108, 81, 109, 78, 110, 73, 111, 76,
            113, 127, 114, 129, 115, 128, 117, 103, 119, 72, 125, LEFT_GUI, 126, RIGHT_GUI, 127, 101,
            183, 104, 184, 105, 185, 106, 186, 107, 187, 108, 188, 109, 189, 110, 190, 111, 191, 112, 192, 113, 193, 114, 194, 115,
        };
        for (int i = 0; i < pairs.length; i += 2) TABLE[pairs[i]] = pairs[i + 1];
    }

    private HidKeys() { }

    /** 0 for a key Sill can't take. */
    public static int fromLinux(int scan) {
        return scan > 0 && scan < TABLE.length ? TABLE[scan] : 0;
    }

    public static boolean isModifier(int usage) {
        return usage >= LEFT_CTRL && usage <= RIGHT_GUI;
    }

    /** The modifier bit Sill's key event carries for this modifier usage. */
    public static long flag(int usage) {
        switch (usage) {
        case LEFT_CTRL: case RIGHT_CTRL: return MacClient.CONTROL;
        case LEFT_SHIFT: case RIGHT_SHIFT: return MacClient.SHIFT;
        case LEFT_ALT: case RIGHT_ALT: return MacClient.OPTION;
        case LEFT_GUI: case RIGHT_GUI: return MacClient.COMMAND;
        default: return 0;
        }
    }

    /**
     * A PC keyboard on a Mac (INPUT.md §5.5): shortcuts are Ctrl on a PC and ⌘ on a Mac, so Ctrl becomes ⌘ and
     * Win (the key where a Mac keyboard has ⌘) becomes Ctrl; Alt is ⌥ as it is.
     */
    public static int pcToMac(int usage) {
        switch (usage) {
        case LEFT_CTRL: return LEFT_GUI;
        case RIGHT_CTRL: return RIGHT_GUI;
        case LEFT_GUI: return LEFT_CTRL;
        case RIGHT_GUI: return RIGHT_CTRL;
        default: return usage;
        }
    }

    /** The usage for one typed letter or digit, for a shortcut typed as text while ⌘, Ctrl or ⌥ is held. */
    public static int forChar(char c) {
        c = Character.toLowerCase(c);
        if (c >= 'a' && c <= 'z') return 4 + c - 'a';
        if (c >= '1' && c <= '9') return 30 + c - '1';
        if (c == '0') return 39;
        return 0;
    }
}
