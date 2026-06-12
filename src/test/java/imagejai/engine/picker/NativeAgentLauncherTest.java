package imagejai.engine.picker;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Phase C — verify Java side reads native_features and emits env vars. */
public class NativeAgentLauncherTest {

    @Test
    public void envEmptyForEntryWithoutNativeFeatures() {
        ModelEntry entry = new ModelEntry("anthropic", "claude-haiku-4-5",
                "Claude Haiku 4.5", "", ModelEntry.Tier.PAID, 200_000,
                true, ModelEntry.Reliability.HIGH, false, true, "");
        assertTrue(NativeAgentLauncher.nativeFeatureEnv(entry).isEmpty());
    }

    @Test
    public void envCarriesPromptCachingFlag() {
        ModelEntry entry = entryWithFeatures(featureMap("prompt_caching", Boolean.TRUE));
        Map<String, String> env = NativeAgentLauncher.nativeFeatureEnv(entry);
        assertEquals("true", env.get("IMAGEJAI_NATIVE_PROMPT_CACHING"));
        assertEquals(1, env.size());
    }

    @Test
    public void envEncodesCollectionsAsCommaJoined() {
        ModelEntry entry = entryWithFeatures(
                featureMap("server_tools", Arrays.asList("web_search", "code_execution")));
        Map<String, String> env = NativeAgentLauncher.nativeFeatureEnv(entry);
        assertEquals("web_search,code_execution", env.get("IMAGEJAI_NATIVE_SERVER_TOOLS"));
    }

    @Test
    public void envEncodesIntegerThinkingBudget() {
        ModelEntry entry = entryWithFeatures(featureMap("thinking_budget", Integer.valueOf(2048)));
        Map<String, String> env = NativeAgentLauncher.nativeFeatureEnv(entry);
        assertEquals("2048", env.get("IMAGEJAI_NATIVE_THINKING_BUDGET"));
    }

    @Test
    public void modelsYamlOpusEntryHasPromptCachingTrue() {
        ProviderRegistry registry = ProviderRegistry.loadBundled();
        ModelEntry opus = registry.lookup("anthropic", "claude-opus-4-7");
        assertNotNull(opus);
        assertEquals(Boolean.TRUE, opus.nativeFeatures().get("prompt_caching"));

        Map<String, String> env = NativeAgentLauncher.nativeFeatureEnv(opus);
        assertEquals("true", env.get("IMAGEJAI_NATIVE_PROMPT_CACHING"));
    }

    @Test
    public void modelsYamlSonnetEntryHasPromptCachingTrue() {
        ProviderRegistry registry = ProviderRegistry.loadBundled();
        ModelEntry sonnet = registry.lookup("anthropic", "claude-sonnet-4-6");
        assertNotNull(sonnet);
        assertEquals(Boolean.TRUE, sonnet.nativeFeatures().get("prompt_caching"));
    }

    @Test
    public void modelsYamlHaikuHasNoNativeFeatures() {
        // Haiku 4.5 deliberately ships without native_features per phase C plan.
        ProviderRegistry registry = ProviderRegistry.loadBundled();
        ModelEntry haiku = registry.lookup("anthropic", "claude-haiku-4-5");
        assertNotNull(haiku);
        assertTrue(haiku.nativeFeatures().isEmpty());
    }

    @Test
    public void modelsYamlGeminiDefaultsToNoNativeFeatures() {
        ProviderRegistry registry = ProviderRegistry.loadBundled();
        for (String modelId : new String[] {"gemini-2.5-pro", "gemini-2.5-flash",
                "gemini-2.5-flash-lite", "gemini-2.0-flash"}) {
            ModelEntry entry = registry.lookup("gemini", modelId);
            assertNotNull("missing gemini entry: " + modelId, entry);
            assertTrue("gemini " + modelId + " must default to no native_features",
                    entry.nativeFeatures().isEmpty());
        }
    }

    @Test
    public void launcherDoesNotThrowOnNullOrFeaturelessEntry() {
        NativeAgentLauncher launcher = new NativeAgentLauncher();
        // Returns null today (skeleton) but must not raise on entries without features.
        ModelEntry entry = new ModelEntry("anthropic", "claude-haiku-4-5",
                "Claude Haiku 4.5", "", ModelEntry.Tier.PAID, 200_000,
                true, ModelEntry.Reliability.HIGH, false, true, "");
        // No assertion on return value beyond not throwing — Phase C keeps the skeleton.
        launcher.launch(entry, null);
        assertFalse(NativeAgentLauncher.nativeFeatureEnv(null).containsKey("anything"));
    }

    private static Map<String, Object> featureMap(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(key, value);
        return m;
    }

    private static ModelEntry entryWithFeatures(Map<String, Object> features) {
        return new ModelEntry("anthropic", "claude-opus-4-7",
                "Claude Opus 4.7", "", ModelEntry.Tier.PAID, 1_000_000,
                true, ModelEntry.Reliability.HIGH, true, true, "",
                null, null, null, features == null ? Collections.<String, Object>emptyMap() : features);
    }
}
