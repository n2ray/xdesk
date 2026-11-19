package com.termux.x11;

import android.content.Context;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.termux.x11.app.R;
import com.termux.x11.mac.AutoQuality;
import com.termux.x11.mac.HostSettings;
import com.termux.x11.mac.MacClient;
import com.termux.x11.mac.MacClients;
import com.termux.x11.mac.MacStore;

import java.util.List;

/**
 * The quick quality row (AutoQuality's three modes, SALL-SYNC.md "XDesk's own additions"): Sharp / Balanced /
 * Smooth as one segmented control; a Mac whose settings match none of the three says Custom in the note. It sits at
 * the top of a Mac's device page (DeviceSettings) and of a Mac item's card (ItemCard); Ctrl+Alt+Q
 * (XDeskActivity) cycles the same modes while a Mac is on the external screen. Picking a mode sends only the
 * fields that differ, together in one change (MacClient.changeAll), and tells the Mac's AutoQuality that this is
 * the user's mode now.
 *
 * With a refill runnable the row also follows the Mac's settings while its page shows (its own SettingsListener,
 * detach() when the page closes); the stream page follows them itself and passes none.
 */
final class MacModeRow {
    private final Context c;
    private final SettingsUi s;
    private final String macId;
    private final MacClient client;
    private final Runnable refill;
    private MacClient.SettingsListener follow;
    private boolean attached;

    private MacModeRow(Context c, SettingsUi s, String macId, Runnable refill) {
        this.c = c;
        this.s = s;
        this.macId = macId;
        this.client = MacClients.get(c, macId);
        this.refill = refill;
    }

    /**
     * The row, drawn into {@code into}. Null when this Mac (or its host) has nothing the modes can set: a mode
     * sets the bitrate, the resolution and the speed, and a field the Mac did not report is never shown or sent.
     */
    static MacModeRow add(Context c, SettingsUi s, String macId, LinearLayout into, Runnable refill) {
        MacModeRow row = new MacModeRow(c, s, macId, refill);
        if (!row.draw(into)) return null;
        if (refill != null) row.follow();
        return row;
    }

    /** The page closed: stop following the Mac's settings. */
    void detach() {
        if (!attached) return;
        attached = false;
        client.removeSettingsListener(follow);
    }

    private void follow() {
        attached = true;
        follow = new MacClient.SettingsListener() {
            @Override public void settingsChanged() { refill.run(); }
            @Override public void settingsAnswer(int result) { }
        };
        client.addSettingsListener(follow);
    }

    private boolean draw(LinearLayout into) {
        HostSettings live = client.settings();
        HostSettings h = live != null ? live : HostSettings.saved(MacStore.stream(c, macId).host);
        if (h == null || h.bitrate == null || h.captureScale == null || h.prioritizeSpeed == null) return false;
        String[] names = c.getResources().getStringArray(R.array.xd_mac_mode_names);
        String[] options = {names[0], names[1], names[2]};
        int at = AutoQuality.modeOf(h.bitrate, h.captureScale, h.prioritizeSpeed);
        String note = c.getString(R.string.xd_mac_mode_note);
        // None of the three matches: a setting made on the Mac or by hand. Said in words, not as a segment that
        // could be picked (there is nothing to pick), and no segment is marked while it holds
        if (at < 0) note = c.getString(R.string.xd_mac_mode_custom_now) + "\n" + note;
        String pending = client.pendingSetting();
        if (pending != null && (HostSettings.BITRATE.equals(pending) || HostSettings.CAPTURE_SCALE.equals(pending)
                || HostSettings.PRIORITIZE_SPEED.equals(pending)))
            note = c.getString(R.string.xd_mac_set_waiting);
        LinearLayout card = s.card(into);
        if (into.getChildCount() == 0) ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = Style.dp(c, 16);
        if (live != null && pending == null) {
            final int now = at;
            s.segmentedRow(card, c.getString(R.string.xd_mac_mode), note, options, now, which -> {
                if (which != now) apply(which);
            });
        } else {
            String value = at >= 0 ? options[at] : c.getString(R.string.xd_mac_quality_custom);
            s.infoRow(card, c.getString(R.string.xd_mac_mode), note, value);
        }
        return true;
    }

    private void apply(int mode) {
        if (!apply(c, client, mode))
            Toast.makeText(c, c.getString(R.string.xd_mac_set_failed), Toast.LENGTH_LONG).show();
    }

    /** Pick a mode for a Mac: the fields that differ, in one change, and it is the user's mode from now on. */
    static boolean apply(Context c, MacClient client, int mode) {
        HostSettings h = client.settings();
        if (h == null) h = HostSettings.saved(MacStore.stream(c, client.mac().id).host);
        if (h == null || h.bitrate == null || h.captureScale == null || h.prioritizeSpeed == null) return false;
        List<AutoQuality.Change> changes = MacClient.modeChanges(h, mode);
        if (changes.isEmpty()) return true;  // already there: nothing to send
        client.quality().userChose(mode, -1, SystemClock.uptimeMillis());
        return client.changeAll(changes, true);
    }
}
