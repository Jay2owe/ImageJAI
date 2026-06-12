package imagejai.ui.picker;

import imagejai.config.Settings;
import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ProviderEntry;
import imagejai.engine.picker.ProviderRegistry;
import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelPickerButtonFilterTest {

    @Test
    public void freeFilterShowsOnlyNonChargingRows() {
        ModelPickerButton button = new ModelPickerButton(registry(), new Settings());
        button.setPopupFilterForTest("free");

        List<String> keys = visibleKeys(button);
        assertTrue(keys.contains("ollama llama3.2:3b"));
        assertTrue(keys.contains("groq llama-3.3-70b-versatile"));
        assertFalse(keys.contains("anthropic claude-sonnet-4-6"));
        assertFalse(keys.contains("openai gpt-x-experimental"));
        assertEquals("Free models", button.headerStripText());
    }

    @Test
    public void curatedFilterDropsOnlyUnverifiedRows() {
        ModelPickerButton button = new ModelPickerButton(registry(), new Settings());
        button.setPopupFilterForTest("curated");

        List<String> keys = visibleKeys(button);
        assertTrue(keys.contains("ollama llama3.2:3b"));
        assertTrue(keys.contains("groq llama-3.3-70b-versatile"));
        assertTrue(keys.contains("anthropic claude-sonnet-4-6"));
        assertFalse(keys.contains("openai gpt-x-experimental"));
        assertEquals("Curated models", button.headerStripText());
    }

    @Test
    public void searchFiltersByNameIdOrProviderCaseInsensitive() {
        ModelPickerButton button = new ModelPickerButton(registry(), new Settings());
        button.setSearchTextForTest("LLAMA");

        List<String> keys = visibleKeys(button);
        assertTrue(keys.contains("ollama llama3.2:3b"));
        assertTrue(keys.contains("groq llama-3.3-70b-versatile"));
        assertFalse(keys.contains("anthropic claude-sonnet-4-6"));
    }

    @Test
    public void searchMatchesProviderKeyToo() {
        ModelPickerButton button = new ModelPickerButton(registry(), new Settings());
        button.setSearchTextForTest("anthropic");

        List<String> keys = visibleKeys(button);
        assertEquals(1, keys.size());
        assertTrue(keys.contains("anthropic claude-sonnet-4-6"));
    }

    @Test
    public void emptySearchShowsEverything() {
        ModelPickerButton button = new ModelPickerButton(registry(), new Settings());
        button.setSearchTextForTest("   ");
        assertEquals(4, visibleKeys(button).size());
    }

    @Test
    public void matchesQueryStaticHelperIsCaseInsensitiveAndNullSafe() {
        ModelEntry m = entry("groq", "llama-3.3-70b-versatile",
                ModelEntry.Tier.FREE_WITH_LIMITS, true);
        assertTrue(ModelPickerButton.matchesQuery(m, ""));
        assertTrue(ModelPickerButton.matchesQuery(m, null));
        assertTrue(ModelPickerButton.matchesQuery(m, "VERSATILE"));
        assertTrue(ModelPickerButton.matchesQuery(m, "groq"));
        assertFalse(ModelPickerButton.matchesQuery(m, "mistral"));
        assertFalse(ModelPickerButton.matchesQuery(null, "x"));
    }

    @Test
    public void freeLocalGroupHoldsOnlyKeylessLocalFreeModels() {
        ProviderRegistry reg = ProviderRegistry.fromMerged(Arrays.asList(
                entry("ollama", "llama3.2:3b", ModelEntry.Tier.FREE, true),
                entry("lmstudio", "qwen2.5-7b-instruct", ModelEntry.Tier.FREE, true),
                // free, but groq is a keyed cloud provider — not local-daemon.
                entry("groq", "llama-3.3-70b-versatile",
                        ModelEntry.Tier.FREE_WITH_LIMITS, true),
                entry("anthropic", "claude-sonnet-4-6", ModelEntry.Tier.PAID, true)
        ), LocalDate.of(2026, 6, 10));
        ModelPickerButton button = new ModelPickerButton(reg, new Settings());

        List<String> freeLocal = button.freeLocalKeysForTest();
        assertTrue(freeLocal.contains("ollama llama3.2:3b"));
        assertTrue(freeLocal.contains("lmstudio qwen2.5-7b-instruct"));
        assertFalse("groq is a keyed cloud provider, not local-daemon",
                freeLocal.contains("groq llama-3.3-70b-versatile"));
        assertFalse(freeLocal.contains("anthropic claude-sonnet-4-6"));
    }

    private static ProviderRegistry registry() {
        return ProviderRegistry.fromMerged(Arrays.asList(
                entry("ollama", "llama3.2:3b", ModelEntry.Tier.FREE, true),
                entry("groq", "llama-3.3-70b-versatile",
                        ModelEntry.Tier.FREE_WITH_LIMITS, true),
                entry("anthropic", "claude-sonnet-4-6", ModelEntry.Tier.PAID, true),
                entry("openai", "gpt-x-experimental", ModelEntry.Tier.UNCURATED, false)
        ), LocalDate.of(2026, 5, 2));
    }

    private static ModelEntry entry(String provider, String modelId,
                                    ModelEntry.Tier tier, boolean curated) {
        return new ModelEntry(provider, modelId, modelId, "",
                tier, 128000, false, ModelEntry.Reliability.HIGH,
                false, curated, "");
    }

    private static List<String> visibleKeys(ModelPickerButton button) {
        List<String> keys = new ArrayList<String>();
        for (ProviderEntry provider : button.visibleProvidersForTest()) {
            for (ModelEntry model : provider.models()) {
                keys.add(model.providerId() + " " + model.modelId());
            }
        }
        return keys;
    }
}
