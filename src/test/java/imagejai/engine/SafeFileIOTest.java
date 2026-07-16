package imagejai.engine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SafeFileIOTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void nonAtomicFallbackPublishesCompleteReplacement() throws Exception {
        Path dir = tmp.newFolder("replace").toPath();
        Path target = dir.resolve("state.json");
        Path source = dir.resolve("pending.tmp");
        Files.writeString(target, "original", StandardCharsets.UTF_8);
        Files.writeString(source, "replacement", StandardCharsets.UTF_8);

        SafeFileIO.replaceWithBackup(source, target);

        assertEquals("replacement", Files.readString(target, StandardCharsets.UTF_8));
        try (Stream<Path> paths = Files.list(dir)) {
            assertTrue(paths.noneMatch(p -> p.getFileName().toString().contains(".backup-")));
        }
    }

    @Test
    public void nonAtomicFallbackPreservesOriginalWhenReplacementFails() throws Exception {
        Path dir = tmp.newFolder("failure").toPath();
        Path target = dir.resolve("state.json");
        Files.writeString(target, "original", StandardCharsets.UTF_8);

        try {
            SafeFileIO.replaceWithBackup(dir.resolve("missing.tmp"), target);
            fail("missing replacement must fail");
        } catch (IOException expected) {
            // expected
        }
        assertEquals("original", Files.readString(target, StandardCharsets.UTF_8));
        try (Stream<Path> paths = Files.list(dir)) {
            assertTrue("a recovery copy remains available",
                    paths.anyMatch(p -> p.getFileName().toString().contains(".backup-")));
        }
    }
}
