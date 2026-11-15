package com.termux.x11.mac;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Mac menu bar's keyboard model (MACMENU.md §4), pure so it is tested in /tmp without Android: ←→ move
 * along the bar (level 0), each move selecting a menu for the caller to open; ↑↓ pick an item of the deepest
 * open menu; Enter presses the selected item (on the bar it opens the menu, as clicking it would); → opens the
 * selected item's submenu; Esc goes back one level, or closes at the bar. Selection skips what cannot be
 * chosen: separators, note rows, disabled items (Sall's isEnabled). The caller feeds each level's rows as they
 * arrive (the bar's, then each fetched menu's) and turns the action codes into fetches and presses: the model
 * only decides what is selected where.
 */
public final class MacMenuNav {
    public static final int NOTHING = 0;
    /** The bar's selected menu should be open (the caller fetches it when it is not the one shown). */
    public static final int OPEN = 1;
    /** The selected item of the deepest level should be pressed (kind 25). */
    public static final int PRESS = 2;
    /** The selected item has a submenu: the caller fetches it and feeds its rows. */
    public static final int OPEN_SUB = 3;
    /** One level back (Esc, or ← on an open submenu). */
    public static final int BACK = 4;
    /** The bar closes (Esc on it). */
    public static final int CLOSE = 5;

    private static final int LEVELS = 4;
    private final List<List<MacMenuState.Row>> levels = new ArrayList<>();
    private final int[] selected = new int[LEVELS];
    /** The deepest level in use: 0 the bar, more an open menu or submenu. */
    private int level;

    public MacMenuNav() {
        for (int i = 0; i < LEVELS; i++) {
            levels.add(Collections.emptyList());
            selected[i] = -1;
        }
    }

    /** The bar's rows (the top level): the levels start over, selection on the first selectable one. */
    public void setBar(List<MacMenuState.Row> rows) {
        levels.set(0, rows == null ? Collections.emptyList() : new ArrayList<>(rows));
        for (int i = 1; i < LEVELS; i++) levels.set(i, Collections.emptyList());
        selected[0] = first(0);
        level = 0;
    }

    /** A menu's rows came (the caller fetched them): its level opens, selecting the first selectable row. */
    public void open(List<MacMenuState.Row> rows) {
        if (level + 1 >= LEVELS) return;
        level++;
        levels.set(level, rows == null ? Collections.emptyList() : new ArrayList<>(rows));
        selected[level] = first(level);
    }

    /** The bar's selection follows a clicked menu (before its rows come). */
    public void selectBar(int index) {
        if (index >= 0 && index < levels.get(0).size() && selectable(levels.get(0).get(index))) selected[0] = index;
    }

    /** The rows of a level, for the view to draw after a level change. */
    public List<MacMenuState.Row> rows(int level) {
        return levels.get(level);
    }

    /** The deepest level's rows: where ↑↓ and Enter act. */
    public int level() {
        return level;
    }

    public int selected(int level) {
        return selected[level];
    }

    public MacMenuState.Row row(int level) {
        int at = selected[level];
        List<MacMenuState.Row> rows = levels.get(level);
        return at >= 0 && at < rows.size() ? rows.get(at) : null;
    }

    /** ←: on the bar, the menu before this one; on an open submenu, back to its parent. */
    public int left() {
        if (level > 0) return back();
        if (selected[0] < 0) return NOTHING;
        int was = selected[0];
        selected[0] = next(0, -1);
        return selected[0] == was ? NOTHING : OPEN;
    }

    /** →: on the bar, the next menu; on an open menu, the selected item's submenu. */
    public int right() {
        if (level > 0) {
            MacMenuState.Row row = row(level);
            return row != null && row.kind == MacMenuState.Kind.SUBMENU && row.enabled ? OPEN_SUB : NOTHING;
        }
        if (selected[0] < 0) return NOTHING;
        int was = selected[0];
        selected[0] = next(0, 1);
        return selected[0] == was ? NOTHING : OPEN;
    }

    public int up() {
        if (selected[level] < 0) return NOTHING;
        selected[level] = next(level, -1);
        return NOTHING;
    }

    public int down() {
        if (selected[level] < 0) return NOTHING;
        selected[level] = next(level, 1);
        return NOTHING;
    }

    /** Enter: the deepest menu's selected item is pressed; on the bar the selected menu opens. */
    public int enter() {
        MacMenuState.Row row = row(level);
        if (row == null || !selectable(row)) return NOTHING;
        return level == 0 ? OPEN : PRESS;
    }

    /** Esc: one level back, or the bar closes. */
    public int esc() {
        return back();
    }

    /** One level back: the dropdown goes; on the bar the whole overlay closes. */
    private int back() {
        if (level == 0) return CLOSE;
        level--;
        return BACK;
    }

    /** The next selectable row from the current selection, clamped; -1 when there is none. */
    private int next(int level, int by) {
        List<MacMenuState.Row> rows = levels.get(level);
        int at = selected[level];
        for (int i = at + by; i >= 0 && i < rows.size(); i += by) {
            if (selectable(rows.get(i))) return i;
        }
        return at;  // stay: the edge
    }

    /** The first selectable row, or -1. */
    private int first(int level) {
        List<MacMenuState.Row> rows = levels.get(level);
        for (int i = 0; i < rows.size(); i++) if (selectable(rows.get(i))) return i;
        return -1;
    }

    /** What a press may choose (Sall's isEnabled): a real item, not disabled, a note is never chosen. */
    public static boolean selectable(MacMenuState.Row row) {
        return row != null && row.kind != MacMenuState.Kind.NOTE && row.enabled && row.id != null;
    }
}
