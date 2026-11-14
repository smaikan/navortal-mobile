package tr.com.navortal;

import android.content.Context;
import android.util.Log;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gelen "notification.ready" veri mesajının işlenmesi: önce saf doğrulama, sonra izin/kanal, dedupe, sunucuda teslim kontrolü,
 * gösterim ve "Displayed" makbuzu. Hata veya belirsizlikte bildirim GÖSTERİLMEZ. onMessageReceived'in arka plan iş
 * parçacığında çağrılır (yaklaşık 20 sn süre); ağ çağrıları 8 sn zaman aşımlıdır. Mesaj içeriği loglanmaz.
 */
final class PushDelivery {
    private static final String TAG = "NavortalPush";
    private static final ExecutorService RECEIPTS = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "navortal-push-receipt");
        t.setDaemon(true);
        return t;
    });

    private PushDelivery() { }

    static void handle(Context context, Map<String, String> data) {
        var ctx = context.getApplicationContext();
        if (!PushPayload.isOurs(data)) return; // timer.changed, bilinmeyen tür/şema: sessizce yok say
        long now = System.currentTimeMillis();
        Log.i(TAG, "mesaj alındı");
        final PushPayload payload;
        try { payload = PushPayload.parse(data, PushStore.boundUserId(ctx), PushStore.bindingVersion(ctx), now); }
        catch (PushPayload.Invalid e) { Log.i(TAG, "mesaj reddedildi: " + e.getMessage()); return; }

        PushNotifications.ensureChannel(ctx);
        if (!PushPermission.granted(ctx) || !PushPermission.channelEnabled(ctx)) { Log.i(TAG, "gösterilmedi: bildirim izni veya kanal kapalı"); return; }
        if (PushStore.alreadyShown(ctx, payload.dedupeKey, now)) { Log.i(TAG, "gösterilmedi: bu bildirim zaten gösterilmiş"); return; }

        var cookie = PushRegistrar.sessionCookie(ctx);
        if (cookie == null) { Log.i(TAG, "gösterilmedi: oturum çerezi yok"); return; }
        var installation = PushStore.installationId(ctx);
        PushApi.Delivery check;
        try {
            var response = PushApi.get(PushApi.DELIVERIES + payload.deliveryId + "?installationId=" + installation
                    + "&bindingVersion=" + payload.bindingVersion, cookie);
            if (response.status != 200) { Log.i(TAG, "teslim kontrolü " + response.status); return; }
            check = PushApi.parseDelivery(response.body);
        } catch (IOException e) {
            Log.i(TAG, "gösterilmedi: teslim kontrolü ağ hatası");
            return; // kontrol edilemedi: gösterme
        }
        if (check == null) { Log.i(TAG, "gösterilmedi: teslim kontrolü yanıtı okunamadı"); return; }
        if (!check.displayAllowed) { Log.i(TAG, "gösterilmedi: sunucu göstermeyi onaylamadı"); return; }
        // Kontrol sürerken çıkış/hesap değişimi olduysa gösterme.
        if (!payload.recipientUserId.equalsIgnoreCase(PushStore.boundUserId(ctx)) || payload.bindingVersion != PushStore.bindingVersion(ctx)) { Log.i(TAG, "gösterilmedi: hesap veya bağlama değişti"); return; }
        if (!PushStore.markShown(ctx, payload.dedupeKey, now)) return; // eşzamanlı çağrıda yalnız biri gösterir
        Log.i(TAG, "bildirim gösteriliyor");

        PushNotifications.showReminder(ctx, payload, check.silent);
        sendReceipt(ctx, payload.deliveryId, payload.recipientUserId, payload.bindingVersion, payload.snoozeCount, "Displayed");
    }

    /** Makbuz arka planda gönderilir; başarısızlık önemsizdir (tekrarı zararsız, Idempotency-Key yok). */
    static void sendReceipt(Context context, String deliveryId, String userId, long bindingVersion, int snoozeCount, String stage) {
        var ctx = context.getApplicationContext();
        RECEIPTS.execute(() -> {
            try {
                var cookie = PushRegistrar.sessionCookie(ctx);
                if (cookie == null) return;
                var body = PushApi.receiptBody(userId, PushStore.installationId(ctx), bindingVersion, snoozeCount, stage);
                var response = PushApi.post(PushApi.DELIVERIES + deliveryId + "/receipts", cookie, body);
                if (response.status != 204) Log.i(TAG, "makbuz " + stage + " " + response.status + " " + response.code());
            } catch (IOException | RuntimeException ignored) { /* önemsiz */ }
        });
    }
}
