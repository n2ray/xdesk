package com.termux.x11;

import com.termux.x11.app.R;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The phone as a gamepad (GAMEPAD.md §6): the controls of a layout, worked by several fingers at once, each held by the
 * control it went down on; the state goes to a real gamepad device (GamepadHid) whenever it changes. Its menu and the
 * layout editor are in here too: no dialog, which would be a focusable window and take the keys from the external display.
 */
final class GamepadView extends FrameLayout {
    interface Host {
        void state(int buttons, int hat, int lx, int ly, int rx, int ry, int lt, int rt);
        /** Nothing pressed. */
        void end();
        /** The player left the gamepad. */
        void exit();
    }

    /** The pad's pieces are a faint white on the dark screen (Gamepad.dc.html): 10% fill, 28% lines, 85% words at 50%. */
    private static final int PAD_COLOR = 0xFFE8ECF1, FACE_Y = 0xFFF2B33D;
    private final Host host;
    private final GamepadStore store;
    private final Pad pad;
    private final LinearLayout menu, editBar;
    private final HorizontalScrollView editScroll;
    private final ScrollView menuScroll;
    private TextView editLabel, editHint;
    private TextView hideButton;
    private GamepadLayout layout;
    private String pkg;
    private boolean editing;
    /** The layout as it was when editing began, for Cancel. */
    private GamepadLayout before;
    private int selected = -1;

    GamepadView(Context c, Host host) {
        super(c);
        this.host = host;
        store = new GamepadStore(c);
        layout = store.forApp(null);
        setBackgroundColor(Style.BACKGROUND);
        pad = new Pad(c);
        addView(pad, new LayoutParams(-1, -1));

        menu = new LinearLayout(c);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));
        menu.setBackground(Style.shell(c.getResources().getDisplayMetrics().density, 14));
        menuScroll = new ScrollView(c);
        menuScroll.addView(menu, new ScrollView.LayoutParams(-1, -2));
        menuScroll.setVisibility(GONE);
        LayoutParams mp = new LayoutParams(dp(224), -2, Gravity.CENTER);
        mp.topMargin = mp.bottomMargin = dp(12);
        addView(menuScroll, mp);

        editBar = row(c);
        // A narrow screen clips a wide toolbar: it scrolls sideways rather than losing its last buttons
        editScroll = new HorizontalScrollView(c);
        editScroll.setHorizontalScrollBarEnabled(false);
        editScroll.addView(editBar);
        LayoutParams bp = new LayoutParams(-1, -2, Gravity.TOP);
        bp.topMargin = dp(8);
        addView(editScroll, bp);
        editScroll.setVisibility(GONE);
        buildEditBar();

        // The line under the editor (GamepadEdit.dc.html): drag, select, resize or hide, and which layout it is
        editHint = Style.label(c, "", Style.FAINT, 11);
        editHint.setGravity(Gravity.CENTER);
        editHint.setVisibility(GONE);
        LayoutParams hp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        hp.bottomMargin = dp(8);
        addView(editHint, hp);

        // The system takes back swipes only from where nothing is excluded, and at most 200dp of each edge
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
            v.setSystemGestureExclusionRects(java.util.Collections.singletonList(new android.graphics.Rect(0, 0, r - l, b - t))));
    }

    private int dp(float v) {
        return Style.dp(getContext(), v);
    }

    private LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(6), dp(6), dp(6), dp(6));
        r.setBackground(Style.shell(c.getResources().getDisplayMetrics().density, 14));
        return r;
    }

    /** A toolbar button: 34 dp high, 10 dp corners, white at 8% (GamepadEdit.dc.html's .tb). */
    private TextView barButton(LinearLayout parent, int text, Runnable r) {
        return barButton(parent, getContext().getString(text), 0x14FFFFFF, r);
    }

    private TextView barButton(LinearLayout parent, int text, int color, Runnable r) {
        return barButton(parent, getContext().getString(text), color, r);
    }

    private TextView barButton(LinearLayout parent, String text, int color, Runnable r) {
        TextView b = Style.button(getContext(), text, color, Style.TEXT_SMALL, 10);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(v -> {
            Style.tick(v);
            r.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(34));
        p.leftMargin = p.rightMargin = dp(3);
        parent.addView(b, p);
        return b;
    }

    // ---- what is shown ----

    /** The app on the external display: its layout. */
    void setPackage(String p) {
        if (p != null && p.equals(pkg)) return;
        pkg = p;
        if (editing) return;
        layout = store.forApp(p);
        pad.invalidate();
    }

    /** Back: closes the menu or the editor first. False when there was neither, and the gamepad should go. */
    boolean back() {
        if (menuScroll.getVisibility() == VISIBLE) {
            closeMenu();
            return true;
        }
        if (editing) {
            stopEditing(false);
            return true;
        }
        return false;
    }

    /** Every finger lifted: for leaving, a pause, a cancelled touch. */
    void releaseAll() {
        pad.release();
    }

    // ---- the menu ----

    private void openMenu() {
        pad.release();
        menu.removeAllViews();
        Context c = getContext();
        TextView title = Style.label(c, c.getString(R.string.xd_pad_menu_title, layout.name), Style.TEXT_DIM, 12);
        title.setPadding(dp(10), dp(4), dp(10), dp(8));
        menu.addView(title);
        item(R.string.xd_pad_resume, this::closeMenu);
        item(R.string.xd_pad_edit, () -> {
            closeMenu();
            startEditing();
        });
        // "布局 标准 ›": the current layout, and tapping it goes to the next (Gamepad.dc.html's row)
        item(getContext().getString(R.string.xd_pad_menu_layout) + " " + layout.name + " ›", () -> {
            java.util.List<GamepadLayout> all = store.all();
            int at = all.indexOf(layout);
            use(all.get((at + 1) % all.size()));
            openMenu();
        });
        item(R.string.xd_pad_new, () -> {
            use(store.add(layout, store.nextName(c.getString(R.string.xd_pad_name_n))));
            openMenu();
        });
        if (!GamepadLayout.builtIn(layout.id)) {
            item(R.string.xd_pad_delete, () -> {
                store.remove(layout);
                use(store.forApp(null));
                openMenu();
            });
        }
        item(getContext().getString(R.string.xd_pad_menu_opacity) + " " + Math.round(layout.opacity * 100) + "%", () -> {
            // The mockup's one row for the pad's opacity: each tap goes one step stronger, then back to faint
            layout.opacity = layout.opacity >= GamepadLayout.OPACITY_MAX - 0.01f
                ? GamepadLayout.OPACITY_MIN : GamepadLayout.clamp(Math.round((layout.opacity + 0.1f) * 10) / 10f,
                    GamepadLayout.OPACITY_MIN, GamepadLayout.OPACITY_MAX);
            store.save();
            openMenu();
        });
        itemSwitch(c.getString(R.string.xd_pad_haptics_label), store.haptics(), () -> {
            store.setHaptics(!store.haptics());
            openMenu();
        });
        item(c.getString(layout.floatingSticks ? R.string.xd_pad_sticks_floating : R.string.xd_pad_sticks_fixed), () -> {
            layout.floatingSticks = !layout.floatingSticks;
            store.save();
            openMenu();
        });
        item(R.string.xd_pad_copy, () -> {
            String text = store.copyText(layout);
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (text != null && cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("XDesk gamepad", text));
                Toast.makeText(c, R.string.xd_pad_copied, Toast.LENGTH_SHORT).show();
            }
        });
        item(R.string.xd_pad_paste, () -> {
            GamepadLayout l = store.paste(clipboard(c), store.nextName(c.getString(R.string.xd_pad_name_n)));
            if (l == null) {
                Toast.makeText(c, R.string.xd_pad_paste_bad, Toast.LENGTH_SHORT).show();
                return;
            }
            use(l);
            openMenu();
        });
        item(R.string.xd_pad_exit, Style.STOP, () -> {
            closeMenu();
            host.exit();
        });
        menuScroll.setVisibility(VISIBLE);
    }

    /**
     * The clipboard's text. The phone's window does not take focus while an app is on the external display (Android
     * then hides the clipboard from it), so what XDesk's clipboard watch last saw comes first.
     */
    private static String clipboard(Context c) {
        ShareHub hub = ShareHub.peek();
        String text = hub != null ? hub.current() : null;
        if (text != null) return text;
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence t = clip.getItemAt(0).getText();
        return t != null ? t.toString() : null;
    }

    private void item(int text, Runnable r) {
        item(getContext().getString(text), Style.TEXT, r);
    }

    private void item(int text, int color, Runnable r) {
        item(getContext().getString(text), color, r);
    }

    private void item(String text, Runnable r) {
        item(text, Style.TEXT, r);
    }

    /** A menu row: 13 dp words, left aligned, white at 8% only while pressed (Gamepad.dc.html's rows). */
    private void item(String text, int color, Runnable r) {
        TextView b = Style.button(getContext(), text, 0x00000000, 13, 8);
        b.setTextColor(color);
        b.setGravity(Gravity.START);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(v -> {
            Style.tick(v);
            r.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(40));
        p.topMargin = dp(2);
        menu.addView(b, p);
    }

    /** A menu row that is on or off, shown by a small switch, never by "on"/"off" in its words (Components.dc.html). */
    private void itemSwitch(String label, boolean on, Runnable r) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        row.setBackground(Style.ripple(getContext(), 0x00000000, 8));
        TextView t = Style.label(getContext(), label, Style.TEXT, 13);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        SwitchBulb sw = new SwitchBulb(getContext(), on);
        row.addView(sw, new LinearLayout.LayoutParams(dp(40), dp(22)));
        row.setOnClickListener(v -> {
            Style.tick(v);
            r.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(40));
        p.topMargin = dp(2);
        menu.addView(row, p);
    }

    /** The menu's on/off look: a 40×22 track with its knob (the mockup's switch at 80%). */
    private static final class SwitchBulb extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean on;

        SwitchBulb(Context c, boolean on) {
            super(c);
            this.on = on;
        }

        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight(), r = h / 2;
            paint.setColor(on ? Style.ACCENT : Style.SURFACE_RAISED);
            canvas.drawRoundRect(0, 0, getWidth(), h, r, r, paint);
            paint.setColor(on ? android.graphics.Color.WHITE : Style.TEXT_DIM);
            float inset = h * 0.15f;
            float cx = on ? getWidth() - r : r;
            canvas.drawCircle(cx, r, r - inset, paint);
        }
    }

    private void closeMenu() {
        menuScroll.setVisibility(GONE);
    }

    private void use(GamepadLayout l) {
        layout = l;
        store.useFor(pkg, l);
        pad.invalidate();
    }

    // ---- the editor ----

    private void buildEditBar() {
        // GamepadEdit.dc.html: what is selected, the size and hide buttons, a hairline, then Cancel and Done
        editLabel = Style.label(getContext(), "", Style.TEXT_DIM, Style.TEXT_SMALL);
        editLabel.setPadding(dp(6), 0, dp(8), 0);
        editBar.addView(editLabel, new LinearLayout.LayoutParams(-2, -2));
        barButton(editBar, R.string.xd_pad_smaller, () -> resize(selected, 1 / 1.1f));
        barButton(editBar, R.string.xd_pad_larger, () -> resize(selected, 1.1f));
        hideButton = barButton(editBar, R.string.xd_pad_hide, () -> {
            if (selected < 0) return;
            layout.hidden[selected] = !layout.hidden[selected];
            showSelected();
        });
        barButton(editBar, R.string.xd_pad_reset_one, () -> {
            if (selected < 0) return;
            layout.moved[selected] = false;
            layout.scale[selected] = 1f;
            layout.hidden[selected] = false;
            showSelected();
        });
        View line = new View(getContext());
        line.setBackgroundColor(Style.SHELL_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(20));
        lp.leftMargin = lp.rightMargin = dp(6);
        editBar.addView(line, lp);
        barButton(editBar, R.string.xd_pad_cancel, () -> stopEditing(false));
        TextView done = barButton(editBar, R.string.xd_pad_done, Style.ACCENT, () -> stopEditing(true));
        done.setTextColor(android.graphics.Color.WHITE);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        showSelected();
    }

    private void startEditing() {
        pad.release();
        host.end();
        before = layout.copy(layout.id, layout.name);
        editing = true;
        selected = -1;
        editScroll.setVisibility(VISIBLE);
        editHint.setText(getContext().getString(R.string.xd_pad_edit_hint, layout.name));
        editHint.setVisibility(VISIBLE);
        showSelected();
    }

    private void stopEditing(boolean keep) {
        if (keep) {
            store.save();
        } else if (before != null) {
            layout.set(before);
        }
        before = null;
        editing = false;
        selected = -1;
        editScroll.setVisibility(GONE);
        editHint.setVisibility(GONE);
        pad.invalidate();
    }

    private void showSelected() {
        if (editLabel != null) {
            String name = "—";
            if (selected >= 0) {
                GamepadLayout.Control c = GamepadLayout.CONTROLS[selected];
                name = c.label == null || c.label.isEmpty() ? c.id : c.label;
            }
            editLabel.setText(getContext().getString(R.string.xd_pad_selected, name));
        }
        if (hideButton != null) {
            hideButton.setText(getContext().getString(selected >= 0 && layout.hidden[selected] ? R.string.xd_pad_show : R.string.xd_pad_hide));
            hideButton.setEnabled(selected >= 0 && GamepadLayout.CONTROLS[selected].kind != GamepadLayout.MENU);
            hideButton.setAlpha(selected >= 0 && GamepadLayout.CONTROLS[selected].kind != GamepadLayout.MENU ? 1f : 0.4f);
        }
        pad.invalidate();
    }

    private void resize(int i, float by) {
        if (i < 0 || i >= GamepadLayout.N) return;
        layout.scale[i] = GamepadLayout.clamp(layout.scale[i] * by, GamepadLayout.SCALE_MIN, GamepadLayout.SCALE_MAX);
        pad.invalidate();
    }

    // ---- the controls ----

    private static final class Touch {
        int control;
        /** Where it went down: a floating stick's centre. */
        float ox, oy, x, y;
        /** Editing: from the finger to the control's centre; and whether it moved enough to be a drag. */
        float dx, dy;
        boolean dragging;
    }

    private final class Pad extends View {
        private final SparseArray<Touch> touches = new SparseArray<>();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] b = new float[4], tmp = new float[4];
        private final RectF r = new RectF();
        private final int[] axes = new int[2];
        private int buttons, hat = GamepadHid.HAT_NONE, lx, ly, rx, ry, lt, rt;
        private boolean sentAny;

        Pad(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            // Gamepad.dc.html: a 1.5 px line, 28% white, with the 10% fill inside
            line.setStrokeWidth(Math.max(1f, Style.dp(c, 1.5f)));
            dashed.setStyle(Paint.Style.STROKE);
            dashed.setStrokeWidth(Math.max(1f, Style.dp(c, 1.5f)));
            dashed.setPathEffect(new DashPathEffect(new float[]{Style.dp(c, 6), Style.dp(c, 5)}, 0));
            text.setTextAlign(Paint.Align.CENTER);
            text.setFakeBoldText(true);
        }

        private float factor() {
            return GamepadLayout.sizeFactor(getHeight(), getResources().getDisplayMetrics().ydpi);
        }

        void release() {
            touches.clear();
            if (sentAny) host.end();
            sentAny = false;
            buttons = 0;
            hat = GamepadHid.HAT_NONE;
            lx = ly = rx = ry = lt = rt = 0;
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            // With the menu open a touch beside it closes it, and presses nothing
            if (menuScroll.getVisibility() == VISIBLE) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) closeMenu();
                return true;
            }
            if (editing) return editTouch(e);
            float W = getWidth(), H = getHeight(), f = factor();
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_CANCEL) {
                release();
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                int k = e.getActionIndex();
                float x = e.getX(k), y = e.getY(k);
                int hit = layout.hit(x, y, W, H, f, false, tmp);
                if (hit >= 0 && GamepadLayout.CONTROLS[hit].kind == GamepadLayout.MENU) {
                    openMenu();
                    return true;
                }
                if (hit >= 0) {
                    Touch t = new Touch();
                    t.control = hit;
                    t.ox = t.x = x;
                    t.oy = t.y = y;
                    touches.put(e.getPointerId(k), t);
                }
            } else if (a == MotionEvent.ACTION_MOVE) {
                for (int k = 0; k < e.getPointerCount(); k++) {
                    Touch t = touches.get(e.getPointerId(k));
                    if (t == null) continue;
                    t.x = e.getX(k);
                    t.y = e.getY(k);
                    // A face button slid off onto another: that one now (a thumb rolls from A to B)
                    if (GamepadLayout.CONTROLS[t.control].face()) {
                        int now = layout.hit(t.x, t.y, W, H, f, false, tmp);
                        if (now >= 0 && now != t.control && GamepadLayout.CONTROLS[now].face()) {
                            layout.box(now, W, H, f, tmp);
                            if (GamepadLayout.sdf(now, tmp, t.x, t.y) < 0) t.control = now;
                        }
                    }
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) {
                touches.remove(e.getPointerId(e.getActionIndex()));
            }
            update(W, H, f);
            return true;
        }

        /** The state from the fingers, sent when it changed. */
        private void update(float W, float H, float f) {
            int nb = 0, nh = GamepadHid.HAT_NONE, nlx = 0, nly = 0, nrx = 0, nry = 0, nlt = 0, nrt = 0;
            for (int k = 0; k < touches.size(); k++) {
                Touch t = touches.valueAt(k);
                GamepadLayout.Control c = GamepadLayout.CONTROLS[t.control];
                layout.box(t.control, W, H, f, b);
                switch (c.kind) {
                    case GamepadLayout.TRIGGER:
                        if (c.button == GamepadLayout.L2) nlt = 255;
                        else nrt = 255;
                        // fall through: the L2 / R2 button too, for games that read only that
                    case GamepadLayout.BUTTON:
                        nb |= 1 << (c.button - 1);
                        break;
                    case GamepadLayout.DPAD:
                        nh = GamepadLayout.hat(t.x - b[0], t.y - b[1], b[2]);
                        break;
                    case GamepadLayout.STICK_L:
                    case GamepadLayout.STICK_R:
                        float cx = layout.floatingSticks ? t.ox : b[0], cy = layout.floatingSticks ? t.oy : b[1];
                        GamepadLayout.stick((t.x - cx) / b[2], (t.y - cy) / b[2], axes);
                        if (c.kind == GamepadLayout.STICK_L) {
                            nlx = axes[0];
                            nly = axes[1];
                        } else {
                            nrx = axes[0];
                            nry = axes[1];
                        }
                        break;
                }
            }
            boolean changed = nb != buttons || nh != hat || nlx != lx || nly != ly || nrx != rx || nry != ry || nlt != lt || nrt != rt;
            if (!changed) return;
            boolean pressed = (nb & ~buttons) != 0 || nh != hat && nh != GamepadHid.HAT_NONE;
            if (pressed && store.haptics()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            buttons = nb;
            hat = nh;
            lx = nlx;
            ly = nly;
            rx = nrx;
            ry = nry;
            lt = nlt;
            rt = nrt;
            host.state(buttons, hat, lx, ly, rx, ry, lt, rt);
            sentAny = true;
            invalidate();
        }

        private boolean editTouch(MotionEvent e) {
            float W = getWidth(), H = getHeight(), f = factor();
            int a = e.getActionMasked();
            int k = e.getActionIndex();
            if (a == MotionEvent.ACTION_DOWN) {
                touches.clear();
                float x = e.getX(), y = e.getY();
                int hit = layout.hit(x, y, W, H, f, true, tmp);
                selected = hit;
                showSelected();
                if (hit >= 0) {
                    layout.box(hit, W, H, f, b);
                    Touch t = new Touch();
                    t.control = hit;
                    t.ox = x;
                    t.oy = y;
                    t.dx = b[0] - x;
                    t.dy = b[1] - y;
                    touches.put(e.getPointerId(0), t);
                }
            } else if (a == MotionEvent.ACTION_MOVE) {
                Touch t = touches.get(e.getPointerId(0));
                if (t == null) return true;
                float x = e.getX(0), y = e.getY(0);
                if (!t.dragging && Math.hypot(x - t.ox, y - t.oy) > GamepadLayout.GRID * H) t.dragging = true;
                if (t.dragging) {
                    layout.moveTo(t.control, x + t.dx, y + t.dy, W, H, f);
                    invalidate();
                }
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL
                    || a == MotionEvent.ACTION_POINTER_UP && e.getPointerId(k) == 0) {
                touches.clear();
            }
            return true;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            // The screen turned: fingers would be held by controls that moved
            if (!editing && touches.size() > 0) release();
        }

        @Override protected void onDraw(Canvas canvas) {
            float W = getWidth(), H = getHeight(), f = factor();
            int alpha = Math.round(layout.opacity * 255);
            text.setTextSize(H * 0.045f * f);
            for (int i = 0; i < GamepadLayout.N; i++) {
                if (layout.hidden[i] && !editing) continue;
                layout.box(i, W, H, f, b);
                GamepadLayout.Control c = GamepadLayout.CONTROLS[i];
                boolean faint = layout.hidden[i];
                int a = faint ? alpha / 3 : c.kind == GamepadLayout.MENU ? Math.max(alpha, 150) : alpha;
                switch (c.kind) {
                    case GamepadLayout.STICK_L:
                    case GamepadLayout.STICK_R:
                        drawStick(canvas, i, a);
                        break;
                    case GamepadLayout.DPAD:
                        drawDpad(canvas, a);
                        break;
                    default:
                        drawButton(canvas, i, c, a);
                }
                if (editing) {
                    float rad = GamepadLayout.corner(i, b);
                    r.set(b[0] - b[2], b[1] - b[3], b[0] + b[2], b[1] + b[3]);
                    if (i == selected) {
                        // The chosen control: accent fill and a solid accent outline (GamepadEdit.dc.html's .sel)
                        fill.setColor(Style.ACCENT);
                        fill.setAlpha(56);
                        canvas.drawRoundRect(r, rad, rad, fill);
                        line.setColor(Style.ACCENT);
                        line.setAlpha(255);
                        line.setStrokeWidth(Math.max(2f, Style.dp(getContext(), 2)));
                        canvas.drawRoundRect(r, rad, rad, line);
                        line.setStrokeWidth(Math.max(1f, Style.dp(getContext(), 1.5f)));
                    } else {
                        dashed.setColor(0x66FFFFFF);
                        canvas.drawRoundRect(r, rad, rad, dashed);
                    }
                }
            }
        }

        private Touch touchOf(int i) {
            for (int k = 0; k < touches.size(); k++) if (touches.valueAt(k).control == i) return touches.valueAt(k);
            return null;
        }

        private void drawButton(Canvas canvas, int i, GamepadLayout.Control c, int a) {
            boolean down = !editing && c.button != 0 && (buttons & (1 << (c.button - 1))) != 0;
            float rad = GamepadLayout.corner(i, b);
            r.set(b[0] - b[2], b[1] - b[3], b[0] + b[2], b[1] + b[3]);
            float stroke = line.getStrokeWidth();
            r.inset(stroke / 2f, stroke / 2f);
            fill.setColor(down ? Style.ACCENT : PAD_COLOR);
            fill.setAlpha(down ? Math.min(255, a + 80) : Math.round(a * 0.2f));
            canvas.drawRoundRect(r, rad, rad, fill);
            line.setColor(down ? Style.ACCENT : PAD_COLOR);
            line.setAlpha(down ? Math.min(255, a + 80) : Math.round(a * 0.56f));
            canvas.drawRoundRect(r, rad, rad, line);
            text.setColor(c.face() ? faceColor(c.button) : PAD_COLOR);
            text.setAlpha(Math.min(255, Math.round(a * 1.7f)));
            text.setTextSize(Math.min(getWidth(), getHeight()) * 0.045f * factor() * (GamepadLayout.CONTROLS[i].kind == GamepadLayout.MENU ? 1.1f : 1f));
            canvas.drawText(c.label, b[0], b[1] - (text.descent() + text.ascent()) / 2, text);
        }

        private int faceColor(int button) {
            switch (button) {
                case GamepadLayout.A: return Style.OK;
                case GamepadLayout.B: return Style.DANGER;
                case GamepadLayout.X: return Style.ACCENT_2;
                case GamepadLayout.Y: return FACE_Y;
                default: return PAD_COLOR;
            }
        }

        private void drawStick(Canvas canvas, int i, int a) {
            Touch t = editing ? null : touchOf(i);
            float cx = b[0], cy = b[1], radius = b[2];
            if (t != null && layout.floatingSticks) {
                cx = t.ox;
                cy = t.oy;
            }
            float stroke = line.getStrokeWidth();
            fill.setColor(PAD_COLOR);
            fill.setAlpha(Math.round(a * 0.2f));
            canvas.drawCircle(cx, cy, radius - stroke / 2f, fill);
            line.setColor(PAD_COLOR);
            line.setAlpha(Math.round(a * 0.56f));
            canvas.drawCircle(cx, cy, radius - stroke / 2f, line);
            float kx = cx, ky = cy;
            if (t != null) {
                float dx = t.x - cx, dy = t.y - cy, len = (float) Math.hypot(dx, dy);
                float m = len > radius ? radius / len : 1f;
                kx = cx + dx * m;
                ky = cy + dy * m;
            }
            fill.setColor(t != null ? Style.ACCENT : PAD_COLOR);
            fill.setAlpha(t != null ? Math.min(255, a + 80) : Math.round(a * 0.45f));
            canvas.drawCircle(kx, ky, radius * 0.42f, fill);
        }

        /** Four pills with the arrows, as Gamepad.dc.html draws the d-pad, lit in its direction. */
        private void drawDpad(Canvas canvas, int a) {
            float s = b[2], half = 0.733f * s / 2f;
            int h = editing ? GamepadHid.HAT_NONE : hat;
            boolean up = h == 7 || h == 0 || h == 1, right = h >= 1 && h <= 3, down = h >= 3 && h <= 5, left = h >= 5 && h <= 7;
            dpadPill(canvas, b[0], b[1] - s + half, half, half * 1.1f, "▲", up, a);
            dpadPill(canvas, b[0], b[1] + s - half, half, half * 1.1f, "▼", down, a);
            dpadPill(canvas, b[0] - s + half, b[1], half * 1.1f, half, "◀", left, a);
            dpadPill(canvas, b[0] + s - half, b[1], half * 1.1f, half, "▶", right, a);
        }

        private void dpadPill(Canvas canvas, float cx, float cy, float hx, float hy, String label, boolean lit, int a) {
            r.set(cx - hx, cy - hy, cx + hx, cy + hy);
            float rad = Math.min(hx, hy) * 0.85f;
            float stroke = line.getStrokeWidth();
            r.inset(stroke / 2f, stroke / 2f);
            fill.setColor(lit ? Style.ACCENT : PAD_COLOR);
            fill.setAlpha(lit ? Math.min(255, a + 80) : Math.round(a * 0.2f));
            canvas.drawRoundRect(r, rad, rad, fill);
            line.setColor(lit ? Style.ACCENT : PAD_COLOR);
            line.setAlpha(lit ? Math.min(255, a + 80) : Math.round(a * 0.56f));
            canvas.drawRoundRect(r, rad, rad, line);
            text.setColor(PAD_COLOR);
            text.setAlpha(Math.min(255, Math.round(a * 1.7f)));
            text.setTextSize(Math.min(hx, hy) * 0.85f);
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text);
        }
    }
}
