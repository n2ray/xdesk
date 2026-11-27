// SPDX-License-Identifier: GPL-3.0-only
package io.github.n2ray.xdesk.input;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class CapturedKeysTest {
    @Test public void unpluggingOneKeyboardPreservesModifiersHeldOnAnother() {
        CapturedKeys keys = new CapturedKeys();
        assertEquals("USB", keys.update(10, "USB", 29, 1));
        assertNull(keys.update(11, "BT", 29, 1));
        assertEquals("BT", keys.update(11, "BT", 30, 1));
        assertEquals(Arrays.asList(29), keys.heldBy(10));
        for (int scan : keys.heldBy(10)) assertNull(keys.update(10, "USB", scan, 0));
        assertEquals("BT", keys.update(11, "BT", 30, 0));
        assertEquals("USB", keys.update(11, "BT", 29, 0));
        assertTrue(keys.heldBy(11).isEmpty());
    }

    @Test public void duplicateAndOutOfOrderEventsAndReusedFds() {
        CapturedKeys keys = new CapturedKeys();
        assertNull(keys.update(10, "USB", 30, 0));
        assertNull(keys.update(10, "USB", 30, 2));
        assertEquals("USB", keys.update(10, "USB", 30, 1));
        assertNull(keys.update(10, "USB", 30, 1));
        assertEquals("USB", keys.update(10, "USB", 30, 2));
        assertEquals("USB", keys.update(10, "USB", 30, 0));
        assertEquals("Replacement", keys.update(10, "Replacement", 30, 1));
        assertEquals("Replacement", keys.update(10, "Replacement", 30, 0));
    }
}
