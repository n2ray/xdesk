package com.termux.x11;

import io.github.n2ray.xdesk.input.Caps;

/**
 * The Dock, the notification centre and the Mac menu over a content that has the mouse to itself (ExternalDock,
 * NOTIFY.md, MACMENU.md): over an X desktop, a Linux window or a Mac the phone's mouse is captured and sent on as
 * moves, the content draws its own pointer, and Android has no pointer on the external display for their edges and
 * windows to notice. The phone's mouse and touchpad, and a receiver's pointer over X or a Mac, pass through here on
 * their way to the Router, so this knows when the content's pointer is pushed against the bottom of the screen
 * (opens the Dock), when it is pushed out through the top-right corner (opens the notification centre) or the
 * top-left one (opens the Mac menu), and gives them the pointer while they are open.
 * `client` is not swapped as it is for the Launchpad: the keyboard, the text bridge and everything else stay with the
 * content, only moves, buttons and the wheel of these devices turn to the Dock or the centre. Only Esc is taken from
 * the keyboard, to close them.
 *
 * When it opens. The Dock: the content's pointer rests on the bottom edge for ExternalDock.ANDROID_DWELL_MS, as over
 * an Android app (owner, 2026-11-19: one way everywhere, no pushing down). Every move starts the wait again; after the
 * last one restCheck reads where the content's pointer is (InputSink.pointerPlace). The centre: the pointer is inside
 * the top-right corner's 48 shell dp (Notify.cornerEdge) and is pushed further out, the rule EdgePush, as over an app
 * (Notify.pushed); the Mac menu the same at the top left. Never with a button held: a drag to the edge is a drag.
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
        /** Null while the Mac menu's windows are not on the display. */
        MacMenus menus();
    }

    private static final int KEY_ESC = 1;
    private static final int SURFACE_DOCK = 1, SURFACE_NOTIFY = 2, SURFACE_MENU = 3;

    private final Host host;
    private final Router.Device[] devices;
    private final float[] place = new float[2];
    /** Buttons that went down on the Dock or the centre, by device id. */
    private final int[] taken = new int[4];
    /** What the Dock or the centre is open over; null while this has not opened it. */
    private InputSink over;
    /** Whose the pointer is: SURFACE_DOCK, SURFACE_NOTIFY, or 0 for the content's. */
    private int surface;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** The centre's corner rule (NOTIFY.md §4.1), TOP and RIGHT edges. */
    private final EdgePush corner = new EdgePush();
    /** The Mac menu's corner rule (MACMENU.md §3), TOP and LEFT edges. */
    private final EdgePush menuCorner = new EdgePush();
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
        if (over == null) {
            // A receiver's placed pointer resting on the bottom opens the Dock on its own, a moment later
            // (ExternalDock.restDriven): it has the pointer then, and the buttons are its too (MACAPP.md §1)
            ExternalDock dock = host.dock();
            InputSink t = host.target();
            if (dock == null || !dock.driven() || t == null) return false;
            over = t;
            surface = SURFACE_DOCK;
            return true;
        }
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
        MacMenus menus = host.menus();
        if (menus != null && menus.driven() && host.target() == over) return true;
        over = null;
        surface = 0;
        if (notify != null) notify.drivenClosed();
        if (menus != null) menus.drivenClosed();
        return false;
    }

    /** Things changed (a content, the display, the Launchpad): the same check without waiting for the next event. */
    void check() {
        open();
        forget();
    }

    private void forget() {
        ui.removeCallbacks(restCheck);
        corner.forget();
        menuCorner.forget();
    }

    void move(Router.Device d, int dx, int dy) {
        if (open()) {
            if (surface == SURFACE_DOCK) {
                host.dock().moveBy(dx, dy);
                return;
            }
            // The centre's or the menu's windows have the pointer. When the arrow left one, what it moved by
            // goes to the content: its pointer ends where the arrow left, outside the window (NotifyGrab)
            int[] left = surface == SURFACE_NOTIFY ? host.notifyCenter().moveBy(dx, dy) : host.menus().moveBy(dx, dy);
            if (left != null) d.move(left[0], left[1]);
            return;
        }
        pushed(dx, dy);
        cornerPushed(dx, dy);
        // The Mac menu's corner on its own: it must not hang on the centre being there (no notification access)
        if (!open()) menuPushed(dx, dy);
        // Sent on also when one of them opened: the content's pointer stays at its edge either way
        d.move(dx, dy);
        noteBottom();
    }

    /**
     * Where the content's pointer is right after a move was sent: on the bottom edge or not. A Mac then reports its
     * pointer back a pixel or so short of the edge (MacInput.pointerPlace), and the rest's check came after that
     * report: over a letterboxed Mac desktop the pointer rested on the picture's bottom and the Dock never opened
     * (owner, 2026-11-19). The place just after the move is the one the user left it at.
     */
    private void noteBottom() {
        InputSink t = host.target();
        restAtBottom = t != null && t.pointerPlace(place) && place[1] >= 1f;
        restX = place[0];
    }

    private boolean restAtBottom;
    private float restX;

    /** A move is about to be sent: the wait for a rest at the bottom edge starts again. */
    private void pushed(int dx, int dy) {
        ui.removeCallbacks(restCheck);
        ExternalDock dock = host.dock();
        if (dock == null || !dock.attached()) return;
        ui.postDelayed(restCheck, ExternalDock.ANDROID_DWELL_MS);
    }

    /** The pointer has not moved for the dwell: on the bottom edge, with no button held, the Dock opens. */
    private final Runnable restCheck = new Runnable() {
        @Override public void run() {
            if (open()) return;
            InputSink t = host.target();
            ExternalDock dock = host.dock();
            if (t == null || dock == null || !dock.attached() || holding()) return;
            boolean now = t.pointerPlace(place) && place[1] >= 1f;
            if (!now && !restAtBottom) return;
            if (dock.openDriven(now ? place[0] : restX)) {
                over = t;
                surface = SURFACE_DOCK;
            }
        }
    };

    /**
     * The centre's corner over X and a Mac (NOTIFY.md §4.1): the content's pointer is inside the top-right corner's
     * 48 shell dp (on the right or the top edge) and is pushed further out. The same rule as the Dock's, with the
     * longer push over a whole desktop. Also the centre's windows take the pointer while it is over them (grab):
     * an open panel, a banner, the bell. The Mac menu's top-left corner is the same, after the centre's (its
     * button only exists over a Mac window or app, where the centre's corner is the other side).
     */
    private void cornerPushed(int dx, int dy) {
        InputSink t = host.target();
        Notify notify = host.notifyCenter();
        if (t == null || notify == null || !notify.attached() || holding() || !t.pointerPlace(place)) {
            corner.forget();
            return;
        }
        Caps rc = t.caps();
        // Resting in the very corner opens it too, with the longer rest over a whole desktop (Notify.restAt)
        notify.restAt(place[0], place[1], rc != null && rc.scope == Caps.DESKTOP);
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

    /**
     * The Mac menu's corner (MACMENU.md §3): the content's pointer is inside the top-left corner's 48 shell dp
     * (on the left or the top edge) and is pushed further out — only over a Mac window or app, so the push is
     * never the desktop's longer one. Also the menu's windows take the pointer while it is over them (grab):
     * the open bar, the ≡ button.
     */
    private void menuPushed(int dx, int dy) {
        InputSink t = host.target();
        MacMenus menus = host.menus();
        if (t == null || menus == null || !menus.attached() || holding() || !t.pointerPlace(place)) {
            menuCorner.forget();
            return;
        }
        int edge = menus.cornerEdge(place[0], place[1]);
        if (edge != 0) {
            if (menuCorner.pushed(edge, dx, dy, false) && menus.openDriven(place[0], place[1])) {
                over = t;
                surface = SURFACE_MENU;
                ExternalDock dock = host.dock();
                if (dock != null) dock.closeDriven();  // one surface at a time has the pointer
                Notify notify = host.notifyCenter();
                if (notify != null) notify.drivenClosed();  // the centre's panel goes, the bar takes its place
                return;
            }
        } else {
            menuCorner.forget();
        }
        if (menus.grab(place[0], place[1])) {
            over = t;
            surface = SURFACE_MENU;
        } else {
            menus.pointerAt(place[0], place[1]);  // the ≡ button's fade follows where the pointer is
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
            // A click in the corner (a maximised window's close button) is no rest there: the side panel waits for
            // the pointer to leave the corner and come back (audit, 17 November)
            Notify n = host.notifyCenter();
            if (n != null) n.restClicked();
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
                else if (surface == SURFACE_NOTIFY) host.notifyCenter().clickDriven();
                else host.menus().clickDriven();
            } else if (button == 3 && open()) {
                if (surface == SURFACE_DOCK) host.dock().secondaryDriven();
                else if (surface == SURFACE_NOTIFY) host.notifyCenter().secondaryDriven();
            }
            return;
        }
        d.button(button, down);
    }

    void click(Router.Device d, int button) {
        if (!open()) d.click(button);
        else if (button == 1) {
            if (surface == SURFACE_DOCK) host.dock().clickDriven();
            else if (surface == SURFACE_NOTIFY) host.notifyCenter().clickDriven();
            else host.menus().clickDriven();
        } else if (button == 3) {
            if (surface == SURFACE_DOCK) host.dock().secondaryDriven();
            else if (surface == SURFACE_NOTIFY) host.notifyCenter().secondaryDriven();
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
        // The centre's Esc is Notify.key's own (the panel, a reply field), the menu's is MacMenus.key's own (the
        // bar's levels); a pointer merely resting over a banner, the bell or the ≡ does not make Esc theirs
        if (surface == SURFACE_NOTIFY && !host.notifyCenter().takesEsc()) return false;
        if (surface == SURFACE_MENU && !host.menus().takesEsc()) return false;
        over = null;
        if (surface == SURFACE_DOCK) host.dock().closeDriven();
        else if (surface == SURFACE_NOTIFY) host.notifyCenter().closeDriven();
        else host.menus().closeDriven();
        surface = 0;
        escTaken = true;
        return true;
    }
}
