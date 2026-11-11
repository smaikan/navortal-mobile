package tr.com.navortal;

import java.time.Instant;
import java.util.UUID;
import org.json.JSONObject;

/** Aynı anlık görüntü iOS tarafından da okunur; saniyelik JS/ağ işi gerektirmez. */
final class TimerSnapshot {
    final String ownerId, id, title;
    final long version, serverTimeMs, elapsedMs;
    final boolean running, requestPermission;

    private TimerSnapshot(String ownerId, String id, String title, long version,
            long serverTimeMs, long elapsedMs, boolean running, boolean requestPermission) {
        this.ownerId = ownerId;
        this.id = id;
        this.title = title;
        this.version = version;
        this.serverTimeMs = serverTimeMs;
        this.elapsedMs = elapsedMs;
        this.running = running;
        this.requestPermission = requestPermission;
    }

    static TimerSnapshot parse(JSONObject message, long nowMs) throws org.json.JSONException {
        if (message.getInt("protocol") != 1 || !message.has("work")) throw new IllegalArgumentException("protocol");
        String owner = message.getString("ownerId");
        long captured = message.getLong("capturedAtMs");
        if (Math.abs(nowMs - captured) > 10_000) throw new IllegalArgumentException("stale message");
        if (message.isNull("work")) {
            if (!owner.isEmpty()) uuid(owner);
            return new TimerSnapshot(owner, null, "", 0, 0, 0, false, false);
        }
        uuid(owner);
        JSONObject work = message.getJSONObject("work");
        String id = work.getString("id");
        uuid(id);
        String rawVersion = work.getString("version");
        if (!rawVersion.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("version");
        long version = Long.parseLong(rawVersion);
        String state = work.getString("state");
        if (!state.equals("Running") && !state.equals("Paused")) throw new IllegalArgumentException("state");
        long elapsed = work.getLong("elapsedMs");
        if (elapsed < 0 || elapsed > 3_155_760_000_000L || work.getDouble("elapsedMs") != elapsed) throw new IllegalArgumentException("duration");
        boolean running = state.equals("Running");
        if (running) elapsed += Math.max(0, nowMs - captured);
        String title = work.getString("title").replaceAll("[\\p{Cntrl}]", " ").strip();
        if (title.isEmpty()) title = "Çalışma";
        if (title.length() > 200) title = title.substring(0, 200);
        return new TimerSnapshot(owner, id, title, version,
                Instant.parse(work.getString("serverNow")).toEpochMilli(), elapsed,
                running, message.getBoolean("requestPermission"));
    }

    private static void uuid(String value) {
        if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("id");
    }

    boolean supersedes(TimerSnapshot previous) {
        if (previous == null || !ownerId.equals(previous.ownerId)) return true;
        if (serverTimeMs < previous.serverTimeMs) return false;
        return !id.equals(previous.id) || version >= previous.version;
    }

    String clock() {
        long seconds = elapsedMs / 1000;
        if (seconds >= 3600) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
