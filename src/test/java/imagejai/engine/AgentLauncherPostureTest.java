package imagejai.engine;

import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentLauncherPostureTest {

    @Test
    public void onPremisesDetectionReturnsOnlyLocalAgents() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.ON_PREMISES);
        AgentLauncher launcher = launcherWithAllExecutables(settings);

        List<AgentLauncher.AgentInfo> agents = launcher.detectAgents();

        assertFalse("the bundled default is a cloud Ollama tag",
                names(agents).contains("Gemma 4 31B"));
        assertFalse(names(agents).contains("Claude Code"));
        assertFalse(names(agents).contains("Codex CLI"));
        for (AgentLauncher.AgentInfo agent : agents) {
            assertTrue(agent.name + " should be local", agent.isLocal());
        }
    }

    @Test
    public void standardAndPseudonymisedDetectionReturnAllAgents() {
        Settings settings = new Settings();
        AgentLauncher launcher = launcherWithAllExecutables(settings);

        settings.setPrivacyPosture(PrivacyPosture.STANDARD);
        List<String> standard = names(launcher.detectAgents());

        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        List<String> pseudonymised = names(launcher.detectAgents());

        assertEquals(standard, pseudonymised);
        assertTrue(standard.contains("Claude Code"));
        assertTrue(standard.contains("Codex CLI"));
        assertTrue(standard.contains("Gemma 4 31B"));
    }

    @Test
    public void detectedGeminiAndCodexDefaultToApprovalProtectedCommands() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.STANDARD);
        AgentLauncher launcher = launcherWithAllExecutables(settings);

        for (AgentLauncher.AgentInfo agent : launcher.detectAgents()) {
            if ("Gemini CLI".equals(agent.name) || "Codex CLI".equals(agent.name)) {
                assertFalse(agent.name, AgentLauncher.containsDangerousPermissionBypass(
                        agent.contextFlags));
                assertFalse(agent.name, AgentLauncher.containsDangerousPermissionBypass(
                        launcher.buildAgentCommandString(agent)));
            }
        }
    }

    @Test
    public void onPremisesRefusesCloudOllamaDefaultModel() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.ON_PREMISES);
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo gemma = new AgentLauncher.AgentInfo(
                "Gemma 4 31B",
                "gemma4_31b_agent",
                "Ollama-backed Gemma agent",
                "gemma4_31b_agent",
                "",
                true,
                "gemma4:31b-cloud");

        try {
            launcher.buildEmbeddedLaunchSpec(gemma);
            fail("Expected PostureViolation");
        } catch (PostureViolation violation) {
            assertEquals(AgentLauncher.CLOUD_OLLAMA_REFUSAL, violation.getMessage());
        }
    }

    @Test
    public void pseudonymisedAllowsCloudOllamaDefaultModel() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo gemma = new AgentLauncher.AgentInfo(
                "Gemma 4 31B",
                "gemma4_31b_agent",
                "Ollama-backed Gemma agent",
                "gemma4_31b_agent",
                "",
                true,
                "gemma4:31b-cloud");

        AgentLaunchSpec spec = launcher.buildEmbeddedLaunchSpec(gemma);

        assertTrue(spec.isLocal());
        assertTrue(spec.agentCommand.toString().contains("gemma4_31b_agent"));
    }

    @Test
    public void bundledGemmaWrapperRunsPythonModuleInsteadOfPathScript() throws Exception {
        Path workspace = Files.createTempDirectory("imagejai-agent");
        Path module = workspace.resolve("gemma4_31b");
        Files.createDirectories(module);
        Files.write(module.resolve("__main__.py"), new byte[0]);

        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        PostureController controller = new PostureController(settings, null, null);
        AgentLauncher launcher = new AgentLauncher(
                workspace.toString(), 7746, settings, controller);
        AgentLauncher.AgentInfo gemma = new AgentLauncher.AgentInfo(
                "Gemma 4 31B",
                "gemma4_31b_agent",
                "Ollama-backed Gemma agent",
                null,
                "",
                true,
                "gemma4:31b-cloud");

        String command = launcher.buildAgentCommandString(gemma);

        assertTrue(command, command.contains("-m gemma4_31b"));
        assertFalse(command, command.contains("gemma4_31b_agent"));
    }

    @Test
    public void bundledGemmaQuotesConfiguredWindowsPythonExecutableWithSpaces()
            throws Exception {
        Path workspace = Files.createTempDirectory("imagejai agent workspace");
        Path module = workspace.resolve("gemma4_31b");
        Files.createDirectories(module);
        Files.write(module.resolve("__main__.py"), new byte[0]);

        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        PostureController controller = new PostureController(settings, null, null);
        AgentLauncher launcher = new AgentLauncher(
                workspace.toString(), 7746, settings, controller) {
            @Override
            String pythonExecutable() {
                return "C:\\Program Files\\ImageJAI Python\\python.exe";
            }

            @Override
            String operatingSystemName() {
                return "Windows 11";
            }
        };
        AgentLauncher.AgentInfo gemma = new AgentLauncher.AgentInfo(
                "Gemma 4 31B",
                AgentLauncher.GEMMA_WRAPPER_COMMAND,
                "Ollama-backed Gemma agent",
                null,
                "",
                true,
                "gemma4:31b-cloud");

        assertEquals("\"C:\\Program Files\\ImageJAI Python\\python.exe\" -m gemma4_31b",
                launcher.buildAgentCommandString(gemma));
    }

    @Test
    public void everySupportedPermissionBypassRequiresAndConsumesOneLaunchConsent() {
        assertOneLaunchConsentRequired("Claude Code", "claude",
                "--dangerously-skip-permissions");
        assertOneLaunchConsentRequired("Gemini CLI", "gemini", "--yolo");
        assertOneLaunchConsentRequired("Codex CLI", "codex",
                "--dangerously-bypass-approvals-and-sandbox");
    }

    @Test
    public void onPremisesRefusesCloudOllamaEnvModel() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.ON_PREMISES);
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo gemma = new AgentLauncher.AgentInfo(
                "Gemma 4 31B",
                "gemma4_31b_agent",
                "Ollama-backed Gemma agent",
                "gemma4_31b_agent",
                "",
                true,
                "gemma3:27b");
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("OLLAMA_MODEL", "gemma4:31b-cloud");

        try {
            launcher.refuseCloudTagIfOnPremises(gemma, env);
            fail("Expected PostureViolation");
        } catch (PostureViolation violation) {
            assertEquals(AgentLauncher.CLOUD_OLLAMA_REFUSAL, violation.getMessage());
        }
    }

    @Test
    public void onPremisesRefusesColonCloudOllamaTag() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.ON_PREMISES);
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo deepseek = new AgentLauncher.AgentInfo(
                "DeepSeek V3.2",
                "gemma4_31b_agent",
                "Ollama-backed cloud agent",
                "gemma4_31b_agent",
                "--provider ollama-cloud --model deepseek-v3.2:cloud",
                true,
                "deepseek-v3.2:cloud");

        try {
            launcher.buildEmbeddedLaunchSpec(deepseek);
            fail("Expected PostureViolation");
        } catch (PostureViolation violation) {
            assertEquals(AgentLauncher.CLOUD_OLLAMA_REFUSAL, violation.getMessage());
        }
    }

    @Test
    public void dangerousClaudePermissionSkippingDefaultsOff() {
        Settings settings = new Settings();
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo claude = new AgentLauncher.AgentInfo(
                "Claude Code", "claude", "", "claude", "");

        assertFalse(settings.claudeUseGsdFlag);
        assertFalse(launcher.buildAgentCommandString(claude)
                .contains("--dangerously-skip-permissions"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void maliciousModelIdentifierCannotReachShellCommand() {
        Settings settings = new Settings();
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo malicious = new AgentLauncher.AgentInfo(
                "Injected", "python -m agent.providers.agent_cli", "", "python",
                "--provider openai --model good;whoami", false, "");

        launcher.buildAgentCommandString(malicious);
    }

    @Test(expected = PostureViolation.class)
    public void credentialCannotBeSmuggledIntoInspectableLaunchEnvironment() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo agent = new AgentLauncher.AgentInfo(
                "Provider", "python", "", "python", "", false, "");
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("IMAGEJAI_API_KEY", "must-not-appear");

        launcher.launch(agent, AgentLauncher.Mode.EXTERNAL, env);
    }

    @Test(expected = PostureViolation.class)
    public void providerIdentityOverridesCallerClaimThatCloudAgentIsLocal() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.ON_PREMISES);
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo falselyLocal = new AgentLauncher.AgentInfo(
                "Provider", "python", "", "python", "", true, "");
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("IMAGEJAI_PROVIDER", "openai");
        env.put("IMAGEJAI_MODEL", "gpt-5");

        launcher.launch(falselyLocal, AgentLauncher.Mode.EXTERNAL, env);
    }

    @Test(expected = PostureViolation.class)
    public void remoteOllamaHostIsNotTreatedAsOnPremises() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.ON_PREMISES);
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo ollama = new AgentLauncher.AgentInfo(
                "Local model", "gemma4_31b_agent", "", "python", "",
                true, "gemma3:27b");
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("IMAGEJAI_PROVIDER", "ollama");
        env.put("IMAGEJAI_MODEL", "gemma3:27b");
        env.put("OLLAMA_HOST", "https://remote.example.invalid");

        launcher.launch(ollama, AgentLauncher.Mode.EXTERNAL, env);
    }

    @Test
    public void terminalTitleCannotInjectShellSyntax() {
        Settings settings = new Settings();
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo maliciousName = new AgentLauncher.AgentInfo(
                "Agent\" & whoami & \"", "claude", "", "claude", "");

        AgentLaunchSpec spec = launcher.buildExternalLaunchSpec(maliciousName);

        assertFalse(spec.agentCommand.toString().contains("&"));
    }

    @Test
    public void allEgressSurfacesShareOnPremisesDenial() {
        for (LaunchPolicy.Surface surface : LaunchPolicy.Surface.values()) {
            LaunchPolicy.Decision decision = LaunchPolicy.evaluate(
                    "openai", "gpt-5", PrivacyPosture.ON_PREMISES,
                    LaunchPolicy.RequestedCapabilities.builder(surface)
                            .localProvider(false)
                            .egress(true)
                            .build());
            assertFalse(surface.name(), decision.allowed());
        }
    }

    @Test
    public void loopbackAndLocalAssistantRemainAllowedOnPremises() {
        assertTrue(LaunchPolicy.isLoopbackEndpoint("http://127.0.0.1:11434/v1"));
        assertFalse(LaunchPolicy.isLocalProviderEndpoint(
                "ollama", "https://remote.example.invalid"));
        LaunchPolicy.Decision decision = LaunchPolicy.evaluate(
                "local-assistant", "builtin", PrivacyPosture.ON_PREMISES,
                LaunchPolicy.RequestedCapabilities.builder(
                                LaunchPolicy.Surface.LOCAL_ASSISTANT)
                        .localProvider(true)
                        .egress(false)
                        .mutation(true)
                        .safeMode(true)
                        .build());
        assertTrue(decision.reason(), decision.allowed());
    }

    private static AgentLauncher launcherWithAllExecutables(Settings settings) {
        PostureController controller = new PostureController(settings, null, null);
        return new AgentLauncher(".", 7746, settings, controller) {
            @Override
            String findExecutable(String command) {
                return "detected-" + command.split(" ")[0];
            }
        };
    }

    private static void assertOneLaunchConsentRequired(String name, String command,
                                                       String bypassFlag) {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.STANDARD);
        AgentLauncher launcher = launcherWithAllExecutables(settings);
        AgentLauncher.AgentInfo agent = new AgentLauncher.AgentInfo(
                name, command, "", command, bypassFlag);

        try {
            launcher.buildAgentCommandString(agent);
            fail("Expected one-launch consent requirement for " + name);
        } catch (PostureViolation denied) {
            assertEquals(AgentLauncher.DANGEROUS_PERMISSION_CONSENT_REQUIRED,
                    denied.getMessage());
        }

        settings.claudeUseGsdFlag = true;
        assertTrue(launcher.buildAgentCommandString(agent).contains(bypassFlag));
        assertFalse("consent must be consumed for " + name,
                settings.claudeUseGsdFlag);

        try {
            launcher.buildAgentCommandString(agent);
            fail("Expected consumed consent to block another " + name + " bypass");
        } catch (PostureViolation denied) {
            assertEquals(AgentLauncher.DANGEROUS_PERMISSION_CONSENT_REQUIRED,
                    denied.getMessage());
        }
    }

    private static List<String> names(List<AgentLauncher.AgentInfo> agents) {
        List<String> names = new ArrayList<String>();
        for (AgentLauncher.AgentInfo agent : agents) {
            names.add(agent.name);
        }
        return names;
    }
}
