package imagejai.engine.security;

import ij.ImagePlus;
import ij.WindowManager;
import imagejai.config.PrivacyPosture;
import imagejai.engine.ImageGraph;
import imagejai.engine.PostureController;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * One-shot visual consent tracker. Requests and grants are session-scoped and
 * expire quickly; a consumed grant is removed before the capture is returned.
 */
public final class VisualOverrideRegistry {
    private static final VisualOverrideRegistry INSTANCE = new VisualOverrideRegistry();
    private static final Duration TTL = Duration.ofSeconds(60);
    public static final int MAX_PENDING_REQUESTS = 128;
    public static final int MAX_GRANTS = 128;
    public static final int MAX_SESSION_CHARS = 256;
    public static final int MAX_REASON_CHARS = 1024;
    public static final int MAX_IMAGE_TOKEN_CHARS = 512;
    public static final int MAX_REQUEST_ID_CHARS = 64;
    private static final String NO_IMAGE_SCOPE = "no-active-image";
    private static final SecureRandom RNG = new SecureRandom();

    private final ConcurrentHashMap<String, PendingRequest> requests =
            new ConcurrentHashMap<String, PendingRequest>();
    private final ConcurrentHashMap<String, Grant> grants =
            new ConcurrentHashMap<String, Grant>();
    private final AtomicLong rejectedEntries = new AtomicLong(0L);
    private final Supplier<ImagePlus> activeImageSupplier;

    public VisualOverrideRegistry() {
        this(WindowManager::getCurrentImage);
    }

    VisualOverrideRegistry(Supplier<ImagePlus> activeImageSupplier) {
        this.activeImageSupplier = activeImageSupplier == null
                ? WindowManager::getCurrentImage : activeImageSupplier;
    }

    public static VisualOverrideRegistry getInstance() {
        return INSTANCE;
    }

    public PendingRequest request(String sessionId, String reason) {
        return request(sessionId, reason, currentImageToken());
    }

    public synchronized PendingRequest request(String sessionId, String reason,
                                               String imageToken) {
        String session = normaliseSession(sessionId);
        purgeExpired();
        if (!requests.containsKey(session)
                && requests.size() >= MAX_PENDING_REQUESTS) {
            rejectedEntries.incrementAndGet();
            throw new IllegalStateException("Visual request capacity reached (max "
                    + MAX_PENDING_REQUESTS + ")");
        }
        grants.remove(session);
        PendingRequest request = new PendingRequest(
                newRequestId(), session,
                bounded(reason, MAX_REASON_CHARS, "reason").trim(),
                normaliseImageToken(imageToken),
                Instant.now());
        requests.put(session, request);
        return request;
    }

    /** Grant only the exact live request that was displayed to the user. */
    public synchronized boolean grant(String sessionId, String requestId,
                                      String reason) {
        String session = normaliseSession(sessionId);
        purgeExpired();
        PendingRequest pending = requests.get(session);
        if (pending == null || !constantTimeEquals(pending.requestId,
                bounded(requestId, MAX_REQUEST_ID_CHARS, "request id"))) {
            return false;
        }
        if (!constantTimeEquals(pending.reason,
                bounded(reason, MAX_REASON_CHARS, "reason").trim())) {
            return false;
        }
        if (!grants.containsKey(session) && grants.size() >= MAX_GRANTS) {
            rejectedEntries.incrementAndGet();
            return false;
        }
        requests.remove(session, pending);
        grants.put(session, new Grant(session, pending.requestId,
                pending.imageToken, pending.reason, Instant.now()));
        auditVisual("visual.granted", session, pending.reason, pending.imageToken);
        return true;
    }

    /**
     * Compatibility entry point for the existing notice. It still requires a
     * live request and an exact reason match, and grants that request atomically.
     */
    public synchronized boolean grant(String sessionId, String reason) {
        String session = normaliseSession(sessionId);
        purgeExpired();
        PendingRequest pending = requests.get(session);
        if (pending == null || !constantTimeEquals(pending.reason, safe(reason))) {
            return false;
        }
        return grant(session, pending.requestId, reason);
    }

    public synchronized void deny(String sessionId) {
        String session = normaliseSession(sessionId);
        PendingRequest request = requests.remove(session);
        grants.remove(session);
        auditVisual("visual.denied", session, request == null ? "" : request.reason, "");
    }

    public synchronized Optional<PendingRequest> pending(String sessionId) {
        PendingRequest request = requests.get(normaliseSession(sessionId));
        if (request == null || expired(request.createdAt)) {
            if (request != null) {
                requests.remove(request.sessionId, request);
            }
            return Optional.empty();
        }
        return Optional.of(request);
    }

    public boolean hasGrant(String sessionId) {
        return hasGrant(sessionId, currentImageToken());
    }

    public synchronized boolean hasGrant(String sessionId, String imageToken) {
        Grant grant = grants.get(normaliseSession(sessionId));
        if (grant == null) {
            return false;
        }
        if (expired(grant.createdAt)) {
            grants.remove(grant.sessionId, grant);
            return false;
        }
        return constantTimeEquals(grant.imageToken, normaliseImageToken(imageToken));
    }

    public boolean consumeIfPresent(String sessionId) {
        return consumeIfPresent(sessionId, currentImageToken());
    }

    public synchronized boolean consumeIfPresent(String sessionId, String imageToken) {
        String session = normaliseSession(sessionId);
        Grant grant = grants.get(session);
        boolean consumed = grant != null && !expired(grant.createdAt)
                && constantTimeEquals(grant.imageToken,
                        normaliseImageToken(imageToken));
        if (consumed) {
            grants.remove(session, grant);
            auditVisual("visual.consumed", session, grant.reason, grant.imageToken);
        } else if (grant != null && expired(grant.createdAt)) {
            grants.remove(session, grant);
        }
        return consumed;
    }

    public synchronized void clear() {
        requests.clear();
        grants.clear();
    }

    public synchronized int pendingCount() {
        purgeExpired();
        return requests.size();
    }

    public synchronized int grantCount() {
        purgeExpired();
        return grants.size();
    }

    public long rejectedEntryCount() {
        return rejectedEntries.get();
    }

    private void purgeExpired() {
        for (PendingRequest request : requests.values()) {
            if (expired(request.createdAt)) {
                requests.remove(request.sessionId, request);
            }
        }
        for (Grant grant : grants.values()) {
            if (expired(grant.createdAt)) {
                grants.remove(grant.sessionId, grant);
            }
        }
    }

    private static String newRequestId() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(safe(left).getBytes(StandardCharsets.UTF_8),
                safe(right).getBytes(StandardCharsets.UTF_8));
    }

    private static String normaliseImageToken(String imageToken) {
        String clean = bounded(imageToken, MAX_IMAGE_TOKEN_CHARS, "image token").trim();
        return clean.isEmpty() ? NO_IMAGE_SCOPE : clean;
    }

    private static boolean expired(Instant createdAt) {
        return createdAt == null || Duration.between(createdAt, Instant.now()).compareTo(TTL) > 0;
    }

    private static String normaliseSession(String sessionId) {
        String clean = bounded(sessionId, MAX_SESSION_CHARS, "session").trim();
        return clean.isEmpty() ? "default" : clean;
    }

    private static String safe(String reason) {
        return reason == null ? "" : reason;
    }

    private static String bounded(String value, int maxChars, String label) {
        String clean = safe(value);
        if (clean.length() > maxChars) {
            throw new IllegalArgumentException(label + " exceeds "
                    + maxChars + " characters");
        }
        return clean;
    }

    private static void auditVisual(String command, String sessionId,
                                    String reason, String imageToken) {
        try {
            PrivacyPosture posture = PostureController.getInstance().current();
            AuditLog.getInstance().append(new AuditRow(
                    Instant.now(),
                    normaliseSession(sessionId),
                    command,
                    posture,
                    "",
                    "",
                    0,
                    0,
                    "",
                    false,
                    Collections.<String>emptyList(),
                    visualNotes(reason, imageToken)));
        } catch (Throwable ignore) {
        }
    }

    private static String visualNotes(String reason, String imageToken) {
        StringBuilder notes = new StringBuilder();
        String scrubbedReason = PseudonymisationFilter.getInstance()
                .freeTextScrubString(reason == null ? "" : reason)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        notes.append("reason='")
                .append(scrubbedReason.isEmpty() ? "not specified" : scrubbedReason)
                .append('\'');
        if (imageToken != null && !imageToken.trim().isEmpty()) {
            notes.append(' ');
            notes.append("on=").append(imageToken.trim());
        }
        return notes.toString();
    }

    private String currentImageToken() {
        try {
            ImagePlus image = activeImageSupplier.get();
            String identity = ImageGraph.stableIdentity(image);
            return identity == null ? "" : identity;
        } catch (Throwable t) {
            // Fail closed: an unavailable live identity must not match any image.
            return "image-scope-unavailable-" + newRequestId();
        }
    }

    public static final class PendingRequest {
        public final String requestId;
        public final String sessionId;
        public final String reason;
        public final String imageToken;
        public final Instant createdAt;

        private PendingRequest(String requestId, String sessionId, String reason,
                               String imageToken, Instant createdAt) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.reason = reason;
            this.imageToken = imageToken;
            this.createdAt = createdAt;
        }
    }

    private static final class Grant {
        final String sessionId;
        final String requestId;
        final String imageToken;
        final String reason;
        final Instant createdAt;

        Grant(String sessionId, String requestId, String imageToken,
              String reason, Instant createdAt) {
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.imageToken = imageToken;
            this.reason = reason;
            this.createdAt = createdAt;
        }
    }
}
