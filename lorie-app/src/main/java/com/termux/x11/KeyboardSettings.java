package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * XDesk's two global keyboard settings, for the code outside XDeskActivity that resolves a device's override
 * (MacInput, MacStore, Devices): a device that follows the global setting uses these. They live in the activity's
 * own preferences, the file Activity.getPreferences uses, so this reads the same file from any context.
 */
public final class KeyboardSettings {
    private static final String TRANSLATE_MODIFIERS = "translate_modifiers", KEYS_VIA_IME = "keys_via_ime";

    private KeyboardSettings() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("com.termux.x11.XDeskActivity", Context.MODE_PRIVATE);
    }

    /** A PC keyboard on a Mac: Ctrl as ⌘ and Alt as ⌥, unless that Mac has a choice of its own. */
    public static boolean translateModifiers(Context c) {
        return prefs(c).getBoolean(TRANSLATE_MODIFIERS, true);
    }

    /** A physical keyboard types through the phone's input method, unless that device has a choice of its own. */
    public static boolean keysViaIme(Context c) {
        return prefs(c).getBoolean(KEYS_VIA_IME, true);
    }
}
