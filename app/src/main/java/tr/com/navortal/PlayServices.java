package tr.com.navortal;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/** Google Play hizmetleri bu cihazda var ve etkin mi? (Bağımlılık eklemeden, yalnız PackageManager ile.) */
final class PlayServices {
    private static final String GMS = "com.google.android.gms";

    private PlayServices() { }

    static boolean available(Context context) {
        var pm = context.getPackageManager();
        try {
            ApplicationInfo info = Build.VERSION.SDK_INT >= 33
                    ? pm.getApplicationInfo(GMS, PackageManager.ApplicationInfoFlags.of(0))
                    : pm.getApplicationInfo(GMS, 0);
            return info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
