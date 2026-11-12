package tr.com.navortal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Kayıt sıklığı, sıra numarası ve geç gelen sonucun saklanıp saklanmayacağı kuralları (saf mantık). */
final class PushRules {
    /** Aynı durum bundan sık sunucuya yeniden gönderilmez. */
    static final long RESEND_MS = 24L * 3600 * 1000;

    private PushRules() { }

    /** Son başarılı kaydın özeti; token yalnız özetiyle (hash) tutulur. */
    static final class Snapshot {
        final String tokenHash, permission, appVersion, userId; // userId null: karşılaştırmada yok sayılır
        final boolean channelEnabled;
        final long time;
        Snapshot(String tokenHash, String permission, boolean channelEnabled, String appVersion, String userId, long time) {
            this.tokenHash = tokenHash; this.permission = permission; this.channelEnabled = channelEnabled;
            this.appVersion = appVersion; this.userId = userId; this.time = time;
        }
    }

    /** Kayıt gerekli mi: hiç yok, durum değişti ya da 24 saati aştı. */
    static boolean due(Snapshot last, Snapshot current, long nowMs) {
        if (last == null) return true;
        if (nowMs - last.time >= RESEND_MS || nowMs < last.time) return true;
        if (!eq(last.tokenHash, current.tokenHash) || !eq(last.permission, current.permission) || !eq(last.appVersion, current.appVersion)) return true;
        if (last.channelEnabled != current.channelEnabled) return true;
        return current.userId != null && !current.userId.equalsIgnoreCase(last.userId);
    }

    /** Kurulum başına kesin artan, hiç sıfırlanmayan sıra numarası (en az 1). */
    static long nextSequence(long last) { return Math.max(0, last) + 1; }

    /** Geç gelen kayıt yanıtı yalnız çıkış/hesap değişimi olmadıysa ve aynı kullanıcıya aitse saklanır. */
    static boolean canStore(int startEpoch, int currentEpoch, String requestedUserId, String responseUserId) {
        return startEpoch == currentEpoch && requestedUserId != null && requestedUserId.equalsIgnoreCase(responseUserId);
    }

    static String tokenHash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
}
