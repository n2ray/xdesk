package io.github.n2ray.xdesk.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Writes down what an engine produced, and boils it down to the words the test vectors use. */
final class Recorder extends GestureOutput {
    final List<String> raw = new ArrayList<>();
    final List<Integer> feedback = new ArrayList<>();
    int moveX, moveY;
    float scrollX, scrollY;

    @Override public void move(int dx, int dy) {
        moveX += dx;
        moveY += dy;
        raw.add("move " + dx + " " + dy);
    }

    @Override public void position(float x, float y) {
        raw.add("position " + Math.round(x) + " " + Math.round(y));
    }

    @Override public void button(int button, boolean down) {
        raw.add("button " + button + (down ? " down" : " up"));
    }

    @Override public void scroll(int phase, float dx, float dy) {
        if (phase == Input.CHANGE || phase == Input.MOMENTUM) {
            scrollX += dx;
            scrollY += dy;
        }
        raw.add("scroll " + Input.phaseName(phase) + " " + dx + " " + dy);
    }

    @Override public void action(int action, int arg) {
        raw.add("action " + Input.actionName(action) + " " + arg);
    }

    @Override public void feedback(int kind) {
        feedback.add(kind);
    }

    @Override public void frame(int action, int[] ids, float[] offsetX, float[] offsetY, int count) {
        raw.add("frame " + action + " " + count);
    }

    /**
     * Moves in a row become one "move"; scroll changes in a row one "scroll change dx dy" with their sum to one
     * decimal; momentum frames one "scroll momentum"; a press right before its release a "click"; repeated move
     * frames one.
     */
    List<String> summary() {
        List<String> out = new ArrayList<>();
        float sx = 0, sy = 0;
        boolean inChange = false;
        for (int k = 0; k < raw.size(); k++) {
            String e = raw.get(k);
            if (e.startsWith("scroll change ")) {
                String[] p = e.split(" ");
                sx += Float.parseFloat(p[2]);
                sy += Float.parseFloat(p[3]);
                inChange = true;
                continue;
            }
            if (inChange) {
                out.add(String.format(Locale.ROOT, "scroll change %.1f %.1f", sx, sy));
                sx = sy = 0;
                inChange = false;
            }
            String last = out.isEmpty() ? "" : out.get(out.size() - 1);
            if (e.startsWith("move ")) {
                if (!last.equals("move")) out.add("move");
                continue;
            }
            if (e.startsWith("scroll momentum ")) {
                if (!last.equals("scroll momentum")) out.add("scroll momentum");
                continue;
            }
            if (e.startsWith("scroll ")) {
                out.add(e.substring(0, e.indexOf(' ', 7)));
                continue;
            }
            if (e.startsWith("frame 2 ") && last.equals(e)) continue;
            if (e.startsWith("button ") && e.endsWith(" down") && k + 1 < raw.size()
                && raw.get(k + 1).equals(e.substring(0, e.length() - 5) + " up")) {
                out.add("click " + e.charAt(7));
                k++;
                continue;
            }
            out.add(e);
        }
        if (inChange) out.add(String.format(Locale.ROOT, "scroll change %.1f %.1f", sx, sy));
        return out;
    }
}
