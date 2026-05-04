package imagejai.engine.safeMode;

import ij.ImagePlus;
import ij.measure.ResultsTable;

import java.util.regex.Pattern;

/**
 * Stage 06 (docs/safe_mode_v2/06_results-source-image-column.md):
 * tag every Results-table row written during one {@code execute_macro}
 * call with the title of the image that was active when the row landed.
 *
 * <p>The biologist legitimately measures across many images and aggregates
 * into a single Results table. Without provenance, a wrong-image mixup
 * is silent. With a {@code Source_Image} column on every row the
 * aggregated table is filterable and a mixup is visible at a glance.
 *
 * <p>Lifecycle is {@code preExec} → caller runs the macro → {@code postExec}.
 * The pre-step records {@code rowsBefore} and the active image's title;
 * the post-step compares the row count against the snapshot and writes
 * {@code Source_Image} into every newly-added row, then refreshes the
 * Results window so the user sees the column populate.
 *
 * <p>Limitation: when a macro switches the active image mid-run via
 * {@code selectImage(...)} between two {@code Measure} calls, all rows
 * added during the macro receive the FINAL active image's title.
 * Documented in the plan §Known risks; provenance per row is v3 work.
 *
 * <p>Agent escape hatch: a macro carrying the comment
 * {@code // @safe_mode allow: legacy_results_format} skips column
 * injection for that call. Detected by {@link #macroOptsOut(String)};
 * the gating call site is responsible for skipping {@code preExec}
 * when the opt-out is present.
 */
public final class SourceImageTagger {

    /** Column name written to every new Results row. Public so tests / agent docs match. */
    public static final String COLUMN_NAME = "Source_Image";

    /** Label used when the active image has no title at preExec time. */
    public static final String UNKNOWN_LABEL = "<none>";

    /**
     * Matches the per-call opt-out annotation. Whitespace-tolerant and
     * case-insensitive so an agent typing {@code // @SAFE_MODE Allow:
     * legacy_results_format} still trips it.
     */
    private static final Pattern OPT_OUT = Pattern.compile(
            "//\\s*@safe_mode\\s+allow\\s*:\\s*legacy_results_format",
            Pattern.CASE_INSENSITIVE);

    private int rowsBefore;
    private String preExecTitle;
    private boolean preExecCalled;
    private boolean postExecDone;

    /**
     * True when the macro source contains {@code // @safe_mode allow:
     * legacy_results_format}. The gating call site uses this to skip
     * tagger construction entirely so the pre/post hooks become no-ops.
     */
    public static boolean macroOptsOut(String code) {
        if (code == null) return false;
        return OPT_OUT.matcher(code).find();
    }

    /**
     * Snapshot the row count and active-image title before the macro
     * runs. Production callers pass the live ImageJ {@link ResultsTable}
     * via {@link #preExec(ImagePlus)}; the explicit-table overload is
     * the test seam.
     */
    public void preExec(ImagePlus activeImp) {
        preExec(activeImp, ResultsTable.getResultsTable());
    }

    /** Test seam: explicit table so unit tests can run headless. */
    void preExec(ImagePlus activeImp, ResultsTable rt) {
        this.rowsBefore = (rt != null) ? rt.getCounter() : 0;
        String title = (activeImp != null) ? activeImp.getTitle() : null;
        this.preExecTitle = (title != null && !title.isEmpty()) ? title : UNKNOWN_LABEL;
        this.preExecCalled = true;
    }

    /**
     * Tag every row added since {@link #preExec} with the FINAL active
     * image's title (falling back to the pre-exec title when no image
     * is active after the macro). No-op when the row count did not
     * change. Refreshes the Results window so the column appears
     * immediately for the user. Production callers pass the live
     * {@link ResultsTable} via {@link #postExec(ImagePlus)}; the
     * explicit-table overload is the test seam.
     */
    public void postExec(ImagePlus activeImpAfter) {
        postExec(activeImpAfter, ResultsTable.getResultsTable(), true);
    }

    /** Test seam: explicit table + suppress {@code show("Results")} GUI side-effect. */
    void postExec(ImagePlus activeImpAfter, ResultsTable rt, boolean refreshWindow) {
        // Idempotent: a second invocation (e.g. exception safety-net in the
        // caller's outer finally) is a no-op so rows are not stamped twice.
        if (!preExecCalled || postExecDone) return;
        postExecDone = true;
        if (rt == null) return;
        int rowsAfter = rt.getCounter();
        if (rowsAfter <= rowsBefore) return;

        String label = preExecTitle;
        if (activeImpAfter != null) {
            String t = activeImpAfter.getTitle();
            if (t != null && !t.isEmpty()) label = t;
        }
        if (label == null || label.isEmpty()) label = UNKNOWN_LABEL;

        for (int r = rowsBefore; r < rowsAfter; r++) {
            rt.setValue(COLUMN_NAME, r, label);
        }
        if (refreshWindow) {
            try { rt.show("Results"); } catch (Throwable ignore) {}
        }
    }
}
