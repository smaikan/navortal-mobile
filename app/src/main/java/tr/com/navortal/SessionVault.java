package tr.com.navortal;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Oturum çerezinin bir kopyasını Android Keystore anahtarıyla şifreli tutar. FCM servisi uygulama kapalıyken yeni bir süreçte
 * başladığında WebView çerez deposu boş dönebilir; bildirim doğrulaması ve cihaz kaydı o zaman bu kopyayı kullanır.
 * Çıkışta silinir. Değer asla loglanmaz.
 */
final class SessionVault {
    private static final String PREFS = "navortal-vault", VALUE = "session", ALIAS = "navortal-session-v1";
    private static String lastSaved;

    private SessionVault() { }

    static synchronized void save(Context c, String pair) {
        if (pair == null || pair.isEmpty() || pair.equals(lastSaved)) return;
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            var encrypted = cipher.doFinal(pair.getBytes(StandardCharsets.UTF_8));
            var packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs(c).edit().putString(VALUE, packed).apply();
            lastSaved = pair;
        } catch (Exception e) { /* kopya tutulamazsa yalnız canlı çerez kullanılır */ }
    }

    static synchronized String load(Context c) {
        try {
            var packed = prefs(c).getString(VALUE, null);
            if (packed == null) return null;
            var parts = packed.split(":");
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized void clear(Context c) {
        lastSaved = null;
        prefs(c).edit().remove(VALUE).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        var store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}
