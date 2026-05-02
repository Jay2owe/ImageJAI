package imagejai.engine.picker;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase G acceptance: docs/multi_provider/02_curation_strategy.md §2.
 *
 * <p>Covers all four cells of the curated×live truth table — both-present,
 * curated-only (soft-deprecation), live-only (uncurated stub) — plus the
 * conflict resolution rule that upstream wins on context_window while curator
 * wins on description.
 */
public class MergeFunctionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 2);

    private static ModelEntry curated(String providerId, String modelId, int contextWindow,
                                      String description, boolean pinned) {
        return new ModelEntry(providerId, modelId, providerId + "/" + modelId,
                description, ModelEntry.Tier.PAID, contextWindow, false,
                ModelEntry.Reliability.HIGH, pinned, true, "",
                LocalDate.of(2026, 4, 1), null, null);
    }

    private static MergeFunction.LiveResult successful(String... ids) {
        Set<String> set = new LinkedHashSet<String>();
        Collections.addAll(set, ids);
        return MergeFunction.LiveResult.success(set);
    }

    @Test
    public void curatedAndLiveBoth_curatorDescriptionWins_upstreamContextWindowWins() {
        ModelEntry seed = curated("anthropic", "claude-opus-4-7", 999, "curator copy", true);
        Map<String, MergeFunction.LiveResult> live = new LinkedHashMap<String, MergeFunction.LiveResult>();
        Map<String, Map<String, Object>> meta = new LinkedHashMap<String, Map<String, Object>>();
        meta.put("claude-opus-4-7", Collections.<String, Object>singletonMap("context_length", 1_000_000));
        live.put("anthropic",
                new MergeFunction.LiveResult(true,
                        new LinkedHashSet<String>(java.util.Collections.singleton("claude-opus-4-7")),
                        meta));

        List<ModelEntry> merged = MergeFunction.merge(
                java.util.Collections.singletonList(seed),
                live,
                java.util.Collections.<String, ModelsLocalLoader.Override>emptyMap(),
                TODAY);
        assertEquals(1, merged.size());
        ModelEntry out = merged.get(0);
        assertEquals("curator copy", out.description());
        assertEquals(1_000_000, out.contextWindow());
        assertEquals(TODAY, out.lastVerified());
        assertNull(out.deprecatedSince());
    }

    @Test
    public void curatedNotLive_softDeprecates() {
        // Upstream returns claude-opus-4-7 (unknown to curator → uncurated stub)
        // and the curator's claude-haiku-3-5 is missing from upstream → soft
        // deprecate. Merged list contains both rows; the curated entry is the
        // one that must carry the deprecated_since stamp.
        ModelEntry seed = curated("anthropic", "claude-haiku-3-5", 200_000, "older haiku", false);
        Map<String, MergeFunction.LiveResult> live = new LinkedHashMap<String, MergeFunction.LiveResult>();
        live.put("anthropic", successful("claude-opus-4-7"));

        List<ModelEntry> merged = MergeFunction.merge(
                java.util.Collections.singletonList(seed),
                live,
                java.util.Collections.<String, ModelsLocalLoader.Override>emptyMap(),
                TODAY);
        ModelEntry haiku = null;
        for (ModelEntry e : merged) {
            if ("claude-haiku-3-5".equals(e.modelId())) {
                haiku = e;
            }
        }
        assertNotNull(haiku);
        assertEquals(TODAY, haiku.deprecatedSince());
    }

    @Test
    public void liveOnly_synthesisesUncuratedStub() {
        Map<String, MergeFunction.LiveResult> live = new LinkedHashMap<String, MergeFunction.LiveResult>();
        live.put("openrouter", successful("moonshotai/kimi-vl-preview"));

        List<ModelEntry> merged = MergeFunction.merge(
                java.util.Collections.<ModelEntry>emptyList(),
                live,
                java.util.Collections.<String, ModelsLocalLoader.Override>emptyMap(),
                TODAY);
        assertEquals(1, merged.size());
        ModelEntry stub = merged.get(0);
        assertEquals("openrouter", stub.providerId());
        assertEquals("moonshotai/kimi-vl-preview", stub.modelId());
        assertFalse(stub.curated());
        assertEquals(ModelEntry.Tier.UNCURATED, stub.tier());
        assertEquals(ModelEntry.Reliability.LOW, stub.toolCallReliability());
        assertTrue("model_id contains 'vl' — vision should be inferred",
                stub.visionCapable());
    }

    @Test
    public void failedDiscovery_doesNotMarkDeprecated_perRisk10() {
        ModelEntry seed = curated("xai", "grok-4", 256_000, "Grok 4", false);
        Map<String, MergeFunction.LiveResult> live = new LinkedHashMap<String, MergeFunction.LiveResult>();
        live.put("xai", MergeFunction.LiveResult.failure());

        List<ModelEntry> merged = MergeFunction.merge(
                java.util.Collections.singletonList(seed),
                live,
                java.util.Collections.<String, ModelsLocalLoader.Override>emptyMap(),
                TODAY);
        assertEquals(1, merged.size());
        assertNull("transient failure must not soft-deprecate",
                merged.get(0).deprecatedSince());
    }

    @Test
    public void userOverrideAddsPin() {
        ModelEntry seed = curated("anthropic", "claude-sonnet-4-6", 1_000_000, "Sonnet", false);
        Map<String, ModelsLocalLoader.Override> overrides = new LinkedHashMap<String, ModelsLocalLoader.Override>();
        overrides.put("anthropic claude-sonnet-4-6",
                new ModelsLocalLoader.Override("anthropic", "claude-sonnet-4-6",
                        Boolean.TRUE, null));

        List<ModelEntry> merged = MergeFunction.merge(
                java.util.Collections.singletonList(seed),
                java.util.Collections.<String, MergeFunction.LiveResult>emptyMap(),
                overrides,
                TODAY);
        assertTrue(merged.get(0).pinned());
    }

    @Test
    public void applyVisibility_dropsHiddenButKeepsPinned() {
        ModelEntry pinnedDeprecated = curated("anthropic", "claude-haiku-3-5",
                200_000, "older", true)
                .withDeprecatedSince(TODAY.minusDays(60));
        ModelEntry hidden = curated("anthropic", "claude-haiku-3-0",
                200_000, "older older", false)
                .withDeprecatedSince(TODAY.minusDays(60));
        List<ModelEntry> merged = new ArrayList<ModelEntry>();
        merged.add(pinnedDeprecated);
        merged.add(hidden);

        List<ModelEntry> visible = MergeFunction.applyVisibility(merged,
                java.util.Collections.<String, ModelsLocalLoader.Override>emptyMap(),
                TODAY);
        assertEquals(1, visible.size());
        assertEquals("claude-haiku-3-5", visible.get(0).modelId());
    }

    @Test
    public void applyVisibility_userHiddenWithoutPinDrops() {
        ModelEntry seed = curated("ollama-cloud", "gemma4:31b-cloud", 128_000, "gemma", false);
        Map<String, ModelsLocalLoader.Override> overrides = new LinkedHashMap<String, ModelsLocalLoader.Override>();
        overrides.put("ollama-cloud gemma4:31b-cloud",
                new ModelsLocalLoader.Override("ollama-cloud", "gemma4:31b-cloud",
                        null, Boolean.TRUE));
        List<ModelEntry> merged = java.util.Collections.singletonList(seed);
        assertTrue(MergeFunction.applyVisibility(merged, overrides, TODAY).isEmpty());
    }
}
