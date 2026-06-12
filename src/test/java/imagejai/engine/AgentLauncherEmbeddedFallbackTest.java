package imagejai.engine;

import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AgentLauncherEmbeddedFallbackTest {

    @Test
    public void embeddedWinPtyLinkageFailureFallsBackToExternalLaunch() throws Exception {
        Path workspace = Files.createTempDirectory("imagejai-agent");
        Path module = workspace.resolve("gemma4_31b");
        Files.createDirectories(module);
        Files.write(module.resolve("__main__.py"), new byte[0]);

        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        FailingEmbeddedLauncher launcher = new FailingEmbeddedLauncher(workspace, settings);
        AgentLauncher.AgentInfo gemma = new AgentLauncher.AgentInfo(
                "Gemma 4 31B (cloud)",
                AgentLauncher.GEMMA_WRAPPER_COMMAND,
                "Ollama-backed Gemma agent",
                null,
                "--provider ollama-cloud --model gemma4:31b-cloud",
                true,
                "gemma4:31b-cloud");

        AgentSession session = launcher.launch(
                gemma,
                AgentLauncher.Mode.EMBEDDED,
                Collections.singletonMap("IMAGEJAI_MODEL", "gemma4:31b-cloud"));

        assertNotNull(session);
        assertTrue(session instanceof ExternalAgentSession);
        ExternalAgentSession external = (ExternalAgentSession) session;
        assertTrue(external.isFallbackLaunch());
        assertTrue(external.notice().contains("WinPty"));
        assertTrue(launcher.externalFallbackLaunched);
        assertEquals("gemma4:31b-cloud", launcher.extraEnv.get("IMAGEJAI_MODEL"));
    }

    private static final class FailingEmbeddedLauncher extends AgentLauncher {
        private boolean externalFallbackLaunched;
        private Map<String, String> extraEnv;

        FailingEmbeddedLauncher(Path workspace, Settings settings) {
            super(workspace.toString(), 7746, settings,
                    new PostureController(settings, null, null));
        }

        @Override
        AgentSession createEmbeddedSession(AgentInfo agent, AgentLaunchSpec spec)
                throws IOException {
            throw new NoClassDefFoundError(
                    "Could not initialize class com.pty4j.windows.winpty.WinPty");
        }

        @Override
        AgentSession launchExternalSession(AgentInfo agent,
                                           Map<String, String> extraEnv,
                                           String notice) {
            this.externalFallbackLaunched = true;
            this.extraEnv = extraEnv;
            return new ExternalAgentSession(agent, true, notice);
        }
    }
}
