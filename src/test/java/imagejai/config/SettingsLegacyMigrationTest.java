package imagejai.config;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase D acceptance: each legacy {@code selectedAgentName} display name maps to
 * the new {@code (provider, model_id)} pair so users don't lose their pick
 * across the multi-provider schema flip. Per
 * docs/multi_provider/05_ui_design.md §10.5 + 07 §D risk #3
 * ("State mismatch on flag flip").
 *
 * <p>The plan called for "a unit test for each legacy-name → new-key entry" —
 * this is that test.
 */
public class SettingsLegacyMigrationTest {

    @Test
    public void everyKnownAgentDisplayNameMapsToCliProvider() {
        Map<String, String> mapping = Settings.legacyAgentNameToNewKey();
        // The Local Assistant fallback must always be present.
        assertEquals("cli:gemma4_31b_agent", mapping.get("Local Assistant"));
        // Each KNOWN_AGENTS row in AgentLauncher.
        assertEquals("cli:claude", mapping.get("Claude Code"));
        assertEquals("cli:aider", mapping.get("Aider"));
        assertEquals("cli:gh copilot", mapping.get("GitHub Copilot CLI"));
        assertEquals("cli:gemini", mapping.get("Gemini CLI"));
        assertEquals("cli:interpreter", mapping.get("Open Interpreter"));
        assertEquals("cli:cline", mapping.get("Cline"));
        assertEquals("cli:codex", mapping.get("Codex CLI"));
        assertEquals("cli:gemma4_31b_agent", mapping.get("Gemma 4 31B"));
        assertEquals("cli:gemma4_31b_agent", mapping.get("Gemma 4 31B (Claude-style)"));
    }

    @Test
    public void seedFromLegacyAgentNamePopulatesProviderAndModelId() {
        Settings s = new Settings();
        s.selectedAgentName = "Claude Code";
        // selectedProvider/selectedModelId start null on a brand-new settings
        // object built from defaults — the seeder is a no-op when they're set.
        s.selectedProvider = null;
        s.selectedModelId = null;
        s.seedMultiProviderFromLegacyAgentName();
        assertEquals("cli", s.selectedProvider);
        assertEquals("claude", s.selectedModelId);
    }

    @Test
    public void seedFromLegacyAgentNameLeavesValuesAloneOnceSet() {
        Settings s = new Settings();
        s.selectedAgentName = "Aider";
        s.selectedProvider = "anthropic";
        s.selectedModelId = "claude-sonnet-4-6";
        s.seedMultiProviderFromLegacyAgentName();
        assertEquals("anthropic", s.selectedProvider);
        assertEquals("claude-sonnet-4-6", s.selectedModelId);
    }

    @Test
    public void seedFromLegacyAgentNameIgnoresUnknownLegacyValue() {
        Settings s = new Settings();
        s.selectedAgentName = "Some Random Custom Agent Name";
        s.selectedProvider = null;
        s.selectedModelId = null;
        s.seedMultiProviderFromLegacyAgentName();
        assertNull(s.selectedProvider);
        assertNull(s.selectedModelId);
    }

    @Test
    public void seedFromLegacyAgentNameNoOpWhenLegacyEmpty() {
        Settings s = new Settings();
        s.selectedAgentName = "";
        s.selectedProvider = null;
        s.selectedModelId = null;
        s.seedMultiProviderFromLegacyAgentName();
        assertNull(s.selectedProvider);
        assertNull(s.selectedModelId);
    }

    @Test
    public void localAssistantFallsBackToGemmaCliEntry() {
        Settings s = new Settings();
        s.selectedAgentName = "Local Assistant";
        s.selectedProvider = null;
        s.selectedModelId = null;
        s.seedMultiProviderFromLegacyAgentName();
        assertEquals("cli", s.selectedProvider);
        assertEquals("gemma4_31b_agent", s.selectedModelId);
    }

    @Test
    public void mappingCoversEveryKnownDisplayNameWithoutOrphans() {
        Map<String, String> mapping = Settings.legacyAgentNameToNewKey();
        // Every value follows the "<provider>:<model_id>" shape callers split on.
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            assertNotNull("null mapping for " + e.getKey(), e.getValue());
            int colon = e.getValue().indexOf(':');
            assertTrue("mapping must contain colon: " + e.getValue(), colon > 0);
            assertTrue("mapping must have non-empty model id: " + e.getValue(),
                    colon < e.getValue().length() - 1);
        }
    }
}
