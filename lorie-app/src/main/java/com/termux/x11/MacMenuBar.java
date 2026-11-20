package com.termux.x11;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.x11.app.R;
import com.termux.x11.mac.MacMenuNav;
import com.termux.x11.mac.MacMenuState;

import java.util.ArrayList;
import java.util.List;

/**
 * MACMENU.md §4: the Mac app's menu bar overlay, hanging from the external screen's top-left corner: a
 * horizontal bar of the top-level menus, and below it the dropdown of the menu opened (its items as the Mac
 * draws them — the title, the key text, the mark, disabled greyed, separators, submenus with › fetched on
 * open). Clicking an item presses it on the Mac (kind 25) and the bar closes; a refusal is said briefly in the
 * bar, and without Sill's Accessibility on the Mac the bar is the one line that says so. The keyboard works it
 * (MacMenuNav: ←→ ↑↓ Enter → Esc); the pointer works it through MacMenus' driven pointer (clickAt), the window
 * never takes the focus. Rows are MacMenuState's shaping of Sall's MacMenuAdapter (mark, title, key, ›), sizes
 * from Sizing.shell, the notification panel's dark style.
 */
final class MacMenuBar {
    /** The bar's height, and the dropdown's width, in shell dp (ExtMacMenu.dc.html: 40 and 280). */
    static final float HEIGHT_DP = 40, DROPDOWN_DP = 280;
    /** How long a refusal is said in the bar; the no-Accessibility line stays while the Mac has none. */
    static final long NOTE_MS = 3000;

    interface Host {
        /** Fetch one menu (kind 27); null or a refusal when nothing was sent. */
        MacMenuState.Sent fetch(String id, String title, int version);
        /** Press the item a row shows (kind 25); null or a refusal when nothing was sent. */
        MacMenuState.Sent press(MacMenuState.Row row);
        void close();
        /** The pointer's hover: it keeps the bar open while inside. */
        void hover(boolean inside);
        /** The dropdown's window was just added: it is above the driven arrow, which has to come up again. */
        void dropdownShown();
    }

    private static final int SELECT = Style.ACCENT;
    private static final int SEPARATOR = Style.SHELL_LINE;
    /** The open menu's raised tab and the dropdown under it (ExtMacMenu.dc.html's #2C313B). */
    private static final int RAISED = Style.SURFACE_RAISED;

    private final Context dc;
    private final float density;
    private final WindowManager windows;
    private final Host host;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Rect rect = new Rect();

    private final LinearLayout root;
    private final WindowManager.LayoutParams params;
    private final LinearLayout barRow;
    private final TextView note;
    private final Runnable hideNote = this::hideNote;

    private LinearLayout dropdown;
    private WindowManager.LayoutParams dropdownParams;

    /** The keyboard's model: level 0 the bar, deeper an open menu. The views follow it. */
    private final MacMenuNav nav = new MacMenuNav();
    private List<MacMenuState.Row> top = new ArrayList<>();
    private final List<TextView> pills = new ArrayList<>();
    /** The dropdown's rows, null between sections (a divider view); the row views follow one to one. */
    private List<MacMenuState.Row> rows = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    /** The bar menu whose dropdown is open, and the fetch that waits for its items. */
    private int openIndex = -1;
    private long pendingToken;
    private boolean closing;
    /** The top level says no Accessibility (NOT_TRUSTED): the bar is only the one line that says so. */
    private boolean noAccess;
    /** The top level the bar was last built from: the same one again builds nothing. */
    private boolean shownTop, shownStale;
    private Integer shownVersion;
    private String shownNote;

    MacMenuBar(Context dc, float density, Host host, WindowManager windows) {
        this.dc = dc;
        this.density = density;
        this.host = host;
        this.windows = windows;

        root = new LinearLayout(dc);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Style.shell(density, 12));
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.setOnHoverListener((v, e) -> {
            hover(e.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT);
            return false;
        });

        barRow = new LinearLayout(dc);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(barRow, new LinearLayout.LayoutParams(-2, dp(HEIGHT_DP - 12)));

        note = new TextView(dc);
        note.setTextColor(Style.TEXT_DIM);
        note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(12 * density));
        note.setSingleLine(true);
        note.setEllipsize(TextUtils.TruncateAt.END);
        note.setPadding(dp(4), 0, dp(4), dp(4));
        note.setVisibility(View.GONE);
        root.addView(note, new LinearLayout.LayoutParams(-2, -2));

        params = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // Joined to the ≡ (owner, 2026-11-15): no gap the pointer could fall through between them; the button,
        // raised above the bar, covers the pixel they share
        params.x = dp(CornerButton.SIZE_DP + 8) - 1;
        params.y = dp(8);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.windowAnimations = 0;
        params.setTitle("XDesk Mac menu");
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void hover(boolean inside) {
        host.hover(inside);
    }

    View view() {
        return root;
    }

    WindowManager.LayoutParams params() {
        return params;
    }

    boolean closing() {
        return closing;
    }

    // --- open, the top level, close ---

    void open() {
        closing = false;
        try {
            windows.addView(root, params);
        } catch (RuntimeException e) {
            android.util.Log.w("XDeskMacMenus", "no menu bar", e);
            closing = true;
            return;
        }
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(140).start();
    }

    /** The top level came or moved (the version changed): the bar follows it; an open menu opens again. */
    void setTop(MacMenuState.Top t) {
        if (closing) return;
        // Every answer to a fetch comes with the top level too (MacClient.macMenu): only a changed one rebuilds the
        // bar. Rebuilding closed the dropdown and fetched again, whose answer did the same: the items never showed
        if (shownTop && java.util.Objects.equals(t.version, shownVersion) && java.util.Objects.equals(t.note, shownNote)
                && t.stale == shownStale) return;
        shownTop = true;
        shownVersion = t.version;
        shownNote = t.note;
        shownStale = t.stale;
        if (t.version == null) {
            // Subscribed but no top level yet: the bar says so until the first one comes
            top = new ArrayList<>();
            pills.clear();
            barRow.removeAllViews();
            barRow.setVisibility(View.VISIBLE);
            sayNote(dc.getString(R.string.xd_mac_menu_loading), true);
            closeDropdown();
            return;
        }
        top = MacMenuState.topRows(t.menus, t.version);
        MacMenuState.Refusal refusal = MacMenuState.Refusal.of(t.note);
        noAccess = refusal != null && refusal.kind == MacMenuState.RefusalKind.NOT_TRUSTED;
        int again = openIndex;
        closeDropdown();
        pills.clear();
        barRow.removeAllViews();
        if (noAccess) {
            barRow.setVisibility(View.GONE);
            sayNote(MacMenus.refusalText(dc, refusal), true);  // the bar is the one line (MACMENU.md §4)
            return;
        }
        barRow.setVisibility(View.VISIBLE);
        hideNote();  // the loading line, once the menus are there
        nav.setBar(top);
        // The app whose menus these are, in bold at the bar's left (ExtMacMenu.dc.html's 访达)
        if (t.app != null && !t.app.isEmpty()) {
            TextView name = pill(t.app);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            barRow.addView(name, new LinearLayout.LayoutParams(-2, -1));
        }
        for (int i = 0; i < top.size(); i++) {
            final int at = i;
            TextView pill = pill(top.get(i).title);
            // A disabled menu opens nothing, as on the Mac; its pill is there but greyed and dead
            if (top.get(i).enabled) pill.setOnClickListener(v -> openMenu(at));
            else pill.setAlpha(0.45f);
            pills.add(pill);
            barRow.addView(pill, new LinearLayout.LayoutParams(-2, -1));
        }
        selectPill(nav.selected(0), false);
        if (again >= 0 && again < top.size()) openMenu(again);  // the same menu under the new version
    }

    void close(Runnable done) {
        if (closing) {
            if (done != null) done.run();
            return;
        }
        closing = true;
        ui.removeCallbacks(hideNote);
        closeDropdown();
        root.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            try {
                windows.removeViewImmediate(root);
            } catch (RuntimeException ignored) { }
            if (done != null) done.run();
        }).start();
    }

    // --- the dropdown (the open menu's items, one level at a time) ---

    /** The bar's menu at this index opens: its items are fetched (kind 27), "loading" until they come. */
    private void openMenu(int index) {
        if (closing || index < 0 || index >= top.size()) return;
        MacMenuState.Row row = top.get(index);
        openIndex = index;
        nav.setBar(top);  // one open menu at a time: the levels start over
        nav.selectBar(index);
        selectPill(index, true);
        fetch(row);
    }

    /** A submenu row was clicked (or →): its items replace the dropdown's. */
    private void openSub(MacMenuState.Row row) {
        fetch(row);
    }

    private void fetch(MacMenuState.Row row) {
        showRows(MacMenuState.flat(MacMenuState.Content.message(dc.getString(R.string.xd_mac_menu_loading))));
        MacMenuState.Sent sent = host.fetch(row.id, row.title, row.version);
        if (sent == null || sent.refusal != null) {
            pendingToken = 0;
            say(sent != null && sent.refusal != null ? sent.refusal : null);
            return;
        }
        pendingToken = sent.command.token;
        ui.postDelayed(() -> {
            if (pendingToken != sent.command.token) return;
            pendingToken = 0;
            say(null);  // the Mac did not answer
        }, MacMenus.MENU_WAIT_MS);
    }

    /** A fetch's answer: its items as rows (the nav's new level), or the refusal said briefly in the bar. */
    void onDone(MacMenuState.Done done) {
        if (closing || done.token != pendingToken) return;
        pendingToken = 0;
        if (done.refusal != null) {
            say(done.refusal);
            return;
        }
        nav.open(MacMenuState.flat(MacMenuState.Content.sections(
                MacMenuState.sections(done.items, false, null, done.more, version()), 0)));
        showRows(nav.rows(nav.level()));
    }

    private Integer version() {
        return top.isEmpty() ? null : top.get(0).version;
    }

    /** The dropdown draws these rows: nulls are dividers between sections. */
    private void showRows(List<MacMenuState.Row> flat) {
        rows = flat;
        rowViews.clear();
        if (dropdown == null) {
            dropdown = new LinearLayout(dc);
            dropdown.setOrientation(LinearLayout.VERTICAL);
            // Joined under the open menu's tab: square at the top-left, rounded elsewhere (ExtMacMenu.dc.html)
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(RAISED);
            float r = dp(12);
            surface.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
            dropdown.setBackground(surface);
            dropdown.setPadding(dp(6), dp(6), dp(6), dp(6));
            dropdown.setOnHoverListener((v, e) -> {
                hover(e.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT);
                return false;
            });
            dropdownParams = new WindowManager.LayoutParams(dp(DROPDOWN_DP), -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            dropdownParams.gravity = Gravity.TOP | Gravity.START;
            dropdownParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            dropdownParams.setFitInsetsTypes(0);
            dropdownParams.windowAnimations = 0;
            dropdownParams.setTitle("XDesk Mac menu items");
        }
        dropdown.removeAllViews();
        for (MacMenuState.Row row : flat) {
            if (row == null) {
                View line = new View(dc);
                line.setBackgroundColor(SEPARATOR);
                dropdown.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
                rowViews.add(line);
                continue;
            }
            View line = rowView(row);
            dropdown.addView(line, new LinearLayout.LayoutParams(-1, -2));  // made but never added: an empty dropdown
            rowViews.add(line);
        }
        placeDropdown();
        updateSelection();
    }

    /** One row, as the Mac draws it (Sall's MacMenuAdapter): mark, title, key, › for a submenu. */
    private View rowView(MacMenuState.Row row) {
        LinearLayout line = new LinearLayout(dc);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(12), dp(5), dp(12), dp(5));
        line.setMinimumHeight(dp(30));
        boolean noteRow = row.kind == MacMenuState.Kind.NOTE;
        MacMenuState.NoteText nt = noteRow ? MacMenuState.noteText(row.title) : null;
        String title = noteRow ? nt.title : row.title;
        String key = noteRow ? nt.subtitle : row.key;
        boolean enabled = !noteRow && MacMenuNav.selectable(row);
        if (!noteRow) {
            TextView mark = text(14, Style.TEXT);
            mark.setText(row.kind == MacMenuState.Kind.ITEM
                    ? row.mark == MacMenuState.Mark.ON ? "✓" : row.mark == MacMenuState.Mark.MIXED ? "−" : ""
                    : "");
            mark.setGravity(Gravity.CENTER);
            line.addView(mark, new LinearLayout.LayoutParams(dp(14), -2));
        }
        TextView words = text(13, noteRow ? Style.TEXT_DIM : Style.TEXT);
        words.setMaxLines(2);
        words.setEllipsize(TextUtils.TruncateAt.END);
        words.setText(title);
        line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView keyText = text(12, Style.TEXT_DIM);
        keyText.setText(key == null ? "" : key);
        keyText.setVisibility(key == null || key.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams keyAt = new LinearLayout.LayoutParams(-2, -2);
        keyAt.leftMargin = dp(10);
        line.addView(keyText, keyAt);
        TextView arrow = text(15, Style.TEXT_DIM);
        arrow.setText("›");
        arrow.setVisibility(enabled && row.kind == MacMenuState.Kind.SUBMENU ? View.VISIBLE : View.GONE);
        line.addView(arrow, new LinearLayout.LayoutParams(dp(12), -2));
        line.setAlpha(enabled ? 1f : 0.45f);
        if (enabled) {
            final MacMenuState.Row r = row;
            line.setOnClickListener(v -> clickRow(r));
        }
        return line;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(dc);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(sp * density));
        return t;
    }

    private void clickRow(MacMenuState.Row row) {
        if (closing) return;
        if (row.kind == MacMenuState.Kind.SUBMENU) openSub(row);
        else press(row);
    }

    private void press(MacMenuState.Row row) {
        MacMenuState.Sent sent = host.press(row);
        if (sent != null && sent.refusal != null) say(sent.refusal);
        host.close();  // the item was pressed on the Mac: the bar goes (MACMENU.md §4)
    }

    private void closeDropdown() {
        pendingToken = 0;
        openIndex = -1;
        rows = new ArrayList<>();
        rowViews.clear();
        if (dropdown != null) {
            try {
                windows.removeViewImmediate(dropdown);
            } catch (RuntimeException ignored) { }
            dropdown = null;
        }
    }

    private void placeDropdown() {
        if (dropdown == null || openIndex < 0 || openIndex >= pills.size()) return;
        int[] at = new int[2];
        pills.get(openIndex).getLocationOnScreen(at);
        int[] barAt = new int[2];
        root.getLocationOnScreen(barAt);
        dropdownParams.x = at[0];
        dropdownParams.y = barAt[1] + root.getHeight() - 1;  // joined to the bar: no gap to fall through
        try {
            if (dropdown.getParent() == null) {
                windows.addView(dropdown, dropdownParams);
                host.dropdownShown();
            }
            else windows.updateViewLayout(dropdown, dropdownParams);
        } catch (RuntimeException e) {
            android.util.Log.w("XDeskMacMenus", "no menu dropdown", e);
        }
    }

    // --- the note line: a refusal said briefly, the no-Accessibility line while the Mac has none ---

    /** A refusal (null: the Mac did not answer): said briefly in the bar, mapped to this side's words. */
    private void say(MacMenuState.Refusal refusal) {
        String text = MacMenus.refusalText(dc, refusal);
        sayNote(text, refusal != null && refusal.kind == MacMenuState.RefusalKind.NOT_TRUSTED);
        showRows(MacMenuState.flat(MacMenuState.Content.message(text)));  // the dropdown says it too, as Sall does
    }

    private void sayNote(String text, boolean stay) {
        note.setText(text);
        note.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideNote);
        if (!stay) ui.postDelayed(hideNote, NOTE_MS);
    }

    private void hideNote() {
        note.setVisibility(View.GONE);
    }

    // --- the keyboard (MACMENU.md §4: ←→ ↑↓ Enter → Esc), MacMenuNav's model ---

    /** Esc is the bar's while it is open, whatever the pointer rests on. */
    boolean takesEsc() {
        return !closing;
    }

    void left() {
        act(nav.left());
    }

    void right() {
        act(nav.right());
    }

    void up() {
        nav.up();
        updateSelection();
    }

    void down() {
        nav.down();
        updateSelection();
    }

    void enter() {
        act(nav.enter());
    }

    void esc() {
        act(nav.esc());
    }

    private void act(int action) {
        switch (action) {
        case MacMenuNav.NOTHING:
            return;
        case MacMenuNav.OPEN:
            openMenu(nav.selected(0));
            return;
        case MacMenuNav.OPEN_SUB:
            openSub(nav.row(nav.level()));
            return;
        case MacMenuNav.PRESS:
            press(nav.row(nav.level()));
            return;
        case MacMenuNav.BACK:
            if (nav.level() == 0) {
                // Back on the bar itself: the dropdown goes, the bar stays (Esc once more closes it)
                closeDropdown();
                selectPill(nav.selected(0), false);
            } else {
                showRows(nav.rows(nav.level()));  // the parent level's rows again
            }
            return;
        case MacMenuNav.CLOSE:
            host.close();
            return;
        default:
        }
    }

    private void selectPill(int index, boolean open) {
        for (int i = 0; i < pills.size(); i++) {
            // The open menu's tab is raised, joined to its dropdown; the others have no fill (ExtMacMenu.dc.html)
            GradientDrawable back = new GradientDrawable();
            back.setColor(i == index && open ? RAISED : 0x00000000);
            back.setCornerRadii(new float[]{dp(9), dp(9), dp(9), dp(9), 0, 0, 0, 0});
            pills.get(i).setBackground(back);
        }
    }

    private void updateSelection() {
        selectPill(nav.selected(0), openIndex == nav.selected(0));
        int at = nav.selected(nav.level());
        for (int i = 0; i < rowViews.size(); i++) {
            View v = rowViews.get(i);
            if (i == at) {
                GradientDrawable back = new GradientDrawable();
                back.setColor(SELECT);
                back.setCornerRadius(dp(7));
                v.setBackground(back);
            } else {
                v.setBackground(null);
            }
        }
    }

    // --- the driven pointer (MacMenus' own arrow) ---

    /** The bar's place on the screen, in pixels — the bar and the open dropdown together. */
    void rect(Rect out) {
        int[] at = new int[2];
        root.getLocationOnScreen(at);
        out.set(at[0], at[1], at[0] + root.getWidth(), at[1] + root.getHeight());
        if (dropdown != null) {
            dropdown.getLocationOnScreen(at);
            out.union(at[0], at[1], at[0] + dropdown.getWidth(), at[1] + dropdown.getHeight());
        }
    }

    boolean holds(int x, int y) {
        rect(rect);
        return x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom;
    }

    /** A click of XDesk's own pointer: on the view that is there, as the Android pointer's click would be. */
    /** The driven arrow is here: the dropdown's row under it is the selected one (the Mac's hover highlight). */
    void pointerAt(int x, int y) {
        if (dropdown == null || closing) return;
        int[] at = new int[2];
        for (int i = 0; i < rowViews.size(); i++) {
            View v = rowViews.get(i);
            v.getLocationOnScreen(at);
            if (x >= at[0] && x < at[0] + v.getWidth() && y >= at[1] && y < at[1] + v.getHeight()) {
                if (nav.selectRow(i)) updateSelection();
                return;
            }
        }
    }

    void clickAt(int x, int y) {
        if (dropdown != null && !clickWalk(dropdown, x, y)) clickWalk(root, x, y);
        else if (dropdown == null) clickWalk(root, x, y);
    }

    private static boolean clickWalk(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE) return false;
        if (v instanceof android.view.ViewGroup) {
            for (int i = ((android.view.ViewGroup) v).getChildCount() - 1; i >= 0; i--) {
                if (clickWalk(((android.view.ViewGroup) v).getChildAt(i), x, y)) return true;
            }
        }
        if (!v.isClickable()) return false;
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        if (x >= at[0] && x < at[0] + v.getWidth() && y >= at[1] && y < at[1] + v.getHeight()) {
            v.performClick();
            return true;
        }
        return false;
    }

    private TextView pill(String title) {
        TextView t = new TextView(dc);
        t.setText(title);
        t.setTextColor(Style.TEXT);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(13 * density));
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        return t;
    }
}
