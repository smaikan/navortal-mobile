package tr.com.navortal;

import java.net.URI;
import java.net.URISyntaxException;

/** Sitenin yolundan "oturum açık mı" çıkarımı. Karşılama ekranının gösterilip gösterilmeyeceğini belirler. */
final class SignInState {
    private SignInState() { }

    /** true: planlama ekranı (oturum açık), false: giriş/kayıt sayfası, null: durum değişmez (başka yol veya güvenilmeyen adres). */
    static Boolean fromUrl(String url) {
        if (url == null) return null;
        final URI uri;
        try { uri = new URI(url); } catch (URISyntaxException e) { return null; }
        if (!"https".equals(uri.getScheme()) || !"www.navortal.com.tr".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null) return null;
        var path = uri.getPath();
        if (path == null) return null;
        if (is(path, "/login") || is(path, "/register")) return Boolean.FALSE;
        if (path.equals("/planning") || path.startsWith("/planning/")) return Boolean.TRUE;
        return null;
    }

    /**
     * Geri tuşu karşılama ekranına mı dönmeli? Site karşılamadan açıldıysa, oturum hâlâ kapalıysa ve WebView
     * geri gidemiyorsa (yanlış düğme, hata ekranı) evet; diğer durumlarda eski davranış sürer.
     */
    static boolean backShowsWelcome(boolean openedFromWelcome, boolean signedIn, boolean webCanGoBack, boolean welcomeShowing) {
        return openedFromWelcome && !signedIn && !webCanGoBack && !welcomeShowing;
    }

    /** Sitenin tanıtım (web karşılama) sayfası: uygulamada yerel karşılama ekranı bunun yerine geçer. */
    static boolean isLanding(String url) {
        if (url == null) return false;
        final URI uri;
        try { uri = new URI(url); } catch (URISyntaxException e) { return false; }
        if (!"https".equals(uri.getScheme()) || !"www.navortal.com.tr".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null) return false;
        var path = uri.getPath();
        return path == null || path.isEmpty() || path.equals("/");
    }

    private static boolean is(String path, String page) { return path.equals(page) || path.equals(page + "/"); }
}
