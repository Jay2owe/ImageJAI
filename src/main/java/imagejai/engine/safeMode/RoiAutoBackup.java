package imagejai.engine.safeMode;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileInfo;
import ij.plugin.frame.RoiManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Stage 05 (docs/safe_mode_v2/05_destructive-scanner-expansion.md):
 * write the live RoiManager to {@code AI_Exports/.safemode_roi_<ts>.zip}
 * before a macro that wipes the manager runs. The file is the standard
 * Fiji ROI-zip format ({@code rm.runCommand("save", path)}); the user
 * recovers via the equivalent {@code Open...} on the same file.
 *
 * <p>Resolution rule for the destination directory mirrors
 * {@link imagejai.engine.SessionCodeJournal}: prefer the
 * active image's source directory's {@code AI_Exports/} subfolder; fall
 * back to {@code <user.dir>/AI_Exports/} when the active image has no
 * on-disk origin (a freshly-created image, a generated test pattern,
 * etc.). The hidden filename prefix ({@code .safemode_}) keeps the
 * automatic backup out of casual file-listing views — the user is
 * surprised by it only when they go looking, per plan §Known risks.
 */
public final class RoiAutoBackup {

    private RoiAutoBackup() {}

    /** Compact UTC timestamp suffix — {@code 20260504T103045Z}. */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);

    /**
     * Result struct: where the backup landed (or null on failure) and a
     * one-line reason the friction-log entry can quote. Caller decides
     * what to do with a null path; the destructive-op pipeline still
     * lets the macro proceed (the rule's severity is {@code BACKUP_THEN_ALLOW}).
     */
    public static final class Result {
        public final Path path;
        public final String message;

        Result(Path path, String message) {
            this.path = path;
            this.message = message;
        }
    }

    /**
     * Write the RoiManager's current contents to a fresh ZIP under
     * {@code AI_Exports/}. No-op (returns null path) when the manager is
     * empty or when the destination directory cannot be resolved /
     * created — the macro is allowed to proceed regardless, so a missing
     * backup never blocks work.
     *
     * @param rm   live RoiManager (caller already filtered count &gt; 0)
     * @param imp  active image, used to resolve the AI_Exports parent
     */
    public static Result backup(RoiManager rm, ImagePlus imp) {
        if (rm == null) {
            return new Result(null, "RoiManager.getInstance() returned null; backup skipped.");
        }
        if (rm.getCount() <= 0) {
            return new Result(null, "RoiManager is empty; backup skipped.");
        }

        Path dir = resolveBackupDir(imp);
        if (dir == null) {
            return new Result(null, "Could not resolve AI_Exports/ for backup; reset proceeded without one.");
        }

        try {
            Files.createDirectories(dir);
        } catch (Throwable t) {
            try { IJ.log("[ImageJAI-SafeMode] backup mkdirs failed: " + t.getMessage()); }
            catch (Throwable ignore) {}
            return new Result(null, "AI_Exports mkdirs failed; reset proceeded without backup.");
        }

        String ts = TS_FMT.format(Instant.now());
        Path zip = dir.resolve(".safemode_roi_" + ts + ".zip");

        try {
            // RoiManager.save() returns boolean only on a few IJ forks; the
            // canonical path is runCommand("save", absolutePath).
            boolean ok = rm.runCommand("save", zip.toAbsolutePath().toString());
            if (!ok) {
                return new Result(null, "RoiManager.runCommand(\"save\") returned false; reset proceeded without backup.");
            }
        } catch (Throwable t) {
            try { IJ.log("[ImageJAI-SafeMode] backup save failed: " + t.getMessage()); }
            catch (Throwable ignore) {}
            return new Result(null, "RoiManager.save threw " + t.getClass().getSimpleName()
                    + "; reset proceeded without backup.");
        }

        return new Result(zip, "ROI backup written to " + zip + " (" + rm.getCount() + " ROIs).");
    }

    /**
     * Resolve {@code AI_Exports/} under the active image's source
     * directory, falling back to {@code <user.dir>/AI_Exports/} when the
     * image is not on disk. Returns null only when both sources are
     * missing — the caller swallows that as a soft skip.
     */
    private static Path resolveBackupDir(ImagePlus imp) {
        if (imp != null) {
            FileInfo fi = imp.getOriginalFileInfo();
            if (fi != null && fi.directory != null && !fi.directory.trim().isEmpty()) {
                return Paths.get(fi.directory).resolve("AI_Exports");
            }
        }
        String cwd = System.getProperty("user.dir", null);
        if (cwd == null || cwd.isEmpty()) return null;
        return Paths.get(cwd).resolve("AI_Exports");
    }

    /**
     * Test seam: resolve the directory the way {@link #backup} would,
     * exposed package-private so unit tests can exercise the path logic
     * without a live RoiManager.
     */
    static Path resolveBackupDirForTest(ImagePlus imp) {
        return resolveBackupDir(imp);
    }
}
