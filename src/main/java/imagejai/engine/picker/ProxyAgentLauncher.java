package imagejai.engine.picker;

import ij.IJ;
import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;

/**
 * Skeleton for the LiteLLM proxy transport. Real implementation arrives once
 * Phases A (proxy bundling) and B (Python provider modules) wire the agent
 * loop to {@code localhost:4000}.
 *
 * <p>Phase D's cascading dropdown can call this; today it logs and returns
 * {@code null} so the launch UX visibly fails rather than silently no-ops.
 */
public class ProxyAgentLauncher {

    public AgentSession launch(ModelEntry entry, AgentLauncher.Mode mode) {
        IJ.log("[ProxyAgentLauncher] Proxy transport not yet wired — " + entry
                + " (mode=" + mode + ")");
        return null;
    }
}
