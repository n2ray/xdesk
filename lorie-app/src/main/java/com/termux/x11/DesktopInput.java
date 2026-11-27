package com.termux.x11;

import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;
import com.termux.x11.input.InputStub;
import io.github.n2ray.xdesk.input.Caps;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 把输入直接交给外屏 MainActivity 里的 LorieView。桌面没连上时丢弃。 */
final class DesktopInput implements InputSink {
    private static final float WHEEL_STEP = 60f;
    private static final long RESAY_HIDDEN_MS = 1000;
    private boolean cursorHidden;
    private LorieView cursorView;
    private long cursorSaidAt;

    /** 桌面窗口已连上 X 时返回它的 LorieView，否则返回 null。 */
    static LorieView view() {
        MainActivity m = MainActivity.getInstance("");
        LorieView v = m != null ? m.getLorieView() : null;
        return v != null && v.connected() ? v : null;
    }

    /** The browser's pointer is on top: the X cursor stays hidden until another pointer moves, or it leaves. */
    private void setCursorHidden(LorieView v, boolean hidden) {
        if (v == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        // The same view may be connected to a new X server by now (an app update restarts it), which shows its
        // cursor again: a hidden one is said again once a second while placements go on (MACAPP.md §1)
        if (hidden == cursorHidden && v == cursorView && (!hidden || now - cursorSaidAt < RESAY_HIDDEN_MS)) return;
        cursorHidden = hidden;
        cursorView = v;
        cursorSaidAt = now;
        v.setCursorHidden(hidden);
    }

    @Override public void pointerGone() {
        setCursorHidden(view(), false);
    }

    @Override public void move(int dx, int dy) {
        LorieView v = view();
        setCursorHidden(v, false);
        if (v != null) v.sendMouseEvent(dx, dy, InputStub.BUTTON_UNDEFINED, false, true);
    }

    @Override public void moveToFraction(float x, float y) {
        LorieView v = view();
        if (v == null) return;
        setCursorHidden(v, true);
        android.graphics.Point s = v.screenSize();
        if (s.x > 0 && s.y > 0) v.sendMouseEvent(x * (s.x - 1), y * (s.y - 1), InputStub.BUTTON_UNDEFINED, false, false);
    }

    /** Placed as moveToFraction, but X's cursor stays shown: the user's own pointer moved there. */
    @Override public void warpTo(float x, float y) {
        LorieView v = view();
        if (v == null) return;
        setCursorHidden(v, false);
        android.graphics.Point s = v.screenSize();
        if (s.x > 0 && s.y > 0) v.sendMouseEvent(x * (s.x - 1), y * (s.y - 1), InputStub.BUTTON_UNDEFINED, false, false);
    }

    /** The X server's own pointer, as the renderer last had it: a move just sent may not be in it yet. */
    @Override public boolean pointerPlace(float[] out) {
        LorieView v = view();
        if (v == null) return false;
        android.graphics.Point s = v.screenSize();
        if (s.x < 2 || s.y < 2) return false;
        android.graphics.PointF at = v.getRealCursorPosition();
        out[0] = Math.max(0f, Math.min(1f, at.x / (s.x - 1)));
        out[1] = at.y >= s.y - 1 ? 1f : Math.max(0f, at.y / (s.y - 1));
        return true;
    }

    @Override public void button(int button, boolean down) {
        LorieView v = view();
        if (v != null) v.sendMouseEvent(0, 0, button, down, true);
    }

    @Override public void click(int button) {
        button(button, true);
        button(button, false);
    }

    @Override public void scroll(int dy, int dx) {
        LorieView v = view();
        if (v != null) v.sendMouseWheelEvent(dx * WHEEL_STEP, dy * WHEEL_STEP);
    }

    @Override public Caps caps() {
        return Caps.X11_DESKTOP;
    }

    /** X has smooth scroll valuators: fractions of a step scroll a fraction. */
    @Override public boolean scrollFine(float dy, float dx, int phase) {
        if (dx == 0 && dy == 0) return true;
        LorieView v = view();
        if (v != null) v.sendMouseWheelEvent(dx * WHEEL_STEP, dy * WHEEL_STEP);
        return true;
    }

    // The same X server and symbol must receive both edges, even if focus, device or layout changes mid-press.
    private final HashMap<Integer, Integer> captured = new HashMap<>();
    private final HashMap<String, Integer> keyboards = new HashMap<>();
    // The X client runs on a different thread and may read a down after its physical key was released.
    private static final ConcurrentHashMap<Integer, Integer> keyboardIds = new ConcurrentHashMap<>();
    static int keyboardForScan(int scan) { return keyboardIds.getOrDefault(scan, -1); }

    private LorieView capturedView;
    private boolean capsLock;

    private InputDevice keyboard(String name) {
        Integer cached = keyboards.get(name);
        InputDevice device = cached == null ? null : InputDevice.getDevice(cached);
        if (device != null && name.equals(device.getName())) return device;
        for (int id : InputDevice.getDeviceIds()) {
            device = InputDevice.getDevice(id);
            if (device != null && device.isExternal() && !device.isVirtual() && name.equals(device.getName()) &&
                    device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                keyboards.put(name, id);
                return device;
            }
        }
        return null;
    }

    private int layoutSymbol(InputDevice device, int scan) {
        Integer code = InputInjector.KEYS.get(scan);
        if (code == null || InputInjector.metaOf(scan) != 0) return 0;
        if (device == null) return 0;
        if (Build.VERSION.SDK_INT >= 33 && scan != 43) {
            int mapped = device.getKeyCodeForKeyLocation(code);
            if (mapped != KeyEvent.KEYCODE_UNKNOWN) code = mapped;
        }
        // Shift and AltGr select a character. Ctrl, left Alt and Super are X shortcuts,
        // so their actual X events stay held but they do not turn Ctrl+C into code point 3.
        int meta = capsLock ? KeyEvent.META_CAPS_LOCK_ON : 0;
        for (int held : captured.keySet()) meta |= InputInjector.metaOf(held);
        int selectors = KeyEvent.META_SHIFT_MASK | KeyEvent.META_CAPS_LOCK_ON;
        if ((meta & KeyEvent.META_ALT_RIGHT_ON) != 0) selectors |= KeyEvent.META_ALT_MASK;
        // Keypad keys must keep their XKB KP_* identity (and NumLock semantics).
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN) return 0;
        return ScreenShareInput.hardwareCharacterKeysym(device.getKeyCharacterMap().get(code, meta & selectors));
    }

    private final HashMap<Integer,KeyEvent> composingKeys = new HashMap<>();

    private int heldMeta() {
        int meta=capsLock ? KeyEvent.META_CAPS_LOCK_ON : 0;
        for (int scan:captured.keySet()) meta |= InputInjector.metaOf(scan);
        for (int scan:composingKeys.keySet()) meta |= InputInjector.metaOf(scan);
        return meta;
    }

    // Keep desktop accelerators in X. Text, candidate selection and editing go through
    // the HDMI window's normal pre-IME / IME / unhandled-key dispatch sequence.
    private boolean linuxImeKey(InputDevice device,int scan,int value,boolean linux) {
        KeyEvent held=composingKeys.get(scan);
        long now=android.os.SystemClock.uptimeMillis();
        if (held!=null) {
            if (value==1) return true;
            KeyEvent next=KeyEvent.changeTimeRepeat(held,now,value==2?held.getRepeatCount()+1:0);
            if (value==0) {
                composingKeys.remove(scan);
                next=KeyEvent.changeAction(next,KeyEvent.ACTION_UP);
            } else composingKeys.put(scan,next);
            if (linux || value==0) dispatchImeKey(next);
            return true;
        }
        if (!linux || value!=1 || device==null) return false;
        Integer code=InputInjector.KEYS.get(scan);
        if (code==null) return false;
        int meta=heldMeta() | InputInjector.metaOf(scan);
        boolean imeSwitch=code==KeyEvent.KEYCODE_SPACE && (meta & KeyEvent.META_CTRL_ON)!=0;
        if ((meta & (KeyEvent.META_META_ON | KeyEvent.META_ALT_LEFT_ON))!=0 ||
                (!imeSwitch && (meta & KeyEvent.META_CTRL_ON)!=0) || scan==58) return false;
        if (Build.VERSION.SDK_INT>=33 && scan!=43) {
            int mapped=device.getKeyCodeForKeyLocation(code);
            if (mapped!=KeyEvent.KEYCODE_UNKNOWN) code=mapped;
        }
        KeyEvent down=new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0,meta,device.getId(),
                scan,0,InputDevice.SOURCE_KEYBOARD);
        if (!dispatchImeKey(down)) return false;
        composingKeys.put(scan,down);
        return true;
    }
    private boolean dispatchImeKey(KeyEvent event) {
        MainActivity host=MainActivity.getInstance("");
        if (host==null || host.getLorieView()!=capturedView || !capturedView.hasWindowFocus()) return false;
        host.getWindow().injectInputEvent(event);
        return true;
    }
    void capturedKey(String device, int scan, int value, boolean useAndroidLayout, boolean linux) {
        LorieView v = view();
        if (v != capturedView) {
            resetCapturedKeys();
            capturedView = v;
        }
        if (v == null) return;
        InputDevice physical=value==1 ? keyboard(device) : null;
        if (linuxImeKey(physical,scan,value,linux) || value == 2) return; // XKB owns repeat, including its configured rate and delay.
        if (value == 1 && !captured.containsKey(scan)) {
            if (scan == 58) capsLock = !capsLock;

            if (physical != null) keyboardIds.put(scan, physical.getId());
            else keyboardIds.remove(scan);
            int sym = useAndroidLayout ? layoutSymbol(physical, scan) : 0;
            captured.put(scan, sym);
            if (sym != 0) v.sendLayoutKey(sym, true);
            else v.sendKeyEvent(scan, 0, true);
        } else if (value == 0) {
            Integer sym = captured.remove(scan);
            if (sym == null) return;
            if (sym != 0) v.sendLayoutKey(sym, false);
            else v.sendKeyEvent(scan, 0, false);
        }
    }

    void resetCapturedKeys() {
        if (capturedView!=null)
            for (KeyEvent held:composingKeys.values()) dispatchImeKey(KeyEvent.changeAction(held,KeyEvent.ACTION_UP));
        composingKeys.clear();
        if (capturedView != null && capturedView.connected()) {
            for (Map.Entry<Integer,Integer> press : captured.entrySet()) {
                if (press.getValue() != 0) capturedView.sendLayoutKey(press.getValue(), false);
                else capturedView.sendKeyEvent(press.getKey(), 0, false);
            }
        }
        captured.clear();
        keyboards.clear();
        keyboardIds.clear();
        capturedView = null;
    }

    @Override public void key(int scan, boolean down) {
        LorieView v = view();
        if (v != null) v.sendKeyEvent(scan, 0, down);
    }

    @Override public void tapKey(int scan) {
        key(scan, true);
        key(scan, false);
    }

    @Override public void text(String s) {
        LorieView v = view();
        if (v != null) v.sendTextEvent(s.getBytes(StandardCharsets.UTF_8));
    }
}
