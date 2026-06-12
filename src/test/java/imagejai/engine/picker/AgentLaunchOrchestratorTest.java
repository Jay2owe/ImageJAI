package imagejai.engine.picker;

import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;
import imagejai.engine.ExternalAgentSession;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class AgentLaunchOrchestratorTest {

    @Test
    public void cliProviderRoutesToCli() {
        assertEquals(AgentLaunchOrchestrator.Transport.CLI,
                AgentLaunchOrchestrator.transportFor("cli"));
    }

    @Test
    public void anthropicAndGeminiTakeNativePath() {
        assertEquals(AgentLaunchOrchestrator.Transport.NATIVE,
                AgentLaunchOrchestrator.transportFor("anthropic"));
        assertEquals(AgentLaunchOrchestrator.Transport.NATIVE,
                AgentLaunchOrchestrator.transportFor("gemini"));
    }

    @Test
    public void everyOtherProviderTakesProxyPath() {
        String[] proxyProviders = {
                "openai", "groq", "cerebras", "openrouter", "github-models",
                "mistral", "ollama", "ollama-cloud", "together", "huggingface",
                "deepseek", "xai", "perplexity"
        };
        for (String key : proxyProviders) {
            assertEquals("expected proxy transport for " + key,
                    AgentLaunchOrchestrator.Transport.PROXY,
                    AgentLaunchOrchestrator.transportFor(key));
        }
    }

    @Test
    public void nullProviderDefaultsToProxy() {
        assertEquals(AgentLaunchOrchestrator.Transport.PROXY,
                AgentLaunchOrchestrator.transportFor((String) null));
    }

    @Test
    public void modelEntryRoutesByProviderId() {
        ModelEntry anthropic = new ModelEntry("anthropic", "claude-sonnet-4-6",
                "Claude Sonnet 4.6", "", ModelEntry.Tier.PAID, 1_000_000,
                true, ModelEntry.Reliability.HIGH, false, true, "");
        assertEquals(AgentLaunchOrchestrator.Transport.NATIVE,
                AgentLaunchOrchestrator.transportFor(anthropic));

        ModelEntry ollama = new ModelEntry("ollama", "llama3.2:3b",
                "Llama 3.2 3B", "", ModelEntry.Tier.FREE, 128_000,
                false, ModelEntry.Reliability.MEDIUM, false, true, "");
        assertEquals(AgentLaunchOrchestrator.Transport.PROXY,
                AgentLaunchOrchestrator.transportFor(ollama));
    }

    @Test
    public void ollamaCloudProviderUsesBundledOllamaWrapperWithoutCliDetection() {
        RecordingLauncher cli = new RecordingLauncher(Collections.<AgentLauncher.AgentInfo>emptyList());
        RecordingProxyLauncher proxy = new RecordingProxyLauncher();
        AgentLaunchOrchestrator orchestrator =
                new AgentLaunchOrchestrator(cli, null, proxy);

        AgentSession session = orchestrator.launch(
                entry(AgentLaunchOrchestrator.GEMMA_CLOUD_PROVIDER,
                        AgentLaunchOrchestrator.GEMMA_CLOUD_MODEL),
                AgentLauncher.Mode.EXTERNAL);

        assertNotNull(session);
        assertEquals(AgentLaunchOrchestrator.OLLAMA_WRAPPER_AGENT_COMMAND,
                cli.launched.command);
        assertEquals("--provider ollama-cloud --model " + AgentLaunchOrchestrator.GEMMA_CLOUD_MODEL,
                cli.launched.contextFlags);
        assertEquals(0, proxy.launchCount);
    }

    @Test
    public void allOllamaCloudModelsUseOllamaWrapper() {
        RecordingLauncher cli = new RecordingLauncher(Collections.<AgentLauncher.AgentInfo>emptyList());
        RecordingProxyLauncher proxy = new RecordingProxyLauncher();
        AgentLaunchOrchestrator orchestrator =
                new AgentLaunchOrchestrator(cli, null, proxy);

        AgentSession session = orchestrator.launch(
                entry(AgentLaunchOrchestrator.GEMMA_CLOUD_PROVIDER,
                        "qwen3-coder:480b-cloud"),
                AgentLauncher.Mode.EXTERNAL);

        assertNotNull(session);
        assertEquals(AgentLaunchOrchestrator.OLLAMA_WRAPPER_AGENT_COMMAND,
                cli.launched.command);
        assertEquals("--provider ollama-cloud --model qwen3-coder:480b-cloud", cli.launched.contextFlags);
        assertEquals(0, proxy.launchCount);
    }

    private static ModelEntry entry(String provider, String modelId) {
        return new ModelEntry(provider, modelId, provider + " " + modelId, "",
                ModelEntry.Tier.FREE, 0, false, ModelEntry.Reliability.HIGH,
                false, true, "");
    }

    private static AgentLauncher.AgentInfo agent(String name, String command) {
        return new AgentLauncher.AgentInfo(name, command, "", command, "");
    }

    private static final class RecordingLauncher extends AgentLauncher {
        private final List<AgentLauncher.AgentInfo> agents;
        private AgentLauncher.AgentInfo launched;

        RecordingLauncher(List<AgentLauncher.AgentInfo> agents) {
            super(".", 7746);
            this.agents = agents;
        }

        @Override
        public List<AgentLauncher.AgentInfo> detectAgents() {
            return agents;
        }

        @Override
        public AgentSession launch(AgentLauncher.AgentInfo agent, Mode mode) {
            launched = agent;
            return new ExternalAgentSession(agent, true);
        }
    }

    private static final class RecordingProxyLauncher extends ProxyAgentLauncher {
        private int launchCount;

        @Override
        public AgentSession launch(ModelEntry entry, AgentLauncher.Mode mode,
                                   java.util.Map<String, String> commonEnv) {
            launchCount++;
            return new ExternalAgentSession(
                    new AgentLauncher.AgentInfo("Proxy", "proxy", "", "proxy", ""),
                    true);
        }
    }
}
