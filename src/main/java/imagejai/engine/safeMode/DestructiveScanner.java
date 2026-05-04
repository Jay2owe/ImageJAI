package imagejai.engine.safeMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 05 (docs/safe_mode_v2/05_destructive-scanner-expansion.md):
 * scientific-integrity scanner. Pure-data static scan of an ImageJ macro
 * source against a {@link Context} snapshot of the live image / RoiManager /
 * Results state, returning a list of {@link DestructiveOp} findings the
 * caller folds into the destructive-op block / auto-backup pipeline.
 *
 * <p>Seven rules ship in v2:
 * <ul>
 *   <li>{@code calibration_loss} — {@code run("Properties...")} with
 *       {@code pixel_width=1} or {@code setVoxelSize(_,_,_,"pixel")} on an
 *       image whose calibration is currently non-trivial.</li>
 *   <li>{@code roi_wipe_with_backup} — {@code roiManager("reset")} or
 *       {@code roiManager("delete")} while the live RoiManager has &gt;0
 *       ROIs. Severity is {@code BACKUP_THEN_ALLOW}: the caller writes a
 *       ZIP and lets the macro proceed.</li>
 *   <li>{@code zproject_overwrite} — {@code run("Z Project...")} followed
 *       in the same macro by a {@code saveAs} whose target equals the
 *       active image's source path.</li>
 *   <li>{@code microscopy_overwrite} — {@code saveAs("PNG"/"JPEG"/"JPG", path)}
 *       overwriting the active image's microscopy original or any existing
 *       file with a microscopy extension. New files anywhere under
 *       {@code AI_Exports/} always allowed.</li>
 *   <li>{@code bit_depth_narrowing} (opt-in) — {@code run("8-bit")} /
 *       {@code run("16-bit")} when current bit depth &gt; target AND
 *       Results table has pending rows.</li>
 *   <li>{@code normalize_contrast} (opt-in) —
 *       {@code run("Enhance Contrast", "...normalize...")}.</li>
 * </ul>
 *
 * <p>Plus {@link #hasDiskWrites(String)} — subsumes the regex previously
 * living in {@code UndoFrame.macroHasDiskWrites} so the disk-write
 * detection lives in one place.
 *
 * <p>Detection is regex-only. Per plan §Known risks, agents that build
 * {@code saveAs} paths dynamically (from variables) will slip through;
 * symbolic execution is deferred to v3.
 */
public final class DestructiveScanner {

    private DestructiveScanner() {}

    /**
     * Severity of a single finding. Drives the caller's reaction:
     * {@link #REJECT} → block the macro and surface a structured-error
     * envelope; {@link #BACKUP_THEN_ALLOW} → take the side-effect that
     * preserves the user's state and let the macro proceed.
     */
    public enum Severity { REJECT, BACKUP_THEN_ALLOW }

    /**
     * Rule identifiers. Stable across versions — surfaced in friction-log
     * rows and the structured error reply's {@code operations[].rule_id}
     * field so downstream agents can branch on them.
     */
    public static final String RULE_CALIBRATION_LOSS    = "calibration_loss";
    public static final String RULE_ROI_WIPE            = "roi_wipe_with_backup";
    public static final String RULE_ZPROJECT_OVERWRITE  = "zproject_overwrite";
    public static final String RULE_MICROSCOPY_OVERWRITE = "microscopy_overwrite";
    public static final String RULE_BIT_DEPTH_NARROWING = "bit_depth_narrowing";
    public static final String RULE_NORMALIZE_CONTRAST  = "normalize_contrast";

    /**
     * Single scanner finding. Plain data — caller decides how to format
     * the structured-error reply and what to log.
     */
    public static final class DestructiveOp {
        public final String ruleId;
        public final Severity severity;
        public final String target;
        public final int line;
        public final String message;

        public DestructiveOp(String ruleId, Severity severity, String target,
                             int line, String message) {
            this.ruleId = ruleId;
            this.severity = severity;
            this.target = target == null ? "" : target;
            this.line = line;
            this.message = message == null ? "" : message;
        }
    }

    /**
     * Live-state snapshot the scanner consults. Populated by the TCP
     * handler from the active {@code ImagePlus} / {@code RoiManager} /
     * Results table just before scanning. Kept as a plain struct so unit
     * tests can construct synthetic contexts without spinning up Fiji.
     *
     * <p>Field semantics:
     * <ul>
     *   <li>{@code activeImagePath} — absolute path of the active image's
     *       on-disk source, or {@code null} when the image was not loaded
     *       from disk. Used by the Z-project and microscopy-overwrite
     *       rules to know what counts as "the original".</li>
     *   <li>{@code aiExportsRoot} — absolute prefix of the per-image
     *       {@code AI_Exports/} directory, or {@code null} when one has
     *       not been resolved. {@code saveAs} into this prefix is always
     *       allowed.</li>
     *   <li>{@code currentBitDepth} — active image's current bit depth
     *       (8 / 16 / 24 / 32). Drives bit-depth narrowing detection.
     *       0 / negative when no active image.</li>
     *   <li>{@code calibrationActive} — true when the active image's
     *       calibration is non-trivial (pixelWidth != 1.0 OR units != "pixel").
     *       Drives the calibration-loss rule.</li>
     *   <li>{@code roiManagerCount} — number of ROIs currently held by
     *       the live RoiManager. Drives ROI-wipe backup decision.</li>
     *   <li>{@code resultsRowCount} — current Results-table row count.
     *       Drives bit-depth narrowing's "pending measurements" guard.</li>
     *   <li>{@code blockBitDepthNarrowing} / {@code blockNormalizeContrast} —
     *       opt-in feature flags from {@code caps.safeModeOptions}. Off
     *       by default; Stage 05 ships them as opt-in because the right
     *       policy depends on what the user is measuring.</li>
     * </ul>
     */
    public static final class Context {
        public final String activeImagePath;
        public final String aiExportsRoot;
        public final int currentBitDepth;
        public final boolean calibrationActive;
        public final int roiManagerCount;
        public final int resultsRowCount;
        public final boolean blockBitDepthNarrowing;
        public final boolean blockNormalizeContrast;
        public final FileExistsCheck fileExists;

        public Context(String activeImagePath,
                       String aiExportsRoot,
                       int currentBitDepth,
                       boolean calibrationActive,
                       int roiManagerCount,
                       int resultsRowCount,
                       boolean blockBitDepthNarrowing,
                       boolean blockNormalizeContrast,
                       FileExistsCheck fileExists) {
            this.activeImagePath = activeImagePath;
            this.aiExportsRoot = aiExportsRoot;
            this.currentBitDepth = currentBitDepth;
            this.calibrationActive = calibrationActive;
            this.roiManagerCount = roiManagerCount;
            this.resultsRowCount = resultsRowCount;
            this.blockBitDepthNarrowing = blockBitDepthNarrowing;
            this.blockNormalizeContrast = blockNormalizeContrast;
            this.fileExists = fileExists != null ? fileExists : DEFAULT_NO_FILES;
        }
    }

    /**
     * Pluggable filesystem probe — production passes a lambda backed by
     * {@code java.nio.file.Files.exists}; tests pass a fixed set so the
     * suite stays headless and reproducible.
     */
    public interface FileExistsCheck {
        boolean exists(String path);
    }

    private static final FileExistsCheck DEFAULT_NO_FILES = new FileExistsCheck() {
        @Override
        public boolean exists(String path) { return false; }
    };

    // -----------------------------------------------------------------------
    // Patterns. All package-private so unit tests can reference them by name
    // (e.g. assert that the Z-Project rule is anchored on the canonical
    // "Z Project..." command name as the IJ Recorder emits it).
    // -----------------------------------------------------------------------

    /** Matches {@code run("Properties...", "...")} — captures the args group. */
    static final Pattern PROPERTIES_RUN = Pattern.compile(
            "run\\s*\\(\\s*[\"']Properties\\.\\.\\.[\"']\\s*,\\s*[\"']([^\"']*)[\"']");

    /** Matches {@code setVoxelSize(w, h, d, "unit")} — captures the unit arg. */
    static final Pattern SET_VOXEL_SIZE = Pattern.compile(
            "setVoxelSize\\s*\\(\\s*[^,]+,\\s*[^,]+,\\s*[^,]+,\\s*[\"']([^\"']*)[\"']\\s*\\)");

    /** Matches {@code roiManager("reset"|"delete")}. */
    static final Pattern ROI_RESET = Pattern.compile(
            "roiManager\\s*\\(\\s*[\"'](reset|Reset|delete|Delete)[\"']\\s*\\)");

    /** Matches {@code run("Z Project...", "...")}. */
    static final Pattern Z_PROJECT_RUN = Pattern.compile(
            "run\\s*\\(\\s*[\"']Z Project\\.\\.\\.[\"']");

    /** Matches {@code saveAs("FORMAT", "path")} — captures format + path. */
    static final Pattern SAVE_AS = Pattern.compile(
            "saveAs\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*[\"']([^\"']+)[\"']\\s*\\)");

    /** Matches {@code run("8-bit")} / {@code run("16-bit")}. */
    static final Pattern BIT_DEPTH_RUN = Pattern.compile(
            "run\\s*\\(\\s*[\"'](8-bit|16-bit)[\"']\\s*\\)");

    /** Matches {@code run("Enhance Contrast", "...normalize...")}. */
    static final Pattern ENHANCE_CONTRAST = Pattern.compile(
            "run\\s*\\(\\s*[\"']Enhance Contrast[\"']\\s*,\\s*[\"']([^\"']*)[\"']");

    /** Microscopy-format extensions the overwrite rule guards. */
    private static final String[] MICROSCOPY_EXTS = {
            ".lif", ".czi", ".nd2", ".ome.tif", ".ome.tiff", ".tif", ".tiff",
            ".lsm", ".oib", ".oif"
    };

    /** Image-export formats the microscopy-overwrite rule watches. */
    private static final String[] WATCHED_SAVE_FORMATS = {
            "png", "jpeg", "jpg"
    };

    // -----------------------------------------------------------------------
    // Scan
    // -----------------------------------------------------------------------

    /**
     * Run every rule against the macro source. Empty list = nothing to
     * worry about. Findings carry rule_id, severity, line number, target,
     * and a one-line message — caller stitches them into the
     * structured-error reply.
     */
    public static List<DestructiveOp> scan(String code, Context ctx) {
        List<DestructiveOp> out = new ArrayList<DestructiveOp>();
        if (code == null || code.isEmpty() || ctx == null) return out;

        scanCalibrationLoss(code, ctx, out);
        scanRoiWipe(code, ctx, out);
        scanZProjectOverwrite(code, ctx, out);
        scanMicroscopyOverwrite(code, ctx, out);
        scanBitDepthNarrowing(code, ctx, out);
        scanNormalizeContrast(code, ctx, out);
        return out;
    }

    /**
     * Disk-write heuristic. Subsumed from {@code UndoFrame.macroHasDiskWrites}
     * so the regex lives next to the other safe-mode checks. Used by the
     * undo / rescue path to decide whether to attach a
     * {@code diskSideEffectWarning} to a rewind reply — does NOT block,
     * does NOT add to the {@link DestructiveOp} list.
     */
    public static boolean hasDiskWrites(String macro) {
        if (macro == null) return false;
        String m = macro.toLowerCase(Locale.ROOT);
        return m.contains("saveas(")
            || m.contains("ij.save")
            || m.contains("file.save")
            || m.contains("file.copy")
            || m.contains("file.append")
            || m.contains("file.write")
            || m.contains("savetable(")
            || m.contains("run(\"save\"")
            || m.contains("run(\"tiff");
    }

    /**
     * Caller helper — true when any finding has {@code REJECT} severity.
     * The {@code BACKUP_THEN_ALLOW} entries in the same list still need
     * to be processed (the backup taken) but they don't gate the macro.
     */
    public static boolean hasRejection(List<DestructiveOp> ops) {
        if (ops == null) return false;
        for (DestructiveOp op : ops) {
            if (op.severity == Severity.REJECT) return true;
        }
        return false;
    }

    /** Filter helper — keep only {@code REJECT} entries. */
    public static List<DestructiveOp> rejections(List<DestructiveOp> ops) {
        if (ops == null) return Collections.emptyList();
        List<DestructiveOp> out = new ArrayList<DestructiveOp>();
        for (DestructiveOp op : ops) {
            if (op.severity == Severity.REJECT) out.add(op);
        }
        return out;
    }

    /** Filter helper — keep only {@code BACKUP_THEN_ALLOW} entries. */
    public static List<DestructiveOp> backups(List<DestructiveOp> ops) {
        if (ops == null) return Collections.emptyList();
        List<DestructiveOp> out = new ArrayList<DestructiveOp>();
        for (DestructiveOp op : ops) {
            if (op.severity == Severity.BACKUP_THEN_ALLOW) out.add(op);
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Rules
    // -----------------------------------------------------------------------

    /**
     * Calibration loss: {@code run("Properties...", "...pixel_width=1...")}
     * OR {@code setVoxelSize(_,_,_,"pixel")}. Only fires when the live
     * image actually has non-trivial calibration — flagging a no-op reset
     * on an uncalibrated image would just be noise.
     */
    private static void scanCalibrationLoss(String code, Context ctx, List<DestructiveOp> out) {
        if (!ctx.calibrationActive) return;

        Matcher pm = PROPERTIES_RUN.matcher(code);
        while (pm.find()) {
            String args = pm.group(1).toLowerCase(Locale.ROOT);
            if (args.contains("pixel_width=1") && !args.contains("pixel_width=1.0e")) {
                out.add(new DestructiveOp(
                        RULE_CALIBRATION_LOSS, Severity.REJECT,
                        "run(\"Properties...\")",
                        lineOf(code, pm.start()),
                        "Macro resets pixel_width to 1 on an image with active calibration; "
                                + "this turns micron measurements into pixel counts."));
            }
        }

        Matcher vm = SET_VOXEL_SIZE.matcher(code);
        while (vm.find()) {
            String unit = vm.group(1);
            if ("pixel".equalsIgnoreCase(unit) || "pixels".equalsIgnoreCase(unit)
                    || unit == null || unit.isEmpty()) {
                out.add(new DestructiveOp(
                        RULE_CALIBRATION_LOSS, Severity.REJECT,
                        "setVoxelSize(_,_,_,\"" + unit + "\")",
                        lineOf(code, vm.start()),
                        "setVoxelSize with unit=\"" + unit
                                + "\" overwrites active calibration; intensity / area "
                                + "measurements lose their physical scale."));
            }
        }
    }

    /**
     * ROI wipe with auto-backup: {@code roiManager("reset")} or
     * {@code roiManager("delete")} while the live RoiManager has &gt;0
     * ROIs. Severity is {@code BACKUP_THEN_ALLOW} — the caller writes a
     * ZIP to {@code AI_Exports/.safemode_roi_<ts>.zip} and lets the
     * macro proceed. No backup, no finding when the manager is empty.
     */
    private static void scanRoiWipe(String code, Context ctx, List<DestructiveOp> out) {
        if (ctx.roiManagerCount <= 0) return;
        Matcher m = ROI_RESET.matcher(code);
        if (m.find()) {
            String op = m.group(1);
            out.add(new DestructiveOp(
                    RULE_ROI_WIPE, Severity.BACKUP_THEN_ALLOW,
                    "roiManager(\"" + op + "\")",
                    lineOf(code, m.start()),
                    "Macro will wipe " + ctx.roiManagerCount
                            + " ROI(s) from the manager; auto-backup written before reset."));
        }
    }

    /**
     * Z-project overwrite: a {@code Z Project...} call followed in the
     * same macro by a {@code saveAs} whose target equals the active
     * image's source path. The order matters — saveAs first then Z
     * Project would not overwrite the source with the projection.
     */
    private static void scanZProjectOverwrite(String code, Context ctx, List<DestructiveOp> out) {
        if (ctx.activeImagePath == null || ctx.activeImagePath.isEmpty()) return;

        Matcher zm = Z_PROJECT_RUN.matcher(code);
        if (!zm.find()) return;
        int zStart = zm.start();

        Matcher sm = SAVE_AS.matcher(code);
        while (sm.find()) {
            if (sm.start() <= zStart) continue;
            String target = sm.group(2);
            if (pathEquals(target, ctx.activeImagePath)) {
                out.add(new DestructiveOp(
                        RULE_ZPROJECT_OVERWRITE, Severity.REJECT,
                        target,
                        lineOf(code, sm.start()),
                        "Z Project produces a 2D image; saving it over the source "
                                + "stack permanently discards the volumetric data."));
                return;
            }
        }
    }

    /**
     * Microscopy-format overwrite: {@code saveAs("PNG"/"JPEG"/"JPG", path)}
     * targeting either the active image's source file or any existing
     * file with a microscopy extension. Saves to anywhere under
     * {@code AI_Exports/} are always allowed — that's the project's
     * sanctioned export location.
     */
    private static void scanMicroscopyOverwrite(String code, Context ctx, List<DestructiveOp> out) {
        Matcher m = SAVE_AS.matcher(code);
        while (m.find()) {
            String fmt = m.group(1).toLowerCase(Locale.ROOT);
            String target = m.group(2);

            if (!isWatchedSaveFormat(fmt)) continue;
            if (isUnderAiExports(target, ctx.aiExportsRoot)) continue;

            boolean overwritesOriginal =
                    ctx.activeImagePath != null
                            && pathEquals(target, ctx.activeImagePath);

            boolean targetIsMicroscopy =
                    endsWithAny(target.toLowerCase(Locale.ROOT), MICROSCOPY_EXTS);
            boolean targetExists = targetIsMicroscopy
                    && ctx.fileExists != null
                    && ctx.fileExists.exists(target);

            if (overwritesOriginal || (targetIsMicroscopy && targetExists)) {
                out.add(new DestructiveOp(
                        RULE_MICROSCOPY_OVERWRITE, Severity.REJECT,
                        target,
                        lineOf(code, m.start()),
                        "saveAs(" + fmt.toUpperCase(Locale.ROOT) + ") would overwrite a "
                                + "microscopy file (" + target + "); 8-bit RGB output "
                                + "destroys the raw bit depth and metadata."));
            }
        }
    }

    /**
     * Bit-depth narrowing (opt-in): {@code run("8-bit")} or
     * {@code run("16-bit")} when the current image is wider than the
     * target bit depth AND the Results table has pending rows. Both
     * conditions must hold — narrowing on a fresh image with no
     * measurements is a legitimate display step.
     */
    private static void scanBitDepthNarrowing(String code, Context ctx, List<DestructiveOp> out) {
        if (!ctx.blockBitDepthNarrowing) return;
        if (ctx.currentBitDepth <= 0) return;
        if (ctx.resultsRowCount <= 0) return;

        Matcher m = BIT_DEPTH_RUN.matcher(code);
        while (m.find()) {
            String tag = m.group(1);
            int target = "8-bit".equals(tag) ? 8 : 16;
            if (ctx.currentBitDepth <= target) continue;
            out.add(new DestructiveOp(
                    RULE_BIT_DEPTH_NARROWING, Severity.REJECT,
                    "run(\"" + tag + "\")",
                    lineOf(code, m.start()),
                    "Narrowing " + ctx.currentBitDepth + "-bit pixels to " + tag
                            + " while " + ctx.resultsRowCount + " Results row(s) are "
                            + "pending invalidates the intensities those rows already cite."));
        }
    }

    /**
     * Enhance Contrast normalize (opt-in):
     * {@code run("Enhance Contrast", "...normalize...")}. Per the
     * project's house rules normalize=true rewrites pixel values; safe
     * for display, never on data being measured. Stage 05 codifies it
     * as opt-in because some users explicitly want a normalised display.
     */
    private static void scanNormalizeContrast(String code, Context ctx, List<DestructiveOp> out) {
        if (!ctx.blockNormalizeContrast) return;
        Matcher m = ENHANCE_CONTRAST.matcher(code);
        while (m.find()) {
            String args = m.group(1).toLowerCase(Locale.ROOT);
            if (!args.contains("normalize")) continue;
            out.add(new DestructiveOp(
                    RULE_NORMALIZE_CONTRAST, Severity.REJECT,
                    "run(\"Enhance Contrast\", \"" + m.group(1) + "\")",
                    lineOf(code, m.start()),
                    "Enhance Contrast with normalize=true rewrites pixel values; "
                            + "use setMinAndMax for display-only contrast on data being measured."));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** 1-based line containing {@code charOffset}. Naive — same as MacroAnalyser. */
    static int lineOf(String code, int charOffset) {
        if (code == null || charOffset <= 0) return 1;
        int line = 1;
        int limit = Math.min(charOffset, code.length());
        for (int i = 0; i < limit; i++) {
            if (code.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * Path equality with normalised separators. Both sides have backslashes
     * converted to forward slashes and a trailing slash stripped before
     * compare — covers the {@code C:\\foo\\bar.tif} vs {@code C:/foo/bar.tif}
     * mismatch macros and Java APIs hand back interchangeably on Windows.
     */
    static boolean pathEquals(String a, String b) {
        if (a == null || b == null) return false;
        return normalisePath(a).equalsIgnoreCase(normalisePath(b));
    }

    private static String normalisePath(String p) {
        String n = p.replace('\\', '/');
        while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
        return n;
    }

    /** True when {@code target} sits anywhere under {@code root} (prefix match). */
    static boolean isUnderAiExports(String target, String root) {
        if (target == null || target.isEmpty()) return false;
        if (root == null || root.isEmpty()) {
            // No resolved root — allow the literal "AI_Exports/" segment
            // anywhere in the path so static unit tests work without a Fiji.
            return target.replace('\\', '/').contains("/AI_Exports/")
                    || target.replace('\\', '/').startsWith("AI_Exports/");
        }
        String t = normalisePath(target);
        String r = normalisePath(root);
        return t.toLowerCase(Locale.ROOT).startsWith(r.toLowerCase(Locale.ROOT) + "/")
                || t.equalsIgnoreCase(r);
    }

    private static boolean isWatchedSaveFormat(String fmt) {
        if (fmt == null) return false;
        String f = fmt.toLowerCase(Locale.ROOT);
        for (String w : WATCHED_SAVE_FORMATS) {
            if (f.equals(w)) return true;
        }
        return false;
    }

    private static boolean endsWithAny(String lowered, String[] suffixes) {
        for (String s : suffixes) {
            if (lowered.endsWith(s)) return true;
        }
        return false;
    }
}
