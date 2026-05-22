package imagejai.ui;

import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.PostureController;
import imagejai.engine.security.AuditLog;
import imagejai.engine.security.AuditRow;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigurationPaneTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void postureSelectorReflectsAndRequestsControllerChanges() throws Exception {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        final PostureController controller = new PostureController(settings, null, null);
        Path csv = tmp.newFolder("audit").toPath().resolve(AuditLog.FILE_NAME);
        final AuditLog log = new AuditLog(csv);
        final ConfigurationPane[] pane = new ConfigurationPane[1];

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                pane[0] = new ConfigurationPane(controller, log);
            }
        });

        assertEquals(PrivacyPosture.PSEUDONYMISED, pane[0].selectedPostureForTest());

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                pane[0].selectPostureForTest(PrivacyPosture.ON_PREMISES);
            }
        });

        assertEquals(PrivacyPosture.ON_PREMISES, controller.current());

        log.append(new AuditRow(Instant.parse("2026-05-22T12:00:00Z"),
                "s1",
                "get_state",
                PrivacyPosture.ON_PREMISES,
                "ollama.local:gemma3",
                "",
                42,
                10,
                "",
                true,
                Collections.singletonList("path"),
                "",
                "{\"ok\":true}"));
        log.flushForTest();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });

        assertTrue(pane[0].statsTextForTest().contains("1 pseudonymised"));

        pane[0].dispose();
        log.shutdownAndAwait(100);
    }
}
