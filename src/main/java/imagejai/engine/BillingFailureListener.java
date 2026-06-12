package imagejai.engine;

/**
 * Receives upstream billing/auth failures (HTTP 401/402/429) observed by the
 * LiteLLM proxy sidecar.
 *
 * <p>The proxy's Python cost middleware emits a {@code [ImageJAI-LiteLLM-Billing]}
 * sentinel line on its stdout when a paid provider rejects a request for a
 * billing-class reason (no card on file, expired key, rate limit). The Java
 * {@link LiteLlmProxyService} log pump parses that line and forwards it here.
 * Phase H's {@code BillingFailureDialog} subscribes via {@link ImageJAIPlugin}.
 */
public interface BillingFailureListener {
    void onBillingFailure(String provider, int status, String message);
}
