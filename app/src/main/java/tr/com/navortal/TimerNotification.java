package tr.com.navortal;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

final class TimerNotification {
    private static final String CHANNEL = "work-timer-v1";
    private static final int ID = 9101, PERMISSION = 9102;
    private final Activity activity;
    private final NotificationManager notifications;
    private TimerSnapshot latest;
    private long acceptedAtMs;

    TimerNotification(Activity activity) {
        this.activity = activity;
        notifications = activity.getSystemService(NotificationManager.class);
        var channel = new NotificationChannel(CHANNEL, "Çalışma sayacı", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Başlattığın görevin aktif çalışma süresi");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
    }

    void accept(TimerSnapshot next) {
        if (next.id == null) {
            if (latest == null || next.ownerId.isEmpty() || next.ownerId.equals(latest.ownerId)) {
                latest = null;
                notifications.cancel(ID);
            }
            return;
        }
        if (!next.supersedes(latest)) return;
        latest = next;
        acceptedAtMs = android.os.SystemClock.elapsedRealtime();
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            var prefs = activity.getPreferences(Activity.MODE_PRIVATE);
            if (next.requestPermission && !prefs.getBoolean("notification-permission-asked", false)) {
                prefs.edit().putBoolean("notification-permission-asked", true).apply();
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION);
            }
            return;
        }
        show();
    }

    void permissionResult(int requestCode) {
        if (requestCode != PERMISSION) return;
        if (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) show();
        else Toast.makeText(activity, "Sayaç uygulamada çalışıyor. Bildirimde görmek için telefon ayarlarından Navortal bildirimlerine izin verebilirsin.", Toast.LENGTH_LONG).show();
    }

    /** Telefon ayarlarından dönüşte: izin/kanal sonradan açıldıysa bildirimi yeniden gösterir. */
    void refresh() {
        if (latest == null) return;
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        show();
    }

    private boolean channelBlocked() {
        var channel = notifications.getNotificationChannel(CHANNEL);
        return channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    void show() {
        if (latest == null || !notifications.areNotificationsEnabled() || channelBlocked()) return;
        long elapsed = latest.elapsedMs + (latest.running ? Math.max(0, android.os.SystemClock.elapsedRealtime() - acceptedAtMs) : 0);
        var intent = new Intent(activity, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        var open = PendingIntent.getActivity(activity, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        var publicVersion = new Notification.Builder(activity, CHANNEL)
                .setSmallIcon(tr.com.navortal.R.drawable.ic_timer).setContentTitle("Navortal")
                .setContentText(latest.running ? "Çalışma sürüyor" : "Çalışma duraklatıldı").build();
        var builder = new Notification.Builder(activity, CHANNEL)
                .setSmallIcon(tr.com.navortal.R.drawable.ic_timer)
                .setContentTitle(latest.title)
                .setContentText(latest.running ? "Aktif çalışma" : "Duraklatıldı · " + latest.clock())
                .setCategory(Build.VERSION.SDK_INT >= 31 ? Notification.CATEGORY_STOPWATCH : Notification.CATEGORY_PROGRESS)
                .setWhen(System.currentTimeMillis() - elapsed)
                .setShowWhen(latest.running)
                .setUsesChronometer(latest.running)
                .setChronometerCountDown(false)
                .setOnlyAlertOnce(true).setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                .setContentIntent(open);
        try { notifications.notify(ID, builder.build()); }
        catch (SecurityException ignored) { /* İzin eşzamanlı geri alınmış olabilir. */ }
    }
}
