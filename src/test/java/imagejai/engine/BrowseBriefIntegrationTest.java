package imagejai.engine;

import com.google.gson.JsonObject;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.security.Brief;
import imagejai.engine.security.PathTokenMap;
import imagejai.engine.security.RedactionReport;
import imagejai.engine.security.SelectionBroker;
import org.junit.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 09 browse brief integration: broker -> TCP poll/retrieve ->
 * reverse-resolution, without exposing local labels.
 */
@Tag("integration")
public class BrowseBriefIntegrationTest {
    @Test
    public void selectedSeriesBriefCanBeRetrievedAndOpenedByToken() {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        PostureController.getInstance().configure(settings);
        String session = "browse-stage09-" + System.nanoTime();
        Path path = Paths.get("MOAB2", "subject_017_visit3.lif");
        String t1 = PathTokenMap.getInstance().tokenForSeries(path, 1);
        String t2 = PathTokenMap.getInstance().tokenForSeries(path, 2);
        String t3 = PathTokenMap.getInstance().tokenForSeries(path, 11);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("channels", 4);
        metadata.put("dimensions", "4096x4096x35");
        SelectionBroker.getInstance().enqueue(new Brief(session,
                Arrays.asList(t1, t2, t3), "8 weeks, wild-type", metadata));

        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        JsonObject pendingRequest = request("browse_pending_brief", session);
        JsonObject pending = server.dispatch(pendingRequest, TCPCommandServer.DEFAULT_CAPS);
        assertTrue(pending.get("ok").getAsBoolean());
        assertTrue(pending.getAsJsonObject("result").get("pending").getAsBoolean());

        JsonObject briefRequest = request("get_pending_brief", session);
        JsonObject brief = server.dispatch(briefRequest, TCPCommandServer.DEFAULT_CAPS);
        assertTrue(brief.get("ok").getAsBoolean());
        JsonObject result = brief.getAsJsonObject("result");
        assertTrue(result.get("pending").getAsBoolean());
        assertEquals(3, result.getAsJsonArray("tokens").size());
        assertEquals(t1, result.getAsJsonArray("tokens").get(0).getAsString());
        assertEquals(t2, result.getAsJsonArray("tokens").get(1).getAsString());
        assertEquals(t3, result.getAsJsonArray("tokens").get(2).getAsString());
        assertEquals("8 weeks, wild-type", result.get("tag").getAsString());
        assertFalse(brief.toString().contains("subject_017"));
        assertFalse(brief.toString().contains("MOAB2"));
        assertFalse(brief.toString().contains("wt_8w_male"));

        PathTokenMap.ResolvedTarget resolved = server.openImageByToken(t2);
        assertNotNull(resolved);
        assertEquals(path, resolved.realPath());
        assertEquals(2, resolved.series());
        assertFalse(SelectionBroker.getInstance().hasPending(session));
    }

    @Test
    public void getPendingBriefAuditNotesRecordTokenListAndTag() throws Exception {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        PostureController.getInstance().configure(settings);
        String session = "browse-audit-" + System.nanoTime();
        Path path = Paths.get("MOAB2", "subject_017_visit3.lif");
        String token = PathTokenMap.getInstance().tokenForSeries(path, 3);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("channels", 2);
        metadata.put("dimensions", "128x64");
        SelectionBroker.getInstance().enqueue(new Brief(session,
                Arrays.asList(token), "SCN-42", metadata));

        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        JsonObject response = server.dispatch(request("get_pending_brief", session),
                TCPCommandServer.DEFAULT_CAPS);

        assertTrue(response.get("ok").getAsBoolean());
        String notes = auditNotes(server, "get_pending_brief",
                request("get_pending_brief", session), response);
        assertTrue(notes, notes.contains("tokens='" + token + "'"));
        assertTrue(notes, notes.contains("tag='SCN-42'"));
        assertFalse(notes.contains("subject_017"));
        assertFalse(notes.contains("MOAB2"));
    }

    private static JsonObject request(String command, String session) {
        JsonObject request = new JsonObject();
        request.addProperty("command", command);
        request.addProperty("session_id", session);
        return request;
    }

    private static String auditNotes(TCPCommandServer server, String command,
                                     JsonObject request, JsonObject response)
            throws Exception {
        java.lang.reflect.Method method = TCPCommandServer.class.getDeclaredMethod(
                "auditNotes", String.class, JsonObject.class, JsonObject.class,
                RedactionReport.class);
        method.setAccessible(true);
        return (String) method.invoke(server, command, request, response,
                RedactionReport.passthrough(command));
    }
}
