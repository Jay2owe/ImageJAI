package imagejai.engine.picker;

import ij.IJ;
import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;

/**
 * Skeleton for the native Anthropic / Gemini transport. Phase C brings the
 * caching + server-side tool features online; Phase D leaves this dormant so
 * the dropdown can route to it once the dependency is satisfied.
 */
public class NativeAgentLauncher {

    public AgentSession launch(ModelEntry entry, AgentLauncher.Mode mode) {
        IJ.log("[NativeAgentLauncher] Native transport not yet wired — " + entry
                + " (mode=" + mode + ")");
        return null;
    }
}
