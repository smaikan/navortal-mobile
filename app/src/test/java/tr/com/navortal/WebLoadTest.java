package tr.com.navortal;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebLoadTest {
    @Test public void reasonTexts() {
        assertEquals("Hata: net::ERR_NAME_NOT_RESOLVED (-2)", WebLoad.errorReason(-2, "net::ERR_NAME_NOT_RESOLVED"));
        assertEquals("Hata: ağ hatası (-1)", WebLoad.errorReason(-1, null));
        assertEquals("Hata: ağ hatası (-1)", WebLoad.errorReason(-1, "  "));
        assertEquals("Hata: HTTP 503", WebLoad.httpReason(503));
        assertEquals("Hata: zaman aşımı (15 sn)", WebLoad.timeoutReason());
        assertEquals("Hata: engellenen adres: evil.example", WebLoad.blockedReason("Evil.example"));
        assertEquals("Hata: engellenen adres: bilinmiyor", WebLoad.blockedReason(null));
    }

    @Test public void errorDecisions() {
        assertTrue(WebLoad.httpIsError(true, 503, true));
        assertTrue(WebLoad.httpIsError(true, 404, true));
        assertFalse(WebLoad.httpIsError(true, 399, true));
        assertFalse(WebLoad.httpIsError(false, 503, true));
        assertFalse(WebLoad.httpIsError(true, 503, false)); // zaten gösterilen sayfada site kendi hata sayfasını gösterir
        assertTrue(WebLoad.blockedIsError(true, false, true));
        assertFalse(WebLoad.blockedIsError(true, true, true));   // kullanıcı dokunuşu dış tarayıcıya gider
        assertFalse(WebLoad.blockedIsError(false, false, true)); // alt çerçeve
        assertFalse(WebLoad.blockedIsError(true, false, false));
    }

    @Test public void apexAndHttpRedirectsMapToTrustedOrigin() {
        assertEquals("https://www.navortal.com.tr/login", WebLoad.trustedEquivalent("https://navortal.com.tr/login"));
        assertEquals("https://www.navortal.com.tr/login?next=%2Fplanning", WebLoad.trustedEquivalent("http://navortal.com.tr/login?next=%2Fplanning"));
        assertEquals("https://www.navortal.com.tr", WebLoad.trustedEquivalent("http://www.navortal.com.tr"));
        assertEquals("https://www.navortal.com.tr/x", WebLoad.trustedEquivalent("https://www.navortal.com.tr:443/x"));
    }

    @Test public void otherAddressesAreNeverMapped() {
        assertNull(WebLoad.trustedEquivalent("https://evil.example/login"));
        assertNull(WebLoad.trustedEquivalent("https://navortal.com.tr.evil.example/login"));
        assertNull(WebLoad.trustedEquivalent("https://user@navortal.com.tr/login"));
        assertNull(WebLoad.trustedEquivalent("https://navortal.com.tr:8443/login"));
        assertNull(WebLoad.trustedEquivalent("ftp://navortal.com.tr/login"));
        assertNull(WebLoad.trustedEquivalent("javascript:alert(1)"));
        assertNull(WebLoad.trustedEquivalent(null));
    }
}
