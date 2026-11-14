package tr.com.navortal;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Hatırlatma sesi tercihi (yalnız cihazda): özel ses açık/kapalı ve üç kategori için seçilen sesler. Varsayılan kapalı = telefonun
 * kendi bildirim sesi. Web ayar ekranı bunu köprüden okur/yazar; bildirim gösterilirken {@link AlertSound#channelFor} kullanılır.
 */
final class AlertPrefs {
    private static final String PREFS = "navortal-alert", ENABLED = "custom-sound-enabled", DEFAULT_SOUND = "soft";

    private AlertPrefs() { }

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean enabled(Context c) { return prefs(c).getBoolean(ENABLED, false); }

    /** Kategorinin seçili sesi; hiç seçilmediyse web'in de varsayılanı olan "soft". */
    static String sound(Context c, String category) {
        var id = AlertSound.valid(prefs(c).getString("sound-" + category, ""));
        return id.isEmpty() ? DEFAULT_SOUND : id;
    }

    /** Bildirim türü için kullanılacak ses; özel ses kapalıysa boş (telefonun sesi). */
    static String soundForEvent(Context c, String eventType) {
        return enabled(c) ? sound(c, AlertSound.category(eventType)) : "";
    }

    /**
     * Köprüdeki "sound.set" isteğini uygular. Açıkken üç kategori de geçerli olmak zorundadır; aksi halde hiçbir şey kaydedilmez.
     * Kapatırken (sounds gelmez) son seçilen sesler korunur. @return kaydedildi mi
     */
    static boolean apply(Context c, JSONObject message) {
        if (!(message.opt("enabled") instanceof Boolean)) return false;
        boolean enabled = message.optBoolean("enabled", false);
        var edit = prefs(c).edit();
        if (enabled) {
            var chosen = AlertSound.parseSounds(message.optJSONObject("sounds"));
            if (chosen == null) return false;
            for (int i = 0; i < AlertSound.CATEGORIES.length; i++) edit.putString("sound-" + AlertSound.CATEGORIES[i], chosen[i]);
        }
        edit.putBoolean(ENABLED, enabled).apply();
        return true;
    }

    /** Köprü yanıtı: {"type":"sound.state","enabled":false,"sounds":{"pomodoro":"soft",...},"available":["bell",...]}. */
    static String stateJson(Context c) {
        try {
            var sounds = new JSONObject();
            for (var category : AlertSound.CATEGORIES) sounds.put(category, sound(c, category));
            var available = new JSONArray();
            for (var id : AlertSound.IDS) available.put(id);
            return new JSONObject().put("type", "sound.state").put("enabled", enabled(c)).put("sounds", sounds)
                    .put("available", available).toString();
        } catch (JSONException e) {
            return "{\"type\":\"sound.state\",\"enabled\":false}";
        }
    }
}
