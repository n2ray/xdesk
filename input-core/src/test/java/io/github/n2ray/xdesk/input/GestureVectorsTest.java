package io.github.n2ray.xdesk.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Runs the shared gesture table (src/test/resources/gestures.json): recorded touch sequences and what each engine
 * must make of them. The web display (xdesk.js) is to be checked against the same file.
 */
public class GestureVectorsTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> vectors() throws IOException {
        try (InputStream in = GestureVectorsTest.class.getResourceAsStream("/gestures.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return (Map<String, Object>) MiniJson.parse(new String(b.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static Tuning tuning(Map<String, Object> base, Map<String, Object> over) {
        Tuning t = new Tuning();
        for (Map<String, Object> m : new Map[]{base, over}) {
            if (m == null) continue;
            if (m.containsKey("density")) t.density = num(m.get("density"));
            if (m.containsKey("longPressMs")) t.longPressMs = (long) num(m.get("longPressMs"));
            if (m.containsKey("doubleTapMs")) t.doubleTapMs = (long) num(m.get("doubleTapMs"));
            if (m.containsKey("slopDp")) t.slopDp = num(m.get("slopDp"));
            if (m.containsKey("scrollStepDp")) t.scrollStepDp = num(m.get("scrollStepDp"));
            if (m.containsKey("naturalScroll")) t.naturalScroll = (Boolean) m.get("naturalScroll");
        }
        return t;
    }

    private static float num(Object o) {
        return ((Double) o).floatValue();
    }

    private static int action(String a) {
        switch (a) {
            case "down": return Touches.DOWN;
            case "pointerDown": return Touches.POINTER_DOWN;
            case "move": return Touches.MOVE;
            case "pointerUp": return Touches.POINTER_UP;
            case "up": return Touches.UP;
            case "cancel": return Touches.CANCEL;
            default: throw new IllegalArgumentException(a);
        }
    }

    interface Engine {
        void onTouch(Touches t);
    }

    @SuppressWarnings("unchecked")
    private static void run(String engineName, Map<String, Object> all) {
        List<Object> cases = (List<Object>) all.get(engineName);
        assertTrue("no cases for " + engineName, cases != null && !cases.isEmpty());
        List<String> failures = new ArrayList<>();
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = (String) c.get("name");
            Tuning tuning = tuning((Map<String, Object>) all.get("tuning"), (Map<String, Object>) c.get("tuning"));
            FakeScheduler clock = new FakeScheduler();
            Recorder rec = new Recorder();
            Engine engine;
            switch (engineName) {
                case "touchpad": {
                    Touchpad p = new Touchpad(tuning, clock, rec);
                    engine = p::onTouch;
                    break;
                }
                case "direct":
                case "directFingers": {
                    DirectTouch p = new DirectTouch(tuning, clock, rec);
                    p.setFingers(engineName.equals("directFingers"));
                    engine = p::onTouch;
                    break;
                }
                case "cursorTouch": {
                    CursorTouch p = new CursorTouch(tuning, clock, rec);
                    engine = p::onTouch;
                    break;
                }
                default:
                    throw new IllegalArgumentException(engineName);
            }
            Touches t = new Touches();
            for (Object eo : (List<Object>) c.get("events")) {
                List<Object> e = (List<Object>) eo;
                String kind = (String) e.get(0);
                long time = (long) num(e.get(1));
                clock.advance(time);
                if (kind.equals("wait")) continue;
                // [kind, time, index, [[id, x, y], ...], tool?]
                t.set(action(kind), (int) num(e.get(2)), time);
                for (Object po : (List<Object>) e.get(3)) {
                    List<Object> p = (List<Object>) po;
                    t.pointer((int) num(p.get(0)), num(p.get(1)), num(p.get(2)));
                }
                t.tool = e.size() > 4 && "pen".equals(e.get(4)) ? Touches.TOOL_STYLUS : Touches.TOOL_FINGER;
                engine.onTouch(t);
            }
            clock.advance(clock.now() + 5000);
            List<String> expect = new ArrayList<>();
            for (Object x : (List<Object>) c.get("expect")) expect.add((String) x);
            List<String> got = rec.summary();
            if (!expect.equals(got)) failures.add(engineName + " / " + name + "\n  expected " + expect + "\n  got      " + got + "\n  raw      " + rec.raw);
        }
        assertEquals(String.join("\n", failures), 0, failures.size());
    }

    @Test public void touchpad() throws IOException {
        run("touchpad", vectors());
    }

    @Test public void directMouseOnly() throws IOException {
        run("direct", vectors());
    }

    @Test public void directFingers() throws IOException {
        run("directFingers", vectors());
    }

    @Test public void cursorTouch() throws IOException {
        run("cursorTouch", vectors());
    }
}
