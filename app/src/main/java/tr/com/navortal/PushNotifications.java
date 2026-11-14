package tr.com.navortal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;

/** Push (FCM) bildirimleri için kanal, gösterim ve cihaz tokenının yerel saklanması (token tek yerde burada). Token loglanmaz. */
final class PushNotifications {
    static final String CHANNEL = "reminders-v2";
    /** Sunucu "silent" derse sessiz gösterim için düşük önemli kardeş kanal (çerçevede bildirim başına setSilent yok). */
    static final String SILENT_CHANNEL = "reminders-silent-v2";
    static final String EXTRA_DELIVERY = "push.deliveryId", EXTRA_RECIPIENT = "push.recipient", EXTRA_BINDING = "push.binding",
            EXTRA_SNOOZE = "push.snooze", EXTRA_PATH = "push.path";
    private static final String PREFS = "navortal-push", TOKEN = "fcm-token", TAG_PREFIX = "push-";
    private static final int NOTIFICATION_ID = 9201;
    private static final long[] VIBRATION = {0, 250, 150, 250};

    private PushNotifications() { }

    static void ensureChannel(Context context) {
        var manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (manager.getNotificationChannel(CHANNEL) == null) {
            var channel = new NotificationChannel(CHANNEL, context.getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.push_channel_description));
            channel.enableVibration(true);
            channel.setVibrationPattern(VIBRATION);
            manager.createNotificationChannel(channel);
        }
        for (var id : AlertSound.IDS) { // Android sesi kanalda tutar: her hatırlatma sesi için bir kanal
            if (manager.getNotificationChannel(AlertSound.channelId(id)) != null) continue;
            var sound = new NotificationChannel(AlertSound.channelId(id),
                    context.getString(R.string.push_channel_sound_name, AlertSound.label(id)), NotificationManager.IMPORTANCE_DEFAULT);
            sound.setDescription(context.getString(R.string.push_channel_description));
            sound.setSound(Uri.parse("android.resource://" + context.getPackageName() + "/raw/" + AlertSound.rawName(id)),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            sound.enableVibration(true);
            sound.setVibrationPattern(VIBRATION);
            manager.createNotificationChannel(sound);
        }
        if (manager.getNotificationChannel(SILENT_CHANNEL) == null) {
            var quiet = new NotificationChannel(SILENT_CHANNEL, context.getString(R.string.push_channel_silent_name), NotificationManager.IMPORTANCE_LOW);
            quiet.setDescription(context.getString(R.string.push_channel_silent_description));
            manager.createNotificationChannel(quiet);
        }
    }

    /** Yeni/yenilenen token yerel olarak saklanır; sunucuya kaydı {@link PushRegistrar} yapar. */
    static void saveToken(Context context, String token) {
        if (token == null || token.isEmpty()) return;
        prefs(context).edit().putString(TOKEN, token).apply();
    }

    static String token(Context context) { return prefs(context).getString(TOKEN, null); }

    /** Sunucunun doğruladığı hatırlatmayı gösterir (kararı ve doğrulamayı çağıran verir). Aynı dedupeKey aynı bildirimi değiştirir. */
    static void showReminder(Context context, PushPayload p, boolean silent) {
        var manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        ensureChannel(context);
        var intent = new Intent(context, MainActivity.class)
                .setData(Uri.parse("navortal://push/" + p.deliveryId)) // her teslim için ayrı PendingIntent
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_DELIVERY, p.deliveryId)
                .putExtra(EXTRA_RECIPIENT, p.recipientUserId)
                .putExtra(EXTRA_BINDING, p.bindingVersion)
                .putExtra(EXTRA_SNOOZE, p.snoozeCount);
        if (p.path != null) intent.putExtra(EXTRA_PATH, p.path);
        var open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        var label = PushEventLabel.of(p.eventType);
        var title = p.title.isEmpty() ? label : p.title; // başlık yoksa türün adı
        var step = PushEventLabel.stepSummary(p.stepIndex, p.stepCount);
        var channelId = silent ? SILENT_CHANNEL : AlertSound.channelFor(!AlertPrefs.soundForEvent(context, p.eventType).isEmpty(), AlertPrefs.soundForEvent(context, p.eventType)); // kapalıysa telefonun sesi
        var builder = new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(title)
                .setSubText(PushEventLabel.subText(p.eventType, p.parentTitle)) // tür · üst görev
                .setColor(0xFF0070F3) // marka mavisi
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentText(p.body)
                .setStyle(new Notification.BigTextStyle().bigText(p.body).setSummaryText(step.isEmpty() ? null : step))
                .setGroup(p.groupKey)
                .setAutoCancel(true)
                .setContentIntent(open);
        if (!step.isEmpty()) builder.setProgress(p.stepCount, p.stepIndex, false); // alt görev ilerleme çubuğu
        try { manager.notify(TAG_PREFIX + PushRules.tokenHash(p.dedupeKey), NOTIFICATION_ID, builder.build()); }
        catch (SecurityException ignored) { /* İzin eşzamanlı geri alınmış olabilir. */ }
    }

    /** Hesap değişiminde/çıkışta eski kullanıcıya ait hâlâ görünen hatırlatmaları kaldırır. */
    static void cancelReminders(Context context) {
        var manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            for (var active : manager.getActiveNotifications())
                if (active.getTag() != null && active.getTag().startsWith(TAG_PREFIX)) manager.cancel(active.getTag(), active.getId());
        } catch (RuntimeException ignored) { }
    }

    /** Eski biçim: gelen bildirim (notification) mesajını basitçe gösterir; veri mesajı akışı {@link PushDelivery}'dedir. */
    static void show(Context context, String title, String body) {
        var manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        ensureChannel(context);
        var intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        var open = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        var builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle(title == null || title.isEmpty() ? context.getString(R.string.brand_name) : title)
                .setContentText(body == null ? "" : body)
                .setAutoCancel(true)
                .setContentIntent(open);
        try { manager.notify(NOTIFICATION_ID, builder.build()); }
        catch (SecurityException ignored) { /* İzin eşzamanlı geri alınmış olabilir. */ }
    }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
}
