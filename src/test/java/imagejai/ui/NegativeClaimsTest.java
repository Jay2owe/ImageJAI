package imagejai.ui;

import com.google.gson.JsonObject;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.OutboundEvent;
import imagejai.engine.PostureController;
import imagejai.engine.security.BurnInDetector;
import imagejai.engine.security.CaptureHandler;
import imagejai.engine.security.OmeXmlScrubber;
import imagejai.engine.security.PathTokenMap;
import imagejai.engine.security.PseudonymisationFilter;
import imagejai.engine.security.VisualOverrideRegistry;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NegativeClaimsTest {
    @Test
    public void unregisteredPathWithoutKnownTokenCanStillLeakFromLogText() {
        PseudonymisationFilter filter = new PseudonymisationFilter(
                new PathTokenMap(), new OmeXmlScrubber(),
                new CaptureHandler(new BurnInDetector(), new VisualOverrideRegistry()));
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("result",
                "[LOG: macro printed /MOAB2/subject_017_visit3]");

        filter.apply(response, "get_log", PrivacyPosture.PSEUDONYMISED, "s");

        assertTrue("This documents a limitation: unregistered free text that "
                        + "is not recognised as a file-like path can leave the JVM.",
                response.toString().contains("subject_017_visit3"));
    }

    @Test
    public void pathTokenMapIsReversibleInsideTheJvm() {
        PathTokenMap map = new PathTokenMap();
        String token = map.tokenForSeries(
                Paths.get("study", "MOAB2_subject_017_visit3.lif"), 4);

        PathTokenMap.ResolvedTarget resolved = map.resolve(token).get();

        assertEquals(Paths.get("study", "MOAB2_subject_017_visit3.lif"),
                resolved.realPath());
        assertEquals(4, resolved.series());
    }

    @Test
    public void egressLampStillFlashesInOnPremisesMode() throws Exception {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.ON_PREMISES);
        PostureController.getInstance().configure(settings);
        EgressIndicator indicator = new EgressIndicator();
        try {
            OutboundEvent.publish("ping", 64);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                }
            });

            assertTrue(indicator.isActiveForTest());
            assertTrue(indicator.getToolTipText().contains(
                    "local process, not over the network"));
        } finally {
            indicator.dispose();
            Settings reset = new Settings();
            reset.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
            PostureController.getInstance().configure(reset);
        }
    }
}
