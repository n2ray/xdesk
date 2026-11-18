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

    private final Host host;
    private final GamepadStore store;
    private final Pad pad;
    private final LinearLayout menu, editBar, selectedBar;
    private final ScrollView menuScroll;
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
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        menu.setBackground(Style.rounded(c, Style.SURFACE, 16));
        menuScroll = new ScrollView(c);
        menuScroll.addView(menu, new ScrollView.LayoutParams(-1, -2));
        menuScroll.setVisibility(GONE);
        LayoutParams mp = new LayoutParams(dp(300), -2, Gravity.CENTER);
        mp.topMargin = mp.bottomMargin = dp(12);
        addView(menuScroll, mp);

        LinearLayout bars = new LinearLayout(c);
        bars.setOrientation(LinearLayout.VERTICAL);
        bars.setGravity(Gravity.CENTER_HORIZONTAL);
        editBar = row(c);
        selectedBar = row(c);
        bars.addView(editBar, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(6);
        bars.addView(selectedBar, sp);
        LayoutParams bp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bp.topMargin = dp(8);
        addView(bars, bp);
        editBar.setVisibility(GONE);
        selectedBar.setVisibility(GONE);
        buildEditBar();

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
        r.setPadding(dp(4), dp(4), dp(4), dp(4));
        r.setBackground(Style.rounded(c, Style.SURFACE, 14));
        return r;
    }

    private TextView barButton(LinearLayout parent, int text, Runnable r) {
        TextView b = Style.button(getContext(), getContext().getString(text), Style.SURFACE_RAISED, Style.TEXT_SMALL + 1, 10);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(v -> {
            Style.tick(v);
            r.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(40));
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
        TextView title = Style.label(c, c.getString(R.string.xd_pad_menu_title, layout.name), Style.TEXT_DIM, Style.TEXT_SMALL + 1);
        title.setPadding(dp(8), dp(4), dp(8), dp(8));
        menu.addView(title);
        item(R.string.xd_pad_resume, this::closeMenu);
        item(R.string.xd_pad_edit, () -> {
            closeMenu();
            startEditing();
        });
        item(c.getString(R.string.xd_pad_switch), () -> {
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
        item(c.getString(layout.floatingSticks ? R.string.xd_pad_sticks_floating : R.string.xd_pad_sticks_fixed), () -> {
            layout.floatingSticks = !layout.floatingSticks;
            store.save();
            openMenu();
        });
        item(c.getString(store.haptics() ? R.string.xd_pad_haptics_on : R.string.xd_pad_haptics_off), () -> {
            store.setHaptics(!store.haptics());
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
        item(R.string.xd_pad_exit, () -> {
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
        item(getContext().getString(text), r);
    }

    private void item(String text, Runnable r) {
        TextView b = Style.button(getContext(), text, Style.SURFACE_RAISED, Style.TEXT_SMALL + 2, 10);
        b.setOnClickListener(v -> {
            Style.tick(v);
            r.run();
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(46));
        p.topMargin = dp(4);
        menu.addView(b, p);
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
        barButton(editBar, R.string.xd_pad_done, () -> stopEditing(true));
        barButton(editBar, R.string.xd_pad_cancel, () -> stopEditing(false));
        barButton(editBar, R.string.xd_pad_reset_all, () -> {
            layout.reset();
            selected = -1;
            showSelected();
        });
        barButton(editBar, R.string.xd_pad_fainter, () -> opacity(-0.1f));
        barButton(editBar, R.string.xd_pad_bolder, () -> opacity(0.1f));
    }

    private void opacity(float by) {
        layout.opacity = GamepadLayout.clamp(Math.round((layout.opacity + by) * 10) / 10f, GamepadLayout.OPACITY_MIN, GamepadLayout.OPACITY_MAX);
        pad.invalidate();
    }

    private void startEditing() {
        pad.release();
        host.end();
        before = layout.copy(layout.id, layout.name);
        editing = true;
        selected = -1;
        editBar.setVisibility(VISIBLE);
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
        editBar.setVisibility(GONE);
        selectedBar.setVisibility(GONE);
        pad.invalidate();
    }

    private void showSelected() {
        selectedBar.removeAllViews();
        if (selected < 0) {
            selectedBar.setVisibility(GONE);
            pad.invalidate();
            return;
        }
        final int i = selected;
        barButton(selectedBar, R.string.xd_pad_smaller, () -> resize(i, 1 / 1.1f));
        barButton(selectedBar, R.string.xd_pad_larger, () -> resize(i, 1.1f));
        if (GamepadLayout.CONTROLS[i].kind != GamepadLayout.MENU) {
            barButton(selectedBar, layout.hidden[i] ? R.string.xd_pad_show : R.string.xd_pad_hide, () -> {
                layout.hidden[i] = !layout.hidden[i];
                showSelected();
            });
        }
        barButton(selectedBar, R.string.xd_pad_reset_one, () -> {
            layout.moved[i] = false;
            layout.scale[i] = 1f;
            layout.hidden[i] = false;
            showSelected();
        });
        selectedBar.setVisibility(VISIBLE);
        pad.invalidate();
    }

    private void resize(int i, float by) {
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
            line.setStrokeWidth(Style.dp(c, 2));
            dashed.setStyle(Paint.Style.STROKE);
            dashed.setStrokeWidth(Style.dp(c, 2));
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
                    dashed.setColor(i == selected ? Style.ACCENT : 0x99FFFFFF);
                    canvas.drawRoundRect(r, rad, rad, dashed);
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
            fill.setColor(down ? Style.ACCENT : 0xFFFFFFFF);
            fill.setAlpha(down ? Math.min(255, a + 80) : a / 4);
            canvas.drawRoundRect(r, rad, rad, fill);
            line.setColor(0xFFFFFFFF);
            line.setAlpha(a);
            canvas.drawRoundRect(r, rad, rad, line);
            text.setColor(0xFFFFFFFF);
            text.setAlpha(Math.min(255, a + 40));
            canvas.drawText(c.label, b[0], b[1] - (text.descent() + text.ascent()) / 2, text);
        }

        private void drawStick(Canvas canvas, int i, int a) {
            Touch t = editing ? null : touchOf(i);
            float cx = b[0], cy = b[1], radius = b[2];
            if (t != null && layout.floatingSticks) {
                cx = t.ox;
                cy = t.oy;
            }
            fill.setColor(0xFFFFFFFF);
            fill.setAlpha(a / 5);
            canvas.drawCircle(cx, cy, radius, fill);
            line.setColor(0xFFFFFFFF);
            line.setAlpha(a);
            canvas.drawCircle(cx, cy, radius, line);
            float kx = cx, ky = cy;
            if (t != null) {
                float dx = t.x - cx, dy = t.y - cy, len = (float) Math.hypot(dx, dy);
                float m = len > radius ? radius / len : 1f;
                kx = cx + dx * m;
                ky = cy + dy * m;
            }
            fill.setColor(t != null ? Style.ACCENT : 0xFFFFFFFF);
            fill.setAlpha(t != null ? Math.min(255, a + 80) : a / 2);
            canvas.drawCircle(kx, ky, radius * 0.42f, fill);
        }

        private void drawDpad(Canvas canvas, int a) {
            float s = b[2], arm = s * 0.36f;
            int h = editing ? GamepadHid.HAT_NONE : hat;
            // 0 up, 1 up-right, 2 right ... 7 up-left: which arms are lit
            boolean up = h == 7 || h == 0 || h == 1, right = h >= 1 && h <= 3, down = h >= 3 && h <= 5, left = h >= 5 && h <= 7;
            arm(canvas, b[0] - arm, b[1] - s, b[0] + arm, b[1] - arm * 0.6f, up, a);
            arm(canvas, b[0] - arm, b[1] + arm * 0.6f, b[0] + arm, b[1] + s, down, a);
            arm(canvas, b[0] - s, b[1] - arm, b[0] - arm * 0.6f, b[1] + arm, left, a);
            arm(canvas, b[0] + arm * 0.6f, b[1] - arm, b[0] + s, b[1] + arm, right, a);
        }

        private void arm(Canvas canvas, float l, float t, float rr, float bb, boolean lit, int a) {
            r.set(l, t, rr, bb);
            float rad = Math.min(rr - l, bb - t) * 0.3f;
            fill.setColor(lit ? Style.ACCENT : 0xFFFFFFFF);
            fill.setAlpha(lit ? Math.min(255, a + 80) : a / 4);
            canvas.drawRoundRect(r, rad, rad, fill);
            line.setColor(0xFFFFFFFF);
            line.setAlpha(a);
            canvas.drawRoundRect(r, rad, rad, line);
        }
    }
}
