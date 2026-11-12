package tr.com.navortal;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;

public class PushApiTest {
    static final String USER = "11111111-1111-4111-8111-111111111111";

    static String jwt(String payloadJson) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test public void sessionPairIsExtractedFromCookieManagerString() {
        assertEquals("__Host-adhd_session=abc.def", PushApi.sessionPair("theme=dark; __Host-adhd_session=abc.def; other=1"));
        assertEquals("__Host-adhd_session=x", PushApi.sessionPair("__Host-adhd_session=x"));
        assertNull(PushApi.sessionPair("theme=dark; adhd_session=zzz"));
        assertNull(PushApi.sessionPair("__Host-adhd_session="));
        assertNull(PushApi.sessionPair("__Host-adhd_session_other=1"));
        assertNull(PushApi.sessionPair(""));
        assertNull(PushApi.sessionPair(null));
    }

    // Aşağıdaki testler tahmine dayalı biçimleri sınar: gerçek /api/auth/me yanıtına karşı DOĞRULANMADI.
    @Test public void userIdFromTopLevelIdOrUserId() {
        assertEquals(USER, PushApi.parseUserId("{\"id\":\"" + USER + "\",\"email\":\"a@b.c\"}", null));
        assertEquals(USER, PushApi.parseUserId("{\"userId\":\"" + USER + "\"}", null));
    }

    @Test public void userIdFromNestedUser() {
        assertEquals(USER, PushApi.parseUserId("{\"user\":{\"id\":\"" + USER + "\"}}", null));
        assertEquals(USER, PushApi.parseUserId("{\"user\":{\"userId\":\"" + USER + "\"}}", null));
    }

    @Test public void nonUuidIdsAreNotAccepted() {
        assertNull(PushApi.parseUserId("{\"id\":\"12345\"}", null));
        assertNull(PushApi.parseUserId("{\"user\":{\"id\":42}}", null));
        assertNull(PushApi.parseUserId("kırık", null));
        assertNull(PushApi.parseUserId(null, null));
    }

    @Test public void fallsBackToJwtSubOfSessionCookie() {
        var cookie = "__Host-adhd_session=" + jwt("{\"sub\":\"" + USER + "\",\"exp\":1}");
        assertEquals(USER, PushApi.parseUserId("{\"name\":\"x\"}", cookie));
        assertEquals(USER, PushApi.jwtSub(cookie));
    }

    @Test public void jwtFallbackIgnoresOpaqueOrBadCookies() {
        assertNull(PushApi.jwtSub("__Host-adhd_session=opaquevalue"));
        assertNull(PushApi.jwtSub("__Host-adhd_session=a.!!!.c"));
        assertNull(PushApi.jwtSub("__Host-adhd_session=" + jwt("{\"sub\":\"not-uuid\"}")));
        assertNull(PushApi.jwtSub("__Host-adhd_session=" + jwt("{\"nosub\":1}")));
        assertNull(PushApi.jwtSub(null));
    }

    @Test public void bodyIdWinsOverJwt() {
        var cookie = "__Host-adhd_session=" + jwt("{\"sub\":\"22222222-2222-4222-8222-222222222222\"}");
        assertEquals(USER, PushApi.parseUserId("{\"id\":\"" + USER + "\"}", cookie));
    }

    @Test public void deviceBodyHasAllRequiredFields() throws Exception {
        var o = new JSONObject(PushApi.deviceBody(USER, "tok", 0, 5, "0.1.0", 1, "14", "granted", true));
        assertEquals(USER, o.getString("expectedUserId"));
        assertEquals("tok", o.getString("fcmToken"));
        assertEquals(0, o.getLong("bindingVersion"));
        assertEquals(5, o.getLong("clientSequence"));
        assertEquals("android", o.getString("platform"));
        assertEquals("tr.com.navortal", o.getString("packageName"));
        assertEquals("0.1.0", o.getString("appVersion"));
        assertEquals(1, o.getLong("buildNumber"));
        assertEquals("14", o.getString("osVersion"));
        assertEquals("granted", o.getString("permission"));
        assertTrue(o.getBoolean("remindersChannelEnabled"));
    }

    @Test public void deviceBodyClipsLongVersionStrings() throws Exception {
        var o = new JSONObject(PushApi.deviceBody(USER, "tok", 2, 9, "v".repeat(60), 3, "o".repeat(100), "denied", false));
        assertEquals(32, o.getString("appVersion").length());
        assertEquals(64, o.getString("osVersion").length());
        assertFalse(o.getBoolean("remindersChannelEnabled"));
        assertEquals(2, o.getLong("bindingVersion"));
    }

    @Test public void receiptBodyHasAllRequiredFields() throws Exception {
        var o = new JSONObject(PushApi.receiptBody(USER, "inst", 4, 1, "Displayed"));
        assertEquals(USER, o.getString("expectedUserId"));
        assertEquals("inst", o.getString("installationId"));
        assertEquals(4, o.getLong("bindingVersion"));
        assertEquals(1, o.getInt("snoozeCount"));
        assertEquals("Displayed", o.getString("stage"));
    }

    @Test public void tokenValidation() {
        assertTrue(PushApi.validToken("abc:APA91b-_x"));
        assertFalse(PushApi.validToken(""));
        assertFalse(PushApi.validToken(null));
        assertFalse(PushApi.validToken("a b"));
        assertFalse(PushApi.validToken("a\nb"));
        assertFalse(PushApi.validToken("x".repeat(4097)));
        assertTrue(PushApi.validToken("x".repeat(4096)));
    }

    @Test public void errorClassificationUsesStatusAndCode() {
        assertEquals(PushApi.Outcome.OK, PushApi.classify(200, ""));
        assertEquals(PushApi.Outcome.OK, PushApi.classify(204, ""));
        assertEquals(PushApi.Outcome.FIX_REQUEST, PushApi.classify(400, "validation_failed"));
        assertEquals(PushApi.Outcome.FIX_REQUEST, PushApi.classify(400, "idempotency_key_required"));
        assertEquals(PushApi.Outcome.DEVICE_LIMIT, PushApi.classify(400, "mobile_device_limit"));
        assertEquals(PushApi.Outcome.NO_SESSION, PushApi.classify(401, "mobile_cookie_required"));
        assertEquals(PushApi.Outcome.NO_SESSION, PushApi.classify(401, ""));
        assertEquals(PushApi.Outcome.CSRF, PushApi.classify(403, "csrf_origin"));
        assertEquals(PushApi.Outcome.NOT_FOUND, PushApi.classify(404, "mobile_device_not_found"));
        assertEquals(PushApi.Outcome.BINDING_CONFLICT, PushApi.classify(409, "mobile_binding_conflict"));
        assertEquals(PushApi.Outcome.TOKEN_OWNED, PushApi.classify(409, "fcm_token_owned"));
        assertEquals(PushApi.Outcome.VERSION_CONFLICT, PushApi.classify(409, "version_conflict"));
        assertEquals(PushApi.Outcome.IDEMPOTENCY_CONFLICT, PushApi.classify(409, "idempotency_conflict"));
        assertEquals(PushApi.Outcome.OTHER, PushApi.classify(409, "başka"));
        assertEquals(PushApi.Outcome.RATE_LIMITED, PushApi.classify(429, "mobile_rate_limited"));
        assertEquals(PushApi.Outcome.SERVER_ERROR, PushApi.classify(500, "internal_error"));
        assertEquals(PushApi.Outcome.SERVER_ERROR, PushApi.classify(503, null));
        assertEquals(PushApi.Outcome.OTHER, PushApi.classify(418, ""));
        assertEquals("fcm_token_owned", PushApi.problemCode("{\"code\":\"fcm_token_owned\",\"title\":\"x\"}"));
        assertEquals("", PushApi.problemCode("html değil json"));
    }

    @Test public void retryAfterIsParsedAndBounded() {
        assertEquals(30_000, PushApi.retryAfterMs("30"));
        assertEquals(PushApi.DEFAULT_RETRY_AFTER_MS, PushApi.retryAfterMs(null));
        assertEquals(PushApi.DEFAULT_RETRY_AFTER_MS, PushApi.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(PushApi.MAX_RETRY_AFTER_MS, PushApi.retryAfterMs("999999"));
        assertEquals(1_000, PushApi.retryAfterMs("0"));
    }

    @Test public void deviceAndDeliveryResponsesParse() {
        var d = PushApi.parseDevice("{\"installationId\":\"i\",\"userId\":\"" + USER + "\",\"bindingVersion\":7,\"active\":true}");
        assertEquals(USER, d.userId);
        assertEquals(7, d.bindingVersion);
        assertNull(PushApi.parseDevice("{\"userId\":\"" + USER + "\"}"));
        assertNull(PushApi.parseDevice("kırık"));
        var ok = PushApi.parseDelivery("{\"displayAllowed\":true,\"silent\":true,\"reason\":\"\"}");
        assertTrue(ok.displayAllowed);
        assertTrue(ok.silent);
        assertFalse(PushApi.parseDelivery("{\"displayAllowed\":false,\"reason\":\"read\"}").displayAllowed);
        assertFalse(PushApi.parseDelivery("{\"displayAllowed\":true}").silent);
        assertNull(PushApi.parseDelivery("{\"reason\":\"read\"}"));
        assertNull(PushApi.parseDelivery(""));
    }
}
