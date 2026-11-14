package io.github.n2ray.xdesk.input;

import static io.github.n2ray.xdesk.input.MouseButtons.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** A physical mouse's buttons however the device reports them, and modifiers an input method kept the release of. */
public class PhysicalInputTest {
    private final List<String> out = new ArrayList<>();
    private final MouseButtons mouse = new MouseButtons();
    private final MouseButtons.Out sink = (b, down) -> out.add(b + (down ? " down" : " up"));

    private void e(int action, int actionButton, int state) {
        mouse.event(action, actionButton, state, sink);
    }

    @Test public void aospRightClickIsOnePressAndOneRelease() {
        e(ACTION_DOWN, 0, SECONDARY);
        e(ACTION_BUTTON_PRESS, SECONDARY, SECONDARY);
        e(ACTION_BUTTON_RELEASE, SECONDARY, 0);
        e(ACTION_UP, 0, 0);
        assertEquals(Arrays.asList("3 down", "3 up"), out);
    }

    @Test public void downAndUpOnlyStillClick() {
        e(ACTION_DOWN, 0, SECONDARY);
        e(ACTION_UP, 0, 0);
        e(ACTION_DOWN, 0, TERTIARY);
        e(ACTION_UP, 0, TERTIARY);
        assertEquals(Arrays.asList("3 down", "3 up", "2 down", "2 up"), out);
    }

    @Test public void pressWithoutActionButtonUsesTheState() {
        e(ACTION_BUTTON_PRESS, 0, PRIMARY);
        e(ACTION_BUTTON_RELEASE, 0, 0);
        assertEquals(Arrays.asList("1 down", "1 up"), out);
    }

    @Test public void dragKeepsTheButtonThroughMovesWithoutState() {
        e(ACTION_DOWN, 0, PRIMARY);
        e(ACTION_BUTTON_PRESS, PRIMARY, PRIMARY);
        e(ACTION_MOVE, 0, PRIMARY);
        e(ACTION_MOVE, 0, 0);
        e(ACTION_MOVE, 0, PRIMARY | SECONDARY);
        e(ACTION_BUTTON_RELEASE, SECONDARY, PRIMARY);
        e(ACTION_BUTTON_RELEASE, PRIMARY, 0);
        e(ACTION_UP, 0, 0);
        assertEquals(Arrays.asList("1 down", "3 down", "3 up", "1 up"), out);
    }

    @Test public void sideButtonsAreLeftToTheKeys() {
        e(ACTION_DOWN, 0, BACK);
        e(ACTION_BUTTON_PRESS, BACK, BACK);
        e(ACTION_BUTTON_RELEASE, BACK, 0);
        e(ACTION_UP, 0, 0);
        assertEquals(new ArrayList<String>(), out);
    }

    @Test public void cancelLetsGo() {
        e(ACTION_DOWN, 0, PRIMARY);
        e(ACTION_CANCEL, 0, PRIMARY);
        assertEquals(Arrays.asList("1 down", "1 up"), out);
        assertFalse(mouse.holding());
    }

    @Test public void modifierWhoseReleaseWasKeptIsLetGo() {
        HeldModifiers m = new HeldModifiers();
        List<Integer> released = new ArrayList<>();
        m.sent(HeldModifiers.LEFT_SHIFT, true);
        m.sent(HeldModifiers.LEFT_CTRL, true);
        // A key with Ctrl still held: Shift's release never came
        m.check(HeldModifiers.META_CTRL_LEFT_ON, released::add);
        assertEquals(Arrays.asList(HeldModifiers.LEFT_SHIFT), released);
        m.sent(HeldModifiers.LEFT_CTRL, false);
        m.check(0, released::add);
        assertEquals(1, released.size());
    }
}
