package imagejai.ui.installer.wizard;

/**
 * Refresh-and-test step run by the install-shape wizards after a credential
 * is persisted, per Phase E acceptance ({@code 07_implementation_plan.md §E}):
 *
 * <ol>
 *   <li>Wizard saves the key to {@code <config>/secrets/<provider>.env}.</li>
 *   <li>Wizard calls
 *       {@link #verify(String, int) verify(providerKey, 4000)} synchronously
 *       (the 4 s timeout is the {@code 06 §4.4} manual-refresh budget).</li>
 *   <li>On {@link Result#ok success}, the dialog disposes and the card flips
 *       to ✓.</li>
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
     * Implementations must respect {@code timeoutMs} so the EDT never blocks
     * longer than the {@code 06 §4.4} budget.
     */
    Result verify(String providerKey, int timeoutMs);

    /** No-op verifier — returns success without any network call. */
    static CredentialVerifier noop() {
        return (providerKey, timeoutMs) ->
                Result.success("(verification skipped — wire a CredentialVerifier in production)");
    }
}
