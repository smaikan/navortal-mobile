package tr.com.navortal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/**
 * Push durumunun kalıcı deposu (kurulum kimliği, sıra numarası, bağlama, son kayıt özeti, gösterilmiş dedupeKey'ler).
 * FCM tokenı tek yerde, {@link PushNotifications} içinde tutulur. Hiçbir değer loglanmaz. Tüm yazmalar aynı kilitle
 * ve commit() ile yapılır; böylece eşzamanlı onMessageReceived/kayıt çağrıları güvenlidir.
 * commit() bilerek kullanılır (ApplySharedPref bastırıldı): sıra numarası ve dedupe kaydı, bildirim/istek gitmeden önce kalıcı olmalı;
 * arka plan servisinin süreci hemen öldürülürse apply() ile yazılan değer kaybolup çift bildirim/geri giden sıra numarası doğabilir.
 */
@SuppressLint("ApplySharedPref")
final class PushStore {
    private static final String PREFS = "navortal-push";
    private static final String INSTALLATION = "installation-id", SEQUENCE = "client-sequence", BINDING = "binding-version",
            USER = "bound-user-id", EPOCH = "epoch", SHOWN = "shown-keys", OWNED = "owned-token-hash", BLOCKED = "blocked-until",
            SESSION = "registered-session", S_TOKEN = "snap-token", S_PERM = "snap-permission", S_CHANNEL = "snap-channel", S_APP = "snap-app", S_USER = "snap-user", S_TIME = "snap-time";
    private static final Object LOCK = new Object();

    private PushStore() { }

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Kurulum başına kalıcı rastgele UUID; hesap değişince sıfırlanmaz. */
    static String installationId(Context c) {
        synchronized (LOCK) {
            var p = prefs(c);
            var id = p.getString(INSTALLATION, null);
            if (id == null) {
                id = UUID.randomUUID().toString();
                p.edit().putString(INSTALLATION, id).commit();
            }
            return id;
        }
    }

    /**
     * Sunucudaki kayıt başka (kapanmış) bir oturuma bağlı olduğu için okunamıyor ve bindingVersion öğrenilemiyorsa yeni bir kurulum
     * kimliği üretilir; yeni kimlik sunucuda yoktur, bindingVersion 0 ve sıra numarası 1 ile doğrudan kaydolur. Eski kayıt pasif kalır.
     */
    static void rotateInstallation(Context c) {
        synchronized (LOCK) {
            prefs(c).edit().putString(INSTALLATION, UUID.randomUUID().toString()).putLong(SEQUENCE, 0)
                    .remove(USER).remove(BINDING).remove(S_TOKEN).remove(S_PERM).remove(S_CHANNEL).remove(S_APP).remove(S_USER).remove(S_TIME)
                    .remove(SESSION).commit();
        }
    }

    /** Her istek için kesin artan, hiç sıfırlanmayan sıra numarası. */
    static long nextSequence(Context c) {
        synchronized (LOCK) {
            var p = prefs(c);
            long next = PushRules.nextSequence(p.getLong(SEQUENCE, 0));
            p.edit().putLong(SEQUENCE, next).commit();
            return next;
        }
    }

    static String boundUserId(Context c) { synchronized (LOCK) { return prefs(c).getString(USER, null); } }

    static long bindingVersion(Context c) { synchronized (LOCK) { return prefs(c).getLong(BINDING, 0); } }

    static int epoch(Context c) { synchronized (LOCK) { return prefs(c).getInt(EPOCH, 0); } }

    static void bind(Context c, String userId, long bindingVersion, PushRules.Snapshot snapshot) {
        synchronized (LOCK) {
            prefs(c).edit().putString(USER, userId).putLong(BINDING, bindingVersion)
                    .putString(S_TOKEN, snapshot.tokenHash).putString(S_PERM, snapshot.permission).putBoolean(S_CHANNEL, snapshot.channelEnabled)
                    .putString(S_APP, snapshot.appVersion).putString(S_USER, userId).putLong(S_TIME, snapshot.time).commit();
        }
    }

    /** Çıkış veya hesap değişimi: bağlama ve son kayıt özeti silinir, dönem (epoch) artar; kurulum kimliği ve sıra numarası kalır. */
    static int clearBinding(Context c) {
        synchronized (LOCK) {
            var p = prefs(c);
            int epoch = p.getInt(EPOCH, 0) + 1;
            p.edit().remove(USER).remove(BINDING).remove(S_TOKEN).remove(S_PERM).remove(S_CHANNEL).remove(S_APP).remove(S_USER).remove(S_TIME).remove(SESSION)
                    .putInt(EPOCH, epoch).commit();
            return epoch;
        }
    }

    static PushRules.Snapshot snapshot(Context c) {
        synchronized (LOCK) {
            var p = prefs(c);
            if (!p.contains(S_TIME)) return null;
            return new PushRules.Snapshot(p.getString(S_TOKEN, null), p.getString(S_PERM, null), p.getBoolean(S_CHANNEL, false),
                    p.getString(S_APP, null), p.getString(S_USER, null), p.getLong(S_TIME, 0));
        }
    }

    /** Son başarılı kaydın yapıldığı oturum çerezinin özeti (çerez değeri değil). Oturum değişince kayıt yenilenir. */
    static String registeredSession(Context c) { synchronized (LOCK) { return prefs(c).getString(SESSION, null); } }

    static void setRegisteredSession(Context c, String hash) { synchronized (LOCK) { prefs(c).edit().putString(SESSION, hash).commit(); } }

    /** fcm_token_owned: token değişene dek denenmez. */
    static String ownedTokenHash(Context c) { synchronized (LOCK) { return prefs(c).getString(OWNED, null); } }

    static void setOwnedTokenHash(Context c, String hash) { synchronized (LOCK) { prefs(c).edit().putString(OWNED, hash).commit(); } }

    /** 429 Retry-After: bu zamana dek kayıt denenmez. */
    static long blockedUntil(Context c) { synchronized (LOCK) { return prefs(c).getLong(BLOCKED, 0); } }

    static void blockUntil(Context c, long timeMs) { synchronized (LOCK) { prefs(c).edit().putLong(BLOCKED, timeMs).commit(); } }

    static boolean alreadyShown(Context c, String dedupeKey, long now) {
        synchronized (LOCK) { return PushDedupe.contains(prefs(c).getString(SHOWN, ""), dedupeKey, now); }
    }

    /** Atomik "yoksa ekle": true = ilk kez, göster; false = zaten gösterilmiş. */
    static boolean markShown(Context c, String dedupeKey, long now) {
        synchronized (LOCK) {
            var p = prefs(c);
            var updated = PushDedupe.insert(p.getString(SHOWN, ""), dedupeKey, now);
            if (updated == null) return false;
            p.edit().putString(SHOWN, updated).commit();
            return true;
        }
    }
}
