package tr.com.navortal;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** FCM alıcısı: token yenilemesi ve "notification.ready" veri mesajları (arka plan iş parçacığında çalışır). */
public final class NavortalMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) {
        PushNotifications.saveToken(this, token);
        PushRegistrar.request(this, false);
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        var data = message.getData();
        if (data.containsKey("type") || data.containsKey("schemaVersion")) {
            PushDelivery.handle(this, data); // bilinmeyen tür/şema orada sessizce yok sayılır
            return;
        }
        var notification = message.getNotification();
        if (notification != null) PushNotifications.show(this, notification.getTitle(), notification.getBody()); // zararsız yedek
    }
}
