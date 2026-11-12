package tr.com.navortal;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * FCM veri mesajının (notification.ready) saf doğrulaması. Ağa veya ekrana dokunmadan önce çağrılır.
 * Geçersiz mesaj {@link Invalid} fırlatır; neden metni yalnız yerel günlük içindir (mesaj içeriği taşımaz).
 */
final class PushPayload {
    static final String SCHEMA = "1", TYPE = "notification.ready", CHANNEL = "reminders-v1";
    static final long SKEW_MS = 30_000;
    static final int MAX_TITLE = 80, MAX_BODY = 200, MAX_KEY = 200;

    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern UUID_RE = Pattern.compile("^" + UUID + "$");
    private static final Pattern PATH_RE = Pattern.compile("^/notifications/" + UUID + "$");

    static final class Invalid extends Exception {
        Invalid(String reason) { super(reason); }
    }

    final String deliveryId, notificationId, recipientUserId, eventType, title, body, dedupeKey, groupKey;
    /** Doğrulanmış "/notifications/&lt;uuid&gt;" yolu; geçersizse null (uygulama yalnız açılır). */
    final String path;
    final long bindingVersion, expiresAtMs;
    final int snoozeCount;

    private PushPayload(String deliveryId, String notificationId, String recipientUserId, String eventType, String title, String body,
                        String path, String dedupeKey, String groupKey, long bindingVersion, int snoozeCount, long expiresAtMs) {
        this.deliveryId = deliveryId; this.notificationId = notificationId; this.recipientUserId = recipientUserId;
        this.eventType = eventType; this.title = title; this.body = body; this.path = path; this.dedupeKey = dedupeKey;
        this.groupKey = groupKey; this.bindingVersion = bindingVersion; this.snoozeCount = snoozeCount; this.expiresAtMs = expiresAtMs;
    }

    /** Bu mesaj bizim şemamız mı? Başka tür/şema (örn. timer.changed) sessizce yok sayılır. */
    static boolean isOurs(Map<String, String> data) {
        return data != null && SCHEMA.equals(data.get("schemaVersion")) && TYPE.equals(data.get("type"));
    }

    static boolean isUuid(String s) { return s != null && UUID_RE.matcher(s).matches(); }

    /** Yalnız "/notifications/&lt;uuid&gt;"; "//evil", "https://evil", ".." gibi her şey null. */
    static String validPath(String path) { return path != null && PATH_RE.matcher(path).matches() ? path : null; }

    static PushPayload parse(Map<String, String> data, String boundUserId, long boundBindingVersion, long nowMs) throws Invalid {
        if (!isOurs(data)) throw new Invalid("schema/type");
        var deliveryId = require(data, "deliveryId");
        var notificationId = require(data, "notificationId");
        var recipient = require(data, "recipientUserId");
        if (!isUuid(deliveryId) || !isUuid(notificationId) || !isUuid(recipient)) throw new Invalid("uuid");
        long binding;
        int snooze;
        try {
            binding = Long.parseLong(require(data, "bindingVersion"));
            snooze = Integer.parseInt(require(data, "snoozeCount"));
        } catch (NumberFormatException e) { throw new Invalid("number"); }
        if (snooze < 0) throw new Invalid("snoozeCount");
        if (boundUserId == null || !boundUserId.equalsIgnoreCase(recipient)) throw new Invalid("recipient");
        if (binding != boundBindingVersion) throw new Invalid("binding");
        if (!CHANNEL.equals(data.get("channelId"))) throw new Invalid("channel");
        var dedupeKey = require(data, "dedupeKey");
        if (dedupeKey.length() > MAX_KEY || !clean(dedupeKey, MAX_KEY).equals(dedupeKey)) throw new Invalid("dedupeKey");
        var groupKey = clean(require(data, "groupKey"), 40);
        var eventType = clean(require(data, "eventType"), 40);
        require(data, "sentAt");
        long expires;
        try { expires = OffsetDateTime.parse(require(data, "expiresAt")).toInstant().toEpochMilli(); }
        catch (java.time.format.DateTimeParseException e) { throw new Invalid("expiresAt"); }
        if (nowMs > expires + SKEW_MS) throw new Invalid("expired");
        var title = clean(data.get("title"), MAX_TITLE);
        var body = clean(data.get("body"), MAX_BODY);
        if (data.get("title") == null || data.get("body") == null || (title.isEmpty() && body.isEmpty())) throw new Invalid("text");
        return new PushPayload(deliveryId, notificationId, recipient, eventType, title, body, validPath(data.get("path")), dedupeKey,
                groupKey.isEmpty() ? "reminders" : groupKey, binding, snooze, expires);
    }

    /** Bildirime dokunuş hâlâ geçerli mi: oturum açık ve alıcı/bağlama kayıtlı olanla aynı mı. */
    static boolean tapValid(String recipient, long binding, String boundUserId, long boundBindingVersion, boolean signedIn) {
        return signedIn && recipient != null && boundUserId != null && boundUserId.equalsIgnoreCase(recipient) && binding == boundBindingVersion;
    }

    /** Başlık/gövde için: denetim ve biçim karakterleri atılır/boşluğa çevrilir, boşluklar sıkıştırılır, uzunluk sınırlanır. */
    static String clean(String s, int maxCodePoints) {
        if (s == null) return "";
        var sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.UNASSIGNED || type == Character.SURROGATE) continue;
            if (type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR || Character.isWhitespace(cp)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                continue;
            }
            sb.appendCodePoint(cp);
        }
        var text = sb.toString().trim();
        if (text.codePointCount(0, text.length()) > maxCodePoints) text = text.substring(0, text.offsetByCodePoints(0, maxCodePoints)).trim();
        return text;
    }

    private static String require(Map<String, String> data, String key) throws Invalid {
        var v = data.get(key);
        if (v == null || v.isEmpty()) throw new Invalid("missing " + key);
        return v;
    }
}
