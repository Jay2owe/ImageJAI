package imagejai.engine;

import com.google.gson.JsonObject;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.security.PathTokenMap;
import imagejai.engine.security.PseudonymisationFilter;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TCPCommandServerDataGovernanceTest {
    @Test
    public void dispatcherAddsGovernanceBlockInPseudonymisedMode() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        PostureController.getInstance().configure(settings);
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        JsonObject request = new JsonObject();
        request.addProperty("command", "ping");

        JsonObject response = server.dispatch(request, TCPCommandServer.DEFAULT_CAPS);

        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.has("_governance"));
        assertEquals("Pseudonymised",
                response.getAsJsonObject("_governance").get("posture").getAsString());
    }

    @Test
    public void readonlyHashShortCircuitKeepsGovernanceBlockInPseudonymisedMode() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        PostureController.getInstance().configure(settings);
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        JsonObject request = new JsonObject();
        request.addProperty("command", "ping");

        JsonObject first = server.dispatch(request, TCPCommandServer.DEFAULT_CAPS);
        JsonObject secondRequest = new JsonObject();
        secondRequest.addProperty("command", "ping");
        secondRequest.addProperty("if_none_match", first.get("hash").getAsString());

        JsonObject second = server.dispatch(secondRequest, TCPCommandServer.DEFAULT_CAPS);

        assertTrue(second.get("unchanged").getAsBoolean());
        assertTrue(second.has("_governance"));
        assertEquals("Pseudonymised",
                second.getAsJsonObject("_governance").get("posture").getAsString());
    }

    @Test
    public void requestVisualIsRefusedInOnPremisesModeWithGovernance() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.ON_PREMISES);
        PostureController.getInstance().configure(settings);
        try {
            TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
            JsonObject request = new JsonObject();
            request.addProperty("command", "request_visual");
            request.addProperty("reason", "inspect neurites");

            JsonObject response = server.dispatch(request, TCPCommandServer.DEFAULT_CAPS);

            assertTrue(!response.get("ok").getAsBoolean());
            assertTrue(response.has("_governance"));
            assertEquals("On-premises",
                    response.getAsJsonObject("_governance").get("posture").getAsString());
        } finally {
            Settings reset = new Settings();
            reset.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
            PostureController.getInstance().configure(reset);
        }
    }

    @Test
    public void openImageByTokenResolvesThroughSharedPathTokenMap() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        String token = PseudonymisationFilter.getInstance().pathTokenMap()
                .tokenForSeries(Paths.get("study", "subject_017.lif"), 3);

        PathTokenMap.ResolvedTarget resolved = server.openImageByToken(token);

        assertNotNull(resolved);
        assertEquals(Paths.get("study", "subject_017.lif"), resolved.realPath());
        assertEquals(3, resolved.series());
    }
}
