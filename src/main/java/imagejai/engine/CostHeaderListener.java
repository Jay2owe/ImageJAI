package imagejai.engine;

/**
 * Receives LiteLLM response-cost header values emitted by the proxy sidecar.
 *
 * Phase H's budget ceiling subscribes here. Phase A only exposes the hook and
 * forwards values observed in the sidecar log stream.
 */
public interface CostHeaderListener {
    void onCostHeader(String costHeaderValue);
}
