package imagejai.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TCPCommandServerMethodsProvenanceTest {
    @Test
    public void helloRetainsLauncherSessionSeparatelyFromTcpSession() {
        SessionCapsRegistry<TCPCommandServer.AgentCaps> registry =
                new SessionCapsRegistry<TCPCommandServer.AgentCaps>();
        TCPCommandServer server = new TCPCommandServer(
                0, null, null, null, null, registry);
        JsonObject request = new JsonObject();
        request.addProperty("command", "hello");
        request.addProperty("agent", "pytest");
        request.addProperty("client_session_id", "launch-123");
        JsonObject requestedCaps = new JsonObject();
        requestedCaps.addProperty("agent_id", "explicit-agent");
        request.add("capabilities", requestedCaps);

        JsonObject response = server.handleHello(request, null);
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        String tcpSession = response.getAsJsonObject("result")
                .get("session_id").getAsString();
        TCPCommandServer.AgentCaps caps = registry.lookup(tcpSession, null).caps();

        assertEquals(tcpSession, caps.sessionId);
        assertEquals("launch-123", caps.clientSessionId);
        assertEquals("explicit-agent", caps.agentId);
        assertFalse(tcpSession.equals(caps.clientSessionId));
    }

    @Test
    public void launcherSessionBecomesFallbackAgentIdentity() {
        SessionCapsRegistry<TCPCommandServer.AgentCaps> registry =
                new SessionCapsRegistry<TCPCommandServer.AgentCaps>();
        TCPCommandServer server = new TCPCommandServer(
                0, null, null, null, null, registry);
        JsonObject request = new JsonObject();
        request.addProperty("command", "hello");
        request.addProperty("client_session_id", "launch-fallback");

        JsonObject response = server.handleHello(request, null);
        String tcpSession = response.getAsJsonObject("result")
                .get("session_id").getAsString();
        TCPCommandServer.AgentCaps caps = registry.lookup(tcpSession, null).caps();

        assertEquals("launch-fallback", caps.agentId);
    }

    @Test
    public void helloRejectsOversizedLauncherSessionIdentity() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i <= TCPCommandServer.MAX_HANDSHAKE_IDENTITY_CHARS; i++) {
            oversized.append('x');
        }
        JsonObject request = new JsonObject();
        request.addProperty("command", "hello");
        request.addProperty("client_session_id", oversized.toString());

        JsonObject response = server.handleHello(request, null);

        assertFalse(response.get("ok").getAsBoolean());
        assertEquals("invalid_hello",
                response.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void methodsDatasetCarriesBothSessionIdentifiersAndImageJVersion() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.sessionId = "tcp-123";
        caps.clientSessionId = "launch-123";
        JsonObject dataset = new JsonObject();

        TCPCommandServer.attachMethodsSessionMetadata(dataset, caps);

        assertEquals("tcp-123", dataset.get("tcpSessionId").getAsString());
        assertEquals("launch-123", dataset.get("clientSessionId").getAsString());
        assertTrue(dataset.has("imagejVersion"));
        assertFalse(dataset.get("imagejVersion").getAsString().trim().isEmpty());
    }

    @Test
    public void methodsScriptResolvesFromTheConfiguredWorkspace() throws Exception {
        String property = "imagejai.agent.workspace";
        String previous = System.getProperty(property);
        Path workspace = java.nio.file.Paths.get("agent").toAbsolutePath().normalize();
        System.setProperty(property, workspace.toString());
        try {
            Path script = TCPCommandServer.resolveMethodsTableScript();
            assertEquals(workspace.resolve("methods_table.py"), script);
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }
}
