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
