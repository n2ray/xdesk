package com.termux.x11;

import android.os.SystemClock;
import io.github.n2ray.xdesk.input.Caps;

/**
 * The Dock over a content that has the mouse to itself (ExternalDock): over an X desktop, a Linux window or a Mac the
 * phone's mouse is captured and sent on as moves, the content draws its own pointer, and Android has no pointer on the
 * external display for the Dock's edge to notice. The phone's mouse and touchpad pass through here on their way to the
 * Router, so this knows when the content's pointer is pushed against the bottom of the screen, opens the Dock, and
 * gives it the pointer while it is open. `client` is not swapped as it is for the Launchpad: the keyboard, the text
 * bridge and everything else stay with the content, only moves, buttons and the wheel of these two devices turn to the
 * Dock. Only Esc is taken from the keyboard, to close it.
 *
 * When it opens. The content's pointer is at the bottom (InputSink.pointerPlace, read before each move is sent on, so
 * what counts is only what goes beyond the edge) and is pushed further down, without a pause longer than GAP_MS and
 * more down than sideways, for DWELL_MS and PUSH units. Resting on the bottom row does not open it, nor does a flick
 * that lands there: a taskbar or a tab at the bottom of the picture is clicked exactly so. A whole desktop (XFCE, a
 * Mac's screen) has things of its own at that edge, a panel, the Mac's Dock that shows itself when the pointer
 * reaches the edge: there the push is DESKTOP_DWELL_MS and DESKTOP_PUSH, long after the Mac's Dock has come, so a
 * touch of the edge is the Mac's and a push kept up is XDesk's (DISPLAY.md §6). Never with a button held: a drag to
 * the edge is a drag.
 *
 * A button that went down on the Dock comes up on the Dock, also when the Dock is gone by then; the Router sees
 * neither half. Main thread only.
 */
final class DockPointer {
    interface Host {
        /** Where input goes now (the activity's client). */
        InputSink target();
        /** Null when there is none. */
        ExternalDock dock();
    }

    private static final long DWELL_MS = 350, DESKTOP_DWELL_MS = 700, GAP_MS = 250;
    /** In units of pointer movement, about pixels of the content. */
    private static final int PUSH = 60, DESKTOP_PUSH = 240, SIDE_SLACK = 12;
    private static final int KEY_ESC = 1;

    private final Host host;
    private final Router.Device[] devices;
    private final float[] place = new float[2];
    /** Buttons that went down on the Dock, by device id. */
    private final int[] taken = new int[4];
    /** What the Dock is open over; null while this has not opened it. */
    private InputSink over;
    private long edgeSince, lastPush;
    private int push, side;
    private boolean escTaken;

    /** @param devices the phone's devices that move the pointer: none of them may hold a button when the Dock opens */
    DockPointer(Host host, Router.Device... devices) {
        this.host = host;
        this.devices = devices;
    }

    /**
     * The Dock is open and has the pointer. It closed by itself, or what it was opened over is no longer the target
     * (the content ended or changed, the Launchpad took the input): then not, and the Dock goes.
     */
    boolean open() {
        if (over == null) return false;
        ExternalDock dock = host.dock();
        if (dock != null && dock.driven() && host.target() == over) return true;
        over = null;
        if (dock != null) dock.closeDriven();
        return false;
    }

    /** Things changed (a content, the display, the Launchpad): the same check without waiting for the next event. */
    void check() {
        open();
        forget();
    }

    private void forget() {
        edgeSince = 0;
        push = side = 0;
    }

    void move(Router.Device d, int dx, int dy) {
        if (open()) {
            host.dock().moveBy(dx, dy);
            return;
        }
        pushed(dx, dy);
        // Sent on also when it opened the Dock: the content's pointer stays at its edge either way
        d.move(dx, dy);
    }

    /** Counts a move that is about to be sent, and opens the Dock when that is the push it waits for. */
    private void pushed(int dx, int dy) {
        InputSink t = host.target();
        ExternalDock dock = host.dock();
        if (dy < 0 || t == null || dock == null || !dock.attached() || holding() || !t.pointerPlace(place) || place[1] < 1f) {
            forget();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (edgeSince == 0 || now - lastPush > GAP_MS) {
            edgeSince = lastPush = now;
            push = side = 0;
        }
        if (dy > 0) lastPush = now;
        push += dy;
        side += Math.abs(dx);
        if (side > push + SIDE_SLACK) {
            // Along the edge, not against it: start again
            edgeSince = now;
            push = side = 0;
            return;
        }
        Caps caps = t.caps();
        boolean desktop = caps != null && caps.scope == Caps.DESKTOP;
        if (now - edgeSince < (desktop ? DESKTOP_DWELL_MS : DWELL_MS) || push < (desktop ? DESKTOP_PUSH : PUSH)) return;
        forget();
        if (dock.openDriven(place[0])) over = t;
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
            // The Dock acts on the release, as the Launchpad does
            if (button == 1 && open()) host.dock().clickDriven();
            else if (button == 3 && open()) host.dock().secondaryDriven();
            return;
        }
        d.button(button, down);
    }

    void click(Router.Device d, int button) {
        if (!open()) d.click(button);
        else if (button == 1) host.dock().clickDriven();
        else if (button == 3) host.dock().secondaryDriven();
    }

    /** True while the Dock has the pointer: the wheel is not the content's then. */
    boolean takesScroll() {
        return open();
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
        over = null;
        host.dock().closeDriven();
        escTaken = true;
        return true;
    }
}
