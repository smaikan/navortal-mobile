package tr.com.navortal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Mobil push API'sinin istemcisi: saf ayrıştırma/gövde üretimi (birim testli) ve kısa zaman aşımlı HttpURLConnection çağrıları.
 * Kimlik doğrulama yalnız WebView oturum çerezidir (Bearer yok). Yalnız https://www.navortal.com.tr'ye gider, yönlendirme izlemez.
 * Çerez, token ve yanıt gövdeleri hiçbir yere yazılmaz. Ağ çağrıları ana iş parçacığında yapılmaz.
 */
final class PushApi {
    static final String DEVICES = MainActivity.ORIGIN + "/api/mobile/push/devices/";
    static final String DELIVERIES = MainActivity.ORIGIN + "/api/mobile/push/deliveries/";
    static final String ME = MainActivity.ORIGIN + "/api/auth/me";
    static final int TIMEOUT_MS = 8_000, MAX_BODY = 64 * 1024;
    static final long DEFAULT_RETRY_AFTER_MS = 60_000, MAX_RETRY_AFTER_MS = 3_600_000;

    private PushApi() { }

    // --- hata sınıflandırma (HTTP durumu + Problem Details "code"; metne göre asla) ---

    enum Outcome { OK, FIX_REQUEST, DEVICE_LIMIT, NO_SESSION, CSRF, NOT_FOUND, BINDING_CONFLICT, TOKEN_OWNED, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT, RATE_LIMITED, SERVER_ERROR, OTHER }

    static Outcome classify(int status, String code) {
        if (status >= 200 && status < 300) return Outcome.OK;
        var c = code == null ? "" : code;
        switch (status) {
            case 400: return "mobile_device_limit".equals(c) ? Outcome.DEVICE_LIMIT : Outcome.FIX_REQUEST;
            case 401: return Outcome.NO_SESSION;
            case 403: return Outcome.CSRF;
            case 404: return Outcome.NOT_FOUND;
            case 409:
                if ("mobile_binding_conflict".equals(c)) return Outcome.BINDING_CONFLICT;
                if ("fcm_token_owned".equals(c)) return Outcome.TOKEN_OWNED;
                if ("version_conflict".equals(c)) return Outcome.VERSION_CONFLICT;
                if ("idempotency_conflict".equals(c)) return Outcome.IDEMPOTENCY_CONFLICT;
                return Outcome.OTHER;
            case 429: return Outcome.RATE_LIMITED;
            default: return status >= 500 ? Outcome.SERVER_ERROR : Outcome.OTHER;
        }
    }

    /** Retry-After (saniye); yoksa/bozuksa 60 sn, en çok 1 saat. */
    static long retryAfterMs(String header) {
        if (header == null) return DEFAULT_RETRY_AFTER_MS;
        try { return Math.max(1_000, Math.min(MAX_RETRY_AFTER_MS, Long.parseLong(header.trim()) * 1000)); }
        catch (NumberFormatException e) { return DEFAULT_RETRY_AFTER_MS; }
    }

    // --- çerez ve kullanıcı kimliği ---

    /** CookieManager.getCookie dizesinden yalnız "__Host-adhd_session=değer" çifti; yoksa null. */
    static String sessionPair(String cookieHeader) {
        if (cookieHeader == null) return null;
        var prefix = GoogleAuthApi.SESSION_COOKIE + "=";
        for (var part : cookieHeader.split(";")) {
            var p = part.trim();
            if (p.startsWith(prefix) && p.length() > prefix.length()) return p;
        }
        return null;
    }

    /**
     * /api/auth/me yanıtından kullanıcı kimliği. DOĞRULANMADI: gerçek yanıt alan adı bize belgelenmedi. Sırayla kabul edilir:
     * üst düzey id / userId, user.id / user.userId (UUID olmalı); hiçbiri yoksa oturum çerezinin JWT yükündeki "sub".
     * Sunucu expectedUserId'yi zaten doğrular; bu yalnız alanı doldurur. Bulunamazsa null.
     */
    static String parseUserId(String meJson, String sessionPair) {
        try {
            var o = new JSONObject(meJson == null ? "" : meJson);
            for (var key : new String[] {"id", "userId"}) {
                var id = o.optString(key, null);
                if (PushPayload.isUuid(id)) return id;
            }
            var user = o.optJSONObject("user");
            if (user != null) for (var key : new String[] {"id", "userId"}) {
                var id = user.optString(key, null);
                if (PushPayload.isUuid(id)) return id;
            }
        } catch (JSONException ignored) { /* JWT yedeğine düşülür */ }
        return jwtSub(sessionPair);
    }

    /** Çerez değeri JWT ise (üç parça) yükündeki "sub" UUID'si; değilse null. Yalnız expectedUserId'yi doldurmak için. */
    static String jwtSub(String sessionPair) {
        if (sessionPair == null) return null;
        int eq = sessionPair.indexOf('=');
        var parts = (eq < 0 ? sessionPair : sessionPair.substring(eq + 1)).split("\\.");
        if (parts.length < 2) return null;
        try {
            var json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            var sub = new JSONObject(json).optString("sub", null);
            return PushPayload.isUuid(sub) ? sub : null;
        } catch (IllegalArgumentException | JSONException e) {
            return null;
        }
    }

    // --- istek gövdeleri ---

    /** Token: 1-4096 karakter, boşluk/denetim karakteri yok. */
    static boolean validToken(String token) {
        if (token == null || token.isEmpty() || token.length() > 4096) return false;
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) return false;
        }
        return true;
    }

    static String clip(String s, int max) {
        var t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String deviceBody(String expectedUserId, String fcmToken, long bindingVersion, long clientSequence, String appVersion,
                             long buildNumber, String osVersion, String permission, boolean remindersChannelEnabled) {
        try {
            return new JSONObject()
                    .put("expectedUserId", expectedUserId)
                    .put("fcmToken", fcmToken)
                    .put("bindingVersion", bindingVersion)
                    .put("clientSequence", clientSequence)
                    .put("platform", "android")
                    .put("packageName", "tr.com.navortal")
                    .put("appVersion", clip(appVersion, 32))
                    .put("buildNumber", buildNumber)
                    .put("osVersion", clip(osVersion, 64))
                    .put("permission", permission)
                    .put("remindersChannelEnabled", remindersChannelEnabled)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String receiptBody(String expectedUserId, String installationId, long bindingVersion, int snoozeCount, String stage) {
        try {
            return new JSONObject()
                    .put("expectedUserId", expectedUserId)
                    .put("installationId", installationId)
                    .put("bindingVersion", bindingVersion)
                    .put("snoozeCount", snoozeCount)
                    .put("stage", stage)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- yanıt ayrıştırma ---

    static final class Device {
        final String userId;
        final long bindingVersion;
        Device(String userId, long bindingVersion) { this.userId = userId; this.bindingVersion = bindingVersion; }
    }

    /** Cihaz yanıtı (token dönmez); userId veya bindingVersion yoksa null. */
    static Device parseDevice(String json) {
        try {
            var o = new JSONObject(json);
            if (!o.has("bindingVersion") || !PushPayload.isUuid(o.optString("userId", null))) return null;
            return new Device(o.getString("userId"), o.getLong("bindingVersion"));
        } catch (JSONException e) {
            return null;
        }
    }

    static final class Delivery {
        final boolean displayAllowed, silent;
        Delivery(boolean displayAllowed, boolean silent) { this.displayAllowed = displayAllowed; this.silent = silent; }
    }

    /** Teslim kontrolü yanıtı; displayAllowed alanı yoksa/bozuksa null (gösterme). */
    static Delivery parseDelivery(String json) {
        try {
            var o = new JSONObject(json);
            if (!o.has("displayAllowed")) return null;
            return new Delivery(o.getBoolean("displayAllowed"), o.optBoolean("silent", false));
        } catch (JSONException e) {
            return null;
        }
    }

    static String problemCode(String body) {
        try { return new JSONObject(body).optString("code", ""); } catch (Exception e) { return ""; }
    }

    // --- ağ ---

    static final class Response {
        final int status;
        final String body, retryAfter;
        Response(int status, String body, String retryAfter) { this.status = status; this.body = body; this.retryAfter = retryAfter; }
        String code() { return problemCode(body); }
        Outcome outcome() { return classify(status, code()); }
    }

    static Response get(String url, String cookie) throws IOException { return call("GET", url, cookie, null, null, false); }

    static Response put(String url, String cookie, String idempotencyKey, String body) throws IOException {
        return call("PUT", url, cookie, idempotencyKey, body, true);
    }

    /** Makbuz POST'u: Idempotency-Key yok (tekrarı zararsız). */
    static Response post(String url, String cookie, String body) throws IOException { return call("POST", url, cookie, null, body, true); }

    private static Response call(String method, String url, String cookie, String idempotencyKey, String body, boolean write) throws IOException {
        var c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Cookie", cookie);
            if (write) {
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("X-ADHD-CSRF", "1"); // sabit işaret
                c.setRequestProperty("Origin", MainActivity.ORIGIN);
            }
            if (idempotencyKey != null) c.setRequestProperty("Idempotency-Key", idempotencyKey);
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = c.getResponseCode();
            var text = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            return new Response(status, text, c.getHeaderField("Retry-After"));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (in; var out = new ByteArrayOutputStream()) {
            var buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_BODY) break;
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
}
