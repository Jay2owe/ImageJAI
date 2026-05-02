package imagejai.engine.picker;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProviderRegistryTest {

    @Test
    public void loadsBundledModelsYaml() {
        ProviderRegistry registry = ProviderRegistry.loadBundled();
        assertNotNull(registry);
        // Phase D ships ~12 curated entries.
        assertTrue("expected at least 8 models in bundled yaml, got " + registry.modelCount(),
                registry.modelCount() >= 8);
        assertNotNull(registry.lookup("anthropic", "claude-sonnet-4-6"));
        assertNotNull(registry.lookup("ollama-cloud", "gemma4:31b-cloud"));
    }

    @Test
    public void everyCanonicalProviderAppearsInProvidersList() {
        ProviderRegistry registry = ProviderRegistry.loadBundled();
        for (String key : ProviderRegistry.CANONICAL_PROVIDERS) {
            assertNotNull("missing provider entry: " + key, registry.provider(key));
        }
    }

    @Test
    public void emptyRegistryHasNoModels() {
        ProviderRegistry registry = ProviderRegistry.empty();
        assertEquals(0, registry.modelCount());
        assertNull(registry.lookup("anthropic", "claude-sonnet-4-6"));
        // All canonical providers still present so the dropdown can render headers.
        assertEquals(ProviderRegistry.CANONICAL_PROVIDERS.size(),
                ((java.util.Collection<?>) registry.providersList()).size());
    }

    @Test
    public void missingFileFallsBackToEmpty() throws IOException {
        Path nonExistent = Files.createTempDirectory("pr-test").resolve("nope.yaml");
        ProviderRegistry registry = ProviderRegistry.loadFromPath(nonExistent);
        assertEquals(0, registry.modelCount());
    }

    @Test
    public void uncuratedEntriesAreFilteredWhenProviderUnknown() throws IOException {
        Path tmp = Files.createTempFile("pr-test-", ".yaml");
        String yaml = ""
                + "models:\n"
                + "  - provider: not-a-real-provider\n"
                + "    model_id: bogus\n"
                + "    display_name: Bogus\n"
                + "    description: should be skipped\n"
                + "    tier: paid\n"
                + "  - provider: anthropic\n"
                + "    model_id: claude-haiku-4-5\n"
                + "    display_name: Claude Haiku 4.5\n"
                + "    description: real entry\n"
                + "    tier: paid\n"
                + "    context_window: 200000\n"
                + "    vision_capable: true\n"
                + "    tool_call_reliability: high\n"
                + "    pinned: false\n"
                + "    curated: true\n";
        Files.write(tmp, yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProviderRegistry registry = ProviderRegistry.loadFromPath(tmp);
        assertEquals(1, registry.modelCount());
        assertNotNull(registry.lookup("anthropic", "claude-haiku-4-5"));
        assertNull(registry.lookup("not-a-real-provider", "bogus"));
    }

    @Test
    public void parsesTierAndReliabilityEnumsLeniently() throws IOException {
        Path tmp = Files.createTempFile("pr-test-", ".yaml");
        String yaml = ""
                + "models:\n"
                + "  - provider: openrouter\n"
                + "    model_id: kimi-vl\n"
                + "    display_name: Kimi VL\n"
                + "    description: stub\n"
                + "    tier: gibberish-not-a-tier\n"
                + "    context_window: 65536\n"
                + "    vision_capable: false\n"
                + "    tool_call_reliability: weird\n"
                + "    pinned: false\n"
                + "    curated: false\n";
        Files.write(tmp, yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProviderRegistry registry = ProviderRegistry.loadFromPath(tmp);
        ModelEntry entry = registry.lookup("openrouter", "kimi-vl");
        assertNotNull(entry);
        assertEquals(ModelEntry.Tier.UNCURATED, entry.tier());
        assertEquals(ModelEntry.Reliability.LOW, entry.toolCallReliability());
        assertFalse(entry.curated());
    }
}
