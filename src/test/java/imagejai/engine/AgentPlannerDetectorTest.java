package imagejai.engine;

import org.junit.After;
import org.junit.Test;
import imagejai.config.Settings;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentPlannerDetectorTest {

    @After
    public void tearDown() {
        AgentPlannerDetector.restoreProbeForTests();
    }

    @Test
    public void settingsOverrideControlsSkillsPath() {
        Settings settings = new Settings();
        settings.gsdSkillsPath = "C:/tmp/custom-gsd";

        assertEquals(Paths.get("C:/tmp/custom-gsd"), AgentPlannerDetector.resolveSkillsPath(settings));
    }

    @Test
    public void installedWhenConfiguredPathExists() {
        Settings settings = new Settings();
        settings.gsdSkillsPath = "C:/tmp/custom-gsd";
        final Path expected = Paths.get("C:/tmp/custom-gsd");

        AgentPlannerDetector.setProbeForTests(new AgentPlannerDetector.Probe() {
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

        assertTrue(AgentPlannerDetector.isInstalled(settings));
    }
}
