package com.termux.x11;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The on-screen gamepad's layout (GAMEPAD.md §6.3), after nx2android's: a control's centre is x screen heights from its
 * anchor (the left edge, the middle or the right edge, so a thumb reaches as far from the edge on any width) and y of the
 * screen's height down; sizes are in screen heights, so round controls stay round. A layout keeps, per control, where the
 * player put it (as fractions of the width and height), its size factor and whether it is hidden. No View in here.
 */
final class GamepadLayout {
    static final int STICK_L = 0, STICK_R = 1, DPAD = 2, BUTTON = 3, TRIGGER = 4, MENU = 5;

    /** Button numbers (GAMEPAD.md §4): bit n-1 of the state. */
    static final int A = 1, B = 2, X = 4, Y = 5, LB = 7, RB = 8, L2 = 9, R2 = 10, BACK = 11, START = 12, HOME = 13, L3 = 14, R3 = 15;

    static final class Control {
        final String id, label;
        final int kind, button;
        final float anchor, x, y, w, h;

        Control(String id, int kind, float anchor, float x, float y, float w, float h, int button, String label) {
            this.id = id;
            this.kind = kind;
            this.anchor = anchor;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.button = button;
            this.label = label;
        }

        boolean round() {
            return w == h || kind == MENU;
        }

        boolean face() {
            return kind == BUTTON && (button == A || button == B || button == X || button == Y);
        }
    }

    /** Laid out on a 20:9 phone held sideways, where a face button is about 10 mm. The face buttons in the Xbox places: Y up, X left, B right, A down. */
    static final Control[] CONTROLS = {
        new Control("stick_l", STICK_L, 0, 0.32f, 0.60f, 0.44f, 0.44f, 0, ""),
        new Control("dpad", DPAD, 0, 0.78f, 0.80f, 0.32f, 0.32f, 0, ""),
        new Control("stick_r", STICK_R, 1, -0.80f, 0.80f, 0.36f, 0.36f, 0, ""),
        new Control("a", BUTTON, 1, -0.36f, 0.77f, 0.19f, 0.19f, A, "A"),
        new Control("b", BUTTON, 1, -0.19f, 0.60f, 0.19f, 0.19f, B, "B"),
        new Control("x", BUTTON, 1, -0.53f, 0.60f, 0.19f, 0.19f, X, "X"),
        new Control("y", BUTTON, 1, -0.36f, 0.43f, 0.19f, 0.19f, Y, "Y"),
        new Control("lt", TRIGGER, 0, 0.17f, 0.17f, 0.28f, 0.13f, L2, "LT"),
        new Control("lb", BUTTON, 0, 0.50f, 0.17f, 0.30f, 0.13f, LB, "LB"),
        new Control("rt", TRIGGER, 1, -0.17f, 0.17f, 0.28f, 0.13f, R2, "RT"),
        new Control("rb", BUTTON, 1, -0.50f, 0.17f, 0.30f, 0.13f, RB, "RB"),
        new Control("back", BUTTON, 0.5f, -0.22f, 0.22f, 0.11f, 0.11f, BACK, "⧉"),
        new Control("start", BUTTON, 0.5f, 0.22f, 0.22f, 0.11f, 0.11f, START, "≡"),
        new Control("home", BUTTON, 0.5f, 0f, 0.42f, 0.12f, 0.12f, HOME, "⌂"),
        new Control("l3", BUTTON, 0, 0.68f, 0.45f, 0.12f, 0.12f, L3, "L3"),
        new Control("r3", BUTTON, 1, -0.72f, 0.50f, 0.12f, 0.12f, R3, "R3"),
        new Control("menu", MENU, 0.5f, 0f, 0.06f, 0.16f, 0.09f, 0, "⋯"),
    };
    static final int N = CONTROLS.length;

    static int index(String id) {
        for (int i = 0; i < N; i++) if (CONTROLS[i].id.equals(id)) return i;
        return -1;
    }

    /** A face button stays at least this wide, on any screen. */
    static final float MIN_MM = 10f;
    static final float SCALE_MIN = 0.5f, SCALE_MAX = 2f, OPACITY_MIN = 0.2f, OPACITY_MAX = 0.8f;
    /** A small button still takes touches this far from its centre, in screen heights. */
    static final float REACH_MIN = 0.045f;
    /** Dragged controls land on multiples of this, in screen heights. */
    static final float GRID = 0.02f;
    static final float DEAD_ZONE = 0.10f;
    /** No direction this near the d-pad's centre, of its half size. */
    static final float DPAD_CENTRE = 0.25f;

    static final String STANDARD = "standard", SIMPLE = "simple";
    private static final int VERSION = 1;

    String id, name;
    final boolean[] moved = new boolean[N], hidden = new boolean[N];
    final float[] fx = new float[N], fy = new float[N], scale = new float[N];
    float opacity = 0.5f;
    boolean floatingSticks = true;

    GamepadLayout(String id, String name) {
        this.id = id;
        this.name = name;
        reset();
    }

    /** Every control where the layout puts it, as it came. */
    void reset() {
        for (int i = 0; i < N; i++) {
            moved[i] = false;
            hidden[i] = false;
            scale[i] = 1f;
        }
        if (SIMPLE.equals(id)) {
            for (String gone : new String[]{"stick_r", "lt", "rt", "back", "home", "l3", "r3"}) hidden[index(gone)] = true;
        }
        opacity = 0.5f;
        floatingSticks = true;
    }

    static boolean builtIn(String id) {
        return STANDARD.equals(id) || SIMPLE.equals(id);
    }

    GamepadLayout copy(String newId, String newName) {
        GamepadLayout c = new GamepadLayout(newId, newName);
        System.arraycopy(moved, 0, c.moved, 0, N);
        System.arraycopy(hidden, 0, c.hidden, 0, N);
        System.arraycopy(fx, 0, c.fx, 0, N);
        System.arraycopy(fy, 0, c.fy, 0, N);
        System.arraycopy(scale, 0, c.scale, 0, N);
        c.opacity = opacity;
        c.floatingSticks = floatingSticks;
        return c;
    }

    /** Takes everything but the id and name from another (the editor's Cancel). */
    void set(GamepadLayout from) {
        System.arraycopy(from.moved, 0, moved, 0, N);
        System.arraycopy(from.hidden, 0, hidden, 0, N);
        System.arraycopy(from.fx, 0, fx, 0, N);
        System.arraycopy(from.fy, 0, fy, 0, N);
        System.arraycopy(from.scale, 0, scale, 0, N);
        opacity = from.opacity;
        floatingSticks = from.floatingSticks;
    }

    // ---- where things are, in pixels of a W x H screen ----

    /** All controls larger on a small screen, so a face button is at least MIN_MM wide. ydpi: the screen's. */
    static float sizeFactor(float H, float ydpi) {
        if (H <= 0 || ydpi <= 0) return 1f;
        float faceMm = CONTROLS[index("a")].w * H / ydpi * 25.4f;
        return faceMm >= MIN_MM ? 1f : MIN_MM / faceMm;
    }

    /** Control i's centre x, y and half its width and height. */
    void box(int i, float W, float H, float factor, float[] b) {
        Control c = CONTROLS[i];
        if (moved[i]) {
            b[0] = fx[i] * W;
            b[1] = fy[i] * H;
        } else {
            b[0] = c.anchor * W + c.x * H;
            b[1] = c.y * H;
        }
        b[2] = c.w * H * factor * scale[i] / 2;
        b[3] = c.h * H * factor * scale[i] / 2;
    }

    static float corner(int i, float[] b) {
        float m = Math.min(b[2], b[3]);
        return CONTROLS[i].round() || CONTROLS[i].kind == DPAD ? m : m * 0.35f;
    }

    /** Signed distance from (px, py) to control i's edge, negative inside. */
    static float sdf(int i, float[] b, float px, float py) {
        float rad = corner(i, b);
        float qx = Math.abs(px - b[0]) - b[2] + rad, qy = Math.abs(py - b[1]) - b[3] + rad;
        float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - rad;
    }

    /** How far outside its edge a control still takes a touch: a button a quarter of its size (at least REACH_MIN), a stick or the d-pad half. */
    static float reach(int i, float[] b, float H, float factor) {
        float m = Math.min(b[2], b[3]);
        int k = CONTROLS[i].kind;
        if (k == BUTTON || k == TRIGGER || k == MENU) return Math.max(m * 0.25f, REACH_MIN * H * factor - m);
        return m * 0.5f;
    }

    /**
     * The control a touch at (px, py) is for: the one it is deepest in, or else the nearest within reach. -1 for none.
     * editing: hidden ones too (they are shown faintly, to bring them back).
     */
    int hit(float px, float py, float W, float H, float factor, boolean editing, float[] tmp) {
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            if (hidden[i] && !editing) continue;
            box(i, W, H, factor, tmp);
            float d = sdf(i, tmp, px, py);
            if (d > reach(i, tmp, H, factor)) continue;
            // Inside beats near; among those inside, the smaller control (a button over a stick's edge)
            float score = d < 0 ? -1e6f + Math.min(tmp[2], tmp[3]) : d;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** A dragged centre on one axis: kept on the screen, on the grid when it fits. */
    static float dragPos(float p, float half, float extent, float step) {
        float lo = Math.min(half, extent / 2), hi = extent - lo;
        if (step > 0) {
            float first = (float) Math.ceil(lo / step), last = (float) Math.floor(hi / step);
            if (first <= last) return Math.min(Math.max(Math.round(p / step), first), last) * step;
        }
        return Math.min(Math.max(p, lo), hi);
    }

    /** Moves control i's centre to (px, py) pixels, on the grid. */
    void moveTo(int i, float px, float py, float W, float H, float factor) {
        float[] b = new float[4];
        box(i, W, H, factor, b);
        moved[i] = true;
        fx[i] = dragPos(px, b[2], W, GRID * H) / W;
        fy[i] = dragPos(py, b[3], H, GRID * H) / H;
    }

    // ---- input ----

    /** The hat (GAMEPAD.md §4: 0 up, clockwise, 8 none) for an offset from the d-pad's centre; half: its half size. */
    static int hat(float dx, float dy, float half) {
        if (Math.hypot(dx, dy) < half * DPAD_CENTRE) return GamepadHid.HAT_NONE;
        double a = Math.toDegrees(Math.atan2(dx, -dy));   // 0 up, 90 right
        if (a < 0) a += 360;
        return (int) Math.floor((a + 22.5) / 45) % 8;
    }

    /** A stick's offset (in its radii) as the two axis values, -32767..32767: round, with a dead zone. */
    static void stick(float ux, float uy, int[] out) {
        float len = (float) Math.hypot(ux, uy);
        if (len < DEAD_ZONE) {
            out[0] = out[1] = 0;
            return;
        }
        float m = Math.min(1f, (len - DEAD_ZONE) / (1f - DEAD_ZONE)) / len;
        out[0] = Math.round(ux * m * 32767);
        out[1] = Math.round(uy * m * 32767);
    }

    // ---- saved as JSON ----

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("v", VERSION);
        o.put("id", id);
        o.put("name", name);
        o.put("opacity", opacity);
        o.put("floating", floatingSticks);
        JSONObject cs = new JSONObject();
        for (int i = 0; i < N; i++) {
            if (!moved[i] && !hidden[i] && scale[i] == 1f) continue;
            JSONObject c = new JSONObject();
            if (moved[i]) {
                c.put("x", fx[i]);
                c.put("y", fy[i]);
            }
            if (scale[i] != 1f) c.put("s", scale[i]);
            if (hidden[i]) c.put("hidden", true);
            cs.put(CONTROLS[i].id, c);
        }
        o.put("controls", cs);
        return o;
    }

    /** Throws for what is not a layout of this version. Controls it does not know are left out, missing ones stay as they come. */
    static GamepadLayout fromJson(JSONObject o) throws JSONException {
        if (o.getInt("v") != VERSION) throw new JSONException("version " + o.optInt("v"));
        GamepadLayout l = new GamepadLayout(o.getString("id"), o.getString("name"));
        // Saved controls replace the built-in hidden set entirely
        for (int i = 0; i < N; i++) l.hidden[i] = false;
        l.opacity = clamp((float) o.optDouble("opacity", 0.5), OPACITY_MIN, OPACITY_MAX);
        l.floatingSticks = o.optBoolean("floating", true);
        JSONObject cs = o.optJSONObject("controls");
        if (cs != null) {
            for (int i = 0; i < N; i++) {
                JSONObject c = cs.optJSONObject(CONTROLS[i].id);
                if (c == null) continue;
                if (c.has("x") && c.has("y")) {
                    l.moved[i] = true;
                    l.fx[i] = clamp((float) c.getDouble("x"), 0, 1);
                    l.fy[i] = clamp((float) c.getDouble("y"), 0, 1);
                }
                l.scale[i] = clamp((float) c.optDouble("s", 1), SCALE_MIN, SCALE_MAX);
                l.hidden[i] = c.optBoolean("hidden", false) && CONTROLS[i].kind != MENU;
            }
        }
        return l;
    }

    static JSONArray toJson(java.util.List<GamepadLayout> all) throws JSONException {
        JSONArray a = new JSONArray();
        for (GamepadLayout l : all) a.put(l.toJson());
        return a;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
