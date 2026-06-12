package imagejai.engine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MenuCommandRegistry} introduced by
 * {@code docs/tcp_upgrade/04_fuzzy_plugin_registry.md}. Uses
 * {@link MenuCommandRegistry#setForTesting(Map)} so the registry is seeded
 * with a known command set per test — no live Fiji.
 */
public class MenuCommandRegistryTest {

    @Before
    public void installRegistry() {
        Map<String, String> cmds = new HashMap<String, String>();
        cmds.put("Gaussian Blur...",     "ij.plugin.filter.GaussianBlur");
        cmds.put("Gaussian Blur 3D...",  "ij.plugin.filter.GaussianBlur3D");
        cmds.put("Median...",            "ij.plugin.filter.RankFilters");
        cmds.put("Analyze Particles...", "ij.plugin.filter.ParticleAnalyzer");
        cmds.put("Threshold...",         "ij.plugin.frame.ThresholdAdjuster");
        MenuCommandRegistry.setForTesting(cmds);
    }

    @After
    public void tearDown() {
        MenuCommandRegistry.setForTesting(new HashMap<String, String>());
    }

    @Test
    public void existsIsCaseSensitiveExactMatch() {
        MenuCommandRegistry r = MenuCommandRegistry.get();
        assertTrue(r.exists("Gaussian Blur..."));
        assertFalse("case-sensitive", r.exists("gaussian blur..."));
        assertFalse("no ellipsis — different name", r.exists("Gaussian Blur"));
    }

    @Test
    public void sizeAndAllCommandsReflectSnapshot() {
        MenuCommandRegistry r = MenuCommandRegistry.get();
        assertEquals(5, r.size());
        List<String> all = r.allCommands();
        assertEquals(5, all.size());
        assertTrue(all.contains("Gaussian Blur..."));
    }

    @Test
    public void classForReturnsRegisteredClass() {
        MenuCommandRegistry r = MenuCommandRegistry.get();
        assertEquals("ij.plugin.filter.GaussianBlur", r.classFor("Gaussian Blur..."));
        assertEquals(null, r.classFor("Nonexistent Command"));
    }

    @Test
    public void findClosestReturnsSortedTopN() {
        MenuCommandRegistry r = MenuCommandRegistry.get();
        List<MenuCommandRegistry.Match> top3 = r.findClosest("Gausian Blur", 3);
        assertEquals(3, top3.size());
        // Highest score must be "Gaussian Blur..." — closer than the 3D variant.
        assertEquals("Gaussian Blur...", top3.get(0).name());
        // Descending order invariant.
        assertTrue(top3.get(0).score() >= top3.get(1).score());
        assertTrue(top3.get(1).score() >= top3.get(2).score());
    }

    @Test
    public void findClosestHandlesEmptyAndZeroTopN() {
        MenuCommandRegistry r = MenuCommandRegistry.get();
        assertNotNull(r.findClosest("anything", 0));
        assertEquals(0, r.findClosest("anything", 0).size());
        assertEquals(0, r.findClosest(null, 5).size());
    }

    @Test
    public void emptyRegistryBehavesSafely() {
        MenuCommandRegistry.setForTesting(new HashMap<String, String>());
        MenuCommandRegistry r = MenuCommandRegistry.get();
        assertEquals(0, r.size());
        assertEquals(0, r.findClosest("Gaussian", 3).size());
        assertFalse(r.exists("anything"));
    }

    // ---- FuzzyMatcher sanity: exact match = 1.0, empty vs something = 0.0 ----

    @Test
    public void fuzzyMatcherExactMatchIsOne() {
        assertEquals(1.0, FuzzyMatcher.jaroWinkler("Gaussian Blur...", "Gaussian Blur..."), 1e-9);
    }

    @Test
    public void fuzzyMatcherNullOrEmpty() {
        assertEquals(0.0, FuzzyMatcher.jaroWinkler(null, "x"),  1e-9);
        assertEquals(0.0, FuzzyMatcher.jaroWinkler("x", null),  1e-9);
        assertEquals(0.0, FuzzyMatcher.jaroWinkler("", "x"),    1e-9);
        assertEquals(0.0, FuzzyMatcher.jaroWinkler("x", ""),    1e-9);
    }

    @Test
    public void fuzzyMatcherCaseInsensitive() {
        double hi = FuzzyMatcher.jaroWinkler("Gaussian", "GAUSSIAN");
        assertEquals("case-insensitive match must score 1.0", 1.0, hi, 1e-9);
    }

    @Test
    public void fuzzyMatcherTypoScoresHigh() {
        double s = FuzzyMatcher.jaroWinkler("Gausian Blur", "Gaussian Blur...");
        assertTrue("single typo should clear 0.85, got " + s, s >= 0.85);
    }

    @Test
    public void fuzzyMatcherUnrelatedScoresLow() {
        double s = FuzzyMatcher.jaroWinkler("Laplacian", "Gaussian Blur...");
        assertTrue("unrelated name must not auto-correct, got " + s, s < 0.95);
    }
}
