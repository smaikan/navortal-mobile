package tr.com.navortal;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class GoogleAuthApiTest {
    static final String CH = "__Host-adhd_google_challenge=PROTECTED123; max-age=600; path=/; secure; samesite=strict; httponly";
    static final String SESSION = "__Host-adhd_session=JWT.VALUE.X; max-age=34560000; path=/; secure; samesite=strict; httponly";
    static final String CH_DELETE = "__Host-adhd_google_challenge=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; secure";

    @Test public void configKeepsNonceAndChallengeCookieTogether() throws Exception {
        var c = GoogleAuthApi.parseConfig("{\"enabled\":true,\"clientId\":\"web.apps.googleusercontent.com\",\"nonce\":\"N0nce\"}", List.of(CH));
        assertTrue(c.enabled);
        assertEquals("web.apps.googleusercontent.com", c.clientId);
        assertEquals("N0nce", c.nonce);
        assertEquals("__Host-adhd_google_challenge=PROTECTED123", c.challengeCookie);
    }

    @Test public void disabledConfigNeedsNoNonceOrCookie() throws Exception {
        var c = GoogleAuthApi.parseConfig("{\"enabled\":false}", Collections.emptyList());
        assertFalse(c.enabled);
    }

    @Test public void enabledConfigWithoutChallengeCookieIsRejected() {
        assertThrows(Exception.class, () -> GoogleAuthApi.parseConfig("{\"enabled\":true,\"clientId\":\"c\",\"nonce\":\"n\"}", Collections.emptyList()));
        assertThrows(Exception.class, () -> GoogleAuthApi.parseConfig("{\"enabled\":true,\"clientId\":\"\",\"nonce\":\"n\"}", List.of(CH)));
    }

    @Test public void deletedCookieLinesAreIgnored() {
        assertNull(GoogleAuthApi.cookiePair(List.of(CH_DELETE), GoogleAuthApi.CHALLENGE_COOKIE));
        assertEquals("__Host-adhd_google_challenge=PROTECTED123", GoogleAuthApi.cookiePair(Arrays.asList(CH_DELETE, CH), GoogleAuthApi.CHALLENGE_COOKIE));
    }

    @Test public void sessionCookieIsRewrittenForWebViewIgnoringAttributeOrderAndDeleteLine() {
        var cookie = GoogleAuthApi.webViewSessionCookie(List.of(CH_DELETE, SESSION));
        assertEquals("__Host-adhd_session=JWT.VALUE.X; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=34560000", cookie);
        var reordered = "__Host-adhd_session=ABC; HttpOnly; Secure; Path=/; SameSite=Strict; Max-Age=60";
        assertEquals("__Host-adhd_session=ABC; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=60", GoogleAuthApi.webViewSessionCookie(List.of(reordered)));
    }

    @Test public void sessionCookieLifetimeIsCapped() {
        var huge = "__Host-adhd_session=ABC; Max-Age=99999999999; Path=/; Secure";
        assertTrue(GoogleAuthApi.webViewSessionCookie(List.of(huge)).endsWith("Max-Age=" + GoogleAuthApi.MAX_SESSION_SECONDS));
    }

    @Test public void sessionCookieWithDomainOrNoValueOrZeroLifetimeIsRefused() {
        assertNull(GoogleAuthApi.webViewSessionCookie(List.of("__Host-adhd_session=ABC; Domain=navortal.com.tr; Path=/")));
        assertNull(GoogleAuthApi.webViewSessionCookie(List.of("__Host-adhd_session=; Path=/")));
        assertNull(GoogleAuthApi.webViewSessionCookie(List.of("__Host-adhd_session=ABC; Max-Age=0; Path=/")));
        assertNull(GoogleAuthApi.webViewSessionCookie(List.of("other=ABC; Path=/")));
        assertNull(GoogleAuthApi.webViewSessionCookie(Collections.emptyList()));
    }

    @Test public void failuresAreChosenByStatusAndCodeNotText() {
        assertEquals(GoogleAuthApi.Failure.Kind.LINK_REQUIRED, GoogleAuthApi.kindFor(409, "google_link_required"));
        assertEquals(GoogleAuthApi.Failure.Kind.ALREADY_LINKED, GoogleAuthApi.kindFor(409, "google_already_linked"));
        assertEquals(GoogleAuthApi.Failure.Kind.RETRY_LOGIN, GoogleAuthApi.kindFor(401, "google_challenge_invalid"));
        assertEquals(GoogleAuthApi.Failure.Kind.RETRY_LOGIN, GoogleAuthApi.kindFor(401, "google_invalid"));
        assertEquals(GoogleAuthApi.Failure.Kind.DISABLED, GoogleAuthApi.kindFor(503, ""));
        assertEquals(GoogleAuthApi.Failure.Kind.RATE_LIMITED, GoogleAuthApi.kindFor(429, ""));
        assertEquals(GoogleAuthApi.Failure.Kind.CONFIG, GoogleAuthApi.kindFor(403, "csrf_required"));
        assertEquals(GoogleAuthApi.Failure.Kind.OTHER, GoogleAuthApi.kindFor(500, "internal_error"));
    }

    @Test public void problemCodeIsReadFromTheBody() {
        assertEquals("google_invalid", GoogleAuthApi.problemCode("{\"status\":401,\"code\":\"google_invalid\"}"));
        assertEquals("", GoogleAuthApi.problemCode("not json"));
        assertEquals("", GoogleAuthApi.problemCode(""));
    }
}
