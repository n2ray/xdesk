package io.github.n2ray.xdesk.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The pointer and scroll feel kept from XDesk 0.5: gain 1.3 + 0.09 per dp of pace, at most 5, scroll step 28 dp. */
public class PointerFeelTest {
    private static Recorder slide(float density, float speed, float stepDp, int events) {
        Tuning t = new Tuning();
        t.density = density;
        t.pointerSpeed = speed;
        FakeScheduler clock = new FakeScheduler();
        Recorder rec = new Recorder();
        Touchpad pad = new Touchpad(t, clock, rec);
        Touches e = new Touches();
        pad.onTouch(e.set(Touches.DOWN, 0, 0).pointer(0, 0, 0));
        for (int k = 1; k <= events; k++) pad.onTouch(e.set(Touches.MOVE, 0, 16 * k).pointer(0, k * stepDp * density, 0));
        pad.onTouch(e.set(Touches.UP, 0, 16 * events + 5).pointer(0, events * stepDp * density, 0));
        return rec;
    }

    private static float desktopGain(float dpPerMs, int interval) {
        Tuning t = new Tuning();
        t.desktopPad = true;
        Recorder rec = new Recorder();
        Touchpad pad = new Touchpad(t, new FakeScheduler(), rec);
        Touches e = new Touches();
        pad.onTouch(e.set(Touches.DOWN, 0, 0).pointer(0, 0, 0));
        int initial = 0;
        for (int ms = interval; ms <= 800; ms += interval) {
            pad.onTouch(e.set(Touches.MOVE, 0, ms).pointer(0, dpPerMs * ms, 0));
            if (ms == 400) initial = rec.moveX;
        }
        return (rec.moveX - initial) / (400 * dpPerMs);
    }

    @Test public void desktopGainProgressesThroughMidrangeAcrossReportRates() {
        float previous = 0;
        for (float speed : new float[]{0.1f, 0.35f, 0.65f, 0.9f, 1.4f, 2f}) {
            float gain = desktopGain(speed, 8);
            assertTrue(gain > previous);
            if (previous > 0) assertTrue(gain - previous < 0.25f);
            assertEquals(gain, desktopGain(speed, 16), 0.03f);
            previous = gain;
        }
    }

    @Test public void desktopFastSwipesGetAGentleTrim() {
        for (float speed : new float[]{0.5f, 1f, 2f, 3f, 5f}) {
            float previousGain = 1f + 1.25f * speed / (1f + speed);
            float measuredGain = desktopGain(speed, 8);
            if (speed <= 1f) assertEquals(previousGain, measuredGain, 0.006f);
            else assertTrue(measuredGain < previousGain * 0.99f && measuredGain > previousGain * 0.94f);
        }
    }

    @Test public void desktopDragStartsAtTheRestingCursor() {
        Tuning t = new Tuning();
        t.desktopPad = true;
        FakeScheduler clock = new FakeScheduler();
        Recorder rec = new Recorder();
        Touchpad pad = new Touchpad(t, clock, rec);
        Touches e = new Touches();
        pad.onTouch(e.set(Touches.DOWN, 0, 0).pointer(0, 100, 100));
        clock.advance(t.longPressMs);
        pad.onTouch(e.set(Touches.MOVE, 0, 650).pointer(0, 114, 100));
        assertEquals(0, rec.moveX);
        assertEquals("button " + Input.LEFT + " down", rec.raw.get(0));
        assertEquals(1, rec.feedback.size());
        pad.onTouch(e.set(Touches.MOVE, 0, 675).pointer(0, 117, 100));
        assertTrue(rec.moveX > 0 && rec.moveX <= 4);
        pad.cancel();
        assertEquals("button " + Input.LEFT + " up", rec.raw.get(rec.raw.size() - 1));
    }

    @Test public void desktopSlowSlideAfterTapNeverArmsDrag() {
        Tuning t = new Tuning();
        t.desktopPad = true;
        FakeScheduler clock = new FakeScheduler();
        Recorder rec = new Recorder();
        Touchpad pad = new Touchpad(t, clock, rec);
        Touches e = new Touches();
        pad.onTouch(e.set(Touches.DOWN, 0, 0).pointer(0, 0, 0));
        pad.onTouch(e.set(Touches.UP, 0, 40).pointer(0, 0, 0));
        rec.feedback.clear();
        rec.raw.clear();
        pad.onTouch(e.set(Touches.DOWN, 0, 90).pointer(0, 0, 0));
        pad.onTouch(e.set(Touches.MOVE, 0, 150).pointer(0, 3, 0));
        clock.advance(900);
        pad.onTouch(e.set(Touches.UP, 0, 910).pointer(0, 3, 0));
        assertTrue(rec.feedback.isEmpty());
        assertEquals(java.util.Collections.singletonList("move"), rec.summary());
    }

    @Test public void gainFollowsPace() {
        // 10 dp per event: gain 1.3 + 0.9 = 2.2, in pixels of a density 2 screen: 44 per event
        assertEquals(88, slide(2, 1, 10, 2).moveX);
    }

    @Test public void gainHasACeiling() {
        // 100 dp per event would be 10.3, the ceiling is 5
        assertEquals(500, slide(1, 1, 100, 1).moveX);
    }

    @Test public void speedSettingMultiplies() {
        assertEquals(176, slide(2, 2, 10, 2).moveX);
    }

    @Test public void scrollSpeedShortensTheStep() {
        Tuning t = new Tuning();
        t.setScrollSpeed(2);
        assertEquals(14f, t.scrollStepDp, 0.001f);
    }

    @Test public void multiFingerSwipeDecision() {
        MultiFinger m = new MultiFinger();
        assertTrue(!m.down(0, 0, 0, 0, false));
        m.down(1, 30, 0, 10, false);
        assertTrue(m.down(2, 60, 0, 20, false));
        for (int k = 1; k <= 5; k++) for (int id = 0; id < 3; id++) m.move(id, id * 30, -10 * k, 20 + 16 * k);
        int d = m.up(2, 60, -50, 110);
        assertEquals(MultiFinger.SWIPE_UP, MultiFinger.gesture(d));
        assertEquals(3, MultiFinger.fingers(d));
    }
}
