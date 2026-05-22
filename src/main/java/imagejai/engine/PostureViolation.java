package imagejai.engine;

/**
 * Raised when a launch would violate the current Data Governance posture.
 */
public final class PostureViolation extends RuntimeException {
    public PostureViolation(String message) {
        super(message);
    }
}
