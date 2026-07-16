package imagejai.engine.safeMode;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    // Host/JVM code escapes
    // -----------------------------------------------------------------------

    @Test
    public void hostCodePrimitivesAreRejectedBeforeFiji() {
        String[] unsafe = {
                "exec(\"python\", \"-V\");",
                "eval(\"script\", \"java.lang.Runtime.getRuntime()\");",
                "call(\"java.lang.System.exit\", \"0\");",
                "runMacro(\"/tmp/untrusted.ijm\");",
                "runMacroFile(\"/tmp/untrusted.ijm\");",
                "IJ.runMacro(\"print(1);\");",
                "IJ.runMacroFile(\"/tmp/untrusted.ijm\");",
                "Ext.install(\"/tmp/extension.jar\");",
                "run(\"Script...\");",
                "run(\"Groovy Script\", \"script=[println 1]\");",
                "run(\"BeanShell Interpreter\");",
                "run(\"Compile and Run...\");",
                "doCommand(\"JavaScript Interpreter\");",
                "run(\"JRuby Interpreter\");",
                "run(\"Scr\" + \"ipt...\");",
                "command = \"Script...\"; run(command);"
        };

        for (String code : unsafe) {
            List<DestructiveScanner.DestructiveOp> ops =
                    DestructiveScanner.scan(code, baseCtx());
            assertEquals("expected one host-code finding for " + code,
                    1, ops.size());
            assertEquals(DestructiveScanner.RULE_HOST_CODE, ops.get(0).ruleId);
            assertEquals(DestructiveScanner.Severity.REJECT, ops.get(0).severity);
        }
    }

    @Test
    public void hostCodeWordsInStringsAndCommentsAreHarmless() {
        String code = "// exec(\\\"python\\\");\n"
                + "/* eval(\\\"script\\\", \\\"danger\\\"); "
                + "Ext.install(\\\"danger\\\"); */\n"
                + "print(\"call( and IJ.runMacro( are documentation\");\n"
                + "print(\"run('Script...') is documentation\");\n"
                + "run(\"Gaussian Blur...\", \"sigma=2\");\n"
                + "run(\"Descriptor-based registration (2d/3d)\");";
        assertTrue(DestructiveScanner.scan(code, baseCtx()).isEmpty());
    }

    @Test
    public void hostCodeFindingCarriesSourceLine() {
        String code = "run(\"Gaussian Blur...\", \"sigma=2\");\n"
                + "call(\"java.lang.System.exit\", \"0\");";
        List<DestructiveScanner.DestructiveOp> ops =
                DestructiveScanner.scan(code, baseCtx());
        assertEquals(1, ops.size());
        assertEquals(2, ops.get(0).line);
    }

    @Test
    public void contextFreeHostRuleStillFailsClosedWithoutImageContext() {
        List<DestructiveScanner.DestructiveOp> ops = DestructiveScanner.scan(
                "exec(\"python\", \"-V\");", null);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_HOST_CODE, ops.get(0).ruleId);
    }

    @Test
    public void explicitlyElevatedScriptScanDoesNotReapplyMacroHostGate() {
        assertTrue(DestructiveScanner.scanElevatedScript(
                "eval(\"arbitrary approved script code\");", baseCtx()).isEmpty());
    }

    @Test
    public void elevatedScriptScanRetainsScientificIntegrityRules() {
        DestructiveScanner.Context ctx = new DestructiveScanner.Context(
                "/raw/cells.lif", "/raw/AI_Exports",
                16, true, 0, 0, false, false, noFiles());
        List<DestructiveScanner.DestructiveOp> ops =
                DestructiveScanner.scanElevatedScript(
                        "run(\"Properties...\", \"pixel_width=1\");", ctx);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_CALIBRATION_LOSS, ops.get(0).ruleId);
    }

    // -----------------------------------------------------------------------
    // Unconditional macro filesystem policy
    // -----------------------------------------------------------------------

    @Test
    public void macroFilesystemReadsAndEnumerationAreRejected() {
        String[] unsafe = {
                "text = File.openAsString(\"/private/subjects.csv\");",
                "raw = File.openAsRawString(\"/private/image.bin\", 64);",
                "text = File.openUrlAsString(\"https://example.invalid/data\");",
                "File.openSequence(\"/private/images\", \"virtual\");",
                "path = File.openDialog(\"Choose private data\");",
                "open(\"/private/subject.tif\");",
                "openVirtual(\"/private/subject.tif\");",
                "names = getFileList(\"/private\");",
                "home = getDirectory(\"home\");",
                "size = File.length(\"/private/subject.tif\");",
                "size = File.getLength(\"/private/subject.tif\");",
                "present = File.exists(\"/private/subject.tif\");",
                "cwd = File.getAbsolutePath(\".\");",
                "defaultDir = File.getDefaultDir();",
                "lastDir = File.directory;",
                "lastName = File.name;",
                "imp = IJ.openImage(\"/private/subject.tif\");",
                "IJ.open(\"/private/subject.tif\");",
                "hash = IJ.checksum(\"MD5 file\", \"/private/subject.tif\");"
        };
        for (String code : unsafe) {
            List<DestructiveScanner.FilesystemAccess> access =
                    DestructiveScanner.classifyMacroFilesystem(
                            code, "/safe/AI_Exports");
            assertEquals("expected one filesystem finding for " + code,
                    1, access.size());
            assertFalse(access.get(0).allowed);
            assertEquals(DestructiveScanner.FilesystemKind.READ,
                    access.get(0).kind);
        }
    }

    @Test
    public void macroDestructiveAndImportPrimitivesAreRejected() {
        String[] unsafe = {
                "File.delete(\"/private/subject.tif\");",
                "File.rename(\"/private/a.tif\", \"/private/b.tif\");",
                "File.copy(\"/private/a.tif\", \"/safe/AI_Exports/a.tif\");",
                "File.setDefaultDir(\"/private\");",
                "run(\"Save\");",
                "run(\"Revert\");",
                "run(\"Open...\");",
                "run(\"Bio-Formats Importer\");"
        };
        for (String code : unsafe) {
            List<DestructiveScanner.DestructiveOp> ops =
                    DestructiveScanner.scanMacroFilesystem(
                            code, "/safe/AI_Exports");
            assertEquals("expected one rejected filesystem access for " + code,
                    1, ops.size());
            assertEquals(DestructiveScanner.RULE_MACRO_FILESYSTEM,
                    ops.get(0).ruleId);
            assertEquals(DestructiveScanner.Severity.REJECT,
                    ops.get(0).severity);
        }
    }

    @Test
    public void macroFilesystemWordsInStringsAndCommentsAreHarmless() {
        String code = "// File.delete(\"/private/a.tif\");\n"
                + "/* File.openAsString(\"/private/subjects.csv\"); */\n"
                + "print(\"File.copy(a,b) and open('/private') docs\");\n"
                + "name = File.getName(\"/private/subject.tif\");\n"
                + "parent = File.getParent(\"/private/subject.tif\");\n"
                + "separator = File.separator;\n"
                + "run(\"Gaussian Blur...\", \"sigma=2\");";
        assertTrue(DestructiveScanner.classifyMacroFilesystem(
                code, "/safe/AI_Exports").isEmpty());
    }

    @Test
    public void unknownFilePrimitiveAndDynamicWritesFailClosed() {
        String[] unsafe = {
                "File.futureFilesystemMethod(\"/private/a.tif\");",
                "IJ.futureFilesystemMethod(\"/private/a.tif\");",
                "File.write(\"data\", handle);",
                "saveAs(\"Tiff\", outputPath);",
                "File.saveString(\"data\", exportDir + \"/result.csv\");",
                "File.open(path);",
                "File.makeDirectory(directory);",
                "run(\"Tiff...\", \"save=\" + outputPath);"
        };
        for (String code : unsafe) {
            assertEquals("expected fail-closed finding for " + code, 1,
                    DestructiveScanner.scanMacroFilesystem(
                            code, "/safe/AI_Exports").size());
        }
    }

    @Test
    public void literalOutputWritesUnderResolvedAiExportsAreAllowed() {
        Path exports = Paths.get("target", "filesystem-policy", "AI_Exports")
                .toAbsolutePath().normalize();
        String root = exports.toString().replace('\\', '/');
        String code = "File.makeDirectory(\"" + root + "/nested\");\n"
                + "File.mkdir(\"" + root + "/nested\");\n"
                + "File.saveString(\"header\", \"" + root + "/result.csv\");\n"
                + "File.append(\"row\", \"" + root + "/result.csv\");\n"
                + "f = File.open(\"" + root + "/log.txt\");\n"
                + "print(f, \"ok\"); File.close(f);\n"
                + "saveAs(\"Results\", \"" + root + "/result.csv\");\n"
                + "IJ.saveAs(\"Tiff\", \"" + root + "/image.tif\");\n"
                + "IJ.saveAsTiff(\"" + root + "/image2.tif\");\n"
                + "IJ.saveString(\"data\", \"" + root + "/data.txt\");\n"
                + "run(\"Tiff...\", \"save=[" + root + "/run.tif]\");";

        List<DestructiveScanner.FilesystemAccess> access =
                DestructiveScanner.classifyMacroFilesystem(code, root);
        assertEquals(10, access.size());
        for (DestructiveScanner.FilesystemAccess item : access) {
            assertTrue(item.message, item.allowed);
            assertEquals(DestructiveScanner.FilesystemKind.OUTPUT_WRITE, item.kind);
        }
        assertTrue(DestructiveScanner.scanMacroFilesystem(code, root).isEmpty());
    }

    @Test
    public void literalOutputOutsideOrTraversingAiExportsIsRejected() {
        Path exports = Paths.get("target", "filesystem-policy", "AI_Exports")
                .toAbsolutePath().normalize();
        String root = exports.toString().replace('\\', '/');
        String outside = exports.resolve("..").resolve("subject.tif")
                .toString().replace('\\', '/');
        List<DestructiveScanner.DestructiveOp> ops =
                DestructiveScanner.scanMacroFilesystem(
                        "saveAs(\"Tiff\", \"" + outside + "\");", root);
        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_MACRO_FILESYSTEM,
                ops.get(0).ruleId);
    }

    @Test
    public void filesystemFindingCarriesSourceLine() {
        List<DestructiveScanner.DestructiveOp> ops =
                DestructiveScanner.scanMacroFilesystem(
                        "run(\"Gaussian Blur...\", \"sigma=2\");\n"
                                + "File.delete(\"/private/a.tif\");",
                        "/safe/AI_Exports");
        assertEquals(1, ops.size());
        assertEquals(2, ops.get(0).line);
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

    @Test
    public void aiExportsLexicalTraversalIsRejected() {
        DestructiveScanner.Context ctx = baseCtx();
        String code = "saveAs(\"PNG\", \"/raw/AI_Exports/../cells.lif\");";

        List<DestructiveScanner.DestructiveOp> ops =
                DestructiveScanner.scan(code, ctx);

        assertEquals(1, ops.size());
        assertEquals(DestructiveScanner.RULE_AI_EXPORTS_ESCAPE,
                ops.get(0).ruleId);
        assertEquals(DestructiveScanner.Severity.REJECT, ops.get(0).severity);
    }

    @Test
    public void canonicalSymlinkEscapeCannotClaimAiExportsContainment() {
        final Path root = Paths.get("safe", "AI_Exports")
                .toAbsolutePath().normalize();
        final Path target = root.resolve("link").resolve("figure.png");
        final Path outside = root.getParent().resolve("outside").resolve("figure.png");
        DestructiveScanner.CanonicalPathResolver resolver =
                new DestructiveScanner.CanonicalPathResolver() {
                    @Override
                    public Path canonicalise(Path intended) {
                        return intended.equals(root) ? root : outside;
                    }
                };

        assertEquals("ESCAPE", DestructiveScanner.containmentForTest(
                target.toString(), root.toString(), resolver));
    }

    @Test
    public void unreadableAiExportsParentFailsClosedInsteadOfLookingEmpty() {
        final Path root = Paths.get("safe", "AI_Exports")
                .toAbsolutePath().normalize();
        Path target = root.resolve("figure.png");
        DestructiveScanner.CanonicalPathResolver unreadable =
                new DestructiveScanner.CanonicalPathResolver() {
                    @Override
                    public Path canonicalise(Path intended) throws IOException {
                        throw new IOException("access denied");
                    }
                };

        assertEquals("ESCAPE", DestructiveScanner.containmentForTest(
                target.toString(), root.toString(), unreadable));
    }

    @Test
    public void literalAiExportsSegmentWithoutResolvedRootIsNotTrusted() {
        assertFalse(DestructiveScanner.isUnderAiExports(
                "AI_Exports/figure.png", null));
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
