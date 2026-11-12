package tr.com.navortal;

import static org.junit.Assert.*;

import org.junit.Test;

public class PushPermissionTest {
    @Test public void asksOnlyOnceAfterSignInWhenMissingOnApi33Plus() {
        assertTrue(PushPermission.shouldAsk(33, false, false, true));
        assertTrue(PushPermission.shouldAsk(36, false, false, true));
    }

    @Test public void neverAsksBeforeSignInOrWhenGrantedOrAlreadyAsked() {
        assertFalse(PushPermission.shouldAsk(36, false, false, false));
        assertFalse(PushPermission.shouldAsk(36, true, false, true));
        assertFalse(PushPermission.shouldAsk(36, false, true, true));
    }

    @Test public void oldAndroidNeedsNoPermission() {
        assertFalse(PushPermission.shouldAsk(32, false, false, true));
        assertFalse(PushPermission.shouldAsk(26, false, false, true));
    }
}
