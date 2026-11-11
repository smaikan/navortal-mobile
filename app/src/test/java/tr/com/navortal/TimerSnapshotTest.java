package tr.com.navortal;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TimerSnapshotTest {
    static final long NOW = 1_794_384_000_000L;
    static final String OWNER = "00000000-0000-0000-0000-000000000001";

    JSONObject payload(String state, long elapsed) throws Exception {
        return new JSONObject().put("protocol", 1).put("ownerId", OWNER)
                .put("capturedAtMs", NOW - 1500).put("requestPermission", true)
                .put("work", new JSONObject().put("id", "00000000-0000-0000-0000-000000000002")
                    .put("version", "9223372036854775807").put("serverNow", "2026-11-11T08:00:00Z")
                    .put("title", "Görev").put("state", state).put("elapsedMs", elapsed));
    }

    @Test public void runningCompensatesOnlyBridgeTransitAndPreservesLongVersion() throws Exception {
        var timer = TimerSnapshot.parse(payload("Running", 120500), NOW);
        assertEquals(122000, timer.elapsedMs);
        assertEquals(Long.MAX_VALUE, timer.version);
        assertEquals("2:02", timer.clock());
    }

    @Test public void pausedDoesNotCountTransit() throws Exception {
        var timer = TimerSnapshot.parse(payload("Paused", 120500), NOW);
        assertEquals(120500, timer.elapsedMs);
        assertEquals("2:00", timer.clock());
    }

    @Test public void oldOrMalformedMessagesCannotClearAnExistingTimer() throws Exception {
        var missing = payload("Running", 0);
        missing.remove("work");
        assertThrows(Exception.class, () -> TimerSnapshot.parse(missing, NOW));
        assertThrows(Exception.class, () -> TimerSnapshot.parse(payload("Running", 0), NOW + 11000));
        assertThrows(Exception.class, () -> TimerSnapshot.parse(payload("Running", -1), NOW));
        var fractional = payload("Running", 0);
        fractional.getJSONObject("work").put("elapsedMs", 0.5);
        assertThrows(Exception.class, () -> TimerSnapshot.parse(fractional, NOW));
        assertThrows(Exception.class, () -> TimerSnapshot.parse(payload("Finished", 0), NOW));
    }

    @Test public void anOlderVersionOrServerReadCannotReplaceNewState() throws Exception {
        var newer = TimerSnapshot.parse(payload("Paused", 120500), NOW);
        var old = payload("Running", 100000);
        old.getJSONObject("work").put("version", "2");
        assertFalse(TimerSnapshot.parse(old, NOW).supersedes(newer));
        old = payload("Running", 100000);
        old.getJSONObject("work").put("serverNow", "2026-11-11T07:59:59Z");
        assertFalse(TimerSnapshot.parse(old, NOW).supersedes(newer));
    }

    @Test public void anotherOwnerDoesNotInheritVersionOrdering() throws Exception {
        var previous = TimerSnapshot.parse(payload("Running", 0), NOW);
        var next = payload("Running", 0).put("ownerId", "00000000-0000-0000-0000-000000000003");
        next.getJSONObject("work").put("version", "1");
        assertTrue(TimerSnapshot.parse(next, NOW).supersedes(previous));
    }

    @Test public void clearRetainsOwnerScope() throws Exception {
        var message = payload("Running", 0).put("work", JSONObject.NULL);
        var clear = TimerSnapshot.parse(message, NOW);
        assertNull(clear.id);
        assertEquals(OWNER, clear.ownerId);
    }
}
