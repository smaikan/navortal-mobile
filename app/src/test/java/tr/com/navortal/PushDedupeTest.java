package tr.com.navortal;

import static org.junit.Assert.*;

import org.junit.Test;

public class PushDedupeTest {
    static final long T0 = 1_800_000_000_000L;

    @Test public void insertsOnceThenReportsDuplicate() {
        var stored = PushDedupe.insert("", "a", T0);
        assertNotNull(stored);
        assertTrue(PushDedupe.contains(stored, "a", T0 + 1));
        assertNull(PushDedupe.insert(stored, "a", T0 + 5));
        assertNotNull(PushDedupe.insert(stored, "b", T0 + 5));
        assertFalse(PushDedupe.contains(stored, "b", T0));
    }

    @Test public void sizeIsBoundedDroppingOldest() {
        String stored = "";
        for (int i = 0; i < PushDedupe.MAX + 50; i++) stored = PushDedupe.insert(stored, "k" + i, T0 + i);
        assertFalse(PushDedupe.contains(stored, "k0", T0 + 1000));
        assertFalse(PushDedupe.contains(stored, "k49", T0 + 1000));
        assertTrue(PushDedupe.contains(stored, "k50", T0 + 1000));
        assertTrue(PushDedupe.contains(stored, "k" + (PushDedupe.MAX + 49), T0 + 1000));
        assertEquals(PushDedupe.MAX, stored.split("\n").length);
    }

    @Test public void entriesExpireAfterTwentyFourHours() {
        var stored = PushDedupe.insert("", "a", T0);
        assertTrue(PushDedupe.contains(stored, "a", T0 + PushDedupe.TTL_MS));
        assertFalse(PushDedupe.contains(stored, "a", T0 + PushDedupe.TTL_MS + 1));
        var again = PushDedupe.insert(stored, "a", T0 + PushDedupe.TTL_MS + 1); // süresi dolan anahtar yeniden gösterilebilir
        assertNotNull(again);
        assertEquals(1, again.split("\n").length);
    }

    @Test public void corruptStoredDataIsIgnoredAndKeysWithSeparatorsAreSafe() {
        assertNotNull(PushDedupe.insert("bozuk\nsatır\tabc\n\t5\n", "x", T0));
        var stored = PushDedupe.insert("", "a\tb\nc", T0);
        assertNotNull(stored);
        assertTrue(PushDedupe.contains(stored, "a\tb\nc", T0));
        assertNull(PushDedupe.insert(stored, "a\tb\nc", T0));
        assertEquals(1, stored.split("\n").length);
    }
}
