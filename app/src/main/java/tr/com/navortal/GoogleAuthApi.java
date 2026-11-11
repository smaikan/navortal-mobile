package tr.com.navortal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Native Google girişinin sunucu tarafı. Akış: config (nonce + challenge çerezi) → Credential Manager → session/google.
 * Yalnız https://www.navortal.com.tr'ye gider, yönlendirme izlemez; token ve çerez hiçbir yere yazılmaz.
 * Çerezler sistem çerez deposuna değil, bu girişim için elle taşınır.
 */
final class GoogleAuthApi {
    static final String CONFIG = MainActivity.ORIGIN + "/api/auth/google/config";
    static final String LOGIN = MainActivity.ORIGIN + "/api/auth/session/google";
    static final String SESSION_COOKIE = "__Host-adhd_session";
    static final String CHALLENGE_COOKIE = "__Host-adhd_google_challenge";
    static final long MAX_SESSION_SECONDS = 400L * 24 * 3600; // sunucu ömrüyle aynı
    private static final int MAX_BODY = 64 * 1024, TIMEOUT_MS = 15_000;

    private GoogleAuthApi() { }

    /** Aynı giriş denemesine ait yapılandırma: nonce Credential Manager'a aynen verilir. */
    static final class Config {
        final boolean enabled;
        final String clientId, nonce, challengeCookie; // challengeCookie: "ad=değer", Cookie başlığına konur
        Config(boolean enabled, String clientId, String nonce, String challengeCookie) {
            this.enabled = enabled; this.clientId = clientId; this.nonce = nonce; this.challengeCookie = challengeCookie;
        }
    }

    /** İstemcinin davranışı HTTP durumuna ve Problem Details "code" alanına göre belirlenir, metne göre değil. */
    static final class Failure extends Exception {
        enum Kind { NETWORK, DISABLED, RETRY_LOGIN, LINK_REQUIRED, ALREADY_LINKED, RATE_LIMITED, CONFIG, OTHER }
        final int status;
        final String code;
        final Kind kind;
        Failure(int status, String code, Kind kind) { super("google " + status + " " + code); this.status = status; this.code = code; this.kind = kind; }
    }

    static Failure.Kind kindFor(int status, String code) {
        if (status == 429) return Failure.Kind.RATE_LIMITED;
        if (status == 503 || "google_disabled".equals(code) || "google_not_configured".equals(code)) return Failure.Kind.DISABLED;
        if ("google_link_required".equals(code)) return Failure.Kind.LINK_REQUIRED;
        if ("google_already_linked".equals(code)) return Failure.Kind.ALREADY_LINKED;
        if ("google_challenge_invalid".equals(code) || "google_invalid".equals(code) || "credentials_changed".equals(code)) return Failure.Kind.RETRY_LOGIN;
        if ("csrf_required".equals(code) || "validation_failed".equals(code)) return Failure.Kind.CONFIG;
        return Failure.Kind.OTHER;
    }

    // --- saf ayrıştırma (birim testli) ---

    static Config parseConfig(String json, List<String> setCookies) throws org.json.JSONException {
        var o = new JSONObject(json);
        boolean enabled = o.optBoolean("enabled", false);
        var clientId = o.optString("clientId", "");
        var nonce = o.optString("nonce", "");
        var challenge = cookiePair(setCookies, CHALLENGE_COOKIE);
        if (enabled && (clientId.isEmpty() || nonce.isEmpty() || challenge == null))
            throw new org.json.JSONException("eksik config");
        return new Config(enabled, clientId, nonce, challenge);
    }

    /** Set-Cookie satırlarından belirtilen adlı çerezin "ad=değer" çiftini verir; silme (boş değer) satırları yok sayılır. */
    static String cookiePair(List<String> setCookies, String name) {
        if (setCookies == null) return null;
        for (var line : setCookies) {
            if (line == null) continue;
            int semi = line.indexOf(';');
            var pair = (semi < 0 ? line : line.substring(0, semi)).trim();
            int eq = pair.indexOf('=');
            if (eq <= 0 || !pair.substring(0, eq).equals(name)) continue;
            if (pair.length() == eq + 1) continue; // boş değer = çerezi silen satır
            return pair;
        }
        return null;
    }

    /**
     * Sunucunun oturum Set-Cookie satırından WebView'e yazılacak çerez dizisini üretir; yoksa veya beklenen özelliklere
     * uymuyorsa null. __Host- çerezi Domain taşımaz, Path=/ ve Secure olmak zorundadır; biz bunları kendimiz yeniden yazarız.
     */
    static String webViewSessionCookie(List<String> setCookies) {
        var pair = cookiePair(setCookies, SESSION_COOKIE);
        if (pair == null) return null;
        long maxAge = MAX_SESSION_SECONDS;
        for (var line : setCookies) {
            if (line == null || !line.startsWith(SESSION_COOKIE + "=")) continue;
            for (var part : line.split(";")) {
                var p = part.trim().toLowerCase(java.util.Locale.ROOT);
                if (p.startsWith("domain=")) return null;
                if (p.startsWith("max-age=")) {
                    try { maxAge = Math.max(0, Math.min(MAX_SESSION_SECONDS, Long.parseLong(p.substring(8)))); }
                    catch (NumberFormatException e) { return null; }
                }
            }
        }
        if (maxAge == 0) return null;
        return pair + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=" + maxAge;
    }

    static String problemCode(String body) {
        try { return new JSONObject(body).optString("code", ""); } catch (Exception e) { return ""; }
    }

    // --- ağ (arka plan iş parçacığında çağrılır) ---

    static Config fetchConfig() throws Failure {
        var c = open(CONFIG, "GET");
        try {
            int status = c.getResponseCode();
            var body = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            if (status != 200) throw failure(status, body);
            return parseConfig(body, setCookies(c));
        } catch (org.json.JSONException e) {
            throw new Failure(200, "config_invalid", Failure.Kind.CONFIG);
        } catch (IOException e) {
            throw new Failure(0, "network", Failure.Kind.NETWORK);
        } finally { c.disconnect(); }
    }

    /** @return WebView çerez deposuna yazılacak çerez dizisi */
    static String login(String idToken, Config config) throws Failure {
        var c = open(LOGIN, "POST");
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-ADHD-CSRF", "1"); // sabit işaret; nonce değil
            c.setRequestProperty("Origin", MainActivity.ORIGIN);
            c.setRequestProperty("Cookie", config.challengeCookie);
            var payload = new JSONObject().put("idToken", idToken).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(payload); }
            int status = c.getResponseCode();
            var body = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            if (status != 200) throw failure(status, body);
            var cookie = webViewSessionCookie(setCookies(c));
            if (cookie == null) throw new Failure(status, "no_session_cookie", Failure.Kind.OTHER);
            return cookie;
        } catch (org.json.JSONException e) {
            throw new Failure(0, "request", Failure.Kind.OTHER);
        } catch (IOException e) {
            throw new Failure(0, "network", Failure.Kind.NETWORK); // POST'u körlemesine tekrarlama
        } finally { c.disconnect(); }
    }

    private static Failure failure(int status, String body) {
        var code = problemCode(body);
        return new Failure(status, code, kindFor(status, code));
    }

    private static HttpURLConnection open(String url, String method) throws Failure {
        try {
            var c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            return c;
        } catch (IOException e) {
            throw new Failure(0, "network", Failure.Kind.NETWORK);
        }
    }

    private static List<String> setCookies(HttpURLConnection c) {
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet())
            if (e.getKey() != null && "set-cookie".equalsIgnoreCase(e.getKey())) return e.getValue();
        return java.util.Collections.emptyList();
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
