package com.termux.x11;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.x11.app.R;
import com.termux.x11.mac.HostSettings;
import com.termux.x11.mac.MacClient;
import com.termux.x11.mac.MacClients;
import com.termux.x11.mac.MacStore;

/**
 * XDesk settings › Mac › a Mac › Picture and sound: Sill's own settings for that Mac (quality, resolution, frame
 * rate limit, prioritise speed, virtual display, send audio), as Sall's settings panel shows them, and this
 * device's three (the rate it asks for, whether it plays the Mac's sound, whether it mutes itself). While it is
 * open, the round trip is kept fresh once a second (Sall's refreshDiagnostics tick). Opened with open(context, macId).
 *
 * The Mac's settings are the Mac's: this page shows what the Mac last said (kind 16) and sends the one field a
 * control changed (kind 17); what the Mac answers is what the page then shows, taken or not. They hold for every
 * device connected to that Mac, and the Mac's own menu changes them too, so the page follows the Mac while open.
 * Not connected, it shows what the Mac said last time, with nothing to tap. SALL-SYNC.md lists what follows Sall.
 */
final class MacStreamSettings implements MacClient.SettingsListener {
    private final Context c;
    private final SettingsUi s;
    private final String macId;
    private final LinearLayout content;
    private Dialog page;
    private String drawn;
    /** The round trip changes with every pong and the Mac says nothing: a tick keeps it and the mute fresh. */
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            MacClient client = MacClients.get(c, macId);
            if (client == null) {
                if (page != null) page.dismiss();
                return;
            }
            // Sall's diagnosticsTick: redraw only what changed, so the page does not rebuild every second
            if (!snapshot(client).equals(drawn)) fill(client);
            else refreshRtt(client);
            ticker.postDelayed(this, 1000);
        }
    };
    private TextView rttValue;
    private String rttDrawn;

    private MacStreamSettings(Context c, String macId) {
        this.c = c;
        this.macId = macId;
        s = new SettingsUi(c);
        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
    }

    static Dialog open(Context c, String macId) {
        final MacStreamSettings m = new MacStreamSettings(c, macId);
        final MacClient client = MacClients.get(c, macId);
        if (client == null) return null;
        // The Mac's settings come with a connection: ask for one (it closes by itself when nothing shows this Mac)
        client.touch();
        client.setSettingsListener(m);
        m.fill(client);
        m.page = m.s.page(c.getString(R.string.xd_mac_stream_title, client.name()), m.content);
        m.page.setOnDismissListener(d -> {
            MacClient now = MacClients.get(c, macId);
            if (now != null) now.setSettingsListener(null);
            m.ticker.removeCallbacks(m.tick);
        });
        m.ticker.postDelayed(m.tick, 1000);
        return m.page;
    }

    @Override public void settingsChanged() {
        MacClient client = MacClients.get(c, macId);
        if (client == null) {
            if (page != null) page.dismiss();
            return;
        }
        if (!snapshot(client).equals(drawn)) fill(client);
    }

    @Override public void settingsAnswer(int result) {
        if (result == REFUSED) toast(R.string.xd_mac_set_refused);
        else if (result == TIMEOUT) toast(R.string.xd_mac_set_timeout);
    }

    private String snapshot(MacClient client) {
        HostSettings live = client.settings();
        MacStore.Stream own = MacStore.stream(c, macId);
        // rttSlow: the slow-link advice appears and goes with it, without the Mac saying anything
        return (live != null ? live.snapshot() : "saved " + own.host) + "|" + client.pendingSetting() + "|" + own.fpsCap
                + "|" + own.sound + "|" + own.muted + "|" + client.rttSlow();
    }

    /** Just the round trip's number (Sall's refreshDiagnostics updating its readout alone). */
    private void refreshRtt(MacClient client) {
        String value = rttText(client);
        if (value.equals(rttDrawn) || rttValue == null) return;
        rttDrawn = value;
        rttValue.setText(value);
    }

    private String rttText(MacClient client) {
        Long rtt = client.rttMedian();
        return rtt == null ? c.getString(R.string.xd_mac_rtt_none) : c.getString(R.string.xd_mac_rtt_ms, rtt);
    }

    private void fill(final MacClient client) {
        drawn = snapshot(client);
        content.removeAllViews();
        rttValue = null;
        rttDrawn = null;
        final HostSettings live = client.settings();
        final MacStore.Stream own = MacStore.stream(c, macId);
        final HostSettings h = live != null ? live : HostSettings.saved(own.host);
        final String pending = client.pendingSetting();
        // A control only while the Mac listens and has answered the last change; otherwise the value alone
        final boolean open = live != null && pending == null;

        LinearLayout now = s.section(content, c.getString(R.string.xd_mac_stream_now));
        if (live == null) {
            s.infoRow(now, c.getString(h != null ? R.string.xd_mac_stream_offline : R.string.xd_mac_stream_waiting), null, null);
        } else {
            String running = !live.streaming ? c.getString(R.string.xd_mac_stream_idle)
                    : c.getString(R.string.xd_mac_stream_running, live.width, live.height, live.fps, live.mbps);
            s.infoRow(now, running, live.streaming && live.onVirtualDisplay ? c.getString(R.string.xd_mac_stream_on_virtual) : null, null);
            // The round trip, as Sall's readout ends with it; the tick keeps this one line fresh
            rttDrawn = rttText(client);
            rttValue = s.infoRow(now, c.getString(R.string.xd_mac_rtt), null, rttDrawn);
            if (live.softwareEncoder) s.infoRow(now, c.getString(R.string.xd_mac_software_encoder), null, null);
            final HostSettings.Advice advice = live.advice(client.rttSlow());
            if (advice.kind == HostSettings.Advice.BEHIND_HOME_RUNS) {
                s.infoRow(now, c.getString(R.string.xd_mac_link_behind_home), null, null);
            } else if (advice.kind == HostSettings.Advice.BEHIND) {
                s.infoRow(now, c.getString(R.string.xd_mac_link_behind), null, null);
            } else if (advice.kind == HostSettings.Advice.BEHIND_SUGGESTION) {
                String to = qualityTitle(advice.bitrate) + (advice.captureScale == null ? ""
                        : " · " + c.getString(advice.captureScale == 1 ? R.string.xd_mac_scale_standard : R.string.xd_mac_scale_retina));
                String label = c.getString(R.string.xd_mac_link_behind_suggest);
                if (open) s.valueRow(now, label, c.getString(R.string.xd_mac_link_use, to), "", () -> change(client, HostSettings.BITRATE, advice.bitrate, advice.captureScale));
                else s.infoRow(now, label, to, null);
            } else if (advice.kind == HostSettings.Advice.SLOW) {
                s.infoRow(now, c.getString(R.string.xd_mac_rtt_slow), null, null);
            }
            if (live.thisConnectionAway) s.infoRow(now, c.getString(R.string.xd_mac_away), null, null);
        }

        if (h != null) {
            LinearLayout picture = s.section(content, c.getString(R.string.xd_mac_picture));
            if (h.bitrate != null) {
                String note = waiting(pending, HostSettings.BITRATE, R.string.xd_mac_quality_note);
                if (open) s.valueRow(picture, c.getString(R.string.xd_mac_quality), note, qualityTitle(h.bitrate), () -> pickQuality(client));
                else s.infoRow(picture, c.getString(R.string.xd_mac_quality), note, qualityTitle(h.bitrate));
            }
            if (h.captureScale != null) {
                String[] names = {c.getString(R.string.xd_mac_scale_retina), c.getString(R.string.xd_mac_scale_standard)};
                final int at = h.captureScale >= 1.5 ? 0 : 1;
                String note = waiting(pending, HostSettings.CAPTURE_SCALE, R.string.xd_mac_scale_note);
                if (open) s.segmentedRow(picture, c.getString(R.string.xd_mac_scale), note, names, at, which -> {
                    if (which != at) change(client, HostSettings.CAPTURE_SCALE, HostSettings.SCALE_CHOICES[which], null);
                });
                else s.infoRow(picture, c.getString(R.string.xd_mac_scale), note, names[at]);
            }
            if (h.maxFPS != null) {
                String[] names = new String[HostSettings.FPS_CHOICES.length];
                int found = -1;
                for (int i = 0; i < names.length; i++) {
                    names[i] = c.getString(R.string.xd_mac_fps_value, HostSettings.FPS_CHOICES[i]);
                    if (HostSettings.FPS_CHOICES[i] == h.maxFPS) found = i;
                }
                final int at = found;
                // A limit set by hand on the Mac (defaults write) is none of the choices: said in the note
                String note = waiting(pending, HostSettings.MAX_FPS, R.string.xd_mac_fps_note);
                if (at < 0) note = c.getString(R.string.xd_mac_fps_value, h.maxFPS) + ". " + note;
                if (open) s.segmentedRow(picture, c.getString(R.string.xd_mac_fps), note, names, at, which -> {
                    if (which != at) change(client, HostSettings.MAX_FPS, HostSettings.FPS_CHOICES[which], null);
                });
                else s.infoRow(picture, c.getString(R.string.xd_mac_fps), note, c.getString(R.string.xd_mac_fps_value, h.maxFPS));
            }
            hostSwitch(picture, client, h, open, pending, HostSettings.PRIORITIZE_SPEED, R.string.xd_mac_speed, c.getString(R.string.xd_mac_speed_note));
            if (h.virtualDisplay != null) {
                String note = h.virtualDisplayNote.isEmpty() ? c.getString(R.string.xd_mac_virtual_note)
                        : h.virtualDisplayNote + "\n" + c.getString(R.string.xd_mac_virtual_note);
                if (h.canChangeVirtualDisplay()) {
                    hostSwitch(picture, client, h, open, pending, HostSettings.VIRTUAL_DISPLAY, R.string.xd_mac_virtual, note);
                } else {
                    s.infoRow(picture, c.getString(R.string.xd_mac_virtual),
                            h.virtualDisplayNote.isEmpty() ? c.getString(R.string.xd_mac_virtual_unavailable) : h.virtualDisplayNote,
                            c.getString(R.string.xd_mac_off));
                }
            }
        }

        // This device's own: kept here (MacStore.Stream), never sent as a setting
        LinearLayout device = s.section(content, c.getString(R.string.xd_mac_this_device));
        final int[] caps = {0, 60, 30};
        String[] capNames = {c.getString(R.string.xd_mac_cap_screen), c.getString(R.string.xd_mac_fps_value, 60), c.getString(R.string.xd_mac_fps_value, 30)};
        int capAt = own.fpsCap == 60 ? 1 : own.fpsCap == 30 ? 2 : 0;
        s.segmentedRow(device, c.getString(R.string.xd_mac_cap), c.getString(R.string.xd_mac_cap_note), capNames, capAt, which -> {
            MacStore.setStreamOptions(c, macId, caps[which], MacStore.stream(c, macId).sound, MacStore.stream(c, macId).muted);
            client.streamOptionsChanged();
            drawn = snapshot(client);
        });

        LinearLayout sound = s.section(content, c.getString(R.string.xd_mac_sound));
        if (h != null && h.sendAudio == null) {
            // A Sill from before it had sound: the switch shows off and cannot be used (StreamSettings.sendAudio)
            s.infoRow(sound, c.getString(R.string.xd_mac_send_audio), c.getString(R.string.xd_mac_send_audio_update), c.getString(R.string.xd_mac_off));
        } else if (h != null) {
            String note = c.getString(R.string.xd_mac_send_audio_note);
            if (!h.audioNote.isEmpty()) note = h.audioNote + "\n" + note;
            hostSwitch(sound, client, h, open, pending, HostSettings.SEND_AUDIO, R.string.xd_mac_send_audio, note);
        }
        if (MacClient.soundSupported()) {
            s.switchRow(sound, c.getString(R.string.xd_mac_play_sound), c.getString(R.string.xd_mac_play_sound_note), own.sound, on -> {
                MacStore.setStreamOptions(c, macId, MacStore.stream(c, macId).fpsCap, on, MacStore.stream(c, macId).muted);
                client.streamOptionsChanged();
                drawn = snapshot(client);
            });
            if (own.sound) {
                // Sall's "mute this device": the sound still streams, this device says nothing; no reconnect
                s.switchRow(sound, c.getString(R.string.xd_mac_mute), c.getString(R.string.xd_mac_mute_note), own.muted, on -> {
                    client.setSoundMuted(on);
                    drawn = snapshot(client);
                });
            }
        } else {
            s.infoRow(sound, c.getString(R.string.xd_mac_play_sound), c.getString(R.string.xd_mac_play_sound_unsupported), c.getString(R.string.xd_mac_off));
        }

        if (h != null) {
            LinearLayout foot = s.section(content, c.getString(R.string.xd_mac_stream_about));
            s.infoRow(foot, c.getString(live != null && !live.persistent ? R.string.xd_mac_stream_kept_session : R.string.xd_mac_stream_kept), null, null);
        }
    }

    /** A switch for one of the Mac's on/off settings, or its value alone while it cannot be changed. */
    private void hostSwitch(LinearLayout card, final MacClient client, HostSettings h, boolean open, String pending,
                            final String field, int label, String note) {
        if (!(h.value(field) instanceof Boolean)) return;
        final boolean on = (Boolean) h.value(field);
        if (field.equals(pending)) note = c.getString(R.string.xd_mac_set_waiting);
        if (open) s.switchRow(card, c.getString(label), note, on, next -> change(client, field, next, null));
        else s.infoRow(card, c.getString(label), note, c.getString(on ? R.string.xd_mac_on : R.string.xd_mac_off));
    }

    private String waiting(String pending, String field, int note) {
        return c.getString(field.equals(pending) ? R.string.xd_mac_set_waiting : note);
    }

    /** "Balanced — 15 Mbps", or "Custom — 12 Mbps" for a bitrate set by hand on the Mac (QualityPreset.title). */
    private String qualityTitle(int bitrate) {
        String[] names = c.getResources().getStringArray(R.array.xd_mac_quality_names);
        int i = HostSettings.preset(bitrate);
        return (i >= 0 && i < names.length ? names[i] : c.getString(R.string.xd_mac_quality_custom)) + " — " + HostSettings.mbps(bitrate) + " Mbps";
    }

    private void pickQuality(final MacClient client) {
        HostSettings h = client.settings();
        if (h == null || h.bitrate == null) return;
        String[] options = new String[HostSettings.QUALITY_RATES.length];
        for (int i = 0; i < options.length; i++) options[i] = qualityTitle(HostSettings.QUALITY_RATES[i]);
        final int at = HostSettings.preset(h.bitrate);
        s.choice(c.getString(R.string.xd_mac_quality), c.getString(R.string.xd_mac_quality_fast_note), options, at, which -> {
            if (which != at) change(client, HostSettings.BITRATE, HostSettings.QUALITY_RATES[which], null);
        });
    }

    private void change(MacClient client, String field, Object value, Double captureScale) {
        if (client.changeSetting(field, value, captureScale)) return;
        // Not sent (the connection just went, or another change still waits): show the Mac's values again
        toast(R.string.xd_mac_set_failed);
        fill(client);
    }

    private void toast(int text) {
        Toast.makeText(c, c.getString(text), Toast.LENGTH_LONG).show();
    }
}
