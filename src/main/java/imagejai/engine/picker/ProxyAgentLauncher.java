package imagejai.engine.picker;

import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;

/**
 * LiteLLM proxy transport. Launches the Python provider agent loop
 * ({@code agent.providers.agent_cli}) in a terminal, pointed at the chosen
 * provider/model. The loop routes through the LiteLLM proxy at
 * {@code localhost:4000} via {@code agent.providers.router.get_client} and
 * controls Fiji over TCP.
 *
 * <p>Constructed with the shared {@link AgentLauncher} so it reuses the
 * embedded-PTY / external-terminal machinery. The no-arg constructor leaves it
 * unwired (launch returns {@code null}) — used only where no launcher is
 * available, such as unit tests of the orchestrator's routing.
 */
public class ProxyAgentLauncher {

    private final AgentLauncher cliLauncher;

    public ProxyAgentLauncher() {
        this(null);
    }

    public ProxyAgentLauncher(AgentLauncher cliLauncher) {
        this.cliLauncher = cliLauncher;
    }

    public AgentSession launch(ModelEntry entry, AgentLauncher.Mode mode) {
        return launch(entry, mode, null);
    }

    public AgentSession launch(ModelEntry entry, AgentLauncher.Mode mode,
                               java.util.Map<String, String> commonEnv) {
        return ProviderAgentLaunch.launch(cliLauncher, entry, mode, commonEnv);
    }
}
