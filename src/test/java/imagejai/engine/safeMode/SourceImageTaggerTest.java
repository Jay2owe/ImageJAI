package imagejai.engine.safeMode;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stage 06 of {@code docs/safe_mode_v2/} — verify {@link SourceImageTagger}
 * stamps {@code Source_Image} on rows added during one macro and only those
 * rows, honors the per-call escape hatch, and stays idempotent if the
 * caller's safety-net runs {@code postExec} a second time.
 *
 * <p>Tests build {@link ResultsTable} fixtures by hand and pass them in via
 * the package-private overloads so the suite stays headless — no global
 * Fiji "Results" window touched, no GUI side-effects.
 */
public class SourceImageTaggerTest {

    /** Three rows added on imageA → all three carry Source_Image=imageA. */
    @Test
    public void postExecTagsAllRowsAddedDuringMacro() {
        ResultsTable rt = new ResultsTable();
        ImagePlus imp = headlessImagePlus("imageA");

        SourceImageTagger tagger = new SourceImageTagger();
        tagger.preExec(imp, rt);

        addRow(rt, "Mean", 1.0);
        addRow(rt, "Mean", 2.0);
        addRow(rt, "Mean", 3.0);

        tagger.postExec(imp, rt, false);

        assertEquals(3, rt.getCounter());
        assertEquals("imageA", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 0));
        assertEquals("imageA", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 1));
        assertEquals("imageA", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 2));
    }

    /**
     * Two macros in succession: macro 1 adds 3 rows on imageA, macro 2 adds 3
     * rows on imageB. The first three rows must keep their imageA label; the
     * next three must read imageB.
     */
    @Test
    public void postExecOnlyTagsRowsFromCurrentMacro() {
        ResultsTable rt = new ResultsTable();
        ImagePlus impA = headlessImagePlus("imageA");
        ImagePlus impB = headlessImagePlus("imageB");

        SourceImageTagger first = new SourceImageTagger();
        first.preExec(impA, rt);
        addRow(rt, "Mean", 1.0);
        addRow(rt, "Mean", 2.0);
        addRow(rt, "Mean", 3.0);
        first.postExec(impA, rt, false);

        SourceImageTagger second = new SourceImageTagger();
        second.preExec(impB, rt);
        addRow(rt, "Mean", 4.0);
        addRow(rt, "Mean", 5.0);
        addRow(rt, "Mean", 6.0);
        second.postExec(impB, rt, false);

        assertEquals(6, rt.getCounter());
        assertEquals("imageA", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 0));
        assertEquals("imageA", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 1));
        assertEquals("imageA", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 2));
        assertEquals("imageB", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 3));
        assertEquals("imageB", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 4));
        assertEquals("imageB", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 5));
    }

    /** Macro that adds no rows → no column written, table untouched. */
    @Test
    public void postExecIsNoOpWhenNoRowsAdded() {
        ResultsTable rt = new ResultsTable();
        ImagePlus imp = headlessImagePlus("imageA");

        SourceImageTagger tagger = new SourceImageTagger();
        tagger.preExec(imp, rt);
        // No rows added.
        tagger.postExec(imp, rt, false);

        assertEquals(0, rt.getCounter());
        assertFalse("column not created when no new rows landed",
                hasSourceImageColumn(rt));
    }

    /**
     * Macro switched the active image mid-run (selectImage). The post hook
     * tags every new row with the FINAL active image's title. Documented
     * limitation in the plan §Known risks.
     */
    @Test
    public void postExecUsesFinalActiveImageWhenSwitchedMidMacro() {
        ResultsTable rt = new ResultsTable();
        ImagePlus impStart = headlessImagePlus("imageA");
        ImagePlus impEnd = headlessImagePlus("imageB");

        SourceImageTagger tagger = new SourceImageTagger();
        tagger.preExec(impStart, rt);
        addRow(rt, "Mean", 1.0);
        addRow(rt, "Mean", 2.0);
        tagger.postExec(impEnd, rt, false);

        assertEquals("imageB", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 0));
        assertEquals("imageB", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 1));
    }

    /**
     * Active image absent at postExec time → falls back to the title captured
     * by preExec rather than {@code null}.
     */
    @Test
    public void postExecFallsBackToPreExecTitleWhenActiveImageGone() {
        ResultsTable rt = new ResultsTable();
        ImagePlus impStart = headlessImagePlus("imageA");

        SourceImageTagger tagger = new SourceImageTagger();
        tagger.preExec(impStart, rt);
        addRow(rt, "Mean", 1.0);
        tagger.postExec(null, rt, false);

        assertEquals("imageA", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 0));
    }

    /** No active image at preExec or postExec → label is the documented sentinel. */
    @Test
    public void postExecUsesSentinelWhenNoActiveImageEver() {
        ResultsTable rt = new ResultsTable();

        SourceImageTagger tagger = new SourceImageTagger();
        tagger.preExec(null, rt);
        addRow(rt, "Mean", 1.0);
        tagger.postExec(null, rt, false);

        assertEquals(SourceImageTagger.UNKNOWN_LABEL,
                rt.getStringValue(SourceImageTagger.COLUMN_NAME, 0));
    }

    /**
     * Caller's outer-finally safety net invokes postExec a second time → the
     * second call is a no-op so a different active image at the second call
     * does NOT relabel the row that was already stamped by the first call.
     */
    @Test
    public void postExecIsIdempotent() {
        ResultsTable rt = new ResultsTable();
        ImagePlus impA = headlessImagePlus("imageA");
        ImagePlus impB = headlessImagePlus("imageB");

        SourceImageTagger tagger = new SourceImageTagger();
        tagger.preExec(impA, rt);
        addRow(rt, "Mean", 1.0);
        tagger.postExec(impA, rt, false);

        assertEquals("imageA", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 0));

        // Safety-net second invocation with a DIFFERENT active image — the
        // done-flag must short-circuit before any setValue runs.
        tagger.postExec(impB, rt, false);
        assertEquals("row label is sticky after the first postExec",
                "imageA", rt.getStringValue(SourceImageTagger.COLUMN_NAME, 0));
    }

    /** Without preExec, postExec must do nothing even if rows appear. */
    @Test
    public void postExecRequiresPreExec() {
        ResultsTable rt = new ResultsTable();
        ImagePlus imp = headlessImagePlus("imageA");

        SourceImageTagger tagger = new SourceImageTagger();
        addRow(rt, "Mean", 1.0);
        tagger.postExec(imp, rt, false);

        assertFalse("postExec without preExec must not create the column",
                hasSourceImageColumn(rt));
    }

    // -----------------------------------------------------------------------
    // macroOptsOut detection
    // -----------------------------------------------------------------------

    @Test
    public void macroOptsOutMatchesCanonicalAnnotation() {
        assertTrue(SourceImageTagger.macroOptsOut(
                "// @safe_mode allow: legacy_results_format\nrun(\"Measure\");"));
    }

    @Test
    public void macroOptsOutIgnoresWhitespaceAndCase() {
        assertTrue(SourceImageTagger.macroOptsOut(
                "//   @SAFE_MODE  allow:legacy_results_format\nrun(\"Measure\");"));
        assertTrue(SourceImageTagger.macroOptsOut(
                "//@safe_mode allow : legacy_results_format"));
    }

    @Test
    public void macroOptsOutDoesNotMatchUnrelatedComments() {
        assertFalse(SourceImageTagger.macroOptsOut(
                "// totally unrelated comment\nrun(\"Measure\");"));
        assertFalse(SourceImageTagger.macroOptsOut(
                "// @safe_mode allow: bit_depth_narrowing\nrun(\"Measure\");"));
        assertFalse(SourceImageTagger.macroOptsOut(null));
        assertFalse(SourceImageTagger.macroOptsOut(""));
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /**
     * Build an ImagePlus with a known title. Uses an ImageStack constructor
     * so {@code show()} is never implicitly called and no AWT components
     * are realised — keeps the test runnable headlessly.
     */
    private static ImagePlus headlessImagePlus(String title) {
        ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ByteProcessor(2, 2));
        return new ImagePlus(title, stack);
    }

    /** Append one row with one numeric column. {@code incrementCounter} +
     * {@code addValue} is the canonical pattern Analyze Particles / Measure
     * use under the hood. */
    private static void addRow(ResultsTable rt, String column, double value) {
        rt.incrementCounter();
        rt.addValue(column, value);
    }

    /** True when the Source_Image column exists on the table. {@code
     * ResultsTable.getColumnIndex} returns -1 for a missing column; the
     * {@code COLUMN_NOT_FOUND} constant is also -1, so a simple compare
     * suffices. */
    private static boolean hasSourceImageColumn(ResultsTable rt) {
        return rt.getColumnIndex(SourceImageTagger.COLUMN_NAME)
                != ResultsTable.COLUMN_NOT_FOUND;
    }
}
