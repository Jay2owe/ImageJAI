package imagejai.engine;

import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import org.junit.Test;

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

        assertTrue(names(agents).contains("Gemma 4 31B"));
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

    private static AgentLauncher launcherWithAllExecutables(Settings settings) {
        PostureController controller = new PostureController(settings, null, null);
        return new AgentLauncher(".", 7746, settings, controller) {
            @Override
            String findExecutable(String command) {
                return "detected-" + command.split(" ")[0];
            }
        };
    }

    private static List<String> names(List<AgentLauncher.AgentInfo> agents) {
        List<String> names = new ArrayList<String>();
        for (AgentLauncher.AgentInfo agent : agents) {
            names.add(agent.name);
        }
        return names;
    }
}
