package imagejai.engine;

import org.junit.After;
import org.junit.Test;
import imagejai.config.Settings;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GsdDetectorTest {

    @After
    public void tearDown() {
        GsdDetector.restoreProbeForTests();
    }

    @Test
    public void settingsOverrideControlsSkillsPath() {
        Settings settings = new Settings();
        settings.gsdSkillsPath = "C:/tmp/custom-gsd";

        assertEquals(Paths.get("C:/tmp/custom-gsd"), GsdDetector.resolveSkillsPath(settings));
    }

    @Test
    public void installedWhenConfiguredPathExists() {
        Settings settings = new Settings();
        settings.gsdSkillsPath = "C:/tmp/custom-gsd";
        final Path expected = Paths.get("C:/tmp/custom-gsd");

        GsdDetector.setProbeForTests(new GsdDetector.Probe() {
            @Override
            public boolean exists(Path path) {
                assertEquals(expected, path);
                return true;
            }

            @Override
            public boolean claudeHelpExitsZero() {
                return false;
            }
        });

        assertTrue(GsdDetector.isInstalled(settings));
    }
}
