package imagejai.engine;

import imagejai.config.Settings;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentLauncherResumeTest {

    @Test
    public void claudeResumeUsesContinueFlag() {
        Settings settings = new Settings();
        settings.claudeUseGsdFlag = false;
        AgentLauncher launcher = new AgentLauncher(".", 7746, settings);
        AgentLauncher.AgentInfo claude = new AgentLauncher.AgentInfo(
                "Claude Code", "claude", "", null, "");

        assertTrue(launcher.supportsResumeLatest(claude));
        assertEquals("claude --continue",
                launcher.buildAgentCommandString(
                        claude, AgentLauncher.SessionAction.RESUME_LATEST));
    }

    @Test
    public void geminiResumeDoesNotBypassApprovalsByDefault() {
        AgentLauncher launcher = new AgentLauncher(".", 7746, new Settings());
        AgentLauncher.AgentInfo gemini = new AgentLauncher.AgentInfo(
                "Gemini CLI", "gemini", "", null, "");

        assertTrue(launcher.supportsResumeLatest(gemini));
        assertEquals("gemini --resume latest",
                launcher.buildAgentCommandString(
                        gemini, AgentLauncher.SessionAction.RESUME_LATEST));
    }

    @Test
    public void codexResumeUsesResumeSubcommand() {
        AgentLauncher launcher = new AgentLauncher(".", 7746, new Settings());
        AgentLauncher.AgentInfo codex = new AgentLauncher.AgentInfo(
                "Codex CLI",
                "codex",
                "",
                null,
                "");

        assertTrue(launcher.supportsResumeLatest(codex));
        assertEquals("codex resume --last",
                launcher.buildAgentCommandString(
                        codex, AgentLauncher.SessionAction.RESUME_LATEST));
    }

    @Test
    public void unsupportedCliDoesNotPretendToResume() {
        AgentLauncher launcher = new AgentLauncher(".", 7746, new Settings());
        AgentLauncher.AgentInfo aider = new AgentLauncher.AgentInfo(
                "Aider", "aider", "", null, "--read .aider.conventions.md");

        assertFalse(launcher.supportsResumeLatest(aider));
    }

    @Test
    public void claudeStyleGemmaWrapperDoesNotLookLikeClaudeCli() {
        AgentLauncher launcher = new AgentLauncher(".", 7746, new Settings());
        AgentLauncher.AgentInfo gemmaClaudeStyle = new AgentLauncher.AgentInfo(
                "Gemma 4 31B (Claude-style)",
                AgentLauncher.GEMMA_WRAPPER_COMMAND,
                "",
                null,
                "--style claude",
                true,
                "gemma4:31b-cloud");

        assertFalse(launcher.supportsResumeLatest(gemmaClaudeStyle));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void unsupportedResumeRequestThrowsInsteadOfLaunchingFresh() {
        AgentLauncher launcher = new AgentLauncher(".", 7746, new Settings());
        AgentLauncher.AgentInfo aider = new AgentLauncher.AgentInfo(
                "Aider", "aider", "", null, "--read .aider.conventions.md");

        launcher.buildAgentCommandString(
                aider, AgentLauncher.SessionAction.RESUME_LATEST);
    }
}
