package com.termux.x11;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The one input choice, 像平板 / 像电脑 (INPUT.md §4), remembered per content: an Android app by its package (the
 * keys AppSession always used, so what was saved stays), a Mac app by its bundle id, the X desktop as one setting.
 * What a choice means for each kind of surface is the Router's (Router.likeTablet): only targets that take touches
 * act on it, so a Mac or an X window is 像电脑 whatever is stored.
 */
final class InputModes {
    private static final String X_DESKTOP = "x11";
    private static InputModes instance;
    private final SharedPreferences prefs;

    private InputModes(Context c) {
        prefs = c.getSharedPreferences("app_touch_mode", Context.MODE_PRIVATE);
    }

    static synchronized InputModes get(Context c) {
        if (instance == null) instance = new InputModes(c.getApplicationContext());
        return instance;
    }

    /** The key a content's choice is stored under: an Android app's package, a Mac app's bundle id, "x11". */
    static String storeKey(String contentKey) {
        if (contentKey == null) return X_DESKTOP;
        if (contentKey.startsWith(MacSource.PREFIX)) {
            int at = contentKey.indexOf(":app:");
            // A window's key has no bundle id: the Mac's windows share one choice
            return at >= 0 ? "mac:" + contentKey.substring(at + 5) : "mac";
        }
        if (ChooserView.isAppKey(contentKey)) {
            String component = ChooserView.componentOf(contentKey);
            int slash = component.indexOf('/');
            return slash >= 0 ? component.substring(0, slash) : component;
        }
        // Termux sessions and Linux programs (windows of the X desktop): the X desktop's one choice
        return X_DESKTOP;
    }

    /** An Android app's choice; default 像电脑. */
    boolean tabletPackage(String pkg) {
        return pkg != null && prefs.getBoolean(pkg, false);
    }

    void setPackage(String pkg, boolean tablet) {
        prefs.edit().putBoolean(pkg, tablet).apply();
    }

    boolean tablet(String contentKey) {
        return prefs.getBoolean(storeKey(contentKey), false);
    }
}
