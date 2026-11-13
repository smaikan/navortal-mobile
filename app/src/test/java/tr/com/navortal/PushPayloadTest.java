package tr.com.navortal;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class PushPayloadTest {
    static final String USER = "11111111-1111-4111-8111-111111111111";
    static final String DELIVERY = "22222222-2222-4222-8222-222222222222";
    static final String NOTIFICATION = "33333333-3333-4333-8333-333333333333";
    static final long NOW = Instant.parse("2026-11-12T10:00:00Z").toEpochMilli();

    static Map<String, String> valid() {
        var m = new HashMap<String, String>();
        m.put("schemaVersion", "1");
        m.put("type", "notification.ready");
        m.put("deliveryId", DELIVERY);
        m.put("notificationId", NOTIFICATION);
        m.put("recipientUserId", USER);
        m.put("bindingVersion", "4");
        m.put("snoozeCount", "0");
        m.put("eventType", "TaskDeadline");
        m.put("title", "Görev vakti");
        m.put("body", "Sunumun girişini yaz");
        m.put("path", "/notifications/" + NOTIFICATION);
        m.put("dedupeKey", "notification:" + NOTIFICATION + ":0");
        m.put("groupKey", "reminders");
        m.put("channelId", "reminders-v1");
        m.put("sentAt", "2026-11-12T10:00:00Z");
        m.put("expiresAt", "2026-11-12T10:02:00Z");
        return m;
    }

    static PushPayload parse(Map<String, String> m) throws PushPayload.Invalid { return PushPayload.parse(m, USER, 4, NOW); }

    static void assertInvalid(Map<String, String> m, String reason) {
        try { parse(m); fail("kabul edilmemeliydi: " + reason); }
        catch (PushPayload.Invalid e) { assertTrue(e.getMessage(), e.getMessage().contains(reason)); }
    }

    @Test public void validPayloadParses() throws Exception {
        var p = parse(valid());
        assertEquals(DELIVERY, p.deliveryId);
        assertEquals(4, p.bindingVersion);
        assertEquals(0, p.snoozeCount);
        assertEquals("/notifications/" + NOTIFICATION, p.path);
        assertEquals("Görev vakti", p.title);
        assertEquals("TaskDeadline", p.eventType);
    }

    @Test public void unknownEventTypeIsStillAllowed() throws Exception {
        var m = valid();
        m.put("eventType", "SomethingNew");
        assertEquals("SomethingNew", parse(m).eventType);
    }

    @Test public void ignoresOtherSchemasAndTypes() {
        var m = valid();
        m.put("type", "timer.changed");
        assertFalse(PushPayload.isOurs(m));
        m = valid();
        m.put("schemaVersion", "2");
        assertFalse(PushPayload.isOurs(m));
        assertInvalid(m, "schema");
        assertFalse(PushPayload.isOurs(null));
        assertTrue(PushPayload.isOurs(valid()));
    }

    @Test public void rejectsMissingFields() {
        for (var key : new String[] {"deliveryId", "notificationId", "recipientUserId", "bindingVersion", "snoozeCount", "eventType",
                "dedupeKey", "groupKey", "sentAt", "expiresAt", "channelId", "title", "body"}) {
            var m = valid();
            m.remove(key);
            try { parse(m); fail(key + " eksikken kabul edildi"); } catch (PushPayload.Invalid expected) { /* beklenen */ }
        }
    }

    @Test public void rejectsWrongRecipientAndBinding() {
        var m = valid();
        m.put("recipientUserId", "44444444-4444-4444-8444-444444444444");
        assertInvalid(m, "recipient");
        m = valid();
        m.put("bindingVersion", "5");
        assertInvalid(m, "binding");
        try { PushPayload.parse(valid(), null, 4, NOW); fail(); } catch (PushPayload.Invalid e) { assertTrue(e.getMessage().contains("recipient")); }
    }

    @Test public void rejectsWrongChannelAndBadIds() {
        var m = valid();
        m.put("channelId", "other");
        assertInvalid(m, "channel");
        m = valid();
        m.put("deliveryId", "not-a-uuid");
        assertInvalid(m, "uuid");
    }

    @Test public void expiryWithThirtySecondTolerance() throws Exception {
        long expires = Instant.parse("2026-11-12T10:02:00Z").toEpochMilli();
        assertNotNull(PushPayload.parse(valid(), USER, 4, expires));
        assertNotNull(PushPayload.parse(valid(), USER, 4, expires + 30_000));
        try { PushPayload.parse(valid(), USER, 4, expires + 30_001); fail(); } catch (PushPayload.Invalid e) { assertTrue(e.getMessage().contains("expired")); }
        var m = valid();
        m.put("expiresAt", "yarın");
        assertInvalid(m, "expiresAt");
    }

    @Test public void fractionalAndOffsetTimestampsParse() throws Exception {
        var m = valid();
        m.put("expiresAt", "2026-11-12T13:02:00.1234567+03:00");
        assertNotNull(parse(m));
    }

    @Test public void pathMustBeExactlyNotificationsUuid() {
        assertEquals("/notifications/" + NOTIFICATION, PushPayload.validPath("/notifications/" + NOTIFICATION));
        for (var bad : new String[] {"//evil", "//evil.example/notifications/" + NOTIFICATION, "https://evil", "https://www.navortal.com.tr/notifications/" + NOTIFICATION,
                "/notifications/../x", "/notifications/not-a-uuid", "/notifications/" + NOTIFICATION + "/x", "/notifications/" + NOTIFICATION + "?a=1",
                "/notifications/" + NOTIFICATION + "\n", "/planning", "", null, "javascript:alert(1)"})
            assertNull(String.valueOf(bad), PushPayload.validPath(bad));
    }

    @Test public void badPathIsDroppedButMessageStillValid() throws Exception {
        var m = valid();
        m.put("path", "//evil");
        assertNull(parse(m).path);
        m.remove("path");
        assertNull(parse(m).path);
    }

    @Test public void titleAndBodyAreCleanedAndLimited() throws Exception {
        var m = valid();
        m.put("title", "\u0007Merhaba‮  dünya\n!");
        m.put("body", "x".repeat(500));
        var p = parse(m);
        assertEquals("Merhaba dünya !", p.title);
        assertEquals(PushPayload.MAX_BODY, p.body.length());
        m.put("title", "t".repeat(300));
        assertEquals(PushPayload.MAX_TITLE, parse(m).title.length());
    }

    @Test public void emptyTextAfterCleaningIsRejected() {
        var m = valid();
        m.put("title", "\u0000\u0001");
        m.put("body", "   ");
        assertInvalid(m, "text");
    }

    @Test public void cleanDoesNotSplitSurrogatePairs() {
        var s = PushPayload.clean("a😀b😀c", 3);
        assertEquals("a😀b", s);
    }

    @Test public void dedupeKeyWithControlCharsIsRejected() {
        var m = valid();
        m.put("dedupeKey", "a\tb");
        assertInvalid(m, "dedupeKey");
    }

    @Test public void tapIsValidOnlyForSignedInMatchingBinding() {
        assertTrue(PushPayload.tapValid(USER, 4, USER, 4, true));
        assertFalse(PushPayload.tapValid(USER, 4, USER, 4, false));
        assertFalse(PushPayload.tapValid(USER, 5, USER, 4, true));
        assertFalse(PushPayload.tapValid(USER, 4, "44444444-4444-4444-8444-444444444444", 4, true));
        assertFalse(PushPayload.tapValid(USER, 4, null, 4, true));
        assertFalse(PushPayload.tapValid(null, 4, USER, 4, true));
    }

    @Test public void optionalParentTaskAndStepsAreParsed() throws Exception {
        var m = valid();
        m.put("parentTitle", "Tez bölüm 4");
        m.put("stepIndex", "2");
        m.put("stepCount", "5");
        var p = parse(m);
        assertEquals("Tez bölüm 4", p.parentTitle);
        assertEquals(2, p.stepIndex);
        assertEquals(5, p.stepCount);
    }

    @Test public void missingOrInvalidOptionalFieldsAreIgnoredNotRejected() throws Exception {
        var none = parse(valid());
        assertEquals("", none.parentTitle);
        assertEquals(0, none.stepCount);
        var bad = valid();
        bad.put("stepIndex", "9");
        bad.put("stepCount", "5"); // sıra sayıdan büyük
        assertEquals(0, parse(bad).stepCount);
        bad.put("stepIndex", "x");
        assertEquals(0, parse(bad).stepIndex);
        bad.put("parentTitle", "a\u0000b\n" + "x".repeat(200));
        assertTrue(parse(bad).parentTitle.codePointCount(0, parse(bad).parentTitle.length()) <= PushPayload.MAX_PARENT);
    }
}
