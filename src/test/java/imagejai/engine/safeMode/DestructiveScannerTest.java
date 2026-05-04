package imagejai.engine.safeMode;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stage 05 of {@code docs/safe_mode_v2/} — verify the seven scientific-
 * integrity rules of {@link DestructiveScanner} fire only when the live
 * image / RoiManager / Results state matches the rule's preconditions, and
 * stay silent otherwise. Tests build {@link DestructiveScanner.Context}
 * fixtures by hand so the suite stays headless.
 *
 * <p>Also covers the {@code hasDiskWrites} subsumption from
 * {@code UndoFrame.macroHasDiskWrites} — the regex moved here in stage 05
 * and the original tests track with it.
 */
public class DestructiveScannerTest {

    /** Default context: no calibration, no ROIs, no Results, image on disk. */
    private DestructiveScanner.Context baseCtx() {
        return new DestructiveScanner.Context(
                "/raw/cells.lif",
                "/raw/AI_Exports",
                16,
                false,
                0,
                0,
                false,
                false,
                noFiles());
    }

    private DestructiveScanner.FileExistsCheck noFiles() {
        return new DestructiveScanner.FileExistsCheck() {
            @Override public boolean exists(String path) { return false; }
        };
    }

    private DestructiveScanner.FileExistsCheck filesPresent(final String... paths) {
        final Set<String> set = new HashSet<String>();
        for (String p : paths) set.add(p);
        return new DestructiveScanner.FileExistsCheck() {
            @Override public boolean exists(String path) { return set.contains(path); }
        };
    }

    // -----------------------------------------------------------------------
    // Calibration loss
    // -----------------------------------------------------------------------

    /** {@code Properties...} with pixel_width=1 on a calibrated image → reject. */
    @Test
    public void calibrationLossPropertiesPixelWidthOneBlocked() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, /*calibrationActive=*/ true,
                0, 0, false, false, noFiles());
        String code = "run(\"Properties...\", \"channels=1 pixel_width=1 pixel_height=1 voxel_depth=1\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_CALIBRATION_LOSS, ops.get(0).ruleId);
        assertEquals(DestructiveScanner.Severity.REJECT, ops.get(0).severity);
    }

    /** Same macro on an uncalibrated image → no finding (already pixel-units). */
    @Test
    public void calibrationLossSilentWhenImageIsNotCalibrated() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, /*calibrationActive=*/ false,
                0, 0, false, false, noFiles());
        String code = "run(\"Properties...\", \"channels=1 pixel_width=1\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    /** {@code setVoxelSize(_,_,_,"pixel")} on a calibrated image → reject. */
    @Test
    public void calibrationLossSetVoxelSizePixelUnitBlocked() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, true,
                0, 0, false, false, noFiles());
        String code = "setVoxelSize(1, 1, 1, \"pixel\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_CALIBRATION_LOSS, ops.get(0).ruleId);
    }

    /** {@code setVoxelSize(_,_,_,"micron")} → no finding (legitimate calibration set). */
    @Test
    public void calibrationLossSilentWhenSetVoxelSizeIsPhysicalUnit() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, true, 0, 0, false, false, noFiles());
        String code = "setVoxelSize(0.32, 0.32, 1.0, \"micron\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    // -----------------------------------------------------------------------
    // ROI wipe + auto-backup
    // -----------------------------------------------------------------------

    /** {@code roiManager("reset")} with 50 ROIs → BACKUP_THEN_ALLOW finding. */
    @Test
    public void roiWipeWithBackupFiresWhenManagerHasRois() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, false,
                /*roiManagerCount=*/ 50,
                0, false, false, noFiles());
        String code = "roiManager(\"reset\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_ROI_WIPE, ops.get(0).ruleId);
        assertEquals(DestructiveScanner.Severity.BACKUP_THEN_ALLOW, ops.get(0).severity);
        assertTrue("REJECT helper sees no rejects",
                DestructiveScanner.rejections(ops).isEmpty());
        assertEquals(1, DestructiveScanner.backups(ops).size());
    }

    /** Same call with an empty manager → no finding (nothing to back up). */
    @Test
    public void roiWipeSilentWhenManagerIsEmpty() {
        DestructiveScanner.Context ctx = baseCtx();
        String code = "roiManager(\"reset\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    /** {@code roiManager("Delete")} variant detected too. */
    @Test
    public void roiWipeRecognisesDeleteVerb() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, false, 12, 0, false, false, noFiles());
        String code = "roiManager(\"Delete\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_ROI_WIPE, ops.get(0).ruleId);
    }

    // -----------------------------------------------------------------------
    // Z-project overwrite
    // -----------------------------------------------------------------------

    /** Z Project then saveAs to original path → reject. */
    @Test
    public void zProjectOverwriteBlocked() {
        DestructiveScanner.Context ctx = baseCtx();
        String code = "run(\"Z Project...\", \"projection=[Max Intensity]\");\n"
                + "saveAs(\"Tiff\", \"/raw/cells.lif\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_ZPROJECT_OVERWRITE, ops.get(0).ruleId);
    }

    /** saveAs first, Z Project second → no overwrite finding (order matters). */
    @Test
    public void zProjectOverwriteIgnoresSaveBeforeProject() {
        DestructiveScanner.Context ctx = baseCtx();
        String code = "saveAs(\"Tiff\", \"/raw/cells.lif\");\n"
                + "run(\"Z Project...\", \"projection=[Max Intensity]\");";
        // Saving the original to itself isn't the Z-project rule; the Z-Project
        // rule needs the projection to land first. (The microscopy-overwrite
        // rule isn't tripped because saveAs format here is "Tiff", not PNG/JPEG.)
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertTrue(ops.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Microscopy overwrite
    // -----------------------------------------------------------------------

    /** PNG export to {@code AI_Exports/} → always allowed. */
    @Test
    public void saveAsPngToAiExportsAllowed() {
        DestructiveScanner.Context ctx = baseCtx();
        String code = "saveAs(\"PNG\", \"/raw/AI_Exports/figure.png\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    /** PNG saveAs over an existing .lif file → reject. */
    @Test
    public void saveAsPngOverExistingMicroscopyBlocked() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, false, 0, 0, false, false,
                filesPresent("/raw/cells.lif"));
        String code = "saveAs(\"PNG\", \"/raw/cells.lif\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_MICROSCOPY_OVERWRITE, ops.get(0).ruleId);
    }

    /** PNG saveAs to a brand new path with .png extension → no finding. */
    @Test
    public void saveAsPngToNewLocationAllowed() {
        DestructiveScanner.Context ctx = baseCtx();
        String code = "saveAs(\"PNG\", \"/elsewhere/figure.png\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    /** JPEG over an existing .czi file → reject. */
    @Test
    public void saveAsJpegOverExistingCziBlocked() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/sample.czi", "/raw/AI_Exports",
                16, false, 0, 0, false, false,
                filesPresent("/raw/sample.czi"));
        String code = "saveAs(\"JPEG\", \"/raw/sample.czi\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_MICROSCOPY_OVERWRITE, ops.get(0).ruleId);
    }

    // -----------------------------------------------------------------------
    // Bit-depth narrowing (opt-in)
    // -----------------------------------------------------------------------

    /** 16-bit image → 8-bit with pending Results AND option ON → reject. */
    @Test
    public void bitDepthNarrowingBlockedWhenOptionOnAndResultsPending() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                /*currentBitDepth=*/ 16, false,
                0, /*resultsRowCount=*/ 12,
                /*blockBitDepthNarrowing=*/ true,
                false, noFiles());
        String code = "run(\"8-bit\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_BIT_DEPTH_NARROWING, ops.get(0).ruleId);
    }

    /** Same call with the option OFF → silent. */
    @Test
    public void bitDepthNarrowingSilentWhenOptionOff() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, false, 0, 12,
                /*blockBitDepthNarrowing=*/ false,
                false, noFiles());
        String code = "run(\"8-bit\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    /** Option ON but no pending Results → silent (no measurement to invalidate). */
    @Test
    public void bitDepthNarrowingSilentWhenNoPendingResults() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, false, 0, /*resultsRowCount=*/ 0,
                true, false, noFiles());
        String code = "run(\"8-bit\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    /** {@code run("8-bit")} on an already-8-bit image → silent (no narrowing). */
    @Test
    public void bitDepthNarrowingSilentWhenAlreadyAtTarget() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                /*currentBitDepth=*/ 8, false, 0, 12,
                true, false, noFiles());
        String code = "run(\"8-bit\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Enhance Contrast normalize (opt-in)
    // -----------------------------------------------------------------------

    /** {@code Enhance Contrast normalize=true} with option ON → reject. */
    @Test
    public void normalizeContrastBlockedWhenOptionOn() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, false, 0, 0,
                false, /*blockNormalizeContrast=*/ true,
                noFiles());
        String code = "run(\"Enhance Contrast\", \"saturated=0.35 normalize\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_NORMALIZE_CONTRAST, ops.get(0).ruleId);
    }

    /** Same call with option OFF → silent (legacy behaviour preserved). */
    @Test
    public void normalizeContrastSilentWhenOptionOff() {
        DestructiveScanner.Context ctx = baseCtx();
        String code = "run(\"Enhance Contrast\", \"saturated=0.35 normalize\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    /** Enhance Contrast WITHOUT normalize, option ON → silent. */
    @Test
    public void normalizeContrastSilentWhenArgsLackNormalize() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, false, 0, 0,
                false, true, noFiles());
        String code = "run(\"Enhance Contrast\", \"saturated=0.35\");";
        assertTrue(DestructiveScanner.scan(code, ctx).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Subsumed disk-writes heuristic (was UndoFrame.macroHasDiskWrites)
    // -----------------------------------------------------------------------

    @Test
    public void hasDiskWritesDetectsCommonForms() {
        assertTrue(DestructiveScanner.hasDiskWrites("saveAs(\"Tiff\", \"/tmp/x.tif\");"));
        assertTrue(DestructiveScanner.hasDiskWrites("IJ.save(imp, \"/tmp/x.tif\");"));
        assertTrue(DestructiveScanner.hasDiskWrites("File.copy(a, b);"));
        assertTrue(DestructiveScanner.hasDiskWrites("saveTable(\"results.csv\");"));
        assertTrue(DestructiveScanner.hasDiskWrites("run(\"Save\")"));
    }

    @Test
    public void hasDiskWritesReturnsFalseForReadOnlyMacros() {
        assertFalse(DestructiveScanner.hasDiskWrites(null));
        assertFalse(DestructiveScanner.hasDiskWrites(""));
        assertFalse(DestructiveScanner.hasDiskWrites("run(\"Gaussian Blur...\", \"sigma=2\");"));
        assertFalse(DestructiveScanner.hasDiskWrites(
                "setAutoThreshold(\"Otsu\");\nrun(\"Convert to Mask\");"));
    }

    @Test
    public void hasDiskWritesDetectsFileWrite() {
        assertTrue(DestructiveScanner.hasDiskWrites(
                "f = File.open(\"out.txt\");\nFile.write(\"hi\", f);"));
    }

    // -----------------------------------------------------------------------
    // Sanity / null-safety
    // -----------------------------------------------------------------------

    @Test
    public void scanReturnsEmptyForNullOrBlankInput() {
        DestructiveScanner.Context ctx = baseCtx();
        assertTrue(DestructiveScanner.scan(null, ctx).isEmpty());
        assertTrue(DestructiveScanner.scan("", ctx).isEmpty());
        assertTrue(DestructiveScanner.scan("// nothing", ctx).isEmpty());
    }

    @Test
    public void scanReturnsEmptyForNullContext() {
        // Defensive — the dispatcher always supplies a context, but nested
        // helpers (e.g. a future intent dry-run path) might not.
        assertTrue(DestructiveScanner.scan("saveAs(\"PNG\", \"/raw/cells.lif\");", null).isEmpty());
    }

    @Test
    public void rejectionsAndBackupsHelpersFilterByseverity() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, true, /*roiCount=*/ 7, 0, false, false, noFiles());
        // ROI wipe (BACKUP) plus calibration loss (REJECT) in one macro.
        String code = "roiManager(\"reset\");\n"
                + "run(\"Properties...\", \"channels=1 pixel_width=1\");";
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(code, ctx);
        assertEquals(2, ops.size());
        assertEquals(1, DestructiveScanner.rejections(ops).size());
        assertEquals(1, DestructiveScanner.backups(ops).size());
        assertTrue(DestructiveScanner.hasRejection(ops));
    }
}
