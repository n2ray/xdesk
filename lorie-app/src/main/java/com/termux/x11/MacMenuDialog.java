package com.termux.x11;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.x11.app.R;
import com.termux.x11.mac.MacClient;
import com.termux.x11.mac.MacClients;
import com.termux.x11.mac.MacMenuState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MACMENU.md §5: a Mac item's "Menu" row opens the app's menus as a list dialog on the phone (Sall's dialog:
 * the top level, drill into a menu with a Back button, press an item). The same MacMenuState rows as the bar
 * overlay's, the same fetch and press (MacClient.menuFetch, menuPress); a refused fetch shows the words (mapped
 * as the bar maps them), a refused press is a toast. A top level that changed while the dialog is open shows it
 * afresh (Sall dismisses it for the top). One dialog at a time, as Sall's menuDialog.
 */
final class MacMenuDialog {
    private static AlertDialog shown;
    private static MacClient client;
    private static Context context;
    private static long pendingToken;
    /** After a press the dialog is gone: its answer is still heard (a refusal is said), then the listener goes. */
    private static boolean waitingPress;
    /** The dialog shows "loading" for the top level itself: the first top that comes shows the menus. */
    private static boolean waitingTop;

    private static final MacClient.MenuListener listener = (top, done) -> {
        if (shown == null || client == null) return;
        if (waitingTop && top.version != null) {
            waitingTop = false;
            topLevel(top);
            return;
        }
        for (MacMenuState.Done d : done) {
            if (d.token == pendingToken && pendingToken != 0) {
                pendingToken = 0;
                if (d.refusal != null) message(MacMenus.refusalText(context, d.refusal), null);
                else rows(MacMenuState.sections(d.items, false, null, d.more, top.version), title(top), null);
                return;
            }
            if (d.pressed != null) {
                waitingPress = false;
                if (d.refusal != null)
                    Toast.makeText(context, MacMenus.refusalText(context, d.refusal), Toast.LENGTH_LONG).show();
                removeListener();
            }
        }
    };

    private MacMenuDialog() { }

    static void show(Context c, String macId) {
        MacClient m = MacClients.get(c, macId);
        if (m == null) return;
        client = m;
        context = c.getApplicationContext();
        pendingToken = 0;
        waitingPress = false;
        m.addMenuListener(listener);
        m.menuSubscribe();
        waitingTop = false;
        MacMenuState.Top top = m.menuTop();
        if (top.version == null) {
            // Not connected, or the top level has not come: the listener shows it when it does
            waitingTop = true;
            message(c.getString(R.string.xd_mac_menu_loading), null);
            return;
        }
        topLevel(top);
    }

    /** The top level as a list: the app's menus (the note first when there is one), as Sall's showTopMenu. */
    private static void topLevel(MacMenuState.Top top) {
        waitingTop = false;
        List<List<MacMenuState.Row>> sections = new ArrayList<>();
        if (top.note != null) sections.add(Collections.singletonList(MacMenuState.Row.note(top.note)));
        if (!top.menus.isEmpty()) sections.add(MacMenuState.topRows(top.menus, top.version));
        if (sections.isEmpty()) sections.add(Collections.singletonList(MacMenuState.Row.note(MacMenuState.NO_ITEMS)));
        rows(sections, top.app != null && !top.app.isEmpty() ? top.app : context.getString(R.string.xd_mac_menu_title), null);
    }

    private static void rows(List<List<MacMenuState.Row>> sections, String title, Runnable back) {
        if (shown != null) {
            try {
                shown.dismiss();
            } catch (RuntimeException ignored) { }
            shown = null;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle(title);
        b.setAdapter(new Adapter(context, MacMenuState.flat(MacMenuState.Content.sections(sections, 0))),
            (d, which) -> picked(which));
        if (back != null) b.setNegativeButton(R.string.xd_mac_menu_back, (d, w) -> back.run());
        shown = b.create();
        shown.setOnDismissListener(d -> {
            if (shown == d) {
                shown = null;
                if (!waitingPress) removeListener();
            }
        });
        shown.show();
    }

    private static void message(String text, Runnable back) {
        rows(Collections.singletonList(Collections.singletonList(MacMenuState.Row.note(text))),
            context.getString(R.string.xd_mac_menu_title), back);
    }

    private static void picked(int which) {
        if (shown == null || client == null) return;
        Adapter a = (Adapter) shown.getListView().getAdapter();
        MacMenuState.Row row = a.row(which);
        if (row == null || row.kind == MacMenuState.Kind.NOTE || !row.enabled || row.id == null) return;
        if (row.kind == MacMenuState.Kind.SUBMENU) {
            fetch(row, () -> topLevel(client.menuTop()));
            return;
        }
        MacMenuState.Sent sent = client.menuPress(row);
        waitingPress = true;
        shown.dismiss();  // the item was pressed: the dialog goes (as the bar does)
        shown = null;
        if (sent != null && sent.refusal != null) {
            waitingPress = false;
            removeListener();
            Toast.makeText(context, MacMenus.refusalText(context, sent.refusal), Toast.LENGTH_LONG).show();
        }
    }

    private static void fetch(MacMenuState.Row row, Runnable back) {
        MacMenuState.Sent sent = client.menuFetch(row.id, row.title, row.version);
        if (sent == null || sent.refusal != null) {
            message(MacMenus.refusalText(context, sent != null ? sent.refusal : null), back);
            return;
        }
        pendingToken = sent.command.token;
        message(context.getString(R.string.xd_mac_menu_loading), back);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (pendingToken != sent.command.token || shown == null) return;
            pendingToken = 0;
            message(context.getString(R.string.xd_mac_menu_no_answer), back);  // the Mac did not answer
        }, MacMenus.MENU_WAIT_MS);
    }

    private static String title(MacMenuState.Top top) {
        return top.app != null && !top.app.isEmpty() ? top.app : context.getString(R.string.xd_mac_menu_title);
    }

    private static void removeListener() {
        if (client != null) {
            client.removeMenuListener(listener);
            client = null;
        }
    }

    /** The rows, as Sall's MacMenuAdapter draws them: mark, title, the key under it, › for a submenu. */
    private static final class Adapter extends BaseAdapter {
        private final Context c;
        private final List<MacMenuState.Row> rows;

        Adapter(Context c, List<MacMenuState.Row> rows) {
            this.c = c;
            this.rows = rows;
        }

        MacMenuState.Row row(int which) {
            return which >= 0 && which < rows.size() ? rows.get(which) : null;
        }

        @Override public int getCount() {
            return rows.size();
        }

        @Override public Object getItem(int position) {
            return rows.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public boolean areAllItemsEnabled() {
            return false;
        }

        @Override public int getViewTypeCount() {
            return 2;  // a row, and a divider (null): never recycled into each other
        }

        @Override public int getItemViewType(int position) {
            return row(position) == null ? 0 : 1;
        }

        @Override public boolean isEnabled(int position) {
            MacMenuState.Row row = row(position);
            return row != null && row.kind != MacMenuState.Kind.NOTE && row.enabled && row.id != null;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            MacMenuState.Row row = row(position);
            if (row == null) {
                // A divider between sections
                if (convertView == null) {
                    LinearLayout separator = new LinearLayout(c);
                    separator.setOrientation(LinearLayout.VERTICAL);
                    separator.setGravity(Gravity.CENTER_VERTICAL);
                    separator.setPadding(dp(16), 0, dp(16), 0);
                    separator.setMinimumHeight(dp(9));
                    View line = new View(c);
                    line.setBackgroundColor(0x26ffffff);
                    separator.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
                    convertView = separator;
                }
                return convertView;
            }
            LinearLayout line = (LinearLayout) convertView;
            if (line == null) {
                line = new LinearLayout(c);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(dp(16), dp(8), dp(16), dp(8));
                line.setMinimumHeight(dp(44));
                TextView mark = text(17, Style.TEXT);
                mark.setGravity(Gravity.CENTER);
                line.addView(mark, new LinearLayout.LayoutParams(dp(22), -2));
                LinearLayout words = new LinearLayout(c);
                words.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams wordsAt = new LinearLayout.LayoutParams(0, -2, 1);
                wordsAt.leftMargin = dp(8);
                line.addView(words, wordsAt);
                TextView title = text(17, Style.TEXT);
                title.setMaxLines(3);
                title.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(title);
                TextView subtitle = text(13, Style.TEXT_DIM);
                subtitle.setMaxLines(3);
                subtitle.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(subtitle);
                TextView arrow = text(22, Style.TEXT_DIM);
                arrow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams arrowAt = new LinearLayout.LayoutParams(dp(24), -2);
                arrowAt.leftMargin = dp(4);
                line.addView(arrow, arrowAt);
            }
            boolean note = row.kind == MacMenuState.Kind.NOTE;
            boolean enabled = isEnabled(position);
            MacMenuState.NoteText nt = note ? MacMenuState.noteText(row.title) : null;
            TextView mark = (TextView) line.getChildAt(0);
            LinearLayout words = (LinearLayout) line.getChildAt(1);
            TextView title = (TextView) words.getChildAt(0);
            TextView subtitle = (TextView) words.getChildAt(1);
            TextView arrow = (TextView) line.getChildAt(2);
            LinearLayout.LayoutParams wordsAt = (LinearLayout.LayoutParams) words.getLayoutParams();
            int margin = note ? 0 : dp(8);
            if (wordsAt.leftMargin != margin) {
                wordsAt.leftMargin = margin;
                words.setLayoutParams(wordsAt);
            }
            mark.setVisibility(note ? View.GONE : View.VISIBLE);
            mark.setText(row.kind != MacMenuState.Kind.ITEM ? ""
                : row.mark == MacMenuState.Mark.ON ? "✓" : row.mark == MacMenuState.Mark.MIXED ? "−" : "");
            title.setText(note ? nt.title : row.title);
            title.setTypeface(note ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
            String key = note ? nt.subtitle : row.key;
            subtitle.setText(key == null ? "" : key);
            subtitle.setVisibility(key == null || key.isEmpty() ? View.GONE : View.VISIBLE);
            arrow.setText("›");
            arrow.setVisibility(!note && enabled && row.kind == MacMenuState.Kind.SUBMENU ? View.VISIBLE : View.GONE);
            line.setEnabled(enabled);
            line.setAlpha(enabled ? 1f : 0.45f);
            return line;
        }

        private int dp(float v) {
            return Math.round(v * c.getResources().getDisplayMetrics().density);
        }

        private TextView text(float sp, int color) {
            TextView view = new TextView(c);
            view.setTextSize(sp);
            view.setTextColor(color);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return view;
        }
    }
}
