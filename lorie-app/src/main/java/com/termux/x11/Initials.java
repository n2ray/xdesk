package com.termux.x11;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The index letter of a name for the launcher's A–Z bar (DEVICES.md §3): A–Z for Latin letters, the pinyin initial
 * for a Chinese character, '#' for anything else. Pinyin comes from ICU's Han-Latin transliterator (public since
 * API 29), which is slow to load: it runs on one background thread only, once per character, and a name whose
 * character is not known yet counts as '#' until {@link #prepare} calls back. UI thread, except the worker.
 */
final class Initials {
    private static final String TAG = "XDesk";
    static final char OTHER = '#';

    /** Han character -> its initial; filled by the worker, read on the UI thread. */
    private static final HashMap<Integer, Character> han = new HashMap<>();
    private static final HashSet<Integer> asked = new HashSet<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static ExecutorService worker;
    private static Object transliterator;
    private static boolean broken;

    private Initials() { }

    /** The first letter or digit of a name (codepoint), or -1. */
    private static int first(String name) {
        if (name == null) return -1;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return cp;
            i += Character.charCount(cp);
        }
        return -1;
    }

    private static boolean isHan(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    private static char latin(int cp) {
        int u = Character.toUpperCase(cp);
        if (u >= 'A' && u <= 'Z') return (char) u;
        // Á, Ö…: their base letter
        String folded = java.text.Normalizer.normalize(new String(Character.toChars(u)), java.text.Normalizer.Form.NFD);
        char b = folded.isEmpty() ? 0 : folded.charAt(0);
        return b >= 'A' && b <= 'Z' ? b : OTHER;
    }

    /** Never blocks. */
    static char of(String name) {
        int cp = first(name);
        if (cp < 0) return OTHER;
        if (!isHan(cp)) return latin(cp);
        Character c = han.get(cp);
        return c != null ? c : OTHER;
    }

    /**
     * Work out the initials of these names' Chinese characters in the background; {@code done} runs on the UI thread
     * if any became known. Cheap when they all are.
     */
    static void prepare(Iterable<String> names, final Runnable done) {
        final HashSet<Integer> todo = new HashSet<>();
        for (String n : names) {
            int cp = first(n);
            if (cp >= 0 && isHan(cp) && !han.containsKey(cp) && asked.add(cp)) todo.add(cp);
        }
        if (todo.isEmpty() || broken) return;
        if (worker == null) worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "xdesk-initials");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        worker.execute(() -> {
            final HashMap<Integer, Character> found = new HashMap<>();
            for (int cp : todo) found.put(cp, pinyinInitial(cp));
            ui.post(() -> {
                han.putAll(found);
                if (done != null) done.run();
            });
        });
    }

    /** On the worker only. */
    private static char pinyinInitial(int cp) {
        try {
            if (transliterator == null) transliterator = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII");
            String latin = ((android.icu.text.Transliterator) transliterator).transliterate(new String(Character.toChars(cp)));
            int l = first(latin);
            return l >= 0 && !isHan(l) ? latin(l) : OTHER;
        } catch (RuntimeException e) {
            Log.w(TAG, "no pinyin", e);
            broken = true;
            return OTHER;
        }
    }

    /** For sorting: A–Z, then '#'. */
    static int rank(char initial) {
        return initial == OTHER ? 26 : initial - 'A';
    }
}
