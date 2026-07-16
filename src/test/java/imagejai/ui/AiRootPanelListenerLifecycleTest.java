package imagejai.ui;

import imagejai.config.Settings;
import imagejai.engine.PostureController;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class AiRootPanelListenerLifecycleTest {

    @Test
    public void repeatedPanelConstructionAndRemovalDoesNotLeakPostureListeners()
            throws Exception {
        int baseline = listenerCount();

        for (int i = 0; i < 3; i++) {
            AiRootPanel panel = new AiRootPanel(new Settings().detachedCopy());
            panel.removeNotify();
            assertEquals(baseline, listenerCount());
        }
    }

    @Test
    public void backendAffectingSaveRefreshesLiveBackendExactlyOnce() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = Files.createTempDirectory("imagejai-root-settings");
        try {
            System.setProperty("user.home", home.toString());
            AiRootPanel panel = new AiRootPanel(new Settings());
            AtomicInteger refreshes = new AtomicInteger();
            panel.setBackendRefreshListener(refreshes::incrementAndGet);

            panel.applyConfirmedSettings(true);

            assertEquals(1, refreshes.get());
            panel.removeNotify();
        } finally {
            if (oldHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", oldHome);
        }
    }

    private static int listenerCount() throws Exception {
        Field field = PostureController.class.getDeclaredField("listeners");
        field.setAccessible(true);
        return ((List<?>) field.get(PostureController.getInstance())).size();
    }
}
