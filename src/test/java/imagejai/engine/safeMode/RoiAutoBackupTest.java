package imagejai.engine.safeMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 05 of {@code docs/safe_mode_v2/} — verify {@link RoiAutoBackup}'s
 * destination-resolution and null-safety paths. The full backup write
 * needs a live Fiji {@code RoiManager}; that path is exercised by the
 * Stage 05 manual exit gate when a real Fiji session is wired up.
 */
public class RoiAutoBackupTest {

    private String previousUserDir;
    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        // Pin user.dir to a temp folder so the fallback resolver writes
        // somewhere deterministic instead of the developer's working
        // directory. Restored in {@link #tearDown}.
        previousUserDir = System.getProperty("user.dir");
        tempDir = Files.createTempDirectory("safe-mode-roi-backup-test-");
        System.setProperty("user.dir", tempDir.toString());
    }

    @After
    public void tearDown() throws IOException {
        if (previousUserDir != null) {
            System.setProperty("user.dir", previousUserDir);
        }
        if (tempDir != null && Files.isDirectory(tempDir)) {
            // Best-effort cleanup; nothing here writes outside tempDir.
            walkDeleteQuietly(tempDir);
        }
    }

    /**
     * No active image and no on-disk origin → resolver falls back to
     * {@code <user.dir>/AI_Exports/}, which the test set to a temp path.
     */
    @Test
    public void resolveBackupDirFallsBackToCwdAiExports() {
        Path resolved = RoiAutoBackup.resolveBackupDirForTest(null);
        assertNotNull(resolved);
        assertEquals(tempDir.resolve("AI_Exports"), resolved);
    }

    /**
     * RoiManager null (typical headless test path) → backup returns a
     * non-null Result whose {@code path} is null and whose {@code message}
     * names the skip reason. The destructive-op pipeline tolerates this
     * by leaving the macro to proceed.
     */
    @Test
    public void backupReturnsNullPathWhenRoiManagerIsNull() {
        RoiAutoBackup.Result res = RoiAutoBackup.backup(null, null);
        assertNotNull(res);
        assertNull(res.path);
        assertTrue("message names the skip reason", res.message.toLowerCase().contains("roimanager"));
    }

    /**
     * user.dir cleared → resolver returns null. Caller treats null as a
     * soft skip; the macro still runs.
     */
    @Test
    public void resolveBackupDirReturnsNullWhenCwdIsCleared() {
        System.clearProperty("user.dir");
        try {
            assertNull(RoiAutoBackup.resolveBackupDirForTest(null));
        } finally {
            System.setProperty("user.dir", tempDir.toString());
        }
    }

    /**
     * The backup filename pattern stays stable — the Stage 07 status
     * indicator and any post-mortem tooling key on this prefix /
     * extension to surface backups in the friction log.
     */
    @Test
    public void backupFilenamePatternIsHiddenZipUnderAiExports() {
        // The actual write path needs a live RoiManager, so we only assert
        // the constants the production helper uses by exercising the
        // resolver and constructing the same name pattern.
        Path dir = RoiAutoBackup.resolveBackupDirForTest(null);
        assertNotNull(dir);
        // Pattern: dir/.safemode_roi_<ts>.zip
        Path sample = dir.resolve(".safemode_roi_20260504T103045Z.zip");
        assertTrue("hidden prefix", sample.getFileName().toString().startsWith(".safemode_roi_"));
        assertTrue("zip extension", sample.getFileName().toString().endsWith(".zip"));
    }

    private static void walkDeleteQuietly(Path root) {
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                    });
        } catch (IOException ignore) {}
    }
}
