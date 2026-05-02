package imagejai.engine.picker;

import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;

/**
 * Decides which transport launches a (provider, model) pick from the cascading
 * dropdown. Phase D wiring per docs/multi_provider/05_ui_design.md §6.1:
 *
 * <ul>
 *   <li>{@code anthropic} / {@code gemini} → {@link NativeAgentLauncher} —
 *       native SDK path with prompt caching, server-side tools.</li>
 *   <li>Synthetic {@code cli} → existing {@link AgentLauncher#launch} —
 *       spawn the user's installed CLI agent.</li>
 *   <li>Everything else → {@link ProxyAgentLauncher} — LiteLLM proxy at
 *       {@code localhost:4000}.</li>
 * </ul>
 *
 * <p>The native and proxy launchers ship as skeletons in Phase D — the
 * cascading dropdown can route to them but they will throw
 * {@link UnsupportedOperationException} until Phases B/C/E bring the call
 * sites online.
 */
public final class AgentLaunchOrchestrator {

    /** Logical transport for a model row. */
    public enum Transport {
        CLI,
        NATIVE,
        PROXY
    }

    private final AgentLauncher cliLauncher;
    private final NativeAgentLauncher nativeLauncher;
    private final ProxyAgentLauncher proxyLauncher;

    public AgentLaunchOrchestrator(AgentLauncher cliLauncher,
                                    NativeAgentLauncher nativeLauncher,
                                    ProxyAgentLauncher proxyLauncher) {
        this.cliLauncher = cliLauncher;
        this.nativeLauncher = nativeLauncher == null
                ? new NativeAgentLauncher()
                : nativeLauncher;
        this.proxyLauncher = proxyLauncher == null
                ? new ProxyAgentLauncher()
                : proxyLauncher;
    }

    public static Transport transportFor(ModelEntry entry) {
        if (entry == null) {
            return Transport.PROXY;
        }
        return transportFor(entry.providerId());
    }

    public static Transport transportFor(String providerId) {
        if (providerId == null) {
            return Transport.PROXY;
        }
        String key = providerId.trim().toLowerCase();
        if ("cli".equals(key)) {
            return Transport.CLI;
        }
        if ("anthropic".equals(key) || "gemini".equals(key)) {
            return Transport.NATIVE;
        }
        return Transport.PROXY;
    }

    /**
     * Launch the chosen model. Returns a session handle for CLI launches
     * (matching {@link AgentLauncher#launch}); native and proxy paths return
     * {@code null} from their skeletons until later phases wire them.
     */
    public AgentSession launch(ModelEntry entry, AgentLauncher.Mode mode) {
        Transport transport = transportFor(entry);
        switch (transport) {
            case CLI:
                if (cliLauncher == null) {
                    return null;
                }
                AgentLauncher.AgentInfo cliAgent = resolveCliAgent(entry.modelId());
                if (cliAgent == null) {
                    return null;
                }
                return cliLauncher.launch(cliAgent, mode);
            case NATIVE:
                return nativeLauncher.launch(entry, mode);
            case PROXY:
            default:
                return proxyLauncher.launch(entry, mode);
        }
    }

    private AgentLauncher.AgentInfo resolveCliAgent(String modelId) {
        if (cliLauncher == null || modelId == null) {
            return null;
        }
        for (AgentLauncher.AgentInfo info : cliLauncher.detectAgents()) {
            if (modelId.equals(info.command)) {
                return info;
            }
        }
        return null;
    }
}
