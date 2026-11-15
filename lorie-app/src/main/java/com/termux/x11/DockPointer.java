package com.termux.x11;

import io.github.n2ray.xdesk.input.Caps;

/**
 * The Dock and the notification centre over a content that has the mouse to itself (ExternalDock, NOTIFY.md): over an
 * X desktop, a Linux window or a Mac the phone's mouse is captured and sent on as moves, the content draws its own
 * pointer, and Android has no pointer on the external display for their edges and windows to notice. The phone's
 * mouse and touchpad, and a receiver's pointer over X or a Mac, pass through here on their way to the Router, so this
 * knows when the content's pointer is pushed against the bottom of the screen (opens the Dock), when it is pushed
 * out through the top-right corner (opens the notification centre), and gives them the pointer while they are open.
 * `client` is not swapped as it is for the Launchpad: the keyboard, the text bridge and everything else stay with the
 * content, only moves, buttons and the wheel of these devices turn to the Dock or the centre. Only Esc is taken from
 * the keyboard, to close them.
 *
 * When it opens. The content's pointer is at the edge (InputSink.pointerPlace, read before each move is sent on, so
 * what counts is only what goes beyond the edge) and is pushed further out: the rule is EdgePush, the same one an
 * Android app's Dock and centre open by (there the injector reports the pushes, ExternalDock.pushed, Notify.pushed).
 * The Dock pushes down, the centre right or up inside the top-right corner's 48 shell dp (Notify.cornerEdge). A whole
 * desktop gets the rule's longer push. Never with a button held: a drag to the edge is a drag.
 *
 * While the centre's windows are up (its panel open, a banner or the bell under the pointer) the pointer is theirs
 * the same way (Notify.grab): moves and clicks work their views, the content's pointer stays where it was.
 *
 * A button that went down on the Dock or the centre comes up on them, also when they are gone by then; the Router
 * sees neither half. Main thread only.
 */
final class DockPointer {
    interface Host {
        /** Where input goes now (the activity's client). */
        InputSink target();
        /** Null when there is none. */
        ExternalDock dock();
        /** Null while the notification centre's windows are not on the display. */
        Notify notifyCenter();
    }

    private static final int KEY_ESC = 1;
    private static final int SURFACE_DOCK = 1, SURFACE_NOTIFY = 2;

    private final Host host;
    private final Router.Device[] devices;
    private final float[] place = new float[2];
    /** Buttons that went down on the Dock or the centre, by device id. */
    private final int[] taken = new int[4];
    /** What the Dock or the centre is open over; null while this has not opened it. */
    private InputSink over;
    /** Whose the pointer is: SURFACE_DOCK, SURFACE_NOTIFY, or 0 for the content's. */
    private int surface;
    private final EdgePush rule = new EdgePush();
    /** The centre's corner rule (NOTIFY.md §4.1), TOP and RIGHT edges. */
    private final EdgePush corner = new EdgePush();
    private boolean escTaken;

    /** @param devices the devices that move the pointer (the phone's mouse and touchpad, the receiver): none of them may hold a button when the Dock or the centre opens */
    DockPointer(Host host, Router.Device... devices) {
        this.host = host;
        this.devices = devices;
    }

    /**
     * The Dock or the centre is open and has the pointer. It closed by itself, or what it was opened over is no
     * longer the target (the content ended or changed, the Launchpad took the input): then not, and it goes.
     */
    boolean open() {
        if (over == null) return false;
        if (surface == SURFACE_DOCK) {
            ExternalDock dock = host.dock();
            if (dock != null && dock.driven() && host.target() == over) return true;
            over = null;
            surface = 0;
            if (dock != null) dock.closeDriven();
            return false;
        }
        Notify notify = host.notifyCenter();
        if (notify != null && notify.driven() && host.target() == over) return true;
        over = null;
        surface = 0;
        if (notify != null) notify.drivenClosed();
        return false;
    }

    /** Things changed (a content, the display, the Launchpad): the same check without waiting for the next event. */
    void check() {
        open();
        forget();
    }

    private void forget() {
        rule.forget();
        corner.forget();
    }

    void move(Router.Device d, int dx, int dy) {
        if (open()) {
            if (surface == SURFACE_DOCK) {
                host.dock().moveBy(dx, dy);
                return;
            }
            // The centre's windows have the pointer. When the arrow left one, what it moved by goes to the
            // content: its pointer ends where the arrow left, outside the window (NotifyGrab)
            int[] left = host.notifyCenter().moveBy(dx, dy);
            if (left != null) d.move(left[0], left[1]);
            return;
        }
        pushed(dx, dy);
        cornerPushed(dx, dy);
        // Sent on also when one of them opened: the content's pointer stays at its edge either way
        d.move(dx, dy);
    }

    /** Counts a move that is about to be sent, and opens the Dock when that is the push it waits for. */
    private void pushed(int dx, int dy) {
        InputSink t = host.target();
        ExternalDock dock = host.dock();
        if (dy < 0 || t == null || dock == null || !dock.attached() || holding() || !t.pointerPlace(place) || place[1] < 1f) {
            rule.forget();
            return;
        }
        Caps caps = t.caps();
        if (rule.pushed(dx, dy, caps != null && caps.scope == Caps.DESKTOP) && dock.openDriven(place[0])) {
            over = t;
            surface = SURFACE_DOCK;
        }
    }

    /**
     * The centre's corner over X and a Mac (NOTIFY.md §4.1): the content's pointer is inside the top-right corner's
     * 48 shell dp (on the right or the top edge) and is pushed further out. The same rule as the Dock's, with the
     * longer push over a whole desktop. Also the centre's windows take the pointer while it is over them (grab):
     * an open panel, a banner, the bell.
     */
    private void cornerPushed(int dx, int dy) {
        InputSink t = host.target();
        Notify notify = host.notifyCenter();
        if (t == null || notify == null || !notify.attached() || holding() || !t.pointerPlace(place)) {
            corner.forget();
            return;
        }
        int edge = notify.cornerEdge(place[0], place[1]);
        if (edge != 0) {
            Caps caps = t.caps();
            if (corner.pushed(edge, dx, dy, caps != null && caps.scope == Caps.DESKTOP) && notify.openDriven(place[0], place[1])) {
                over = t;
                surface = SURFACE_NOTIFY;
                ExternalDock dock = host.dock();
                if (dock != null) dock.closeDriven();  // one surface at a time has the pointer
                return;
            }
        } else {
            corner.forget();
        }
        if (notify.grab(place[0], place[1])) {
            over = t;
            surface = SURFACE_NOTIFY;
        } else {
            notify.pointerAt(place[0], place[1]);  // the bell's fade follows where the pointer is
        }
    }

    private boolean holding() {
        for (Router.Device d : devices) if (d.holdsButtons()) return true;
        return false;
    }

    private static int bit(int button) {
        return button > 0 && button < 31 ? 1 << button : 0;
    }

    void button(Router.Device d, int button, boolean down) {
        int bit = bit(button);
        if (down) {
            if (open()) {
                taken[d.id] |= bit;
                return;
            }
            taken[d.id] &= ~bit;
        } else if ((taken[d.id] & bit) != 0) {
            taken[d.id] &= ~bit;
            // They act on the release, as the Launchpad does
            if (button == 1 && open()) {
                if (surface == SURFACE_DOCK) host.dock().clickDriven();
                else host.notifyCenter().clickDriven();
            } else if (button == 3 && open()) {
                if (surface == SURFACE_DOCK) host.dock().secondaryDriven();
                else host.notifyCenter().secondaryDriven();
            }
            return;
        }
        d.button(button, down);
    }

    void click(Router.Device d, int button) {
        if (!open()) d.click(button);
        else if (button == 1) {
            if (surface == SURFACE_DOCK) host.dock().clickDriven();
            else host.notifyCenter().clickDriven();
        } else if (button == 3) {
            if (surface == SURFACE_DOCK) host.dock().secondaryDriven();
            else host.notifyCenter().secondaryDriven();
        }
    }

    /** The wheel while the Dock or the centre has the pointer: the centre scrolls its list, the Dock takes it. */
    boolean scroll(float dy, float dx) {
        if (!open()) return false;
        if (surface == SURFACE_NOTIFY) host.notifyCenter().wheel(dy, dx);
        return true;
    }

    /** A physical key on its way to the target. True when it is Esc closing the Dock, or that Esc coming up. */
    boolean key(int scan, boolean down) {
        if (scan != KEY_ESC) return false;
        if (!down) {
            boolean was = escTaken;
            escTaken = false;
            return was;
        }
        if (escTaken) return true;  // its repeats
        if (!open()) return false;
        // The centre's Esc is Notify.key's own (the panel, a reply field); a pointer merely resting over a banner
        // or the bell does not make Esc theirs
        if (surface == SURFACE_NOTIFY && !host.notifyCenter().takesEsc()) return false;
        over = null;
        if (surface == SURFACE_DOCK) host.dock().closeDriven();
        else host.notifyCenter().closeDriven();
        surface = 0;
        escTaken = true;
        return true;
    }
}
