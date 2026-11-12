package tr.com.navortal;

import static org.junit.Assert.*;

import org.junit.Test;

public class PushRulesTest {
    static final long T0 = 1_800_000_000_000L;
    static final String USER = "11111111-1111-4111-8111-111111111111";

    static PushRules.Snapshot snap(String token, String perm, boolean channel, String app, String user, long time) {
        return new PushRules.Snapshot(token, perm, channel, app, user, time);
    }

    @Test public void dueWhenNoPreviousRegistration() {
        assertTrue(PushRules.due(null, snap("t", "granted", true, "0.1.0", USER, T0), T0));
    }

    @Test public void identicalStateIsNotResentWithinTwentyFourHours() {
        var last = snap("t", "granted", true, "0.1.0", USER, T0);
        assertFalse(PushRules.due(last, snap("t", "granted", true, "0.1.0", USER, T0 + 1000), T0 + 1000));
        assertFalse(PushRules.due(last, snap("t", "granted", true, "0.1.0", null, 0), T0 + PushRules.RESEND_MS - 1)); // userId yok sayılır
        assertTrue(PushRules.due(last, snap("t", "granted", true, "0.1.0", USER, 0), T0 + PushRules.RESEND_MS));
    }

    @Test public void anyChangeMakesItDue() {
        var last = snap("t", "granted", true, "0.1.0", USER, T0);
        assertTrue(PushRules.due(last, snap("t2", "granted", true, "0.1.0", USER, 0), T0 + 1));
        assertTrue(PushRules.due(last, snap("t", "denied", true, "0.1.0", USER, 0), T0 + 1));
        assertTrue(PushRules.due(last, snap("t", "granted", false, "0.1.0", USER, 0), T0 + 1));
        assertTrue(PushRules.due(last, snap("t", "granted", true, "0.2.0", USER, 0), T0 + 1));
        assertTrue(PushRules.due(last, snap("t", "granted", true, "0.1.0", "22222222-2222-4222-8222-222222222222", 0), T0 + 1));
    }

    @Test public void clockGoingBackwardsIsTreatedAsDue() {
        assertTrue(PushRules.due(snap("t", "granted", true, "0.1.0", USER, T0), snap("t", "granted", true, "0.1.0", USER, 0), T0 - 1));
    }

    @Test public void sequenceIsStrictlyIncreasingAndPositive() {
        assertEquals(1, PushRules.nextSequence(0));
        assertEquals(1, PushRules.nextSequence(-5));
        assertEquals(8, PushRules.nextSequence(7));
        long s = 0;
        for (int i = 0; i < 5; i++) { long next = PushRules.nextSequence(s); assertTrue(next > s); s = next; }
    }

    @Test public void lateResultOnlyStoredForSameEpochAndUser() {
        assertTrue(PushRules.canStore(3, 3, USER, USER.toUpperCase()));
        assertFalse(PushRules.canStore(3, 4, USER, USER)); // arada çıkış/hesap değişimi oldu
        assertFalse(PushRules.canStore(3, 3, USER, "22222222-2222-4222-8222-222222222222"));
        assertFalse(PushRules.canStore(3, 3, null, USER));
    }

    @Test public void tokenHashIsStableShortAndNotTheToken() {
        var h = PushRules.tokenHash("secret-token");
        assertEquals(16, h.length());
        assertEquals(h, PushRules.tokenHash("secret-token"));
        assertNotEquals(h, PushRules.tokenHash("secret-token2"));
        assertFalse(h.contains("secret"));
    }
}
