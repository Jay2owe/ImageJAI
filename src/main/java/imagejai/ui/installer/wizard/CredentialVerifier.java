package imagejai.ui.installer.wizard;

import javax.swing.SwingWorker;

/**
 * Validate-and-persist step run by the install-shape wizards, per Phase E
 * acceptance:
 *
 * <ol>
 *   <li>The candidate key is tested in a background worker without saving it.</li>
 *   <li>Only an accepted candidate is saved to
 *       {@code <config>/secrets/<provider>.env}.</li>
 *   <li>On {@link Result#ok success}, the dialog disposes and the card flips
 *       to its connected state.</li>
 *   <li>On failure, the wizard surfaces the {@link Result#message} as an
 *       inline red error and the dialog stays open for a retry.</li>
 * </ol>
 *
 * <p>The default no-op implementation ({@link #noop()}) is used when no
 * verifier is wired — keeps tests deterministic and lets headless builds
 * skip network calls. Production wiring lives in the dropdown / picker side
 * (Phase D / G) where the {@link imagejai.engine.picker.ProviderRegistry}
 * already knows how to fetch a provider's models.
 */
public interface CredentialVerifier {

    /** Outcome of a single verify attempt. */
    final class Result {
        public final boolean ok;
        public final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }

        public static Result success(String message) {
            return new Result(true, message);
        }

        public static Result failure(String message) {
            return new Result(false, message);
        }
    }

    /**
     * Verify that the saved credential for {@code providerKey} actually works.
     * Implementations must respect {@code timeoutMs}; callers should still run
     * network-backed implementations away from the Swing event thread.
     */
    Result verify(String providerKey, int timeoutMs);

    /** Validate a candidate before it reaches persistent storage. */
    default Result verifyCandidate(String providerKey, String candidate, int timeoutMs) {
        return verify(providerKey, timeoutMs);
    }

    /** No-op verifier — returns success without any network call. */
    static CredentialVerifier noop() {
        return (providerKey, timeoutMs) ->
                Result.success("(verification skipped — wire a CredentialVerifier in production)");
    }

    interface Persister {
        void persist(String providerKey, String candidate) throws Exception;
    }

    interface Completion {
        void complete(Result result);
    }

    /** Off-EDT validate-then-persist transaction used by credential wizards. */
    final class ValidationWorker extends SwingWorker<Result, Void> {
        private final String providerKey;
        private final String candidate;
        private final int timeoutMs;
        private final CredentialVerifier verifier;
        private final Persister persister;
        private final Completion completion;

        public ValidationWorker(String providerKey, String candidate, int timeoutMs,
                                CredentialVerifier verifier, Persister persister,
                                Completion completion) {
            this.providerKey = providerKey;
            this.candidate = candidate == null ? "" : candidate;
            this.timeoutMs = timeoutMs;
            this.verifier = verifier == null ? CredentialVerifier.noop() : verifier;
            this.persister = persister;
            this.completion = completion;
        }

        @Override protected Result doInBackground() {
            Result result;
            try {
                result = verifier.verifyCandidate(providerKey, candidate, timeoutMs);
            } catch (Throwable failure) {
                result = Result.failure("validation failed ("
                        + failure.getClass().getSimpleName() + ")");
            }
            if (result == null) result = Result.failure("validator returned no result");
            result = new Result(result.ok, safeMessage(result.message, candidate));
            if (!result.ok || isCancelled()) return result;
            if (persister == null) return Result.failure("credential store unavailable");
            try {
                if (isCancelled()) return Result.failure("validation cancelled");
                persister.persist(providerKey, candidate);
                return result;
            } catch (Throwable failure) {
                return Result.failure("credential save failed ("
                        + failure.getClass().getSimpleName() + ")");
            }
        }

        @Override protected void done() {
            if (completion == null || isCancelled()) return;
            try {
                completion.complete(get());
            } catch (Exception failure) {
                completion.complete(Result.failure("validation worker failed ("
                        + failure.getClass().getSimpleName() + ")"));
            }
        }

        private static String safeMessage(String message, String candidate) {
            String safe = message == null ? "" : message;
            if (candidate != null && !candidate.isEmpty()) {
                safe = safe.replace(candidate, "[REDACTED]");
            }
            safe = safe.replace('\r', ' ').replace('\n', ' ');
            return safe.length() > 240 ? safe.substring(0, 240) : safe;
        }
    }
}
