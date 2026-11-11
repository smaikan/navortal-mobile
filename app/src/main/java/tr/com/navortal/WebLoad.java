package tr.com.navortal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Site yüklenirken "yükleme ekranı sonsuza kadar kalmasın" kararlarının saf mantığı ve hata nedeni metinleri.
 * Nedenler kullanıcının telefondan okuyup iletebileceği kısa metinlerdir: yalnız ana makine adı, asla tam adres,
 * sorgu, çerez veya belirteç içermez.
 */
final class WebLoad {
    /** openSite/retry sonrası içerik görünmezse beklemeyi bırakma süresi. */
    static final long WATCHDOG_MS = 15_000;

    private WebLoad() { }

    /**
     * navortal.com.tr / www.navortal.com.tr için http veya https adresini güvenilen https://www.navortal.com.tr
     * biçimine çevirir (yol ve sorgu korunur). Başka her şey (kullanıcı bilgisi, farklı port, başka ana makine) null.
     */
    static String trustedEquivalent(String url) {
        if (url == null) return null;
        final URI uri;
        try { uri = new URI(url); } catch (URISyntaxException e) { return null; }
        var scheme = uri.getScheme();
        var host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null) return null;
        boolean https = "https".equals(scheme);
        if (!https && !"http".equals(scheme)) return null;
        if (!"navortal.com.tr".equals(host) && !"www.navortal.com.tr".equals(host)) return null;
        int port = uri.getPort();
        if (port != -1 && port != (https ? 443 : 80)) return null;
        var path = uri.getRawPath() == null ? "" : uri.getRawPath();
        var query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return "https://www.navortal.com.tr" + path + query;
    }

    /** Güvenilmeyen adrese kullanıcı dokunuşu olmadan giden ana çerçeve gezintisi, bir şey beklenirken hata sayılır. */
    static boolean blockedIsError(boolean mainFrame, boolean hasGesture, boolean waiting) {
        return mainFrame && !hasGesture && waiting;
    }

    static boolean httpIsError(boolean mainFrame, int status, boolean waiting) {
        return mainFrame && status >= 400 && waiting;
    }

    static String errorReason(int code, CharSequence description) {
        var text = description == null ? "" : description.toString().trim();
        return "Hata: " + (text.isEmpty() ? "ağ hatası" : text) + " (" + code + ")";
    }

    static String httpReason(int status) { return "Hata: HTTP " + status; }

    static String timeoutReason() { return "Hata: zaman aşımı (" + WATCHDOG_MS / 1000 + " sn)"; }

    static String blockedReason(String host) {
        return "Hata: engellenen adres: " + (host == null || host.isEmpty() ? "bilinmiyor" : host.toLowerCase(Locale.ROOT));
    }

    static String sslReason(int primaryError) { return "Hata: SSL sertifika hatası (" + primaryError + ")"; }

    static String rendererReason() { return "Hata: sayfa görüntüleyici durdu"; }
}
