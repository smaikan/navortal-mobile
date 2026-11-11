package tr.com.navortal;

import org.junit.Test;
import static org.junit.Assert.*;

public class SignInStateTest {
    static final String SITE = "https://www.navortal.com.tr";

    @Test public void loginAndRegisterMeanSignedOut() {
        assertEquals(Boolean.FALSE, SignInState.fromUrl(SITE + "/login"));
        assertEquals(Boolean.FALSE, SignInState.fromUrl(SITE + "/login?next=%2Fplanning"));
        assertEquals(Boolean.FALSE, SignInState.fromUrl(SITE + "/register"));
        assertEquals(Boolean.FALSE, SignInState.fromUrl(SITE + "/register/"));
    }

    @Test public void planningMeansSignedIn() {
        assertEquals(Boolean.TRUE, SignInState.fromUrl(SITE + "/planning"));
        assertEquals(Boolean.TRUE, SignInState.fromUrl(SITE + "/planning/"));
        assertEquals(Boolean.TRUE, SignInState.fromUrl(SITE + "/planning/x"));
        assertEquals(Boolean.TRUE, SignInState.fromUrl(SITE + "/planning?tab=1"));
    }

    @Test public void otherPathsLeaveStateUnchanged() {
        assertNull(SignInState.fromUrl(SITE + "/help"));
        assertNull(SignInState.fromUrl(SITE + "/"));
        assertNull(SignInState.fromUrl(SITE + "/loginx"));
        assertNull(SignInState.fromUrl(SITE + "/planningx"));
        assertNull(SignInState.fromUrl(SITE));
    }

    @Test public void backReturnsToWelcomeOnlyInTheStuckSignedOutCase() {
        assertTrue(SignInState.backShowsWelcome(true, false, false, false));
        assertFalse(SignInState.backShowsWelcome(false, false, false, false)); // site karşılamadan açılmadı
        assertFalse(SignInState.backShowsWelcome(true, true, false, false));   // oturum açık
        assertFalse(SignInState.backShowsWelcome(true, false, true, false));   // WebView geri gidebilir
        assertFalse(SignInState.backShowsWelcome(true, false, false, true));   // zaten karşılamada
    }

    @Test public void untrustedAddressesNeverChangeState() {
        assertNull(SignInState.fromUrl("https://evil.example/login"));
        assertNull(SignInState.fromUrl("https://evil.example/planning"));
        assertNull(SignInState.fromUrl("https://www.navortal.com.tr.evil.example/login"));
        assertNull(SignInState.fromUrl("http://www.navortal.com.tr/login"));
        assertNull(SignInState.fromUrl("https://user@www.navortal.com.tr/login"));
        assertNull(SignInState.fromUrl("https://www.navortal.com.tr:8443/login"));
        assertNull(SignInState.fromUrl("about:blank"));
        assertNull(SignInState.fromUrl("not a url"));
        assertNull(SignInState.fromUrl(null));
    }

    @org.junit.Test public void landingIsOnlyTheSiteRoot() {
        org.junit.Assert.assertTrue(SignInState.isLanding("https://www.navortal.com.tr/"));
        org.junit.Assert.assertTrue(SignInState.isLanding("https://www.navortal.com.tr"));
        org.junit.Assert.assertFalse(SignInState.isLanding("https://www.navortal.com.tr/login"));
        org.junit.Assert.assertFalse(SignInState.isLanding("https://www.navortal.com.tr/planning"));
        org.junit.Assert.assertFalse(SignInState.isLanding("https://evil.example/"));
        org.junit.Assert.assertFalse(SignInState.isLanding(null));
    }
}
