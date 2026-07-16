package imagejai.engine.picker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collections;

import org.junit.Test;

/**
 * Unit tests for the provider-agent launch plan — the command + environment the
 * proxy/native launchers hand to {@code AgentLauncher}. Side-effect-free, so no
 * terminal is spawned.
 */
public class ProviderAgentLaunchTest {

    private static ModelEntry entry(String provider, String modelId) {
        return new ModelEntry(provider, modelId, provider + " " + modelId, "",
                ModelEntry.Tier.FREE, 0, false, ModelEntry.Reliability.HIGH,
                false, true, "");
    }

    @Test
    public void planBuildsPythonModuleCommand() {
        ProviderAgentLaunch.Plan plan = ProviderAgentLaunch.plan(
                "/home/me/agent", entry("groq", "llama-3.3-70b-versatile"), null);
        assertNotNull(plan);
        assertTrue(plan.info.command.contains("-m agent.providers.agent_cli"));
        assertTrue(plan.info.command.contains("--provider groq"));
        assertTrue(plan.info.command.contains("--model llama-3.3-70b-versatile"));
    }

    @Test
    public void planQuotesConfiguredWindowsPythonExecutableWithSpaces() {
        String python = "C:\\Program Files\\ImageJAI Python\\python.exe";
        ProviderAgentLaunch.Plan plan = ProviderAgentLaunch.plan(
                "C:\\work\\agent", entry("groq", "model"), null,
                python, "Windows 11");

        assertNotNull(plan);
        assertEquals("\"" + python + "\" -m agent.providers.agent_cli"
                        + " --provider groq --model model",
                plan.info.command);
        // AgentLauncher probes/executes the executable as an argv element in
        // non-shell paths, so this field must remain unquoted.
        assertEquals(python, plan.info.executablePath);
    }

    @Test
    public void planSetsProviderModelAndPythonpath() {
        ProviderAgentLaunch.Plan plan = ProviderAgentLaunch.plan(
                "/home/me/agent", entry("anthropic", "claude-sonnet-4-6"), null);
        assertNotNull(plan);
        assertEquals("anthropic", plan.env.get("IMAGEJAI_PROVIDER"));
        assertEquals("claude-sonnet-4-6", plan.env.get("IMAGEJAI_MODEL"));
        String pp = plan.env.get("PYTHONPATH");
        assertNotNull(pp);
        // Parent of the agent/ workspace must be on PYTHONPATH so
        // `python -m agent.providers.agent_cli` resolves.
        assertTrue(pp.contains("home" + File.separator + "me")
                || pp.contains("/home/me") || pp.contains("home"));
    }

    @Test
    public void planMergesNativeFeatureEnv() {
        ProviderAgentLaunch.Plan plan = ProviderAgentLaunch.plan(
                "/ws/agent", entry("gemini", "gemini-2.5-pro"),
                Collections.singletonMap("IMAGEJAI_NATIVE_GOOGLE_SEARCH", "true"));
        assertNotNull(plan);
        assertEquals("true", plan.env.get("IMAGEJAI_NATIVE_GOOGLE_SEARCH"));
    }

    @Test
    public void planCarriesOllamaModelTagForPostureRefusal() {
        ProviderAgentLaunch.Plan plan = ProviderAgentLaunch.plan(
                "/ws/agent", entry("ollama-cloud", "gemma4:31b-cloud"), null);
        assertNotNull(plan);
        // isOllama() must be true and the tag must surface so AgentLauncher's
        // on-premises cloud-tag refusal can still fire.
        assertTrue(plan.info.isOllama());
        assertEquals("gemma4:31b-cloud", plan.info.defaultOllamaModel());
    }

    @Test
    public void localHostCodeGrantIsExplicitAndClassifiedDangerous() {
        java.util.Map<String, String> env = Collections.singletonMap(
                ProviderAgentLaunch.LOCAL_HOST_CODE_ENV, "true");
        ProviderAgentLaunch.Plan plan = ProviderAgentLaunch.plan(
                "/ws/agent", entry("ollama", "gemma3:27b"), env);

        assertNotNull(plan);
        assertTrue(plan.info.command.contains("--allow-local-host-code"));
        assertTrue(plan.dangerousPermissions);
        assertEquals("true", plan.env.get(
                ProviderAgentLaunch.LOCAL_HOST_CODE_ENV));
    }

    @Test
    public void localHostCodeGrantDefaultsOffAndRejectsCloudOrTypos() {
        ProviderAgentLaunch.Plan defaultPlan = ProviderAgentLaunch.plan(
                "/ws/agent", entry("ollama", "gemma3:27b"), null);
        assertFalse(defaultPlan.info.command.contains("--allow-local-host-code"));
        assertFalse(defaultPlan.dangerousPermissions);

        assertHostCodePlanRejected("ollama-cloud", "true");
        assertHostCodePlanRejected("groq", "true");
        assertHostCodePlanRejected("ollama", "treu");

        java.util.Map<String, String> remote = new java.util.LinkedHashMap<>();
        remote.put(ProviderAgentLaunch.LOCAL_HOST_CODE_ENV, "true");
        remote.put("OLLAMA_HOST", "https://ollama.example.invalid:11434");
        try {
            ProviderAgentLaunch.plan(
                    "/ws/agent", entry("ollama", "model"), remote);
            org.junit.Assert.fail("remote Ollama host must not receive host code");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static void assertHostCodePlanRejected(String provider, String value) {
        try {
            ProviderAgentLaunch.plan("/ws/agent", entry(provider, "model"),
                    Collections.singletonMap(
                            ProviderAgentLaunch.LOCAL_HOST_CODE_ENV, value));
            org.junit.Assert.fail("unsafe host-code grant must be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void planRejectsBlankWorkspaceOrEntry() {
        assertNull(ProviderAgentLaunch.plan("", entry("groq", "x"), null));
        assertNull(ProviderAgentLaunch.plan("/ws/agent", null, null));
    }

    @Test
    public void launchReturnsNullWithoutCliLauncher() {
        // Both transports degrade gracefully (no NPE) when unwired.
        assertNull(new ProxyAgentLauncher().launch(
                entry("groq", "x"), imagejai.engine.AgentLauncher.Mode.EXTERNAL));
        assertNull(new NativeAgentLauncher().launch(
                entry("anthropic", "y"), imagejai.engine.AgentLauncher.Mode.EXTERNAL));
    }
}
