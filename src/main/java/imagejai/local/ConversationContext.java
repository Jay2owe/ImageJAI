package imagejai.local;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Tiny in-memory memory for one Local Assistant session.
 */
public class ConversationContext {

    public static final long IDLE_SUNSET_MS = 10L * 60L * 1000L;

    private final LongSupplier nowMs;
    private String lastIntentId;
    private Map<String, String> lastSlots = Collections.emptyMap();
    private Integer lastRoiIndex;
    private long lastUpdated;

    public ConversationContext() {
        this(System::currentTimeMillis);
    }

    public ConversationContext(LongSupplier nowMs) {
        this.nowMs = nowMs == null ? System::currentTimeMillis : nowMs;
    }

    public synchronized void recordIntentRun(String intentId, Map<String, String> slots) {
        if (intentId == null || intentId.trim().length() == 0) {
            return;
        }
        lastIntentId = intentId;
        lastSlots = Collections.unmodifiableMap(new HashMap<String, String>(
                slots == null ? Collections.<String, String>emptyMap() : slots));
        lastUpdated = nowMs.getAsLong();
    }

    public synchronized void recordRoiSelection(int index) {
        lastRoiIndex = index;
        lastUpdated = nowMs.getAsLong();
    }

    public synchronized boolean isStale() {
        return lastUpdated == 0L || nowMs.getAsLong() - lastUpdated > IDLE_SUNSET_MS;
    }

    public synchronized Optional<String> lastIntentId() {
        return isStale() ? Optional.<String>empty() : Optional.ofNullable(lastIntentId);
    }

    public synchronized Optional<Map<String, String>> lastSlots() {
        return isStale()
                ? Optional.<Map<String, String>>empty()
                : Optional.of(lastSlots);
    }

    public synchronized Optional<Integer> lastRoiIndex() {
        return isStale() ? Optional.<Integer>empty() : Optional.ofNullable(lastRoiIndex);
    }

    public synchronized void clear() {
        lastIntentId = null;
        lastSlots = Collections.emptyMap();
        lastRoiIndex = null;
        lastUpdated = 0L;
    }
}
