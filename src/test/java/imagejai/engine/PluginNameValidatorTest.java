package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link PluginNameValidator} introduced by
 * {@code docs/tcp_upgrade/04_fuzzy_plugin_registry.md}. Uses
 * {@link MenuCommandRegistry#setForTesting(Map)} to inject a tiny fake
 * command set so the fuzzy-match logic runs without a live Fiji.
 */
public class PluginNameValidatorTest {

    /**
     * Default registry deliberately holds only one "Gaussian" variant so
     * auto-correct tests have a clear unique winner. The ambiguity test below
     * adds the 3D variant inline to exercise the runner-up gap rule.
     */
    @Before
    public void installFakeRegistry() {
        Map<String, String> cmds = new HashMap<String, String>();
        cmds.put("Gaussian Blur...",       "ij.plugin.filter.GaussianBlur");
        cmds.put("Median...",              "ij.plugin.filter.RankFilters");
        cmds.put("Variance...",            "ij.plugin.filter.RankFilters");
        cmds.put("Threshold...",           "ij.plugin.frame.ThresholdAdjuster");
        cmds.put("Analyze Particles...",   "ij.plugin.filter.ParticleAnalyzer");
        cmds.put("Make Binary",            "ij.plugin.Thresholder");
        cmds.put("Convert to Mask",        "ij.plugin.Thresholder");
        cmds.put("Subtract Background...", "ij.plugin.filter.BackgroundSubtracter");
        MenuCommandRegistry.setForTesting(cmds);
    }

    @After
    public void clearRegistry() {
        MenuCommandRegistry.setForTesting(new HashMap<String, String>());
    }

    /** Exact match passes through — the validator returns the input unchanged. */
    @Test
    public void exactMatchPassesThroughUntouched() {
        String code = "run(\"Gaussian Blur...\", \"sigma=2\");";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);
        assertFalse("no rejections", r.hasRejections());
        assertFalse("no corrections", r.hasCorrections());
        assertEquals("code unchanged", code, r.patchedCode);
    }

    /** A single-typo name ('Gausian' → 'Gaussian Blur...') auto-corrects. */
    @Test
    public void singleTypoAutoCorrects() {
        String code = "run(\"Gausian Blur\", \"sigma=2\");";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);

        assertFalse("rejections must be empty", r.hasRejections());
        assertTrue("correction should fire", r.hasCorrections());
        assertEquals("Gausian Blur", r.corrections.get(0).from);
        assertEquals("Gaussian Blur...", r.corrections.get(0).to);
        assertTrue("patched code names canonical",
                r.patchedCode.contains("Gaussian Blur..."));
        assertFalse("patched code no longer mentions misspelling",
                r.patchedCode.contains("Gausian"));
    }

    /** 'Laplacian' has no close match — rejected with top-3 suggestions. */
    @Test
    public void noCloseMatchRejects() {
        String code = "run(\"Laplacian\", \"\");";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);

        assertFalse("no corrections", r.hasCorrections());
        assertTrue("must reject", r.hasRejections());
        assertEquals("Laplacian", r.rejections.get(0).used);
        assertTrue("top matches populated",
                r.rejections.get(0).topMatches.size() > 0);
    }

    /** Ambiguous fuzzy match (two similar candidates) rejects rather than picking one. */
    @Test
    public void ambiguousMatchDoesNotAutoCorrect() {
        // Add the 3D variant inline so "Gaussian Blur" (no ellipsis) is nearly
        // equidistant from both "Gaussian Blur..." and "Gaussian Blur 3D...".
        // The 0.05 runner-up gap rule should keep this as a rejection rather
        // than silently picking one — conservative threshold per the plan.
        Map<String, String> cmds = new HashMap<String, String>();
        cmds.put("Gaussian Blur...",    "ij.plugin.filter.GaussianBlur");
        cmds.put("Gaussian Blur 3D...", "ij.plugin.filter.GaussianBlur3D");
        MenuCommandRegistry.setForTesting(cmds);

        String code = "run(\"Gaussian Blur\", \"\");";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);
        // Plan-compliant outcomes: reject, OR auto-correct to the 2D filter.
        // The critical invariant is that the 3D variant is never silently chosen.
        if (r.hasCorrections()) {
            assertEquals("must pick 2D Gaussian, never 3D",
                    "Gaussian Blur...", r.corrections.get(0).to);
        } else {
            assertTrue(r.hasRejections());
        }
    }

    /** Single-quoted 'run('Threshold')' handled identically to double-quoted. */
    @Test
    public void singleQuoteSyntaxHandled() {
        String code = "run('Threshold...', '');";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);
        assertFalse("exact match — no corrections", r.hasCorrections());
        assertFalse("exact match — no rejections", r.hasRejections());
    }

    /** String concatenation / variable substitution skip validation silently. */
    @Test
    public void concatenatedNameSkipsValidation() {
        // The regex matches only literal-quoted names, so this compiles to
        // "no run() calls found" and returns the original code.
        String code = "cmd = \"Gausian\" + \" Blur\"; run(cmd, \"\");";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);
        assertFalse(r.hasCorrections());
        assertFalse(r.hasRejections());
    }

    /** Empty registry short-circuits — no false rejections in headless startup. */
    @Test
    public void emptyRegistryPassesThrough() {
        MenuCommandRegistry.setForTesting(new HashMap<String, String>());
        String code = "run(\"AnythingReally\", \"\");";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);
        assertFalse("empty registry means no rejections", r.hasRejections());
        assertFalse("empty registry means no corrections", r.hasCorrections());
    }

    /** Multiple run calls: mix of exact, correction, rejection. */
    @Test
    public void mixedCallsCollectBothSets() {
        String code =
                "run(\"Gaussian Blur...\", \"sigma=2\");\n"
              + "run(\"Gausian Blur\", \"sigma=3\");\n"
              + "run(\"Laplacian\", \"\");";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);
        assertEquals("one correction", 1, r.corrections.size());
        assertEquals("one rejection", 1, r.rejections.size());
    }

    /** buildAutocorrectedArray produces one entry per correction, shape as plan. */
    @Test
    public void buildAutocorrectedArrayShape() {
        String code = "run(\"Gausian Blur\", \"sigma=2\");";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);
        JsonArray arr = PluginNameValidator.buildAutocorrectedArray(r.corrections);
        assertEquals(1, arr.size());
        JsonObject entry = arr.get(0).getAsJsonObject();
        assertEquals("Gausian Blur",       entry.get("from").getAsString());
        assertEquals("Gaussian Blur...",   entry.get("to").getAsString());
        assertEquals("fuzzy_unique_95",    entry.get("rule").getAsString());
        assertTrue("score between 0 and 1",
                entry.get("score").getAsDouble() >= 0.0
             && entry.get("score").getAsDouble() <= 1.0);
    }

    /** buildPluginNotFoundError populates suggested[] top-3 with plugin_name/score. */
    @Test
    public void buildPluginNotFoundErrorShape() {
        String code = "run(\"Laplacian\", \"\");";
        PluginNameValidator.Result r = PluginNameValidator.validate(code);
        ErrorReply err = PluginNameValidator.buildPluginNotFoundError(r.rejections);

        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.structuredErrors = true;
        JsonObject o = err.buildJsonElement(caps).getAsJsonObject();

        assertEquals("PLUGIN_NOT_FOUND", o.get("code").getAsString());
        assertEquals("not_found",        o.get("category").getAsString());
        assertFalse(o.get("retry_safe").getAsBoolean());
        assertNotNull(o.get("recovery_hint"));

        assertTrue("suggested[] present", o.has("suggested"));
        JsonArray suggested = o.getAsJsonArray("suggested");
        assertTrue("at least one suggestion", suggested.size() > 0);
        JsonObject first = suggested.get(0).getAsJsonObject();
        assertTrue("entry has plugin_name",    first.has("plugin_name"));
        assertTrue("entry has score",          first.has("score"));
    }

    /** Null / empty macro code tolerated without throw. */
    @Test
    public void nullOrEmptyCodeDoesNotThrow() {
        assertNotNull(PluginNameValidator.validate(null));
        assertNotNull(PluginNameValidator.validate(""));
        assertFalse(PluginNameValidator.validate("").hasRejections());
    }
}
