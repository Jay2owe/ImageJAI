package imagejai.engine;

import imagejai.config.FolderPostureStore;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PostureControllerTest {

    private Path tmpDir;
    private FolderPostureStore store;

    @Before
    public void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("imagejai-posture-controller-test-");
        store = new FolderPostureStore();
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
    public void stricterFolderDownshiftsCurrentPosture() throws IOException {
        Path folder = Files.createDirectory(tmpDir.resolve("on-premises-folder"));
        store.write(folder, PrivacyPosture.ON_PREMISES, "test", "");

        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.STANDARD);
        PostureController controller = new PostureController(settings, store, null);

        controller.onFolderOpened(folder);

        assertEquals(PrivacyPosture.ON_PREMISES, controller.current());
    }

    @Test
    public void weakerFolderDoesNotAutoUpshiftCurrentPosture() throws IOException {
        Path folder = Files.createDirectory(tmpDir.resolve("standard-folder"));
        store.write(folder, PrivacyPosture.STANDARD, "test", "");

        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.ON_PREMISES);
        PostureController controller = new PostureController(settings, store, null);

        controller.onFolderOpened(folder);

        assertEquals(PrivacyPosture.ON_PREMISES, controller.current());
    }

    @Test
    public void postureChangeAtomicallyReplacesExistingSidecar() throws IOException {
        Path folder = Files.createDirectory(tmpDir.resolve("changing-folder"));
        store.write(folder, PrivacyPosture.STANDARD, "test", "old");
        Settings settings = new Settings();
        PostureController controller = new PostureController(settings, store, null);
        controller.onFolderOpened(folder);

        controller.requestPosture(PrivacyPosture.ON_PREMISES, folder, "new");

        assertEquals(PrivacyPosture.ON_PREMISES, store.read(folder).get().posture());
        assertEquals("new", store.read(folder).get().notes());
        assertFalse(Files.exists(folder.resolve(FolderPostureStore.FILE_NAME + ".tmp")));
    }

    @Test
    public void revokeDeletesActiveFolderSidecar() throws IOException {
        Path folder = Files.createDirectory(tmpDir.resolve("revoked-folder"));
        store.write(folder, PrivacyPosture.ON_PREMISES, "test", "");
        Settings settings = new Settings();
        PostureController controller = new PostureController(settings, store, null);
        controller.onFolderOpened(folder);

        controller.revokeFolderPosture(null, "user revoked");

        assertFalse(Files.exists(folder.resolve(FolderPostureStore.FILE_NAME)));
        assertEquals(PrivacyPosture.PSEUDONYMISED, controller.current());
    }
}
