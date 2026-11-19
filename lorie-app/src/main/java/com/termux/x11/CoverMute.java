package com.termux.x11;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * In the injector (the shell user): the sound of the Android apps on the external screen, off while the privacy cover
 * hides it (owner, 2026-11-19), through each app's PLAY_AUDIO app op, and on again after. What it turned off, with each
 * app's mode before, is written to SAVED first, so a crash of XDesk or of this process never leaves an app silent: the
 * next injector, and every session's end, puts them back.
 */
final class CoverMute {
    private static final String TAG = "XDeskInjector";
    private static final File SAVED = new File("/data/local/tmp/xdesk-cover-muted");
    private static final long APPOPS_TIMEOUT_MS = 5000;

    /** Silences these apps; an app already silenced (by XDesk or anyone) is left as it is. */
    synchronized void mute(List<String> packages) {
        List<String> lines = read();
        for (String pkg : packages) {
            if (pkg == null || pkg.isEmpty() || listed(lines, pkg)) continue;
            String before = mode(pkg);
            if (before == null || before.equals("ignore") || before.equals("deny")) continue;
            lines.add(pkg + " " + before);
            write(lines);
            String[] r = InjectorService.exec(APPOPS_TIMEOUT_MS, "/system/bin/cmd", "appops", "set", pkg, "PLAY_AUDIO", "ignore");
            if (r[0] != null) Log.w(TAG, "cover mute " + pkg + ": " + r[0]);
        }
        if (!lines.isEmpty()) Log.i(TAG, "cover: sound off for " + lines);
    }

    /** Every app silenced here back to what it was. Safe to call any time; nothing listed, nothing done. */
    synchronized void restore() {
        List<String> lines = read();
        if (lines.isEmpty()) return;
        for (String line : lines) {
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            String pkg = line.substring(0, sp), before = line.substring(sp + 1);
            // A list from the first build wrote "default" for an app with none set: allow is what that meant
            if (before.equals("default")) before = "allow";
            String[] r = InjectorService.exec(APPOPS_TIMEOUT_MS, "/system/bin/cmd", "appops", "set", pkg, "PLAY_AUDIO", before);
            if (r[0] != null) Log.w(TAG, "cover unmute " + pkg + ": " + r[0]);
        }
        Log.i(TAG, "cover: sound back for " + lines);
        if (!SAVED.delete()) write(new ArrayList<>());
    }

    /**
     * The app's PLAY_AUDIO mode to put back: what appops prints for it ("allow", "ignore", ...), or for an app with
     * none set ("No operations.") the op's own default it names ("Default mode: allow"). Never "default": set
     * explicitly, the audio system takes it as not allowed and the app stayed silent after the cover (2026-11-19).
     */
    private static String mode(String pkg) {
        String[] r = InjectorService.exec(APPOPS_TIMEOUT_MS, "/system/bin/cmd", "appops", "get", pkg, "PLAY_AUDIO");
        if (r[0] != null) return null;
        String fallback = "allow";
        for (String line : r[1].split("\n")) {
            line = line.trim();
            if (line.startsWith("Default mode:")) {
                String m = line.substring("Default mode:".length()).trim();
                if (!m.isEmpty() && !m.equals("default")) fallback = m;
                continue;
            }
            if (!line.startsWith("PLAY_AUDIO:")) continue;
            String rest = line.substring("PLAY_AUDIO:".length()).trim();
            int end = rest.indexOf(';');
            String m = (end >= 0 ? rest.substring(0, end) : rest).trim();
            if (!m.isEmpty() && !m.equals("default")) return m;
        }
        return fallback;
    }

    private static boolean listed(List<String> lines, String pkg) {
        for (String l : lines) if (l.startsWith(pkg + " ")) return true;
        return false;
    }

    private static List<String> read() {
        List<String> out = new ArrayList<>();
        try {
            if (SAVED.exists())
                for (String l : Files.readAllLines(SAVED.toPath(), StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) out.add(l.trim());
        } catch (IOException e) {
            Log.w(TAG, "cover mute list", e);
        }
        return out;
    }

    private static void write(List<String> lines) {
        try (FileWriter w = new FileWriter(SAVED)) {
            for (String l : lines) w.write(l + "\n");
        } catch (IOException e) {
            Log.w(TAG, "cover mute list", e);
        }
    }
}
