package com.termux.x11;

import com.termux.x11.mac.HidKeys;
import com.termux.x11.mac.MacClient;

import io.github.n2ray.xdesk.input.Input;
import java.util.HashSet;

/**
 * XDesk's input for a Mac window or the Mac desktop (INPUT.md Target "Mac"): the touchpad's relative moves and a
 * browser's absolute ones become a pointer in fractions of the streamed frame; left and right buttons; wheel
 * steps become Sill scrolls; Linux key codes become HID usages with INPUT.md §5.5's modifier translation (each
 * Mac's own choice, else XDesk's input setting). Text comes already reconciled by the phone's text
 * bridge (TextBridge, shared with the X desktop): corrections arrive as Backspace keys and the new text. The host repeats held keys when its list says keyRepeat, so a repeated down from
 * Android is dropped then.
 *
 * Window scope (a single Mac window): nothing global on the Mac is triggered (no Mission Control, no ⌘Tab).
 * Alt+Tab steps through this Mac's windows instead. Desktop scope: Alt+Tab is the Mac's own ⌘Tab.
 */
final class MacInput implements InputSink {
    /** One wheel step moves the content this much of the frame. */
    private static final float WHEEL_FRACTION = 0.05f;
    private static final int SCAN_ALT = 56, SCAN_RIGHT_ALT = 100, SCAN_TAB = 15, SCAN_SHIFT = 42, SCAN_RIGHT_SHIFT = 54;

    /** Home and overview on a Mac window: XDesk's switcher for this Mac (prefix), never Mission Control. */
    interface Switcher {
        void show(String prefix);
    }

    private final MacClient client;
    private final String prefix;
    private final Switcher switcher;
    /** HID usages down now, as sent (after translation). */
    private final HashSet<Integer> down = new HashSet<>();
    private boolean altHeld, shiftHeld, commandForAlt, tabStepped;
    /** INPUT.md §12 "modifier translation": the global value, for Macs that follow it. Off sends the keys as they are. */
    static volatile boolean translateModifiers = true;
    /** The global "keyboard through the phone's input method", for Macs that follow it. */
    static volatile boolean keysViaIme = true;
    /**
     * The sending keyboard is a Mac keyboard (a receiver said KEYBOARD kind 1): its Command already arrives as Meta,
     * so this Mac gets the keys untranslated (INPUT.md §5.5 "Mac keyboard → Mac"). Per wireless session.
     */
    static volatile boolean sourceMacKeyboard;

    /** What kind of keyboard sends to this session's Mac targets, said by the receiver (WirelessDisplay KEYBOARD). */
    static void setSourceMacKeyboard(boolean mac) {
        sourceMacKeyboard = mac;
    }

    MacClient client() {
        return client;
    }

    MacInput(MacClient client, String prefix, Switcher switcher) {
        this.client = client;
        this.prefix = prefix;
        this.switcher = switcher;
    }

    @Override public boolean anyThread() {
        // Every call only queues a message for the connection's sender
        return true;
    }

    /** INPUT.md §6: the Mac desktop is a whole desktop, a window is a window (actions above it are XDesk's). */
    @Override public io.github.n2ray.xdesk.input.Caps caps() {
        return desktop() ? io.github.n2ray.xdesk.input.Caps.MAC_DESKTOP : io.github.n2ray.xdesk.input.Caps.MAC_WINDOW;
    }

    private boolean desktop() {
        return client.want() == MacClient.DESKTOP;
    }

    /**
     * A physical keyboard's keys go to this Mac as keys, letters too (INPUT.md §5.2): the Mac's own input method
     * composes, and its candidates are in the picture, at the text. False: this Mac is set to the phone's input method.
     */
    boolean rawKeys() {
        return !client.mac().phoneIme(keysViaIme);
    }

    // --- pointer ---

    @Override public synchronized void move(int dx, int dy) {
        int[] r = client.frameRect();
        float w = r[2] > 0 ? r[2] : 1920, h = r[3] > 0 ? r[3] : 1080;
        client.pointerTo(client.pointerX() + dx / w, client.pointerY() + dy / h);
    }

    @Override public synchronized void moveToFraction(float x, float y) {
        int[] r = client.frameRect();
        if (r[2] <= 0 || r[4] <= 0) {
            client.pointerTo(x, y);
            return;
        }
        client.pointerTo((x * r[4] - r[0]) / r[2], (y * r[5] - r[1]) / r[3]);
    }

    /**
     * The picture may be narrower than the screen; down, 1 is the picture's bottom, where the pointer stops. The Mac
     * reports its pointer back a pixel or so short of that, which is the bottom as well.
     */
    @Override public boolean pointerPlace(float[] out) {
        int[] r = client.frameRect();
        float x = client.pointerX(), y = client.pointerY();
        out[0] = r[2] > 0 && r[4] > 0 ? (r[0] + x * r[2]) / r[4] : x;
        out[1] = y >= 1f - 2f / (r[3] > 0 ? r[3] : 1080) ? 1f : y;
        return true;
    }

    /** The inverse of moveToFraction: the Launchpad's arrow starts here, and its place comes back through that (audit:
     * pointerPlace's picture fraction moved the pointer on a letterboxed Mac at each open and close). */
    @Override public boolean screenPlace(float[] out) {
        int[] r = client.frameRect();
        float x = client.pointerX(), y = client.pointerY();
        boolean laid = r[2] > 0 && r[3] > 0 && r[4] > 0 && r[5] > 0;
        out[0] = laid ? (r[0] + x * r[2]) / r[4] : x;
        out[1] = laid ? (r[1] + y * r[3]) / r[5] : y;
        return true;
    }

    /** X numbering: 1 left, 2 middle, 3 right, 8 back, 9 forward. */
    @Override public synchronized void button(int button, boolean isDown) {
        switch (button) {
        case 1:
            client.pointerButton(isDown ? "leftDown" : "leftUp");
            break;
        case 3:
            client.pointerButton(isDown ? "rightDown" : "rightUp");
            break;
        case 8:
            if (isDown) shortcut(HidKeys.LEFT_BRACKET);
            break;
        case 9:
            if (isDown) shortcut(HidKeys.RIGHT_BRACKET);
            break;
        default:
            // Middle: the Mac target has left and right only (INPUT.md §6); dropped
        }
    }

    @Override public void click(int button) {
        button(button, true);
        button(button, false);
    }

    @Override public synchronized void scroll(int dy, int dx) {
        // XDesk: dy > 0 is the wheel turned down, so the content moves up. Sill: positive dy moves the content down
        client.scroll(-dx * WHEEL_FRACTION, -dy * WHEEL_FRACTION);
    }

    /**
     * Fine scrolling with its phases (Caps.scrollPhases): the touchpad's two fingers reach the Mac as a trackpad
     * gesture, began, deltas, ended, and the coast after the lift as momentum, so AppKit scrolls smoothly and
     * rubber-bands instead of seeing wheel steps. A wheel (Input.WHEEL) is bare deltas.
     */
    @Override public synchronized boolean scrollFine(float dy, float dx, int phase) {
        switch (phase) {
        case Input.BEGIN: client.scrollGesture("began"); break;
        case Input.END: client.scrollGesture("ended"); break;
        case Input.MOMENTUM_BEGIN: client.scrollGesture("momentumBegan"); break;
        case Input.MOMENTUM_END: client.scrollGesture("momentumEnded"); break;
        default:
            if (dx != 0 || dy != 0) client.scroll(-dx * WHEEL_FRACTION, -dy * WHEEL_FRACTION);
        }
        return true;
    }

    // --- keys ---

    private long flags() {
        long f = 0;
        for (int u : down) f |= HidKeys.flag(u);
        return f;
    }

    private void send(int usage, boolean isDown) {
        if (isDown) down.add(usage);
        else down.remove(usage);
        client.key(usage, isDown, flags());
    }

    /** ⌘ + key, for the Back and Forward actions (INPUT.md §3.4). */
    private void shortcut(int usage) {
        boolean had = down.contains(HidKeys.LEFT_GUI);
        if (!had) send(HidKeys.LEFT_GUI, true);
        send(usage, true);
        send(usage, false);
        if (!had) send(HidKeys.LEFT_GUI, false);
    }

    /** scan code to the usage its press sent, until its release */
    private final android.util.SparseIntArray pressedAs = new android.util.SparseIntArray();
    /** Above 0 while Router sends a semantic action's keys (INPUT.md §3.4): those are a Mac's keys already (⌘ as Meta). */
    private volatile int semantic;

    synchronized void semanticKeys(boolean on) {
        semantic += on ? 1 : -1;
    }

    @Override public synchronized void key(int scan, boolean isDown) {
        if (scan == SCAN_ALT || scan == SCAN_RIGHT_ALT) altHeld = isDown;
        if (scan == SCAN_SHIFT || scan == SCAN_RIGHT_SHIFT) shiftHeld = isDown;
        int usage = HidKeys.fromLinux(scan);
        if (usage == 0) return;
        // This Mac's own choice, else the global one; a Mac keyboard's keys already are the Mac's
        if (client.mac().translateModifiers(translateModifiers) && !sourceMacKeyboard && semantic == 0)
            usage = HidKeys.pcToMac(usage);
        // Released as it was pressed: the translation may have changed in between (a session ending, the setting)
        if (isDown) pressedAs.put(scan, usage);
        else {
            int was = pressedAs.get(scan, 0);
            pressedAs.delete(scan);
            if (was != 0) usage = was;
        }

        // Alt+Tab is "switch app" (INPUT.md §5.5, §3.4)
        if (scan == SCAN_TAB && altHeld) {
            if (desktop()) {
                // The Mac's own switcher: ⌘ held while Alt is, Tab pressed under it
                if (!commandForAlt) {
                    if (down.contains(HidKeys.LEFT_ALT)) send(HidKeys.LEFT_ALT, false);
                    if (down.contains(HidKeys.RIGHT_ALT)) send(HidKeys.RIGHT_ALT, false);
                    send(HidKeys.LEFT_GUI, true);
                    commandForAlt = true;
                }
                send(HidKeys.TAB, isDown);
            } else {
                // One window per press: Android's repeats of a held Tab would flip through them all
                if (isDown && !tabStepped) nextWindow(shiftHeld ? -1 : 1);
                tabStepped = isDown;
            }
            return;
        }
        if ((scan == SCAN_ALT || scan == SCAN_RIGHT_ALT) && !isDown && commandForAlt) {
            commandForAlt = false;
            if (down.contains(HidKeys.TAB)) send(HidKeys.TAB, false);
            send(HidKeys.LEFT_GUI, false);
            return;
        }

        if (isDown && down.contains(usage)) {
            // Android's own repeat. The host repeats by itself when it says so; else one more press. A locked Mac
            // repeats nothing (Sill's LockPolicy), so there too: Backspace held over a mistyped password
            if (HidKeys.isModifier(usage) || (client.keyRepeat() && !client.locked())) return;
            send(usage, false);
        }
        send(usage, isDown);
    }

    /** Android's repeats come here; the host's own repeat (keyRepeat) drops them again in key(). */
    @Override public boolean takesRepeats() {
        return true;
    }

    @Override public void tapKey(int scan) {
        key(scan, true);
        key(scan, false);
    }

    @Override public synchronized void text(String s) {
        if (s.isEmpty()) return;
        long f = flags();
        // A shortcut typed as text with a latched modifier (XDesk's Ctrl, ⌥ or ⌘ button): a key, not text
        if ((f & (MacClient.CONTROL | MacClient.OPTION | MacClient.COMMAND)) != 0 && s.length() == 1) {
            int usage = HidKeys.forChar(s.charAt(0));
            if (usage != 0) {
                send(usage, true);
                send(usage, false);
                return;
            }
        }
        client.text(s);
    }

    // --- actions ---

    /** 1 Back → ⌘[ ; 2 Home and 3 overview → XDesk's switcher for this Mac (INPUT.md §3.4). */
    @Override public synchronized void androidButton(int button) {
        if (button == 1) {
            shortcut(HidKeys.LEFT_BRACKET);
        } else if (button == 2 || button == 3) {
            if (switcher != null) switcher.show(prefix);
        }
    }

    /** The switch-app gesture over a Mac window (INPUT.md §3.4): this Mac's next or previous window, one per step. */
    synchronized void stepWindow(int step) {
        if (step != 0) nextWindow(step > 0 ? 1 : -1);
    }

    /** This Mac's next (or previous) window, in the order the Mac lists them. */
    private void nextWindow(int step) {
        java.util.List<MacClient.Window> ws = client.windows();
        if (ws.isEmpty()) return;
        int at = -1;
        for (int i = 0; i < ws.size(); i++) if (ws.get(i).id == client.want()) at = i;
        int next = ((at < 0 ? 0 : at + step) % ws.size() + ws.size()) % ws.size();
        client.show(ws.get(next).id);
    }

    /** Everything this side holds down goes up: the content left the screen, or a gesture was cancelled. */
    synchronized void releaseAll() {
        for (Integer u : new HashSet<>(down)) send(u, false);
        commandForAlt = tabStepped = false;
        altHeld = shiftHeld = false;
    }
}
