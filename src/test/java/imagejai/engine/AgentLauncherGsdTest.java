package imagejai.engine;

import org.junit.After;
import org.junit.Test;
import imagejai.config.Settings;

import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentLauncherGsdTest {

    @After
    public void tearDown() {
        GsdDetector.restoreProbeForTests();
    }

    @Test
    public void claudeLaunchIncludesDangerousFlagOnlyWhenGsdInstalled() {
        Settings settings = new Settings();
        settings.claudeUseGsdFlag = true;
        AgentLauncher launcher = new AgentLauncher(".", 7746, settings);
        AgentLauncher.AgentInfo claude = new AgentLauncher.AgentInfo(
                "Claude Code", "claude", "Anthropic's Claude CLI agent", null, "");

        GsdDetector.setProbeForTests(new FixedProbe(true));
        assertTrue(launcher.buildAgentCommandString(claude)
                .contains("--dangerously-skip-permissions"));

        GsdDetector.setProbeForTests(new FixedProbe(false));
        assertFalse(launcher.buildAgentCommandString(claude)
                .contains("--dangerously-skip-permissions"));
    }

    @Test
    public void claudeLaunchOmitsDangerousFlagWhenUserDisablesSetting() {
        Settings settings = new Settings();
        settings.claudeUseGsdFlag = false;
        AgentLauncher launcher = new AgentLauncher(".", 7746, settings);
        AgentLauncher.AgentInfo claude = new AgentLauncher.AgentInfo(
                "Claude Code", "claude", "Anthropic's Claude CLI agent", null, "");

        GsdDetector.setProbeForTests(new FixedProbe(true));

        assertFalse(launcher.buildAgentCommandString(claude)
                .contains("--dangerously-skip-permissions"));
    }

    private static class FixedProbe implements GsdDetector.Probe {
        private final boolean installed;

        FixedProbe(boolean installed) {
            this.installed = installed;
        }

        @Override
        public boolean exists(Path path) {
            return installed;
        }

        @Override
        public boolean claudeHelpExitsZero() {
            return installed;
        }
    }
}
