package imagejai.engine.security;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-JVM queue of pseudonymised file-selection briefs keyed by agent session.
 */
public final class SelectionBroker {
    private static final SelectionBroker INSTANCE = new SelectionBroker();

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Brief>> queues =
            new ConcurrentHashMap<String, ConcurrentLinkedQueue<Brief>>();

    public static SelectionBroker getInstance() {
        return INSTANCE;
    }

    public void enqueue(Brief brief) {
        if (brief == null || brief.tokens().isEmpty()) {
            return;
        }
        String sessionId = Brief.normaliseSession(brief.sessionId());
        queues.computeIfAbsent(sessionId,
                k -> new ConcurrentLinkedQueue<Brief>()).add(brief);
    }

    public Optional<Brief> peek(String sessionId) {
        ConcurrentLinkedQueue<Brief> queue = queues.get(Brief.normaliseSession(sessionId));
        return queue == null ? Optional.empty() : Optional.ofNullable(queue.peek());
    }

    public Optional<Brief> consume(String sessionId) {
        String key = Brief.normaliseSession(sessionId);
        ConcurrentLinkedQueue<Brief> queue = queues.get(key);
        if (queue == null) {
            return Optional.empty();
        }
        Brief brief = queue.poll();
        if (queue.isEmpty()) {
            queues.remove(key, queue);
        }
        return Optional.ofNullable(brief);
    }

    public boolean hasPending(String sessionId) {
        return peek(sessionId).isPresent();
    }

    void clearForTest(String sessionId) {
        queues.remove(Brief.normaliseSession(sessionId));
    }
}
