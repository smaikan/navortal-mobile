package tr.com.navortal;

import java.util.LinkedHashMap;

/**
 * Gösterilmiş bildirimlerin dedupeKey kümesi (saf mantık). Saklama biçimi: her satır "anahtar\tzaman_ms".
 * Sınırlı (en çok {@link #MAX}) ve 24 saatten eski girdiler atılır. Atomiklik çağıranın kilidiyle sağlanır.
 */
final class PushDedupe {
    static final int MAX = 200;
    static final long TTL_MS = 24L * 3600 * 1000;

    private PushDedupe() { }

    static String normalize(String key) { return key.replace('\t', '_').replace('\n', '_').replace('\r', '_'); }

    private static LinkedHashMap<String, Long> load(String stored, long now) {
        var map = new LinkedHashMap<String, Long>();
        if (stored == null || stored.isEmpty()) return map;
        for (var line : stored.split("\n")) {
            int tab = line.lastIndexOf('\t');
            if (tab <= 0) continue;
            try {
                long time = Long.parseLong(line.substring(tab + 1));
                if (now - time <= TTL_MS && time <= now + TTL_MS) map.put(line.substring(0, tab), time);
            } catch (NumberFormatException ignored) { /* bozuk satır atılır */ }
        }
        return map;
    }

    static boolean contains(String stored, String key, long now) { return load(stored, now).containsKey(normalize(key)); }

    /** Anahtarı ekler; zaten varsa null (gösterme), yoksa yeni saklama metni. */
    static String insert(String stored, String key, long now) {
        var map = load(stored, now);
        var k = normalize(key);
        if (map.containsKey(k)) return null;
        map.put(k, now);
        while (map.size() > MAX) map.remove(map.keySet().iterator().next());
        var sb = new StringBuilder();
        for (var e : map.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        return sb.toString();
    }
}
