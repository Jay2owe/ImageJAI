package imagejai.terminal;

import imagejai.engine.AgentLauncher;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AgentRegistryTest {

    @Test
    public void bundledGemmaModuleKeepsGemmaAgentId() {
        AgentLauncher.AgentInfo info = new AgentLauncher.AgentInfo(
                "Gemma 4 31B",
                "python -m gemma4_31b",
                "",
                null,
                "",
                true,
                "gemma4:31b-cloud");

        assertEquals("gemma4_31b", AgentRegistry.agentId(info));
    }

    @Test
    public void imagejaiAgentAliasKeepsGemmaAgentId() {
        AgentLauncher.AgentInfo info = new AgentLauncher.AgentInfo(
                "ImageJAI Agent",
                "imagejai_agent",
                "",
                null,
                "",
                true,
                "gemma4:31b-cloud");

        assertEquals("gemma4_31b", AgentRegistry.agentId(info));
    }

    @Test
    public void providerAgentCliUsesProviderAgentId() {
        AgentLauncher.AgentInfo info = new AgentLauncher.AgentInfo(
                "Claude Opus 4.7",
                "python -m agent.providers.agent_cli --provider anthropic --model claude-opus-4-7",
                "",
                null,
                "",
                false,
                "");

        assertEquals("provider_anthropic", AgentRegistry.agentId(info));
    }
}
