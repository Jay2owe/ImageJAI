package imagejai.engine.picker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
