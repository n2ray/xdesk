package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

/**
 * The icons of the switchboard's items, drawn the same on every surface (the phone's list, the Launchpad's tiles, the
 * Docks), and the key of its own tile. What the items are, their labels and what opening one does is the Switchboard's.
 * Main thread only.
 *
 * An item of an external device carries the device's badge (DEVICES.md §3.2) in the icon's bottom-left corner: a round
 * disc in the device's own colour, its type's glyph (a monitor for a Mac, "&gt;_" for Termux, a Linux environment's
 * first letter), ringed in the surface it sits on so it reads as cut out of the icon. It is an overlay of the
 * ImageView: it rides along with whatever is drawn under it and scales with the view (the Dock's zoom). Phone apps
 * carry none: no badge means the phone.
 */
final class ExternalLauncher {
    /** The dock's last tile: opens the switcher (SwitcherOverlay) on the external display. */
    static final String SWITCHER = "x:switcher";

    /** The badge's diameter, in parts of the icon. */
    private static final float BADGE = 1f / 3f;
    /** The badge's centre sits this far (in parts of the badge) in from the icon's corner: it overlaps the edge a little. */
    private static final float BADGE_IN = 0.30f;
    /** The ring's width, in parts of the badge's diameter. */
    private static final float RING = 0.09f;

    private ExternalLauncher() { }

    /**
     * The icon of an item in a view of its own (the Launchpad's tiles, the Docks). {@code surface} is the colour of
     * what the icon sits on: the badge's ring is drawn in it. {@code ownDevice} true where the view is the item's own
     * device's tab (Switchboard.ownTab): there every item is already that device's, the badge would say nothing.
     */
    static View icon(Context c, Switchboard.Item it, int surface, boolean ownDevice) {
        ImageView icon = new ImageView(c);
        icon(c, it, icon, surface, ownDevice);
        return icon;
    }

    /**
     * Draws an item's icon into {@code into}: its app's picture, its content's picture, or the one letter tile every
     * view uses. A whole desktop gets the same glyph here, in the phone's list and in both Docks.
     */
    static void icon(Context c, Switchboard.Item it, ImageView into, int surface, boolean ownDevice) {
        into.setTag(null);
        into.getOverlay().clear();
        if (SWITCHER.equals(it.key)) {
            into.setImageDrawable(Style.letterIcon(c, "⊞"));
            return;
        }
        if (it.app != null) {
            AppCatalog.get(c).showIcon(it.app, into);
        } else if (it.bitmap != null) {
            into.setImageBitmap(it.bitmap);
        } else {
            into.setImageDrawable(Style.letterIcon(c, it.desktop ? "▣" : it.label));
        }
        if (!ownDevice) {
            Devices d = Devices.get(c);
            String id = d.deviceOf(it.key);
            Devices.Device dev = d.find(id);
            if (dev != null && dev.external()) into.getOverlay().add(badge(c, into, id, dev, surface));
        }
    }

    // --- the device badge ---

    /** The badge's fill: one of a small fixed palette, picked by the device id, the same colour forever. */
    private static int fill(String deviceId) {
        int h = (int) 2166136261L;  // FNV-1a, spelled out: stable on any Java
        for (int i = 0; i < deviceId.length(); i++) h = (h ^ deviceId.charAt(i)) * 16777619;
        return Style.BADGE[Math.floorMod(h, Style.BADGE.length)];
    }

    private static Drawable badge(final Context c, final ImageView into, final String deviceId, final Devices.Device d,
                                  final int surface) {
        final int fill = fill(deviceId);
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            { paint.setColor(fill); ring.setColor(surface); ring.setStyle(Paint.Style.STROKE);
              text.setColor(Style.TEXT); text.setTextAlign(Paint.Align.CENTER); }

            @Override public void draw(Canvas canvas) {
                // The badge follows the view's own size (not its layout bounds at creation), so it survives recycling
                // and scales with the view, the Dock's zoom included
                float size = Math.min(into.getWidth(), into.getHeight());
                if (size < 4) return;  // not laid out yet
                float s = size * BADGE;
                float cx = s * BADGE_IN, cy = size - s * BADGE_IN;
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, s / 2f, paint);
                float ringW = Math.max(Style.dp(c, 1.5f), s * RING);
                ring.setStrokeWidth(ringW);
                canvas.drawCircle(cx, cy, (s - ringW) / 2f, ring);
                glyph(canvas, cx, cy, s, d, c, text);
            }

            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter colorFilter) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /** The glyph inside the badge: what kind of device it is. */
    private static void glyph(Canvas canvas, float cx, float cy, float s, Devices.Device d, Context c, Paint p) {
        switch (d.type) {
            case MAC:
                monitor(canvas, cx, cy, s, p);
                return;
            case LINUX:
                if (d.id.equals(Devices.LINUX + Devices.TERMUX)) {
                    letter(canvas, cx, cy, s, ">_", 0.42f, p);
                    return;
                }
                // An environment's badge shows its distro's initial, as Devices.linuxName displays it ("U" for Ubuntu)
                letter(canvas, cx, cy, s, firstLetter(Devices.linuxName(c, d.id)), 0.55f, p);
                return;
            default:
                // A future device type (Windows, another Android device): its name's first letter, until a glyph of
                // its own is drawn in a branch above
                letter(canvas, cx, cy, s, firstLetter(d.name), 0.55f, p);
        }
    }

    /** The first letter of a name, uppercase; "?" for none. */
    private static String firstLetter(String name) {
        return name == null || name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
    }

    /** A Mac's badge: a monitor, its screen and a short stand. Drawn, so there is no logo to carry. */
    private static void monitor(Canvas canvas, float cx, float cy, float s, Paint p) {
        p.setStyle(Paint.Style.FILL);
        RectF screen = new RectF(cx - s * 0.32f, cy - s * 0.24f, cx + s * 0.32f, cy + s * 0.12f);
        canvas.drawRoundRect(screen, s * 0.06f, s * 0.06f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, s * 0.05f));
        p.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx, cy + s * 0.16f, cx, cy + s * 0.28f, p);
        canvas.drawLine(cx - s * 0.14f, cy + s * 0.30f, cx + s * 0.14f, cy + s * 0.30f, p);
    }

    /** Text drawn in the middle of the badge, {@code em} parts of the badge's diameter. */
    private static void letter(Canvas canvas, float cx, float cy, float s, String glyph, float em, Paint p) {
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(s * em);
        Paint.FontMetrics m = p.getFontMetrics();
        canvas.drawText(glyph, cx, cy - (m.ascent + m.descent) / 2f, p);
    }
}
