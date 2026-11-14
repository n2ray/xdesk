/*
 * Derived from Sill (https://github.com/Saffsanity/sill, Sources/StreamProtocol/HostSettings.swift) and its
 * Android client Sall (https://github.com/n2ray/sall: MainActivity's settings panel, HostSettingsOptions.java,
 * ConnectionDiagnostics.java). Copyright 2026 Noah Saffer. Licensed under the Apache License, Version 2.0.
 * Modified for XDesk. SALL-SYNC.md says which Sall commit this follows and how to compare later.
 */
package com.termux.x11.mac;

import org.json.JSONObject;

/**
 * The Mac's settings as Sill tells a device (kind 16, HostSettingsState) and what a device may ask for (kind 17,
 * HostSettingsChange). The Mac keeps them (Sill.app saves them, and its own menu changes them too): a device never
 * stores or re-sends them, it shows what the Mac last said and sends the one field a control changed.
 *
 * Every field is optional, as on the wire: null is a host that does not have the setting, and such a field is
 * never shown as a control nor sent (HostSettings.swift's rule 6).
 */
public final class HostSettings {
    // HostSettingsChange's keys (the Swift property names)
    public static final String MAX_FPS = "maxFPS", BITRATE = "bitrate", CAPTURE_SCALE = "captureScale",
            PRIORITIZE_SPEED = "prioritizeSpeed", VIRTUAL_DISPLAY = "virtualDisplay", SEND_AUDIO = "sendAudio";

    /** SettingsChoices: exactly what the Mac's menu offers; the host refuses anything else from a device. */
    public static final int[] FPS_CHOICES = {60, 120};
    public static final double[] SCALE_CHOICES = {2, 1};
    /** QualityPreset, ascending: Low, Efficient, Balanced, High, Pro, Ultra, Extreme. Bits per second per 60 fps. */
    public static final int[] QUALITY_RATES = {4000000, 8000000, 15000000, 25000000, 40000000, 80000000, 150000000};

    // settings (StreamSettings): the target, already reported while a change still waits for its restart
    public final Integer maxFPS, bitrate;
    public final Double captureScale;
    public final Boolean prioritizeSpeed, virtualDisplay, directWireless, sendAudio;

    /** Sill.app saves changes; SillHost (the command line host) keeps them until it quits. */
    public final boolean persistent;
    /** FALSE: this host cannot run the virtual display at all. */
    public final Boolean virtualDisplayAvailable;
    public final String virtualDisplayNote, audioNote;
    public final boolean softwareEncoder;

    // stream (RunningStream): what runs now; streaming false while nothing streams
    public final boolean streaming, onVirtualDisplay;
    public final int width, height, fps, mbps;

    // away (AwayQuality): this connection's quality picks set the Mac's away quality
    public final boolean hasAway, thisConnectionAway, awayRunning;

    // link (LinkReport): only while the link to this device does not keep up
    public final String linkState;
    public final Number suggestedBitrate, suggestedCaptureScale;
    public final int carriedKbps;

    /** The token of the change this state answers, or -1. */
    public final int answering;
    /** The whole "settings" object as text: what MacStore keeps to show while the Mac is not connected. */
    public final String settingsJson;

    private HostSettings(JSONObject o) {
        JSONObject s = o.optJSONObject("settings");
        if (s == null) s = new JSONObject();
        settingsJson = s.toString();
        maxFPS = s.opt(MAX_FPS) instanceof Number ? ((Number) s.opt(MAX_FPS)).intValue() : null;
        bitrate = s.opt(BITRATE) instanceof Number ? ((Number) s.opt(BITRATE)).intValue() : null;
        captureScale = s.opt(CAPTURE_SCALE) instanceof Number ? ((Number) s.opt(CAPTURE_SCALE)).doubleValue() : null;
        prioritizeSpeed = bool(s, PRIORITIZE_SPEED);
        virtualDisplay = bool(s, VIRTUAL_DISPLAY);
        directWireless = bool(s, "directWireless");
        sendAudio = bool(s, SEND_AUDIO);
        persistent = o.optBoolean("persistent", false);
        virtualDisplayAvailable = bool(o, "virtualDisplayAvailable");
        virtualDisplayNote = text(o, "virtualDisplayNote");
        audioNote = Boolean.TRUE.equals(sendAudio) ? text(o, "audioNote") : "";
        softwareEncoder = o.optBoolean("softwareEncoder", false);
        JSONObject r = o.optJSONObject("stream");
        width = r == null ? 0 : r.optInt("width");
        height = r == null ? 0 : r.optInt("height");
        fps = r == null ? 0 : r.optInt("fps");
        mbps = r == null ? 0 : r.optInt("mbps");
        streaming = width > 0 && height > 0 && fps > 0 && mbps > 0;
        onVirtualDisplay = r != null && r.optBoolean("onVirtualDisplay", false);
        JSONObject a = o.optJSONObject("away");
        hasAway = a != null;
        thisConnectionAway = a != null && a.optBoolean("thisConnectionAway");
        awayRunning = a != null && a.optBoolean("awayRunning");
        JSONObject l = o.optJSONObject("link");
        linkState = l == null ? "" : l.optString("state", "");
        suggestedBitrate = l != null && l.opt("suggestedBitrate") instanceof Number ? (Number) l.opt("suggestedBitrate") : null;
        suggestedCaptureScale = l != null && l.opt("suggestedCaptureScale") instanceof Number ? (Number) l.opt("suggestedCaptureScale") : null;
        carriedKbps = l == null ? 0 : l.optInt("carriedKbps", 0);
        answering = o.optInt("answering", -1);
    }

    private static Boolean bool(JSONObject o, String key) {
        return o.opt(key) instanceof Boolean ? (Boolean) o.opt(key) : null;
    }

    private static String text(JSONObject o, String key) {
        return o.opt(key) instanceof String ? ((String) o.opt(key)).trim() : "";
    }

    /** A kind 16 payload. */
    static HostSettings parse(JSONObject state) {
        return new HostSettings(state);
    }

    /** What MacStore kept of an earlier session: the settings alone, nothing of a stream or a link. */
    public static HostSettings saved(String settingsJson) {
        if (settingsJson == null || settingsJson.isEmpty()) return null;
        try {
            return new HostSettings(new JSONObject().put("settings", new JSONObject(settingsJson)));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** Whether a control for this field has anything to show and may send it. */
    public Object value(String field) {
        switch (field) {
        case MAX_FPS: return maxFPS;
        case BITRATE: return bitrate;
        case CAPTURE_SCALE: return captureScale;
        case PRIORITIZE_SPEED: return prioritizeSpeed;
        case VIRTUAL_DISPLAY: return virtualDisplay;
        case SEND_AUDIO: return sendAudio;
        default: return null;
        }
    }

    /** Sall's HostSettingsOptions.canChangeVirtualDisplay: off is always allowed, on only where the host can. */
    public boolean canChangeVirtualDisplay() {
        return Boolean.TRUE.equals(virtualDisplay) || !Boolean.FALSE.equals(virtualDisplayAvailable);
    }

    /** The index in QUALITY_RATES, or -1 for a bitrate set by hand on the Mac ("Custom"). */
    public static int preset(int bitrate) {
        for (int i = 0; i < QUALITY_RATES.length; i++) if (QUALITY_RATES[i] == bitrate) return i;
        return -1;
    }

    /** "15", or "8.5" for a bitrate that is not whole megabits (QualityPreset.mbps). */
    public static String mbps(int bitsPerSecond) {
        return bitsPerSecond % 1000000 == 0 ? String.valueOf(bitsPerSecond / 1000000)
                : String.format(java.util.Locale.ROOT, "%.1f", bitsPerSecond / 1e6);
    }

    /** Everything a settings page shows, as one text: it draws again only when this changes. */
    public String snapshot() {
        return settingsJson + "|" + persistent + "|" + virtualDisplayAvailable + "|" + virtualDisplayNote + "|" + audioNote
                + "|" + softwareEncoder + "|" + width + "x" + height + "@" + fps + "/" + mbps + "/" + onVirtualDisplay
                + "|" + thisConnectionAway + "/" + awayRunning + "|" + linkState + "/" + suggestedBitrate + "/" + suggestedCaptureScale;
    }

    /** What the Mac's link report asks of the user (Sall's ConnectionDiagnostics.advice, without its round trip part). */
    public static final class Advice {
        public static final int NONE = 0, BEHIND = 1, BEHIND_HOME_RUNS = 2, BEHIND_SUGGESTION = 3;
        public final int kind;
        /** With BEHIND_SUGGESTION: the preset that would fit, and 1 or 2 when the resolution should change too. */
        public final int bitrate;
        public final Double captureScale;

        Advice(int kind, int bitrate, Double captureScale) {
            this.kind = kind;
            this.bitrate = bitrate;
            this.captureScale = captureScale;
        }
    }

    /**
     * "behind": the link cannot carry the quality (frames withheld in 3 of the last 5 seconds, LinkJudge.swift). Any
     * other state reads as keeping up. A suggestion is offered only when it is one of the presets and a resolution
     * the host takes; away while a device at home holds the Mac at its home quality, there is nothing to pick.
     */
    public Advice advice() {
        if (!"behind".equals(linkState)) return new Advice(Advice.NONE, 0, null);
        if (thisConnectionAway && !awayRunning) return new Advice(Advice.BEHIND_HOME_RUNS, 0, null);
        int rate = suggestedBitrate == null ? 0 : suggestedBitrate.intValue();
        boolean known = suggestedBitrate != null && suggestedBitrate.doubleValue() == rate && preset(rate) >= 0;
        Double scale = suggestedCaptureScale == null ? null : suggestedCaptureScale.doubleValue();
        if (known && bitrate != null && (scale == null || captureScale != null && (scale == 1 || scale == 2)))
            return new Advice(Advice.BEHIND_SUGGESTION, rate, scale);
        return new Advice(Advice.BEHIND, 0, null);
    }

    /** Sall's ConnectionDiagnostics.accepted: the answer shows what was asked for. */
    static boolean accepted(Object actual, Object expected, Double actualScale, Double expectedScale) {
        boolean primary = actual instanceof Number && expected instanceof Number
                ? ((Number) actual).doubleValue() == ((Number) expected).doubleValue()
                : expected != null && expected.equals(actual);
        return primary && (expectedScale == null || actualScale != null && actualScale.doubleValue() == expectedScale);
    }
}
