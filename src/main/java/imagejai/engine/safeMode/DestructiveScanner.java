package imagejai.engine.safeMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * <p>The scanner includes these scientific-integrity and execution rules:
 * <ul>
 *   <li>{@code host_code_execution} â€” macro primitives such as
 *       {@code exec}, {@code eval}, {@code call}, nested macro runners,
 *       extension calls, and script-interpreter menu commands.</li>
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

    /** Macro filesystem classification used by the unconditional TCP gate. */
    public enum FilesystemKind { READ, DESTRUCTIVE, OUTPUT_WRITE, UNKNOWN }

    /**
     * One filesystem-capable macro call. Safe output writes are returned with
     * {@link #allowed} true so callers can audit them without blocking them.
     * Reads, destructive operations, unknown File.* calls, dynamic paths, and
     * outputs outside the resolved AI_Exports root have {@code allowed=false}.
     */
    public static final class FilesystemAccess {
        public final FilesystemKind kind;
        public final String primitive;
        public final String target;
        public final int line;
        public final boolean allowed;
        public final String message;

        FilesystemAccess(FilesystemKind kind, String primitive, String target,
                         int line, boolean allowed, String message) {
            this.kind = kind;
            this.primitive = primitive == null ? "" : primitive;
            this.target = target == null ? "" : target;
            this.line = line;
            this.allowed = allowed;
            this.message = message == null ? "" : message;
        }
    }

    /**
     * Rule identifiers. Stable across versions — surfaced in friction-log
     * rows and the structured error reply's {@code operations[].rule_id}
     * field so downstream agents can branch on them.
     */
    public static final String RULE_CALIBRATION_LOSS    = "calibration_loss";
    public static final String RULE_ROI_WIPE            = "roi_wipe_with_backup";
    public static final String RULE_ZPROJECT_OVERWRITE  = "zproject_overwrite";
    public static final String RULE_MICROSCOPY_OVERWRITE = "microscopy_overwrite";
    public static final String RULE_AI_EXPORTS_ESCAPE    = "ai_exports_escape";
    public static final String RULE_BIT_DEPTH_NARROWING = "bit_depth_narrowing";
    public static final String RULE_NORMALIZE_CONTRAST  = "normalize_contrast";
    public static final String RULE_HOST_CODE           = "host_code_execution";
    public static final String RULE_MACRO_FILESYSTEM    = "macro_filesystem_access";

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

    /** Macro-language escapes that can execute operating-system or JVM code. */
    static final Pattern HOST_EXEC = Pattern.compile(
            "(?<![\\w.])exec\\s*\\(", Pattern.CASE_INSENSITIVE);
    static final Pattern HOST_EVAL = Pattern.compile(
            "(?<![\\w.])eval\\s*\\(", Pattern.CASE_INSENSITIVE);
    static final Pattern HOST_CALL = Pattern.compile(
            "(?<![\\w.])call\\s*\\(", Pattern.CASE_INSENSITIVE);
    static final Pattern HOST_RUN_MACRO = Pattern.compile(
            "(?<![\\w.])runMacro(?:File)?\\s*\\(", Pattern.CASE_INSENSITIVE);
    static final Pattern HOST_IJ_RUN_MACRO = Pattern.compile(
            "\\bIJ\\s*\\.\\s*runMacro(?:File)?\\s*\\(", Pattern.CASE_INSENSITIVE);
    static final Pattern HOST_EXTENSION_CALL = Pattern.compile(
            "\\bExt\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    static final Pattern COMMAND_CALL_START = Pattern.compile(
            "(?<![\\w.])(run|doCommand)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final String[] HOST_COMMAND_TOKENS = {
            "script", "interpreter", "groovy", "beanshell", "javascript",
            "jython", "clojure"
    };
    private static final String[] HOST_COMMAND_PHRASES = {
            "compile and run", "run macro"
    };

    /** Calls whose names are needed for the standalone filesystem policy. */
    static final Pattern FILESYSTEM_CALL_START = Pattern.compile(
            "(?<![\\w.])((?:File|IJ)\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*|"
                    + "saveAs|save|getFileList|getDirectory|openVirtual|open|"
                    + "run|doCommand)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    static final Pattern FILESYSTEM_PROPERTY = Pattern.compile(
            "\\bFile\\s*\\.\\s*(directory|nameWithoutExtension|name|separator)"
                    + "\\b(?!\\s*\\()",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RUN_SAVE_PATH = Pattern.compile(
            "(?:^|\\s)save\\s*=\\s*(?:\\[([^\\]]+)\\]|([^\\s]+))",
            Pattern.CASE_INSENSITIVE);

    private static final String[] FILESYSTEM_READ_CALLS = {
            "open", "openvirtual", "getfilelist", "getdirectory",
            "file.openasstring", "file.openasrawstring",
            "file.openurlasstring", "file.openurl", "file.opensequence",
            "file.opendialog", "file.exists", "file.isfile",
            "file.isdirectory", "file.length", "file.getlength",
            "file.lastmodified", "file.datelastmodified",
            "file.getdefaultdir", "file.getabsolutepath", "file.directory",
            "file.name", "file.namewithoutextension",
            "ij.open", "ij.openimage", "ij.checksum"
    };

    private static final String[] FILESYSTEM_DESTRUCTIVE_CALLS = {
            "file.delete", "file.rename", "file.copy", "file.setdefaultdir"
    };

    private static final String[] FILESYSTEM_BENIGN_CALLS = {
            "file.getname", "file.getnamewithoutextension",
            "file.getdirectory", "file.getparent", "file.close", "file.separator",
            "ij.renameresults", "ij.deleterows", "ij.log",
            "ij.redirecterrormessages", "ij.freememory", "ij.currentmemory",
            "ij.maxmemory", "ij.getfullversion", "ij.gettoolname", "ij.pad"
    };

    private static final String[] READ_MENU_COMMANDS = {
            "open", "open...", "open next", "open samples",
            "image sequence...", "url...", "bio-formats importer",
            "bio-formats remote importer"
    };

    private static final String[] DESTRUCTIVE_MENU_COMMANDS = {
            "save", "save as...", "revert"
    };

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
        if (code == null || code.isEmpty()) return out;

        // This rule is context-free and is deliberately first: macro escapes
        // must be rejected before Fiji gets a chance to interpret the source.
        scanHostCode(code, out);
        if (ctx == null) return out;

        scanScientificRules(code, ctx, out);
        return out;
    }

    /**
     * Run only the scientific-integrity rules for an explicitly elevated
     * {@code run_script} request. Script code is intentionally host-capable;
     * applying ImageJ macro escape detection there would undo the separate
     * capability and one-call approval gate before this scanner is reached.
     */
    public static List<DestructiveOp> scanElevatedScript(String code, Context ctx) {
        List<DestructiveOp> out = new ArrayList<DestructiveOp>();
        if (code == null || code.isEmpty() || ctx == null) return out;
        scanScientificRules(code, ctx, out);
        return out;
    }

    /**
     * Classify every filesystem-capable ImageJ macro call independently of
     * safe-mode feature flags. The dispatcher uses this policy for every
     * macro entry point; trusted local host access belongs in the separately
     * elevated {@code run_script} capability.
     *
     * <p>The classifier is deliberately fail-closed: dynamic output paths,
     * malformed calls, and unknown {@code File.*} methods are denied. The
     * only allowed accesses are explicit output writes whose one literal path
     * resolves canonically beneath {@code aiExportsRoot}.
     */
    public static List<FilesystemAccess> classifyMacroFilesystem(
            String code, String aiExportsRoot) {
        List<FilesystemAccess> out = new ArrayList<FilesystemAccess>();
        if (code == null || code.isEmpty()) return out;

        MacroViews views = macroViews(code);
        Matcher properties = FILESYSTEM_PROPERTY.matcher(views.masked);
        while (properties.find()) {
            String property = properties.group(1);
            if ("separator".equalsIgnoreCase(property)) continue;
            out.add(filesystemDenied(FilesystemKind.READ,
                    "File." + property, "", lineOf(code, properties.start()),
                    "reveals the last selected host path"));
        }
        Matcher calls = FILESYSTEM_CALL_START.matcher(views.masked);
        while (calls.find()) {
            String rawName = calls.group(1);
            String name = rawName.replaceAll("\\s+", "")
                    .toLowerCase(Locale.ROOT);
            List<String> args = parseCallArguments(
                    views.commentsRemoved, calls.end() - 1);
            int line = lineOf(code, calls.start());

            if (equalsAny(name, FILESYSTEM_READ_CALLS)) {
                out.add(filesystemDenied(FilesystemKind.READ, rawName, "", line,
                        "reads, selects, or enumerates host data"));
                continue;
            }
            if (equalsAny(name, FILESYSTEM_DESTRUCTIVE_CALLS)) {
                out.add(filesystemDenied(FilesystemKind.DESTRUCTIVE, rawName, "", line,
                        "can modify, move, copy, or remove host files"));
                continue;
            }

            if ("run".equals(name) || "docommand".equals(name)) {
                if (args == null || args.isEmpty()) {
                    out.add(filesystemDenied(FilesystemKind.UNKNOWN, rawName, "", line,
                            "has malformed arguments"));
                    continue;
                }
                String command = literalString(args.get(0));
                if (command == null) {
                    out.add(filesystemDenied(FilesystemKind.UNKNOWN, rawName, "", line,
                            "uses a dynamic command name"));
                    continue;
                }
                String normalizedCommand = command.replaceAll("\\s+", " ")
                        .trim().toLowerCase(Locale.ROOT);
                if (equalsAny(normalizedCommand, READ_MENU_COMMANDS)) {
                    out.add(filesystemDenied(FilesystemKind.READ, rawName, command, line,
                            "opens or imports host data"));
                    continue;
                }
                if (equalsAny(normalizedCommand, DESTRUCTIVE_MENU_COMMANDS)) {
                    out.add(filesystemDenied(FilesystemKind.DESTRUCTIVE, rawName,
                            command, line, "can overwrite the current host file"));
                    continue;
                }
                if (args.size() >= 2) {
                    String options = literalString(args.get(1));
                    if (options == null) {
                        if (Pattern.compile("\\bsave\\s*=", Pattern.CASE_INSENSITIVE)
                                .matcher(args.get(1)).find()) {
                            out.add(filesystemDenied(FilesystemKind.OUTPUT_WRITE,
                                    rawName, "", line, "uses a dynamic save path"));
                        }
                        continue;
                    }
                    Matcher savePath = RUN_SAVE_PATH.matcher(options);
                    if (savePath.find()) {
                        String target = savePath.group(1) != null
                                ? savePath.group(1) : savePath.group(2);
                        out.add(classifyOutputWrite(rawName, target, line,
                                aiExportsRoot));
                    }
                }
                continue;
            }

            Integer pathIndex = filesystemWritePathIndex(name, args);
            if (pathIndex != null) {
                String target = null;
                if (args != null && pathIndex >= 0 && pathIndex < args.size()) {
                    target = literalString(args.get(pathIndex));
                }
                out.add(classifyOutputWrite(rawName, target, line, aiExportsRoot));
                continue;
            }

            if ((name.startsWith("file.") || name.startsWith("ij."))
                    && !equalsAny(name, FILESYSTEM_BENIGN_CALLS)) {
                out.add(filesystemDenied(FilesystemKind.UNKNOWN, rawName, "", line,
                        "is an unrecognised File.* or IJ.* primitive"));
            }
        }
        return out;
    }

    /** Convert denied classifications into the scanner's standard envelope. */
    public static List<DestructiveOp> scanMacroFilesystem(
            String code, String aiExportsRoot) {
        List<DestructiveOp> out = new ArrayList<DestructiveOp>();
        for (FilesystemAccess access : classifyMacroFilesystem(code, aiExportsRoot)) {
            if (access.allowed) continue;
            String target = access.target.isEmpty()
                    ? access.primitive : access.target;
            out.add(new DestructiveOp(RULE_MACRO_FILESYSTEM, Severity.REJECT,
                    target, access.line, access.message));
        }
        return out;
    }

    private static void scanScientificRules(String code, Context ctx,
                                            List<DestructiveOp> out) {
        scanCalibrationLoss(code, ctx, out);
        scanRoiWipe(code, ctx, out);
        scanZProjectOverwrite(code, ctx, out);
        scanMicroscopyOverwrite(code, ctx, out);
        scanBitDepthNarrowing(code, ctx, out);
        scanNormalizeContrast(code, ctx, out);
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

    /** Reject macro primitives and menu commands that execute host/JVM code. */
    private static void scanHostCode(String code, List<DestructiveOp> out) {
        MacroViews views = macroViews(code);
        scanHostPattern(code, views.masked, HOST_EXEC, "exec", out);
        scanHostPattern(code, views.masked, HOST_EVAL, "eval", out);
        scanHostPattern(code, views.masked, HOST_CALL, "call", out);
        scanHostPattern(code, views.masked, HOST_RUN_MACRO, "runMacro", out);
        scanHostPattern(code, views.masked, HOST_IJ_RUN_MACRO, "IJ.runMacro", out);
        scanHostPattern(code, views.masked, HOST_EXTENSION_CALL, "Ext.*", out);

        // Locate calls on the masked view so run(...) text inside strings is
        // ignored. The first argument must be one literal command name;
        // dynamic concatenation/variables could otherwise hide Script... .
        Matcher commands = COMMAND_CALL_START.matcher(views.masked);
        while (commands.find()) {
            String command = literalCommandArgument(
                    views.commentsRemoved, commands.end());
            if (command == null) {
                addHostCodeFinding(code, commands.start(),
                        commands.group(1) + "(<dynamic command>)", out);
            } else if (isHostCommandName(command)) {
                addHostCodeFinding(code, commands.start(),
                        commands.group(1) + "(\"" + command + "\")", out);
            }
        }
    }

    private static void scanHostPattern(String source, String masked,
                                        Pattern pattern, String target,
                                        List<DestructiveOp> out) {
        Matcher matcher = pattern.matcher(masked);
        while (matcher.find()) {
            addHostCodeFinding(source, matcher.start(), target, out);
        }
    }

    private static void addHostCodeFinding(String code, int offset,
                                           String target,
                                           List<DestructiveOp> out) {
        out.add(new DestructiveOp(
                RULE_HOST_CODE, Severity.REJECT, target, lineOf(code, offset),
                "Macro host/JVM code primitive '" + target
                        + "' is blocked before Fiji execution; use an explicitly "
                        + "elevated host-code tool when host execution is required."));
    }

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
            Containment containment = aiExportsContainment(
                    target, ctx.aiExportsRoot, REAL_PATH_RESOLVER);
            if (containment == Containment.INSIDE) continue;
            if (containment == Containment.ESCAPE) {
                out.add(new DestructiveOp(
                        RULE_AI_EXPORTS_ESCAPE, Severity.REJECT,
                        target,
                        lineOf(code, m.start()),
                        "Output path claims AI_Exports but escapes its canonical root "
                                + "through traversal, a symbolic link, or an unreadable parent."));
                continue;
            }

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

    private static FilesystemAccess filesystemDenied(
            FilesystemKind kind, String primitive, String target, int line,
            String reason) {
        return new FilesystemAccess(kind, primitive, target, line, false,
                "Macro filesystem primitive '" + primitive
                        + "' is blocked because it " + reason
                        + "; use separately elevated local run_script when "
                        + "host-file access is genuinely required.");
    }

    private static FilesystemAccess classifyOutputWrite(
            String primitive, String target, int line, String aiExportsRoot) {
        if (target == null) {
            return filesystemDenied(FilesystemKind.OUTPUT_WRITE, primitive, "",
                    line, "does not use one plain literal output path");
        }
        Containment containment = aiExportsContainment(
                target, aiExportsRoot, REAL_PATH_RESOLVER);
        if (containment == Containment.INSIDE) {
            return new FilesystemAccess(FilesystemKind.OUTPUT_WRITE, primitive,
                    target, line, true,
                    "Literal output resolves beneath the active AI_Exports root.");
        }
        String reason = containment == Containment.ESCAPE
                ? "escapes the canonical AI_Exports root through traversal, "
                        + "a symbolic link, or an unreadable parent"
                : "writes outside the resolved AI_Exports root";
        return filesystemDenied(FilesystemKind.OUTPUT_WRITE, primitive, target,
                line, reason);
    }

    /** Return the path argument for a known write, or null for a non-write. */
    private static Integer filesystemWritePathIndex(
            String name, List<String> args) {
        if ("saveas".equals(name) || "file.savestring".equals(name)
                || "file.append".equals(name)) return Integer.valueOf(1);
        if ("save".equals(name) || "file.makedirectory".equals(name)
                || "file.mkdir".equals(name) || "file.open".equals(name)) {
            return Integer.valueOf(0);
        }
        if ("ij.saveas".equals(name) || "ij.save".equals(name)) {
            return Integer.valueOf(args == null ? -1 : args.size() - 1);
        }
        if ("ij.saveastiff".equals(name) || "ij.savestring".equals(name)) {
            return Integer.valueOf(args == null ? -1 : args.size() - 1);
        }
        return null;
    }

    /** Parse one call's top-level argument expressions; null means malformed. */
    private static List<String> parseCallArguments(String source, int openParen) {
        if (source == null || openParen < 0 || openParen >= source.length()
                || source.charAt(openParen) != '(') return null;
        List<String> args = new ArrayList<String>();
        int start = openParen + 1;
        int depth = 1;
        char quote = 0;
        boolean escaped = false;
        for (int i = start; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    String value = source.substring(start, i).trim();
                    if (!value.isEmpty() || !args.isEmpty()) args.add(value);
                    return args;
                }
            } else if (ch == ',' && depth == 1) {
                args.add(source.substring(start, i).trim());
                start = i + 1;
            }
        }
        return null;
    }

    /** Decode one whole string literal; concatenations and variables fail. */
    private static String literalString(String expression) {
        if (expression == null) return null;
        String value = expression.trim();
        if (value.length() < 2) return null;
        char quote = value.charAt(0);
        if (quote != '"' && quote != '\'') return null;
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                if (ch == quote || ch == '\\') {
                    out.append(ch);
                } else {
                    out.append('\\').append(ch);
                }
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == quote) {
                return value.substring(i + 1).trim().isEmpty()
                        ? out.toString() : null;
            } else {
                out.append(ch);
            }
        }
        return null;
    }

    private static boolean equalsAny(String value, String[] candidates) {
        if (value == null || candidates == null) return false;
        for (String candidate : candidates) {
            if (value.equals(candidate)) return true;
        }
        return false;
    }

    private static final class MacroViews {
        final String commentsRemoved;
        final String masked;

        MacroViews(String commentsRemoved, String masked) {
            this.commentsRemoved = commentsRemoved;
            this.masked = masked;
        }
    }

    /** Parse a literal first run/doCommand argument; null means dynamic. */
    private static String literalCommandArgument(String source, int offset) {
        int i = offset;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) i++;
        if (i >= source.length()) return null;
        char quote = source.charAt(i);
        if (quote != '\"' && quote != '\'') return null;
        i++;
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        while (i < source.length()) {
            char ch = source.charAt(i);
            if (escaped) {
                value.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == quote) {
                i++;
                while (i < source.length()
                        && Character.isWhitespace(source.charAt(i))) i++;
                if (i < source.length()
                        && source.charAt(i) != ',' && source.charAt(i) != ')') {
                    return null;
                }
                return value.toString();
            } else {
                value.append(ch);
            }
            i++;
        }
        return null;
    }

    private static boolean isHostCommandName(String command) {
        String normalized = command.replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
        String[] tokens = normalized.split("[^a-z0-9]+");
        for (String token : tokens) {
            for (String blocked : HOST_COMMAND_TOKENS) {
                if (blocked.equals(token)) return true;
            }
        }
        return containsAny(normalized, HOST_COMMAND_PHRASES);
    }

    /** Produce source views with comments and string literals safely masked. */
    private static MacroViews macroViews(String code) {
        char[] commentsRemoved = code.toCharArray();
        char[] masked = code.toCharArray();
        int state = 0; // 0 code, 1 string, 2 line comment, 3 block comment
        char quote = 0;
        boolean escaped = false;
        int i = 0;
        while (i < code.length()) {
            char ch = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : 0;
            if (state == 0) {
                if (ch == '/' && next == '/') {
                    commentsRemoved[i] = masked[i] = ' ';
                    commentsRemoved[i + 1] = masked[i + 1] = ' ';
                    state = 2;
                    i += 2;
                    continue;
                }
                if (ch == '/' && next == '*') {
                    commentsRemoved[i] = masked[i] = ' ';
                    commentsRemoved[i + 1] = masked[i + 1] = ' ';
                    state = 3;
                    i += 2;
                    continue;
                }
                if (ch == '\"' || ch == '\'') {
                    quote = ch;
                    escaped = false;
                    masked[i] = ' ';
                    state = 1;
                }
                i++;
                continue;
            }
            if (state == 1) {
                if (ch != '\r' && ch != '\n') masked[i] = ' ';
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    state = 0;
                    quote = 0;
                }
                i++;
                continue;
            }

            if (ch != '\r' && ch != '\n') {
                commentsRemoved[i] = masked[i] = ' ';
            }
            if (state == 2 && (ch == '\r' || ch == '\n')) {
                state = 0;
            } else if (state == 3 && ch == '*' && next == '/') {
                commentsRemoved[i] = masked[i] = ' ';
                commentsRemoved[i + 1] = masked[i + 1] = ' ';
                state = 0;
                i += 2;
                continue;
            }
            i++;
        }
        return new MacroViews(new String(commentsRemoved), new String(masked));
    }

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
        try {
            return Paths.get(p).toAbsolutePath().normalize().toString()
                    .replace('\\', '/');
        } catch (InvalidPathException e) {
            String n = p.replace('\\', '/');
            while (n.endsWith("/")) n = n.substring(0, n.length() - 1);
            return n;
        }
    }

    private enum Containment { INSIDE, OUTSIDE, ESCAPE }

    interface CanonicalPathResolver {
        Path canonicalise(Path intended) throws IOException;
    }

    private static final CanonicalPathResolver REAL_PATH_RESOLVER =
            new CanonicalPathResolver() {
                @Override
                public Path canonicalise(Path intended) throws IOException {
                    return canonicaliseIntendedPath(intended);
                }
            };

    private static Containment aiExportsContainment(
            String target, String root, CanonicalPathResolver resolver) {
        if (target == null || target.trim().isEmpty()
                || root == null || root.trim().isEmpty()) {
            return Containment.OUTSIDE;
        }
        try {
            Path rawTarget = Paths.get(target).toAbsolutePath();
            Path rawRoot = Paths.get(root).toAbsolutePath();
            Path lexicalTarget = rawTarget.normalize();
            Path lexicalRoot = rawRoot.normalize();
            boolean claimsRoot = rawTarget.startsWith(lexicalRoot)
                    || lexicalTarget.startsWith(lexicalRoot);
            if (!claimsRoot) return Containment.OUTSIDE;
            if (!lexicalTarget.startsWith(lexicalRoot)) return Containment.ESCAPE;

            CanonicalPathResolver effective = resolver == null
                    ? REAL_PATH_RESOLVER : resolver;
            Path canonicalRoot = effective.canonicalise(lexicalRoot);
            Path canonicalTarget = effective.canonicalise(lexicalTarget);
            if (canonicalRoot == null || canonicalTarget == null) {
                return Containment.ESCAPE;
            }
            return canonicalTarget.startsWith(canonicalRoot)
                    ? Containment.INSIDE : Containment.ESCAPE;
        } catch (IOException | InvalidPathException | SecurityException e) {
            return claimsAiExportsLexically(target, root)
                    ? Containment.ESCAPE : Containment.OUTSIDE;
        }
    }

    static String containmentForTest(String target, String root,
                                     CanonicalPathResolver resolver) {
        return aiExportsContainment(target, root, resolver).name();
    }

    private static boolean claimsAiExportsLexically(String target, String root) {
        try {
            Path targetPath = Paths.get(target).toAbsolutePath();
            Path rootPath = Paths.get(root).toAbsolutePath().normalize();
            return targetPath.startsWith(rootPath)
                    || targetPath.normalize().startsWith(rootPath);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Resolve an intended output through its nearest existing ancestor. */
    private static Path canonicaliseIntendedPath(Path intended) throws IOException {
        Path absolute = intended.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null
                && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unreadable output path ancestor");
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("No readable ancestor for output path");
        }
        Path realAncestor = existing.toRealPath();
        Path suffix = existing.relativize(absolute);
        return realAncestor.resolve(suffix).normalize();
    }

    static boolean isUnderAiExports(String target, String root) {
        return aiExportsContainment(target, root, REAL_PATH_RESOLVER)
                == Containment.INSIDE;
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

    private static boolean containsAny(String lowered, String[] fragments) {
        if (lowered == null) return false;
        for (String fragment : fragments) {
            if (lowered.contains(fragment)) return true;
        }
        return false;
    }
}
