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
