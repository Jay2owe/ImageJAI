package imagejai.engine.picker;

import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;

import java.util.Map;
import java.util.function.Supplier;

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
 * <p>All three transports are live: CLI spawns the user's installed agent,
 * while native and proxy spawn the Python provider agent loop
 * ({@code agent.providers.agent_cli}) via {@link ProviderAgentLaunch}, reusing
 * the same terminal/{@link AgentSession} machinery as the CLI path.
 */
public final class AgentLaunchOrchestrator {

    static final String GEMMA_CLOUD_PROVIDER = "ollama-cloud";
    static final String GEMMA_CLOUD_MODEL = "gemma4:31b-cloud";
    static final String OLLAMA_WRAPPER_AGENT_COMMAND = AgentLauncher.GEMMA_WRAPPER_COMMAND;

    /** Logical transport for a model row. */
    public enum Transport {
        CLI,
        NATIVE,
        PROXY
    }

    private final AgentLauncher cliLauncher;
    private final NativeAgentLauncher nativeLauncher;
    private final ProxyAgentLauncher proxyLauncher;
    private final Supplier<Map<String, String>> launchEnvSupplier;

    public AgentLaunchOrchestrator(AgentLauncher cliLauncher,
                                    NativeAgentLauncher nativeLauncher,
                                    ProxyAgentLauncher proxyLauncher) {
        this(cliLauncher, nativeLauncher, proxyLauncher, null);
    }

    /**
     * @param launchEnvSupplier optional extra environment merged into every
     *        native/proxy provider-agent launch (evaluated at launch time).
     *        Used to inject the live LiteLLM proxy port
     *        ({@code IMAGEJAI_LITELLM_PORT}) and the active budget ceiling
     *        ({@code IMAGEJAI_BUDGET_CEILING_USD}) into the Python agent loop.
     */
    public AgentLaunchOrchestrator(AgentLauncher cliLauncher,
                                    NativeAgentLauncher nativeLauncher,
                                    ProxyAgentLauncher proxyLauncher,
                                    Supplier<Map<String, String>> launchEnvSupplier) {
        this.cliLauncher = cliLauncher;
        // Default launchers are wired with the CLI launcher so they can reuse
        // its terminal/AgentSession machinery to spawn the Python provider
        // agent loop (agent.providers.agent_cli).
        this.nativeLauncher = nativeLauncher == null
                ? new NativeAgentLauncher(cliLauncher)
                : nativeLauncher;
        this.proxyLauncher = proxyLauncher == null
                ? new ProxyAgentLauncher(cliLauncher)
                : proxyLauncher;
        this.launchEnvSupplier = launchEnvSupplier;
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
                return nativeLauncher.launch(entry, mode, commonLaunchEnv());
            case PROXY:
            default:
                if (isOllamaCloud(entry)) {
                    return launchOllamaCloudWrapper(entry, mode);
                }
                return proxyLauncher.launch(entry, mode, commonLaunchEnv());
        }
    }

    /**
     * Ollama Cloud rows use the bundled Ollama/Gemma wrapper, not the generic
     * LiteLLM provider loop. This keeps the animated terminal, Ollama-native
     * tool calling, and the per-agent terminal registries on the path users
     * already tested.
     */
    private AgentSession launchOllamaCloudWrapper(ModelEntry entry,
                                                  AgentLauncher.Mode mode) {
        if (entry == null || cliLauncher == null) {
            return null;
        }
        String model = entry.modelId() == null ? "" : entry.modelId().trim();
        String flags = "--provider " + entry.providerId();
        if (!model.isEmpty()) {
            flags += " --model " + model;
        }
        AgentLauncher.AgentInfo wrapper = new AgentLauncher.AgentInfo(
                entry.displayName(),
                OLLAMA_WRAPPER_AGENT_COMMAND,
                "Ollama-backed ImageJAI agent: " + entry.providerId()
                        + " / " + entry.modelId(),
                null,
                flags,
                true,
                model);
        return cliLauncher.launch(wrapper, mode);
    }

    private static boolean isOllamaCloud(ModelEntry entry) {
        return entry != null
                && GEMMA_CLOUD_PROVIDER.equals(entry.providerId());
    }

    /** Extra env from the supplier, or {@code null} when none/empty. */
    private Map<String, String> commonLaunchEnv() {
        if (launchEnvSupplier == null) {
            return null;
        }
        Map<String, String> env = launchEnvSupplier.get();
        return env == null || env.isEmpty() ? null : env;
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
