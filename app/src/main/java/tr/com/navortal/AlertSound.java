package tr.com.navortal;

/**
 * Web uygulamasındaki hatırlatma seslerinin yerel karşılıkları. Android 8+ sesi bildirim değil kanal belirler, bu yüzden her ses için
 * ayrı kanal vardır. Bilinmeyen veya boş ses telefonun varsayılan bildirim sesini kullanan ana kanala düşer (saf mantık).
 */
final class AlertSound {
    /** Web'deki sıra ve adlarla aynı. */
    static final String[] IDS = {"bell", "marimba", "digital", "glass", "pulse", "soft"};
    /** Web ayar ekranındaki kategoriler. */
    static final String[] CATEGORIES = {"pomodoro", "reminder", "taskEnd"};
    private static final String[] LABELS = {"Çan", "Marimba", "Dijital", "Cam tınısı", "Nabız", "Yumuşak bip"};

    private AlertSound() { }

    /** Geçerli ses kimliği, aksi halde boş metin. */
    static String valid(String id) {
        if (id == null) return "";
        var trimmed = id.trim();
        for (var known : IDS) if (known.equals(trimmed)) return known;
        return "";
    }

    static String channelId(String id) { return "reminders-" + id + "-v3"; }

    /** Hatırlatma kanalı: özel ses açık ve geçerliyse onun kanalı, aksi halde telefonun varsayılan bildirim sesli ana kanal. */
    static String channelFor(boolean customEnabled, String id) {
        var valid = valid(id);
        return customEnabled && !valid.isEmpty() ? channelId(valid) : PushNotifications.CHANNEL;
    }

    /** FCM eventType alanından ses kategorisi; bilinmeyen türler "reminder" sayılır. */
    static String category(String eventType) {
        if (eventType == null) return "reminder";
        switch (eventType) {
            case "FocusTarget":
            case "BreakEnd": return "pomodoro";
            case "ScheduleEnd": return "taskEnd";
            default: return "reminder";
        }
    }

    /** "sounds" nesnesinden üç kategorinin sesleri (CATEGORIES sırasıyla); biri eksik/geçersizse null. */
    static String[] parseSounds(org.json.JSONObject sounds) {
        if (sounds == null) return null;
        var chosen = new String[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) {
            var value = sounds.opt(CATEGORIES[i]);
            if (!(value instanceof String) || valid((String) value).isEmpty()) return null;
            chosen[i] = valid((String) value);
        }
        return chosen;
    }

    static String rawName(String id) { return "alert_" + id; }

    static String label(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return LABELS[i];
        return id;
    }
}
