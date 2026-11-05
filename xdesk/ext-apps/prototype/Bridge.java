import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;

/** Turn evcap's text events into real mouse and key events on one display. Runs as the adb shell user. */
public class Bridge {
    static Object im; static Method inject, setDisplayM, setDisplayK, setActionButton;
    static int display, W, H, buttons = 0, meta = 0;
    static float x, y, speed = 2f, wheelStep = 1f, nudge = 0f;
    static boolean real = false, menuOpen = false;
    static final float TOOLBAR_Y = 1440f;
    static long downT = 0;
    static final HashMap<Integer, Integer> K = new HashMap<>();
    static final HashSet<Integer> held = new HashSet<>();

    static void map(int... pairs) { for (int i = 0; i < pairs.length; i += 2) K.put(pairs[i], pairs[i + 1]); }
    static {
        int[] row1 = {16,KeyEvent.KEYCODE_Q,17,KeyEvent.KEYCODE_W,18,KeyEvent.KEYCODE_E,19,KeyEvent.KEYCODE_R,20,KeyEvent.KEYCODE_T,21,KeyEvent.KEYCODE_Y,22,KeyEvent.KEYCODE_U,23,KeyEvent.KEYCODE_I,24,KeyEvent.KEYCODE_O,25,KeyEvent.KEYCODE_P};
        int[] row2 = {30,KeyEvent.KEYCODE_A,31,KeyEvent.KEYCODE_S,32,KeyEvent.KEYCODE_D,33,KeyEvent.KEYCODE_F,34,KeyEvent.KEYCODE_G,35,KeyEvent.KEYCODE_H,36,KeyEvent.KEYCODE_J,37,KeyEvent.KEYCODE_K,38,KeyEvent.KEYCODE_L};
        int[] row3 = {44,KeyEvent.KEYCODE_Z,45,KeyEvent.KEYCODE_X,46,KeyEvent.KEYCODE_C,47,KeyEvent.KEYCODE_V,48,KeyEvent.KEYCODE_B,49,KeyEvent.KEYCODE_N,50,KeyEvent.KEYCODE_M};
        map(row1); map(row2); map(row3);
        for (int i = 1; i <= 9; i++) K.put(i + 1, KeyEvent.KEYCODE_0 + i);
        map(11,KeyEvent.KEYCODE_0, 1,KeyEvent.KEYCODE_ESCAPE, 12,KeyEvent.KEYCODE_MINUS, 13,KeyEvent.KEYCODE_EQUALS, 14,KeyEvent.KEYCODE_DEL, 15,KeyEvent.KEYCODE_TAB,
            26,KeyEvent.KEYCODE_LEFT_BRACKET, 27,KeyEvent.KEYCODE_RIGHT_BRACKET, 28,KeyEvent.KEYCODE_ENTER, 29,KeyEvent.KEYCODE_CTRL_LEFT,
            39,KeyEvent.KEYCODE_SEMICOLON, 40,KeyEvent.KEYCODE_APOSTROPHE, 41,KeyEvent.KEYCODE_GRAVE, 42,KeyEvent.KEYCODE_SHIFT_LEFT, 43,KeyEvent.KEYCODE_BACKSLASH,
            51,KeyEvent.KEYCODE_COMMA, 52,KeyEvent.KEYCODE_PERIOD, 53,KeyEvent.KEYCODE_SLASH, 54,KeyEvent.KEYCODE_SHIFT_RIGHT, 56,KeyEvent.KEYCODE_ALT_LEFT, 57,KeyEvent.KEYCODE_SPACE, 58,KeyEvent.KEYCODE_CAPS_LOCK,
            97,KeyEvent.KEYCODE_CTRL_RIGHT, 100,KeyEvent.KEYCODE_ALT_RIGHT, 102,KeyEvent.KEYCODE_MOVE_HOME, 103,KeyEvent.KEYCODE_DPAD_UP, 104,KeyEvent.KEYCODE_PAGE_UP, 105,KeyEvent.KEYCODE_DPAD_LEFT, 106,KeyEvent.KEYCODE_DPAD_RIGHT,
            107,KeyEvent.KEYCODE_MOVE_END, 108,KeyEvent.KEYCODE_DPAD_DOWN, 109,KeyEvent.KEYCODE_PAGE_DOWN, 110,KeyEvent.KEYCODE_INSERT, 111,KeyEvent.KEYCODE_FORWARD_DEL, 125,KeyEvent.KEYCODE_META_LEFT, 126,KeyEvent.KEYCODE_META_RIGHT);
        for (int i = 0; i < 10; i++) K.put(59 + i, KeyEvent.KEYCODE_F1 + i);
        K.put(87, KeyEvent.KEYCODE_F11); K.put(88, KeyEvent.KEYCODE_F12);
    }

    static int metaOf(int code) {
        switch (code) {
            case 42: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case 54: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
            case 29: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case 97: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
            case 56: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case 100: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
            case 125: return KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
            case 126: return KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON;
        }
        return 0;
    }

    static void send(InputEvent e) throws Exception { inject.invoke(im, e, 0); }

    static void mouse(int action, int actionButton, float vs, float hs) throws Exception {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0; p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x + nudge; c.y = y; c.size = 1f;
        c.pressure = buttons != 0 ? 1f : 0f;
        if (action == MotionEvent.ACTION_SCROLL) { c.setAxisValue(MotionEvent.AXIS_VSCROLL, vs); c.setAxisValue(MotionEvent.AXIS_HSCROLL, hs); }
        long now = SystemClock.uptimeMillis();
        boolean inGesture = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE;
        MotionEvent e = MotionEvent.obtain(inGesture ? downT : now, now, action, 1,
            new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c},
            meta, buttons, 1f, 1f, -1, 0, InputDevice.SOURCE_MOUSE, 0);
        setDisplayM.invoke(e, display);
        if (actionButton != 0) setActionButton.invoke(e, actionButton);
        send(e); e.recycle();
    }

    /* RustDesk 1.5.1 turns an Android DOWN into a mouse and a touch pointer, and the touch switches off physical
       mouse mode, so moves during a press are lost. Its mouse handler also treats a change of buttons on a hover as
       press or release, so send every button change as HOVER_MOVE and never send DOWN or UP. */
    static void button(int bit, boolean down) throws Exception {
        // RustDesk's own toolbar and its menus need a real press. After a toolbar click the next click also goes to
        // RustDesk, because a menu is open and covers the remote picture.
        if (down && buttons == 0) { real = y >= TOOLBAR_Y || menuOpen; }
        if (real) {
            if (down) {
                boolean first = buttons == 0;
                if (first) { downT = SystemClock.uptimeMillis(); nudge = 0f; }
                buttons |= bit;
                mouse(first ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE, 0, 0, 0);
            } else {
                buttons &= ~bit;
                if (buttons == 0) {
                    mouse(MotionEvent.ACTION_UP, 0, 0, 0);
                    menuOpen = y >= TOOLBAR_Y;
                    System.out.println("ui click y=" + y + " menuOpen=" + menuOpen);
                    real = false;
                    Thread.sleep(30);
                    mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
                } else mouse(MotionEvent.ACTION_MOVE, 0, 0, 0);
            }
            return;
        }
        // Flutter drops a hover whose position did not change, along with its button change, so a click
        // without movement would vanish. Nudge one pixel. Android also merges hovers within a frame and keeps
        // the first one's buttons, so give each change its own frame.
        Thread.sleep(20);
        if (down) buttons |= bit; else buttons &= ~bit;
        nudge = nudge == 0f ? (x >= W - 2 ? -1f : 1f) : 0f;
        mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
        Thread.sleep(40);
    }

    static void key(int linux, int value) throws Exception {
        Integer code = K.get(linux);
        if (code == null) { System.out.println("unmapped key " + linux); return; }
        int m = metaOf(linux);
        if (value == 1) { meta |= m; held.add(linux); } else if (value == 0) { meta &= ~m; held.remove(linux); }
        long now = SystemClock.uptimeMillis();
        KeyEvent e = new KeyEvent(now, now, value == 0 ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN, code, value == 2 ? 1 : 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, linux, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
        setDisplayK.invoke(e, display);
        send(e);
    }

    /** Injected keys only reach a display that has a focused window; touching the phone takes focus away. */
    static void focusDisplay() {
        try {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "activity_task");
            Object atm = Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            atm.getClass().getMethod("focusTopTask", int.class).invoke(atm, display);
            System.out.println("focused display " + display);
        } catch (Throwable t) {
            System.out.println("focus failed: " + t + " cause=" + t.getCause());
        }
    }

    static void releaseAll() throws Exception {
        for (Integer c : new HashSet<>(held)) key(c, 0);
        if (buttons != 0) { for (int b : new int[]{1, 2, 4}) if ((buttons & b) != 0) button(b, false); }
    }

    public static void main(String[] a) throws Exception {
        Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
        im = g.getMethod("getInstance").invoke(null);
        inject = g.getMethod("injectInputEvent", InputEvent.class, int.class);
        setDisplayM = MotionEvent.class.getMethod("setDisplayId", int.class);
        setDisplayK = KeyEvent.class.getMethod("setDisplayId", int.class);
        setActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
        display = Integer.parseInt(a[0]); W = Integer.parseInt(a[1]); H = Integer.parseInt(a[2]);
        x = W / 2f; y = H / 2f;
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        int dx = 0, dy = 0, wheel = 0, hwheel = 0; String l;
        System.out.println("bridge ready");
        while ((l = r.readLine()) != null) {
            String[] f = l.split(" ");
            if (f[0].equals("G")) {
                System.out.println("grab " + f[1]);
                if (f[1].equals("1")) {
                    focusDisplay();
                    Thread.sleep(150);
                    mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
                }
                continue;
            }
            if (f[0].equals("R")) { releaseAll(); continue; }
            if (f[0].equals("C")) {
                // The hotkey leaves Ctrl and Alt cached in the remote client; a full press and release clears them.
                meta = 0; held.clear();
                for (int c : new int[]{29, 56}) { key(c, 1); Thread.sleep(20); key(c, 0); Thread.sleep(20); }
                System.out.println("modifiers cleared");
                continue;
            }
            int type = Integer.parseInt(f[0]), code = Integer.parseInt(f[1]), val = Integer.parseInt(f[2]);
            if (type == 2) { // EV_REL
                if (code == 0) dx += val; else if (code == 1) dy += val; else if (code == 8) wheel += val; else if (code == 6) hwheel += val;
            } else if (type == 1) { // EV_KEY
                if (code == 272) button(MotionEvent.BUTTON_PRIMARY, val != 0);
                else if (code == 273) button(MotionEvent.BUTTON_SECONDARY, val != 0);
                else if (code == 274) button(MotionEvent.BUTTON_TERTIARY, val != 0);
                else if (code < 256) key(code, val);
            } else if (type == 0) { // EV_SYN
                if (dx != 0 || dy != 0) {
                    x = Math.max(0, Math.min(W - 1, x + dx * speed)); y = Math.max(0, Math.min(H - 1, y + dy * speed));
                    mouse(real && buttons != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE, 0, 0, 0);
                    dx = dy = 0;
                }
                if (wheel != 0 || hwheel != 0) { mouse(MotionEvent.ACTION_SCROLL, 0, wheel * wheelStep, hwheel * wheelStep); wheel = hwheel = 0; }
            }
        }
        releaseAll();
    }
}
