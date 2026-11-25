package local.cc3.manager;

import android.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded operational event counters. Never records exception messages, addresses or user data. */
final class Diagnostics {
    private static final Map<String, Long> last = new LinkedHashMap<>();
    static synchronized void event(String component, String operation, Throwable error) {
        if (!component.matches("[A-Za-z]{1,24}") || !operation.matches("[A-Za-z]{1,32}")) return;
        String key = component + "/" + operation;
        long now = android.os.SystemClock.elapsedRealtime();
        Long previous = last.get(key);
        if (previous != null && now - previous < 30000) return;
        if (last.size() >= 64) last.remove(last.keySet().iterator().next());
        last.put(key, now);
        Log.w("CC3Operations", key + ": " + error.getClass().getSimpleName());
    }
    private Diagnostics() { }
}
