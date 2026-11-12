package tr.com.navortal;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Android 13+ bildirim izni kararı ve (ileride sunucuya bildirilecek) izin/kanal durumu. */
final class PushPermission {
    static final String ASKED_KEY = "notification-permission-asked"; // sayaç bildirimiyle ortak: kullanıcıya bir kez sorulur
    static final int REQUEST_CODE = 9202;
    static final String PERMISSION = "android.permission.POST_NOTIFICATIONS"; // API 33'te eklendi; yalnız sürüm kontrolüyle kullanılır

    private PushPermission() { }

    /** Giriş yapıldıktan sonra, izin yokken ve daha önce sorulmadıysa sor. API 33 öncesi izin zaten verilidir. */
    static boolean shouldAsk(int sdk, boolean granted, boolean askedBefore, boolean signedIn) {
        return sdk >= 33 && signedIn && !granted && !askedBefore;
    }

    static boolean granted(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Sunucu şemasındaki "permission" değeri: granted | denied | notDetermined. */
    static String state(Context context, boolean askedBefore) {
        if (granted(context)) return "granted";
        return askedBefore ? "denied" : "notDetermined";
    }

    /** Hatırlatma kanalı kullanıcı tarafından kapatılmamış mı. */
    static boolean channelEnabled(Context context) {
        var manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(PushNotifications.CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
}
