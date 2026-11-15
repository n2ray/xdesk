/*
 * Port of Sall's MacMenuState.java (https://github.com/n2ray/sall, MainActivity's `mirroredMenus`), the
 * device side of Sill's menu protocol (kinds 24, 25 and 27, Sources/StreamProtocol/MacMenu.swift; the
 * host's MenuReader/MenuMirror/MenuPolicy.swift). Copyright 2026 Noah Saffer.
 * Licensed under the Apache License, Version 2.0. Modified for XDesk.
 */
package com.termux.x11.mac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One connection's mirrored Mac menus: the streamed app's menu bar, read on the Mac over Accessibility.
 * Sall's rows and sections (what its dialogs draw) are not ported — XDesk has no menu bar UI, only
 * MacClient's quitApp — so a settled request goes to the caller whole. Plain Java, no Android imports:
 * the test in /tmp compiles and runs this class alone (as AutoQuality's does).
 *
 * The protocol's rules, from MacMenu.swift's header:
 * - A device asks once per connection, with a kind 27 without an id, after its first window list; only
 *   such a connection (a subscriber) is ever sent a top level or served a menu or a choice.
 * - The version is the host's, one for all devices. An id is good only within its version, and a fetch
 *   and a choice carry the title the device showed; the host acts only while the item at the id still
 *   has it. Otherwise the answer is "The menus changed…", at once, nothing read or pressed.
 * - Tokens are the device's, strictly increasing per connection; the host only echoes them in `answering`.
 *
 * The host's refusals arrive as `note` words only (MenuRefusal.note in MenuPolicy.swift); {@link Refusal}
 * tells them apart by those words, as they are. Methods are synchronized: MacClient's reader thread
 * feeds {@code receiveTop}/{@code receiveAnswer}, its quit machine asks {@code fetch}/{@code press}.
 */
public final class MacMenuState {
    // The host's words (MenuPolicy.swift's MenuRefusal.note), matched as they are (a newer host that words
    // them differently is an OTHER refusal, and its note is shown as it is).
    /** "The menus changed. Open the menu again.": another version, an id that names nothing, a moved title. */
    public static final String CHANGED_NOTE = "The menus changed. Open the menu again.";
    /** "It isn't available right now.": the item is disabled, or Accessibility refused it with another error. */
    public static final String UNAVAILABLE_NOTE = "It isn’t available right now.";
    /** "Too many requests. Open the menu again.": over the host's rate, or the answer would come too late. */
    public static final String TOO_MANY_NOTE = "Too many requests. Open the menu again.";
    /** "Allow Accessibility for Sill on the Mac…": Sill has no Accessibility permission on the Mac. */
    public static final String NOT_TRUSTED_NOTE = "Allow Accessibility for Sill on the Mac (System Settings › Privacy & Security › Accessibility).";
    /** "<App> is no longer open.": the app quit. */
    public static final String GONE_SUFFIX = " is no longer open.";
    /** "<App> isn't responding.": the app did not answer Accessibility within the timeout. */
    public static final String NOT_RESPONDING_SUFFIX = " isn’t responding.";
    /** A stale top level without the host's note: shown the same way. */
    public static final String DEFAULT_NOT_RESPONDING = "The app isn’t responding.";
    /** The Quit item's shortcut as the Mac draws it: found whatever the menu's language. */
    public static final String QUIT_KEY = "⌘Q";
    /**
     * Without a ⌘Q item, a title that starts with one of these is the Quit item: "Quit", "退出", "Beenden" and
     * the like. Only a prefix: "Quit Safari" matches, Japanese "Safari を終了" does not (the ⌘Q key is the
     * sure way, these are the fallback).
     */
    public static final String[] QUIT_TITLES = {"Quit", "退出", "Beenden", "Quitter", "Salir", "Esci"};

    /** Why a fetch or a press was refused; the host's refusals, told apart by the note's words. */
    public enum RefusalKind {
        /** "It isn't available right now.": the item is disabled, or Accessibility refused it (disabled, failed). */
        UNAVAILABLE,
        /** "The menus changed…": another version, an id that names nothing, a title that is not the item's now. */
        CHANGED,
        /** "<App> is no longer open.": the app quit (a quit press then needs no more pressing). */
        GONE,
        /** "<App> isn't responding.": the app did not answer Accessibility within the timeout. */
        NOT_ANSWERING,
        /** "Allow Accessibility for Sill on the Mac…": Sill has no Accessibility permission on the Mac. */
        NOT_TRUSTED,
        /** "Too many requests…": over the host's rate, or the answer would have come too late (tooMany, late). */
        TOO_MANY,
        /** Any other words from the host (a newer Sill's, a locked Mac's): shown as they are. */
        OTHER
    }

    /** One menu or item, as the Mac draws it (MacMenu.swift's MacMenuItem). */
    public static final class Item {
        /** The item's indexes from the menu bar, 0-based as Accessibility lists them ("3.4"). Good within one version. */
        public final String id;
        public final String title;
        /** A separator: no id, no title. */
        public final boolean separator;
        /** Nil counts as enabled, as on the Mac. */
        public final Boolean enabled;
        /** The Mac's mark: "✓", "-", or another character an app set; null unmarked. */
        public final String mark;
        /** The shortcut as the Mac draws it: "⌘Q". Display only: the device never makes it a key command of its own. */
        public final String key;
        /** Has items of its own, fetched with kind 27. */
        public final boolean submenu;

        public Item(String id, String title, boolean separator, Boolean enabled, String mark, String key, boolean submenu) {
            this.id = id;
            this.title = title;
            this.separator = separator;
            this.enabled = enabled;
            this.mark = mark;
            this.key = key;
            this.submenu = submenu;
        }

        /** Pressable: a real item, an id a press can name it by, not disabled. */
        public boolean isEnabled() {
            return !separator && id != null && !Boolean.FALSE.equals(enabled);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Item)) return false;
            Item item = (Item) o;
            return separator == item.separator && submenu == item.submenu && Objects.equals(id, item.id)
                    && Objects.equals(title, item.title) && Objects.equals(enabled, item.enabled)
                    && Objects.equals(mark, item.mark) && Objects.equals(key, item.key);
        }

        @Override public int hashCode() {
            return Objects.hash(id, title, separator, enabled, mark, key, submenu);
        }
    }

    /** The top level of the streamed app's menu bar, in the Mac's order, the Apple menu left out. */
    public static final class Top {
        /** The tree this belongs to; ids are good only within one version. */
        public final Integer version;
        /** The app whose menus these are ("Code", "com.microsoft.VSCode"). */
        public final String app, bundleID;
        /** Each a submenu whose items come with kind 27. Empty when there are no menus: `note` says why. */
        public final List<Item> menus;
        /** The app did not answer the last read: what is shown may be old, and presses are refused. */
        public final boolean stale;
        /** Words shown as they are: why there are no menus, why a press was refused. */
        public final String note;

        Top(Integer version, String app, String bundleID, List<Item> menus, boolean stale, String note) {
            this.version = version;
            this.app = app;
            this.bundleID = bundleID;
            this.menus = menus;
            this.stale = stale;
            this.note = note;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Top)) return false;
            Top top = (Top) o;
            return stale == top.stale && Objects.equals(version, top.version) && Objects.equals(app, top.app)
                    && Objects.equals(bundleID, top.bundleID) && Objects.equals(menus, top.menus)
                    && Objects.equals(note, top.note);
        }

        @Override public int hashCode() {
            return Objects.hash(version, app, bundleID, menus, stale, note);
        }
    }

    /** Why the Mac would not serve a request, as the device shows it (the note) and knows it (the kind). */
    public static final class Refusal {
        public final RefusalKind kind;
        public final String note;

        public Refusal(RefusalKind kind, String note) {
            this.kind = kind;
            this.note = note;
        }

        /** The host's note words, classified; null for none (an answer that carries no refusal). */
        public static Refusal of(String note) {
            if (note == null) return null;
            if (CHANGED_NOTE.equals(note)) return new Refusal(RefusalKind.CHANGED, note);
            if (TOO_MANY_NOTE.equals(note)) return new Refusal(RefusalKind.TOO_MANY, note);
            if (NOT_TRUSTED_NOTE.equals(note)) return new Refusal(RefusalKind.NOT_TRUSTED, note);
            if (UNAVAILABLE_NOTE.equals(note) || "It isn't available right now.".equals(note))
                return new Refusal(RefusalKind.UNAVAILABLE, note);
            if (note.endsWith(GONE_SUFFIX)) return new Refusal(RefusalKind.GONE, note);
            if (note.endsWith(NOT_RESPONDING_SUFFIX) || note.endsWith(" isn't responding."))
                return new Refusal(RefusalKind.NOT_ANSWERING, note);
            return new Refusal(RefusalKind.OTHER, note);
        }
    }

    /** One kind 25 or 27 to send: the version the id was shown in, the id, the title shown, the token. */
    public static final class Command {
        public final int version;
        public final String id, title;
        public final long token;

        public Command(int version, String id, String title, long token) {
            this.version = version;
            this.id = id;
            this.title = title;
            this.token = token;
        }
    }

    /** One settled request: a fetched menu's items, or the answer to a press, or the refusal. */
    public static final class Done {
        /** The request's token. */
        public final long token;
        /** A fetched menu's items (at most 500); null when a press was answered. */
        public final List<Item> items;
        /** How many more items the menu has, when the read stopped. */
        public final int more;
        /** The answer to a press: whether the host pressed the item; null for fetches. */
        public final Boolean pressed;
        /** Non-null when the host refused the request (or settled it here). */
        public final Refusal refusal;

        Done(long token, List<Item> items, int more, Boolean pressed, Refusal refusal) {
            this.token = token;
            this.items = items;
            this.more = more;
            this.pressed = pressed;
            this.refusal = refusal;
        }
    }

    /** What receiveTop or receiveAnswer settled. */
    public static final class Received {
        /** The requests settled by what came in (their answers, or that they can no longer be served). */
        public final List<Done> done;
        /** The top level changed (the version moved, its titles or flags did): read it again. */
        public final boolean topChanged;

        Received(List<Done> done, boolean topChanged) {
            this.done = done;
            this.topChanged = topChanged;
        }
    }

    /** fetch()/press()'s answer: the command to send, or the refusal that settled the request here. */
    public static final class Sent {
        public final Command command;
        public final Refusal refusal;

        Sent(Command command, Refusal refusal) {
            this.command = command;
            this.refusal = refusal;
        }
    }

    /** A fetch (or press) sent and not answered yet; the host echoes its token. */
    private static final class Waiting {
        final String id, title;
        final int version;
        final long token, sentAt;

        Waiting(String id, String title, int version, long token, long sentAt) {
            this.id = id;
            this.title = title;
            this.version = version;
            this.token = token;
            this.sentAt = sentAt;
        }
    }

    /** A press sent and not answered yet. */
    private static final class Choice {
        final long token;
        final String id, title;

        Choice(long token, String id, String title) {
            this.token = token;
            this.id = id;
            this.title = title;
        }
    }

    private static final int MAX_CHOICES = 16;  // Sall's cap on unanswered choices

    private Integer version;
    private String app, bundleID, note;
    private boolean stale;
    private List<Item> menus = Collections.emptyList();
    private final List<Waiting> waiting = new ArrayList<>();
    private final List<Choice> choices = new ArrayList<>();

    /** The top level as last heard; version null before the first one. */
    public synchronized Top top() {
        return new Top(version, app, bundleID, menus, stale, note);
    }

    /**
     * A kind 24 top level (with `menus`). Requests of another version can no longer be served (an id is good
     * only within its version): they settle with CHANGED, as Sall's receive does.
     */
    public synchronized Received receiveTop(Integer v, String a, String b, List<Item> m, boolean s, String n) {
        if (v == null) return new Received(Collections.emptyList(), false);
        Top before = top();
        version = v;
        app = a;
        bundleID = b;
        stale = s;
        note = n;
        menus = m == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(m));
        List<Done> done = new ArrayList<>();
        waiting.removeIf(w -> {
            if (w.version == version) return false;
            done.add(new Done(w.token, null, 0, null, new Refusal(RefusalKind.CHANGED, CHANGED_NOTE)));
            return true;
        });
        return new Received(Collections.unmodifiableList(done), !top().equals(before));
    }

    /**
     * A kind 24 answer (with `answering`): the fetched menu's items, or the answer to a press, to the request
     * whose token the host echoes. A fetch answered with a note was refused (nothing was read); a press with
     * `pressed` false was refused, and without a note that counts as the menus having changed, as in Sall.
     */
    public synchronized Received receiveAnswer(long answering, Integer v, List<Item> items, int more,
                                               Boolean pressed, String note) {
        for (int i = 0; i < waiting.size(); i++) {
            Waiting w = waiting.get(i);
            if (w.token != answering) continue;
            waiting.remove(i);
            Refusal refusal = note == null ? null : Refusal.of(note);
            if (refusal == null && !Objects.equals(v, w.version)) refusal = new Refusal(RefusalKind.CHANGED, CHANGED_NOTE);
            List<Done> done = new ArrayList<>();
            done.add(new Done(w.token, refusal == null
                    ? (items == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(items)))
                    : null, refusal == null ? more : 0, null, refusal));
            return new Received(Collections.unmodifiableList(done), false);
        }
        for (int i = 0; i < choices.size(); i++) {
            Choice c = choices.get(i);
            if (c.token != answering) continue;
            choices.remove(i);
            // Nil counts as pressed, as in Sall: the host's older shapes never say false without a note
            Refusal refusal = Boolean.FALSE.equals(pressed) ? Refusal.of(note) : null;
            if (refusal == null && Boolean.FALSE.equals(pressed)) refusal = new Refusal(RefusalKind.CHANGED, CHANGED_NOTE);
            List<Done> done = new ArrayList<>();
            done.add(new Done(c.token, null, 0, refusal == null ? Boolean.TRUE : Boolean.FALSE, refusal));
            return new Received(Collections.unmodifiableList(done), false);
        }
        return new Received(Collections.emptyList(), false);
    }

    /**
     * Ask for one menu's items, by the id and the title shown in that version. The command to send (kind 27),
     * or the refusal that settled the request here: not connected, another version, or the app not answering
     * (stale) — nothing is read then, as Sall's fetch does.
     */
    public synchronized Sent fetch(String id, String title, Integer askedVersion, long token, long now) {
        if (version == null) return settled(CHANGED_NOTE);
        if (!Objects.equals(askedVersion, version)) return settled(CHANGED_NOTE);
        if (id == null || title == null) return settled(CHANGED_NOTE);
        if (stale) return settled(note == null ? DEFAULT_NOT_RESPONDING : note);
        waiting.add(new Waiting(id, title, version, token, now));
        return new Sent(new Command(version, id, title, token), null);
    }

    /**
     * Choose one item. The command to send (kind 25), or the refusal that settled the request here: a disabled
     * or nameless item, not connected, or the app not answering (stale) — the host would refuse each the same.
     */
    public synchronized Sent press(Item item, long token) {
        if (item == null || !item.isEnabled() || item.id == null) return settled(UNAVAILABLE_NOTE);
        if (version == null) return settled(CHANGED_NOTE);
        if (stale) return settled(note == null ? DEFAULT_NOT_RESPONDING : note);
        choices.add(new Choice(token, item.id, item.title));
        if (choices.size() > MAX_CHOICES) choices.remove(0);
        return new Sent(new Command(version, item.id, item.title, token), null);
    }

    /** Requests the Mac did not answer in time (Sall's expire): settled, so no late answer finds them. */
    public synchronized List<Done> expire(long now, long timeout) {
        if (timeout < 0) throw new IllegalArgumentException("negative timeout");
        List<Done> done = new ArrayList<>();
        waiting.removeIf(w -> {
            if (now < w.sentAt || now - w.sentAt < timeout) return false;
            done.add(new Done(w.token, null, 0, null,
                    new Refusal(RefusalKind.NOT_ANSWERING, "The Mac didn’t answer. Open the menu again.")));
            return true;
        });
        return Collections.unmodifiableList(done);
    }

    /** Forget one request that timed out here, so its late answer matches nothing. */
    public synchronized void drop(long token) {
        waiting.removeIf(w -> w.token == token);
        choices.removeIf(c -> c.token == token);
    }

    /** The connection ended: nothing of it is served any more, and the next one starts afresh (Sall's reset). */
    public synchronized List<Done> reset() {
        List<Done> done = settleAll(CHANGED_NOTE);
        version = null;
        app = bundleID = note = null;
        stale = false;
        menus = Collections.emptyList();
        return done;
    }

    private List<Done> settleAll(String message) {
        List<Done> done = new ArrayList<>();
        for (Waiting w : waiting) done.add(new Done(w.token, null, 0, null, new Refusal(RefusalKind.CHANGED, message)));
        waiting.clear();
        choices.clear();
        return Collections.unmodifiableList(done);
    }

    private static Sent settled(String n) {
        return new Sent(null, Refusal.of(n));
    }

    // --- what quitApp looks for in a menu ---

    /**
     * The Quit item of an app menu: the enabled item whose key is "⌘Q" (the shortcut as the Mac draws it,
     * whatever the menu's language). Only when no item has that key at all: the enabled item whose title
     * starts with one of QUIT_TITLES. Null when there is none; a ⌘Q item that is disabled counts as the Quit
     * item being there, so no other item is pressed for it.
     */
    public static Item quitItem(List<Item> items) {
        Item byTitle = null;
        for (Item item : items) {
            if (item == null || !item.isEnabled()) continue;
            if (QUIT_KEY.equals(item.key)) return item;
            if (byTitle == null && quitTitle(item.title)) byTitle = item;
        }
        for (Item item : items) if (item != null && QUIT_KEY.equals(item.key)) return null;
        return byTitle;
    }

    static boolean quitTitle(String title) {
        if (title == null) return false;
        for (String prefix : QUIT_TITLES) if (title.startsWith(prefix)) return true;
        return false;
    }

    /** A top-level menu's id (Sall's isTopId): one or two digits, not starting with 0, as Accessibility lists them. */
    static boolean isTopId(String id) {
        if (id == null || id.isEmpty() || id.length() > 4 || id.charAt(0) == '0') return false;
        for (int i = 0; i < id.length(); i++) if (id.charAt(i) < '0' || id.charAt(i) > '9') return false;
        return true;
    }

    /** The first top-level menu — the app menu: the host leaves the Apple menu out of the top level. Null: none. */
    public static Item firstMenu(List<Item> menus) {
        for (Item item : menus)
            if (item != null && isTopId(item.id) && item.title != null && !item.title.isEmpty()) return item;
        return null;
    }
}
