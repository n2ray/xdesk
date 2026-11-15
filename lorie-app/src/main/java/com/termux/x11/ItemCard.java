package com.termux.x11;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;

import java.util.List;

/**
 * The one card of a launcher item, whatever it is: an Android app ("a:"), a whole Linux desktop ("t:"), a Linux
 * program or window ("l:"), a Mac's desktop, window or app ("m:"). Opened the same way everywhere: a long press on a
 * row of the phone's launcher (ChooserView) or on an icon of the favourites strip (DockBar), the secondary button on a
 * tile of the external Launchpad (SwitcherOverlay) or on an icon of the external Dock (ExternalDock). It is always a
 * dialog on the phone.
 *
 * The top is the same for every kind: icon, name, the device it belongs to and its state, then the actions that
 * apply (open, close, favourite, hide) and its place among the favourites while it is one. Below that only what the
 * kind really has: an Android app's own settings; for everything of a Linux environment or a Mac the way to that
 * device's settings. Nothing is shown greyed out. The launcher's own tiles (ExternalLauncher.SWITCHER) have no card.
 *
 * The top follows what changes while the card is open (Devices' listeners); the settings below are built once, so a
 * number being typed is not lost. UI thread.
 */
final class ItemCard {
    /** What the card asks of XDeskActivity. All calls on the UI thread. */
    interface Host {
        /** The key on the display now, or null. */
        String current();
        /** The display in use is the car's: the wording differs. */
        boolean onCar();
        List<String> favourites();
        void toggleFavourite(String key);
        /** One place towards the start (-1) or the end (1) of the favourites. */
        void moveFavourite(String key, int by);
        /** The normal way anything is opened or switched to. The card has closed. */
        void open(String key);
        /** Android apps: their saved settings and closing them. */
        AppSession apps();
        /** An Android app's input mode was changed here. */
        void appInputChanged();
    }

    private static Dialog shown;

    private final Context c;
    private final Host host;
    private final String key;
    private final SettingsUi s;
    private final Devices devices;
    /** Not null for an Android app. */
    private final AppCatalog.App app;
    private final String session;
    private final ContentSource source;
    private TextView title, sub, detail;
    private LinearLayout actions, order;
    private Dialog card;

    private ItemCard(Context c, Host host, String key) {
        this.c = c;
        this.host = host;
        this.key = key;
        s = new SettingsUi(c);
        devices = Devices.get(c);
        app = ChooserView.isAppKey(key) ? AppCatalog.get(c).find(ChooserView.componentOf(key)) : null;
        source = Contents.sourceOf(key);
        session = source == null && app == null && key.startsWith("t:") ? ChooserView.sessionOf(key) : null;
    }

    /**
     * @param c an activity: the card is a dialog
     * @return false when this key has no card: a tile of the launcher itself, an app that is gone, the apps-only X
     */
    static boolean show(Context c, Host host, String key) {
        if (key == null || ExternalLauncher.SWITCHER.equals(key)) return false;
        ItemCard card = new ItemCard(c, host, key);
        if (card.app == null && card.session == null && card.entry() == null) return false;
        // What Linux programs run on is not an item (DEVICES.md §2)
        if (card.session != null && card.session.endsWith(Devices.APPS_ONLY)) return false;
        // One card at a time: asked for again from the external screen, the new one takes its place
        if (shown != null) {
            try {
                shown.dismiss();
            } catch (RuntimeException ignored) { }  // its activity is gone, and its window with it
            shown = null;
        }
        card.build();
        return true;
    }

    private int dp(float v) {
        return Style.dp(c, v);
    }

    private String str(int id) {
        return c.getString(id);
    }

    /** A content's entry as its source has it now; null for other kinds, and when it is gone. */
    private ContentSource.Entry entry() {
        return source != null ? Contents.find(key) : null;
    }

    private boolean isFavourite() {
        return host.favourites().contains(key);
    }

    /** The device as it is now, or null when it has no tab of its own (a Linux environment not added as a device). */
    private Devices.Device device() {
        return devices.find(devices.deviceOf(key));
    }

    private String deviceName() {
        String id = devices.deviceOf(key);
        if (id.equals(Devices.PHONE)) return str(R.string.xd_dev_phone);
        if (id.startsWith(Devices.LINUX)) return Devices.linuxName(c, id);
        Devices.Device d = devices.find(id);
        return d != null ? d.name : "";
    }

    /** An app, not a window or a desktop: only those are hidden (DEVICES.md §3). */
    private boolean hideable() {
        if (app != null) return true;
        if (source == null) return false;
        for (ContentSource.Entry e : source.apps()) if (e.key.equals(key)) return true;
        return false;
    }

    private boolean isMac() {
        return key.startsWith(MacSource.PREFIX);
    }

    private boolean running() {
        if (key.equals(host.current())) return true;
        if (session != null) return session.equals(devices.runningSession());
        ContentSource.Entry e = entry();
        return e != null && e.running;
    }

    // --- the top ---

    private void build() {
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // A device badge hangs a little past the icon's edge: don't cut it at the head's bounds
        head.setClipToPadding(false);
        int iconSize = dp(52);
        head.addView(icon(), new LinearLayout.LayoutParams(iconSize, iconSize));
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        title = Style.label(c, "", Style.TEXT, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);
        // A window's title: two windows of one program have the same name
        detail = Style.label(c, "", Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(detail);
        sub = Style.label(c, "", Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = dp(12);
        head.addView(titles, tp);

        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        actions = new LinearLayout(c);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        // The place among the favourites: only while it is one
        order = s.card(content);
        ((LinearLayout.LayoutParams) order.getLayoutParams()).topMargin = dp(10);
        LinearLayout orderRow = new LinearLayout(c);
        orderRow.setGravity(Gravity.CENTER_VERTICAL);
        orderRow.setPadding(dp(12), dp(6), dp(8), dp(6));
        orderRow.addView(Style.label(c, str(R.string.xd_card_position), Style.TEXT, Style.TEXT_BODY), new LinearLayout.LayoutParams(0, -2, 1));
        TextView left = Style.button(c, "←", Style.SURFACE_RAISED, Style.TEXT_BODY + 1, Style.RADIUS);
        left.setOnClickListener(v -> {
            Style.tick(v);
            host.moveFavourite(key, -1);
        });
        orderRow.addView(left, new LinearLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET - 8)));
        TextView right = Style.button(c, "→", Style.SURFACE_RAISED, Style.TEXT_BODY + 1, Style.RADIUS);
        right.setOnClickListener(v -> {
            Style.tick(v);
            host.moveFavourite(key, 1);
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET - 8));
        rp.leftMargin = dp(6);
        orderRow.addView(right, rp);
        order.addView(orderRow, new LinearLayout.LayoutParams(-1, -2));

        // By kind
        final LinearLayout below = new LinearLayout(c);
        below.setOrientation(LinearLayout.VERTICAL);
        content.addView(below, new LinearLayout.LayoutParams(-1, -2));
        if (app != null) appSettings(below);

        final Runnable again = () -> {
            refresh();
            // The device's rows say its state (Disconnect only while connected); an app's settings stay as built
            if (app == null) {
                below.removeAllViews();
                deviceRows(below);
            }
        };
        again.run();
        devices.addListener(again);
        card = s.bottomSheet(head, content);
        shown = card;
        final Dialog mine = card;
        card.setOnDismissListener(d -> {
            devices.removeListener(again);
            if (shown == mine) shown = null;
        });
    }

    /** What the lists draw for it. */
    private View icon() {
        ImageView view = new ImageView(c);
        Switchboard.Item it = Switchboard.get(c).find(key);
        if (it != null) ExternalLauncher.icon(c, it, view, Style.BACKGROUND, false);
        else view.setImageDrawable(Style.letterIcon(c, "?"));  // gone: the card is closing
        return view;
    }

    /** Name, device and state, the actions: everything that can change while the card is open. */
    private void refresh() {
        ContentSource.Entry e = entry();
        title.setText(app != null ? app.label : session != null ? session : e != null ? e.label : title.getText());
        boolean window = e != null && e.detail != null && !ChooserView.isDesktopKey(key) && !e.detail.equalsIgnoreCase(e.label);
        detail.setText(window ? e.detail : "");
        detail.setVisibility(window ? View.VISIBLE : View.GONE);
        boolean onDisplay = key.equals(host.current());
        String state = state(e, onDisplay);
        String where = session != null ? deviceName() + " · " + str(R.string.xd_dev_whole_desktop) : deviceName();
        sub.setText(state != null ? where + " · " + state : where);
        sub.setTextColor(onDisplay ? Style.OK : Style.TEXT_DIM);
        order.setVisibility(isFavourite() ? View.VISIBLE : View.GONE);
        fillActions(e);
    }

    /** On the display, running or not; for something of a Mac that is not connected or locked, that. Null: nothing to say. */
    private String state(ContentSource.Entry e, boolean onDisplay) {
        if (onDisplay) return str(host.onCar() ? R.string.xd_on_car : R.string.xd_card_on_display);
        if (app != null) return null;  // whether an Android app runs behind is not known here
        if (session != null) {
            if (session.equals(devices.stoppingSession())) return str(R.string.xd_chooser_stopping_short);
            return str(session.equals(devices.runningSession()) ? R.string.xd_dev_state_running : R.string.xd_dev_state_not_running);
        }
        if (isMac()) {
            Devices.Device d = device();
            if (d != null && d.state != Devices.State.CONNECTED) return str(Devices.stateLabel(d));
            if (d != null && Devices.isLocked(c, d.id)) return str(R.string.xd_mac_locked_short);
            if (ChooserView.isDesktopKey(key)) return str(R.string.xd_dev_state_connected);
        }
        if (e == null) return null;  // gone while the card is open
        return str(e.running ? R.string.xd_dev_state_running : R.string.xd_dev_state_not_running);
    }

    // --- the actions ---

    private TextView action(int label, int color, int textColor, final Runnable run) {
        TextView b = Style.button(c, str(label), color, Style.TEXT_SMALL + 2, Style.RADIUS);
        b.setTextColor(textColor);
        b.setMaxLines(2);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setOnClickListener(v -> {
            Style.tick(v);
            run.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(Style.TARGET), 1);
        if (actions.getChildCount() > 0) p.leftMargin = dp(Style.GAP);
        actions.addView(b, p);
        return b;
    }

    private void fillActions(ContentSource.Entry e) {
        actions.removeAllViews();
        // Gone while the card was open (the window closed, the Mac was removed): only the favourite is left to undo
        boolean there = source == null || e != null;
        final boolean hidden = devices.isHidden(key);
        if (there) {
            if (!key.equals(host.current())) {
                action(running() ? R.string.xd_card_switch_to : R.string.xd_card_open, Style.ACCENT, Style.TEXT, () -> {
                    card.dismiss();
                    host.open(key);
                });
            }
            addClose(e);
            if (hidden || hideable()) {
                action(hidden ? R.string.xd_dev_unhide : R.string.xd_dev_hide, Style.SURFACE, Style.TEXT, () -> {
                    // Hidden things are not favourites: the favourites must not hold what can't be seen
                    if (!hidden && isFavourite()) host.toggleFavourite(key);
                    devices.setHidden(key, !hidden);  // its listener fills the card again
                });
            }
        }
        // No star on what is hidden, as in the lists
        if (hidden && !isFavourite()) return;
        boolean docked = isFavourite();
        ImageView star = new ImageView(c);
        int pad = dp(11);
        star.setPadding(pad, pad, pad, pad);
        star.setBackground(Style.ripple(c, Style.SURFACE, Style.RADIUS));
        star.setImageResource(docked ? R.drawable.xd_ic_star : R.drawable.xd_ic_star_outline);
        star.setColorFilter(docked ? Style.WARN : Style.TEXT_DIM);
        star.setContentDescription(str(docked ? R.string.xd_chooser_unfavourite : R.string.xd_chooser_favourite));
        star.setOnClickListener(v -> {
            Style.tick(v);
            host.toggleFavourite(key);
            refresh();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(Style.TARGET), dp(Style.TARGET));
        if (actions.getChildCount() > 0) sp.leftMargin = dp(Style.GAP);
        actions.addView(star, sp);
    }

    /** Close or quit, where there is a way to: nothing new is invented here. */
    private void addClose(ContentSource.Entry e) {
        if (app != null) {
            // Android apps are closed through the injector service; asked first, as before
            final AppSession apps = host.apps();
            if (!apps.canCloseApps()) return;
            action(R.string.xd_close, Style.SURFACE, Style.DANGER, () ->
                s.confirm(c.getString(R.string.xd_card_close_title, app.label), str(R.string.xd_unsaved_may_be_lost), str(R.string.xd_close), () -> {
                    card.dismiss();
                    apps.closeApp(app);
                }));
        } else if (session != null) {
            // A whole desktop ends with the X server all Linux environments share: the device's Stop, which asks first
            final Devices.Device d = device();
            if (d == null || !d.canStop || d.busy || !session.equals(devices.runningSession())) return;
            action(R.string.xd_dev_stop, Style.SURFACE, Style.DANGER, () -> devices.stop(d.id));
        } else if (e != null && e.running && !ChooserView.isDesktopKey(key)) {
            // A window closes; a program quits, which for a Mac app is its windows closing (ContentSource.close).
            // A Mac's desktop is not closed: its Mac is disconnected, below
            boolean program = hideable();
            action(program ? R.string.xd_dev_quit : R.string.xd_close, Style.SURFACE, Style.DANGER, () -> source.close(key));
        }
    }

    // --- below, by kind ---

    /** An Android app: what XDesk remembers for it. */
    private void appSettings(LinearLayout into) {
        final AppSession apps = host.apps();
        int global = apps.savedDensity() > 0 ? apps.savedDensity() : apps.defaultDensity();
        s.groupLabel(into, str(R.string.xd_card_display));
        LinearLayout display = s.card(into);
        s.inlineNumberRow(display, str(R.string.xd_density), global > 0 ? c.getString(R.string.xd_card_density_note_value, global) : str(R.string.xd_card_density_note),
            global > 0 ? String.valueOf(global) : str(R.string.xd_chooser_default), apps.appDensity(app), XDeskActivity.DENSITY_MIN, XDeskActivity.DENSITY_MAX,
            dpi -> apps.setAppDensity(app, dpi), () -> apps.setAppDensity(app, 0));
        if (AppSession.fullscreenSupported()) {
            s.segmentedRow(display, str(R.string.xd_card_fullscreen), str(apps.isFullscreenDefault() ? R.string.xd_card_fullscreen_note_on : R.string.xd_card_fullscreen_note_off),
                new String[]{str(R.string.xd_card_follow_default), str(R.string.xd_on), str(R.string.xd_off)}, apps.appFullscreen(app), which -> apps.setAppFullscreen(app, which));
        }
        if (AppSession.touchModeAllowed(app)) {
            s.groupLabel(into, str(R.string.xd_section_input));
            LinearLayout input = s.card(into);
            s.segmentedRow(input, str(R.string.xd_card_mode), str(R.string.xd_card_mode_note), new String[]{str(R.string.xd_mode_mouse), str(R.string.xd_mode_touch)},
                apps.isTouchMode(app) ? 1 : 0, which -> {
                    apps.setTouchMode(app, which == 1);
                    host.appInputChanged();
                });
        }
    }

    /**
     * Everything of a Linux environment or a Mac: its settings are the device's, so this is the way there. A Linux
     * device's page has Stop and its desktops; a Mac's settings its keyboard and fit, and a connected Mac can be
     * disconnected here.
     */
    private void deviceRows(LinearLayout into) {
        if (isMac()) {
            final Devices.Device d = device();
            if (d == null) return;  // removed while the card is open
            s.groupLabel(into, d.name);
            LinearLayout rows = s.card(into);
            s.valueRow(rows, c.getString(R.string.xd_card_device_settings, d.name), str(R.string.xd_dev_mac_options), "", () -> MacSettings.open(c));
            if (d.state == Devices.State.CONNECTED && !d.busy)
                s.valueRow(rows, str(R.string.xd_dev_disconnect), null, "", () -> confirmDisconnect(c, d));
            return;
        }
        // An environment that is not a device of its own has no page: Termux's then, where the X server they share is
        Devices.Device own = device();
        final Devices.Device d = own != null ? own : devices.find(Devices.LINUX + Devices.TERMUX);
        if (d == null) return;
        s.groupLabel(into, d.name);
        LinearLayout rows = s.card(into);
        String state = str(Devices.stateLabel(d));
        s.valueRow(rows, c.getString(R.string.xd_card_device_settings, d.name), d.note != null && !d.note.isEmpty() ? state + " · " + d.note : state, "",
            () -> DeviceSettings.openDevice(c, d.id));
    }

    /** Disconnecting a Mac is asked first, wherever it is offered (the launcher's device bar, this card). */
    static void confirmDisconnect(final Context c, final Devices.Device d) {
        new SettingsUi(c).confirm(c.getString(R.string.xd_dev_disconnect_title, d.name), c.getString(R.string.xd_dev_disconnect_mac_note),
            c.getString(R.string.xd_dev_disconnect), () -> Devices.get(c).disconnect(d.id));
    }
}
