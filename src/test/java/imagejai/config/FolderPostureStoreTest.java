package imagejai.config;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FolderPostureStoreTest {

    private Path tmpDir;

    @Before
    public void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("imagejai-posture-store-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (tmpDir == null) {
            return;
        }
        List<Path> paths = new ArrayList<Path>();
        Files.walk(tmpDir).forEach(paths::add);
        paths.sort(Comparator.reverseOrder());
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignore) {
            }
        }
    }

    @Test
    public void writeThenReadRoundTripsPrivacyPosture() throws IOException {
        FolderPostureStore store = new FolderPostureStore();

        FolderPostureStore.Record written = store.write(tmpDir,
                PrivacyPosture.ON_PREMISES, "test", "manual test note");
        Optional<FolderPostureStore.Record> read = store.read(tmpDir);

        assertTrue(Files.exists(tmpDir.resolve(FolderPostureStore.FILE_NAME)));
        assertTrue(read.isPresent());
        assertEquals(PrivacyPosture.ON_PREMISES, written.posture());
        assertEquals(PrivacyPosture.ON_PREMISES, read.get().posture());
        assertEquals("test", read.get().setBy());
        assertEquals("manual test note", read.get().notes());
    }
}
