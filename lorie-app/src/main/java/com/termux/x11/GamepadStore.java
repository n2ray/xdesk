package com.termux.x11;

import com.termux.x11.app.R;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The gamepad's layouts (GAMEPAD.md §6.5): the two built-in ones, as changed, and the player's own, in one JSON array;
 * which layout each app uses; whether presses tick.
 */
final class GamepadStore {
    private static final String TAG = "XDeskGamepad";
    private static final String PREFS = "gamepad", LAYOUTS = "layouts", FOR = "layout_for_", HAPTICS = "haptics";

    private final SharedPreferences prefs;
    private final List<GamepadLayout> layouts = new ArrayList<>();
    private final String standardName, simpleName;

    GamepadStore(Context c) {
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        standardName = c.getString(R.string.xd_pad_standard);
        simpleName = c.getString(R.string.xd_pad_simple);
        load();
    }

    private void load() {
        layouts.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString(LAYOUTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                try {
                    GamepadLayout l = GamepadLayout.fromJson(a.getJSONObject(i));
                    if (find(l.id) == null) layouts.add(l);
                } catch (JSONException e) {
                    Log.w(TAG, "layout " + i + " left out", e);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "layouts unreadable", e);
        }
        // The built-in ones first, named in the current language
        GamepadLayout simple = find(GamepadLayout.SIMPLE), standard = find(GamepadLayout.STANDARD);
        if (simple == null) simple = new GamepadLayout(GamepadLayout.SIMPLE, simpleName);
        if (standard == null) standard = new GamepadLayout(GamepadLayout.STANDARD, standardName);
        simple.name = simpleName;
        standard.name = standardName;
        layouts.remove(simple);
        layouts.remove(standard);
        layouts.add(0, simple);
        layouts.add(0, standard);
    }

    void save() {
        try {
            prefs.edit().putString(LAYOUTS, GamepadLayout.toJson(layouts).toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "layouts not saved", e);
        }
    }

    List<GamepadLayout> all() {
        return layouts;
    }

    GamepadLayout find(String id) {
        for (GamepadLayout l : layouts) if (l.id.equals(id)) return l;
        return null;
    }

    /** The layout this app had last, or the standard one. pkg may be null. */
    GamepadLayout forApp(String pkg) {
        GamepadLayout l = pkg != null ? find(prefs.getString(FOR + pkg, GamepadLayout.STANDARD)) : null;
        return l != null ? l : layouts.get(0);
    }

    void useFor(String pkg, GamepadLayout l) {
        if (pkg != null) prefs.edit().putString(FOR + pkg, l.id).apply();
    }

    /** A copy of l under a new name, added after the others. */
    GamepadLayout add(GamepadLayout from, String name) {
        GamepadLayout l = from.copy("u" + System.currentTimeMillis(), name);
        layouts.add(l);
        save();
        return l;
    }

    /** Only the player's own. */
    boolean remove(GamepadLayout l) {
        if (GamepadLayout.builtIn(l.id)) return false;
        layouts.remove(l);
        save();
        return true;
    }

    /** A layout given as JSON (the clipboard), added as a new one. Null when it is not one. */
    GamepadLayout paste(String json, String name) {
        if (json == null) return null;
        try {
            GamepadLayout read = GamepadLayout.fromJson(new JSONObject(json.trim()));
            return add(read, name != null ? name : read.name);
        } catch (JSONException e) {
            return null;
        }
    }

    String copyText(GamepadLayout l) {
        try {
            return l.toJson().toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /** "布局 N": the first N no layout has. */
    String nextName(String pattern) {
        for (int n = layouts.size(); ; n++) {
            String name = String.format(pattern, n);
            boolean taken = false;
            for (GamepadLayout l : layouts) taken |= l.name.equals(name);
            if (!taken) return name;
        }
    }

    boolean haptics() {
        return prefs.getBoolean(HAPTICS, true);
    }

    void setHaptics(boolean on) {
        prefs.edit().putBoolean(HAPTICS, on).apply();
    }
}
