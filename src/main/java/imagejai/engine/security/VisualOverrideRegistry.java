package imagejai.engine.security;

import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import imagejai.config.PrivacyPosture;
import imagejai.engine.PostureController;

import java.time.Instant;
import java.util.Collections;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot visual consent tracker. Requests and grants are session-scoped and
 * expire quickly; a consumed grant is removed before the capture is returned.
 */
public final class VisualOverrideRegistry {
    private static final VisualOverrideRegistry INSTANCE = new VisualOverrideRegistry();
    private static final Duration TTL = Duration.ofSeconds(60);

    private final ConcurrentHashMap<String, PendingRequest> requests =
            new ConcurrentHashMap<String, PendingRequest>();
    private final ConcurrentHashMap<String, Grant> grants =
            new ConcurrentHashMap<String, Grant>();

    public static VisualOverrideRegistry getInstance() {
        return INSTANCE;
    }

    public PendingRequest request(String sessionId, String reason) {
        String session = normaliseSession(sessionId);
        PendingRequest request = new PendingRequest(
                UUID.randomUUID().toString(), session, safe(reason), Instant.now());
        requests.put(session, request);
        return request;
    }

    public void grant(String sessionId, String reason) {
        String session = normaliseSession(sessionId);
        requests.remove(session);
        grants.put(session, new Grant(session, safe(reason), Instant.now()));
        auditVisual("visual.granted", session, reason, "");
    }

    public void deny(String sessionId) {
        String session = normaliseSession(sessionId);
        PendingRequest request = requests.remove(session);
        grants.remove(session);
        auditVisual("visual.denied", session, request == null ? "" : request.reason, "");
    }

    public Optional<PendingRequest> pending(String sessionId) {
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
        Grant grant = grants.get(normaliseSession(sessionId));
        if (grant == null) {
            return false;
        }
        if (expired(grant.createdAt)) {
            grants.remove(grant.sessionId, grant);
            return false;
        }
        return true;
    }

    public boolean consumeIfPresent(String sessionId) {
        String session = normaliseSession(sessionId);
        Grant grant = grants.remove(session);
        boolean consumed = grant != null && !expired(grant.createdAt);
        if (consumed) {
            auditVisual("visual.consumed", session, grant.reason, currentImageToken());
        }
        return consumed;
    }

    public void clear() {
        requests.clear();
        grants.clear();
    }

    private static boolean expired(Instant createdAt) {
        return createdAt == null || Duration.between(createdAt, Instant.now()).compareTo(TTL) > 0;
    }

    private static String normaliseSession(String sessionId) {
        return sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId.trim();
    }

    private static String safe(String reason) {
        return reason == null ? "" : reason;
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

    private static String currentImageToken() {
        try {
            ImagePlus image = WindowManager.getCurrentImage();
            if (image == null) {
                return "";
            }
            FileInfo fileInfo = image.getOriginalFileInfo();
            if (fileInfo != null && fileInfo.directory != null
                    && fileInfo.fileName != null) {
                return PathTokenMap.getInstance().tokenForPathString(
                        fileInfo.directory + fileInfo.fileName);
            }
            return PathTokenMap.getInstance().tokenForSensitiveText(
                    image.getTitle() == null ? "" : image.getTitle(), "image");
        } catch (Throwable t) {
            return "";
        }
    }

    public static final class PendingRequest {
        public final String requestId;
        public final String sessionId;
        public final String reason;
        public final Instant createdAt;

        private PendingRequest(String requestId, String sessionId, String reason,
                               Instant createdAt) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.reason = reason;
            this.createdAt = createdAt;
        }
    }

    private static final class Grant {
        final String sessionId;
        final String reason;
        final Instant createdAt;

        Grant(String sessionId, String reason, Instant createdAt) {
            this.sessionId = sessionId;
            this.reason = reason;
            this.createdAt = createdAt;
        }
    }
}
