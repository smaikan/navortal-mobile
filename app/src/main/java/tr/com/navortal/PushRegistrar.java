package tr.com.navortal;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cihazı sunucuya kaydeder/yeniler. Tek iş parçacıklı yürütücüde sıralı çalışır, birkaç saniye geciktirilip birleştirilir,
 * ana iş parçacığında asla ağa çıkmaz. Google Play hizmeti olmayan cihazlarda hiçbir şey yapmaz. Hata tablosu
 * {@link PushApi#classify} ile (HTTP durumu + code); döngü yok, 429'da Retry-After sonuna dek denenmez.
 * Günlüğe yalnız durum kodu/hata kodu yazılır; çerez, token ve yanıt gövdesi asla.
 */
final class PushRegistrar {
    private static final String TAG = "NavortalPush";
    private static final long DEBOUNCE_SECONDS = 3;
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "navortal-push-register");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledFuture<?> pending;
    private static boolean pendingForce;
    private static volatile boolean halted; // 403 (başlık/Origin hatası): bu süreçte tekrar denenmez

    private PushRegistrar() { }

    /**
     * Kaydı zamanlar (birkaç sn geciktirir, ardışık istekleri birleştirir).
     * @param force oturum açıldığında true: durum aynı olsa bile /api/auth/me ile kullanıcıyı doğrula
     */
    static synchronized void request(Context context, boolean force) {
        var app = context.getApplicationContext();
        pendingForce |= force;
        if (pending != null) pending.cancel(false);
        pending = EXECUTOR.schedule(() -> {
            boolean f;
            synchronized (PushRegistrar.class) { f = pendingForce; pendingForce = false; }
            try { run(app, f); }
            catch (RuntimeException e) { Log.w(TAG, "kayıt hatası: " + e.getClass().getSimpleName()); }
        }, DEBOUNCE_SECONDS, TimeUnit.SECONDS);
    }

    /** WebView çerez deposundan yalnız oturum çerezi çifti; yoksa veya okunamazsa null. */
    static String sessionCookie() {
        try { return PushApi.sessionPair(CookieManager.getInstance().getCookie(MainActivity.ORIGIN)); }
        catch (RuntimeException e) { return null; }
    }

    private static void run(Context ctx, boolean force) {
        if (!PlayServices.available(ctx)) { Log.i(TAG, "durdu: Google Play hizmeti yok"); return; }
        if (halted) { Log.i(TAG, "durdu: önceki 403 nedeniyle bu süreçte kapalı"); return; }
        long now = System.currentTimeMillis();
        if (now < PushStore.blockedUntil(ctx)) { Log.i(TAG, "durdu: hız sınırı bekleniyor"); return; }

        var asked = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getBoolean(PushPermission.ASKED_KEY, false);
        var permission = PushPermission.state(ctx, asked);
        boolean channel = PushPermission.channelEnabled(ctx);
        var token = PushNotifications.token(ctx);
        if (token == null) {
            token = fetchToken();
            if (token != null) PushNotifications.saveToken(ctx, token);
        }
        if (!PushApi.validToken(token)) { Log.i(TAG, "durdu: FCM token alınamadı"); return; }
        var tokenHash = PushRules.tokenHash(token);
        if (tokenHash.equals(PushStore.ownedTokenHash(ctx))) { Log.i(TAG, "durdu: token başka kuruluma ait"); return; } // fcm_token_owned: token değişene dek denenmez

        var appVersion = PushApi.clip(versionName(ctx), 32);
        if (appVersion.isEmpty()) { Log.i(TAG, "durdu: uygulama sürümü okunamadı"); return; }
        // Ucuz yerel ön kontrol: durum değişmediyse ve zorlanmadıysa ağa hiç çıkma.
        var bound = PushStore.boundUserId(ctx);
        if (!force && !PushRules.due(PushStore.snapshot(ctx), new PushRules.Snapshot(tokenHash, permission, channel, appVersion, null, now), now)) { Log.i(TAG, "atlandı: durum değişmedi (izin=" + permission + ", kanal=" + channel + ")"); return; }

        var cookie = sessionCookie();
        if (cookie == null) { Log.i(TAG, "durdu: oturum çerezi yok"); return; } // oturum yok: bir sonraki girişte tekrar denenir
        String userId = currentUserId(cookie);
        if (userId == null) { Log.i(TAG, "durdu: kullanıcı kimliği alınamadı"); return; }

        if (bound != null && !bound.equalsIgnoreCase(userId)) { // hesap değişti: eski kullanıcının izleri silinir
            PushStore.clearBinding(ctx);
            PushNotifications.cancelReminders(ctx);
        }
        int epoch = PushStore.epoch(ctx);
        var current = new PushRules.Snapshot(tokenHash, permission, channel, appVersion, userId, now);
        if (!PushRules.due(PushStore.snapshot(ctx), current, now)) { Log.i(TAG, "atlandı: bu durum zaten kayıtlı"); return; }
        Log.i(TAG, "kayıt isteği gönderiliyor (izin=" + permission + ", kanal=" + channel + ")");
        register(ctx, cookie, userId, token, current, epoch);
    }

    private static String currentUserId(String cookie) {
        try {
            var me = PushApi.get(PushApi.ME, cookie);
            if (me.status != 200) { Log.i(TAG, "auth/me " + me.status); return null; }
            var id = PushApi.parseUserId(me.body, cookie);
            if (id == null) Log.w(TAG, "kullanıcı kimliği bulunamadı");
            return id;
        } catch (IOException e) {
            return null;
        }
    }

    /** PUT cihaz; binding çakışmasında bir kez GET + yeniden dene; ağ/500'de aynı istek (aynı anahtar) bir kez tekrarlanır. */
    private static void register(Context ctx, String cookie, String userId, String token, PushRules.Snapshot snapshot, int epoch) {
        long binding = PushStore.bindingVersion(ctx);
        var response = send(ctx, cookie, userId, token, snapshot, binding);
        if (response != null && response.outcome() == PushApi.Outcome.BINDING_CONFLICT) {
            long adopted = 0;
            try {
                var get = PushApi.get(PushApi.DEVICES + PushStore.installationId(ctx), cookie);
                var device = get.status == 200 ? PushApi.parseDevice(get.body) : null;
                if (get.status == 200 && device == null) return;
                if (device != null) adopted = device.bindingVersion;
            } catch (IOException e) { return; }
            response = send(ctx, cookie, userId, token, snapshot, adopted); // yalnız bir kez
        }
        if (response == null) return;
        switch (response.outcome()) {
            case OK: {
                Log.i(TAG, "kayıt başarılı");
                var device = PushApi.parseDevice(response.body);
                if (device != null && PushRules.canStore(epoch, PushStore.epoch(ctx), userId, device.userId))
                    PushStore.bind(ctx, device.userId, device.bindingVersion, snapshot);
                break;
            }
            case RATE_LIMITED: PushStore.blockUntil(ctx, System.currentTimeMillis() + PushApi.retryAfterMs(response.retryAfter)); break;
            case TOKEN_OWNED: PushStore.setOwnedTokenHash(ctx, snapshot.tokenHash); break;
            case CSRF: halted = true; break;
            default: Log.i(TAG, "kayıt reddedildi: " + response.status + " " + response.code()); // NO_SESSION, FIX_REQUEST, DEVICE_LIMIT, ...
        }
    }

    /** Tek PUT (yeni sıra numarası + yeni Idempotency-Key). Ağ hatası/500'de AYNI istek bir kez tekrarlanır. null: yanıt alınamadı. */
    private static PushApi.Response send(Context ctx, String cookie, String userId, String token, PushRules.Snapshot snapshot, long binding) {
        var url = PushApi.DEVICES + PushStore.installationId(ctx);
        var key = UUID.randomUUID().toString();
        var body = PushApi.deviceBody(userId, token, binding, PushStore.nextSequence(ctx), snapshot.appVersion, buildNumber(ctx),
                Build.VERSION.RELEASE, snapshot.permission, snapshot.channelEnabled);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var response = PushApi.put(url, cookie, key, body);
                if (response.outcome() != PushApi.Outcome.SERVER_ERROR) return response;
                if (attempt == 1) return response;
            } catch (IOException e) {
                if (attempt == 1) return null;
            }
            try { Thread.sleep(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    private static String fetchToken() {
        try { return Tasks.await(FirebaseMessaging.getInstance().getToken(), 10, TimeUnit.SECONDS); }
        catch (Exception e) { return null; }
    }

    private static String versionName(Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException | RuntimeException e) { return ""; }
    }

    private static long buildNumber(Context ctx) {
        try {
            var info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) { return 1; }
    }
}
