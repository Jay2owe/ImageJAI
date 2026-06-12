package imagejai.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the post-execution macro analyser
 * (docs/tcp_upgrade/06_nresults_trap.md). Covers the five scenarios enumerated
 * in the step's Tests section — the four rule decisions plus the empty-input
 * fast path. The rule is a pure function of (code, state) so no Fiji is
 * needed; tests construct {@link MacroAnalyser.PostExec} directly.
 */
public class MacroAnalyserTest {

    /** Classic trap: Analyze Particles with no results flag, zero rows. */
    @Test
    public void nresultsTrapFiresWhenNoFlagAndNoRows() {
        String code = "setAutoThreshold(\"Default dark\");\n"
                + "run(\"Convert to Mask\");\n"
                + "run(\"Analyze Particles...\", \"size=50-Infinity\");\n";
        List<MacroAnalyser.Warning> warnings =
                MacroAnalyser.analyse(code, new MacroAnalyser.PostExec(0));

        assertEquals(1, warnings.size());
        MacroAnalyser.Warning w = warnings.get(0);
        assertEquals(ErrorReply.CODE_NRESULTS_TRAP, w.code);
        assertEquals(List.of(3), w.affectedLines);
        assertTrue("message mentions Analyze Particles",
                w.message.toLowerCase().contains("analyze particles"));
        assertTrue("hint steers toward summarize/display",
                w.hint.contains("summarize") || w.hint.contains("display"));

        JsonObject json = w.toJson();
        assertEquals("NRESULTS_TRAP", json.get("code").getAsString());
        assertTrue(json.has("hint"));
        assertEquals(1, json.getAsJsonArray("affectedLines").size());
        assertEquals(3, json.getAsJsonArray("affectedLines").get(0).getAsInt());
    }

    /** summarize in options → no warning; the macro populated Results. */
    @Test
    public void summarizeFlagSuppressesWarning() {
        String code = "run(\"Analyze Particles...\", \"size=50-Infinity summarize\");";
        // Even with 0 rows, summarize makes the reply shape valid — user
        // asked for a summary row and got it (or explicitly asked for it).
        List<MacroAnalyser.Warning> warnings =
                MacroAnalyser.analyse(code, new MacroAnalyser.PostExec(0));
        assertTrue(warnings.isEmpty());
    }

    /** display in options → no warning either. */
    @Test
    public void displayFlagSuppressesWarning() {
        String code = "run(\"Analyze Particles...\", \"size=0-Infinity display\");";
        List<MacroAnalyser.Warning> warnings =
                MacroAnalyser.analyse(code, new MacroAnalyser.PostExec(0));
        assertTrue(warnings.isEmpty());
    }

    /** show=[Outlines] counts as a results-writing flag. */
    @Test
    public void showBracketFlagSuppressesWarning() {
        String code = "run(\"Analyze Particles...\", \"size=10 show=[Masks]\");";
        List<MacroAnalyser.Warning> warnings =
                MacroAnalyser.analyse(code, new MacroAnalyser.PostExec(0));
        assertTrue(warnings.isEmpty());
    }

    /** Rows present after execution → no warning even with no flag. */
    @Test
    public void rowsPresentSuppressesWarning() {
        String code = "run(\"Analyze Particles...\", \"size=50-Infinity\");";
        // Nonzero rows means the trap didn't bite — something populated the
        // table (agent called Measure beforehand, a previous step left rows,
        // etc.). Don't fire.
        List<MacroAnalyser.Warning> warnings =
                MacroAnalyser.analyse(code, new MacroAnalyser.PostExec(7));
        assertTrue(warnings.isEmpty());
    }

    /** add in options with no other flag → warning fires, hint steers to ROI Manager. */
    @Test
    public void addAloneStillFiresButHintsRoiManager() {
        String code = "run(\"Analyze Particles...\", \"size=50 add\");";
        List<MacroAnalyser.Warning> warnings =
                MacroAnalyser.analyse(code, new MacroAnalyser.PostExec(0));
        assertEquals(1, warnings.size());
        String hint = warnings.get(0).hint;
        assertTrue("hint mentions roiManager",
                hint.contains("roiManager"));
        assertTrue("hint explains why rows are absent",
                hint.contains("ROI Manager"));
    }

    /** Macro with no Analyze Particles call → analyser returns empty. */
    @Test
    public void unrelatedMacroProducesNoWarnings() {
        String code = "run(\"Gaussian Blur...\", \"sigma=2\");\nrun(\"Measure\");";
        List<MacroAnalyser.Warning> warnings =
                MacroAnalyser.analyse(code, new MacroAnalyser.PostExec(0));
        assertTrue(warnings.isEmpty());
    }

    /** Null inputs are tolerated — analyser returns empty instead of throwing. */
    @Test
    public void nullInputsReturnEmpty() {
        assertTrue(MacroAnalyser.analyse(null, new MacroAnalyser.PostExec(0)).isEmpty());
        assertTrue(MacroAnalyser.analyse("run(\"Analyze Particles...\", \"\");", null).isEmpty());
    }

    /** Line number of the first character is 1, not 0. */
    @Test
    public void lineOfReturnsOneBasedPosition() {
        assertEquals(1, MacroAnalyser.lineOf("run();", 0));
        assertEquals(1, MacroAnalyser.lineOf("run();\nmore();", 3));
        assertEquals(2, MacroAnalyser.lineOf("run();\nmore();", 7));
        assertEquals(3, MacroAnalyser.lineOf("a\nb\nc\n", 4));
    }

    /** The rule-level helper is also accessible for direct testing. */
    @Test
    public void nresultsTrapHelperReturnsOptional() {
        String code = "run(\"Analyze Particles...\", \"size=50\");";
        Optional<MacroAnalyser.Warning> w = MacroAnalyser.nresultsTrap(
                code, new MacroAnalyser.PostExec(0));
        assertTrue(w.isPresent());
        assertEquals(ErrorReply.CODE_NRESULTS_TRAP, w.get().code);

        Optional<MacroAnalyser.Warning> none = MacroAnalyser.nresultsTrap(
                "run(\"Measure\");", new MacroAnalyser.PostExec(0));
        assertFalse(none.isPresent());
    }

    /** Single-quoted options also match (Gemma swaps quote styles freely). */
    @Test
    public void singleQuotedOptionsStillMatch() {
        String code = "run('Analyze Particles...', 'size=50');";
        List<MacroAnalyser.Warning> warnings =
                MacroAnalyser.analyse(code, new MacroAnalyser.PostExec(0));
        assertEquals(1, warnings.size());
    }
}
