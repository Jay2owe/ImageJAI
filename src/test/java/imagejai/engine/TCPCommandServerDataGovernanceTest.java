package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.security.AuditLog;
import imagejai.engine.security.AuditRow;
import imagejai.engine.security.PathTokenMap;
import imagejai.engine.security.PseudonymisationFilter;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TCPCommandServerDataGovernanceTest {
    @Test
    public void subscriptionsRequireExactParsedCommandAndAuthenticatedSession()
            throws Exception {
        String previous = System.getProperty("imagejai.tcp.requireToken");
        System.setProperty("imagejai.tcp.requireToken", "true");
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.STANDARD);
        PostureController.getInstance().configure(settings);
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        server.setServerTokenForTest("stream-secret");
        Socket stream = null;
        try {
            int port = startAndAwait(server);
            JsonObject hello = exchange(port, parse(
                    "{\"command\":\"hello\",\"token\":\"stream-secret\"," +
                    "\"capabilities\":{\"accept_events\":[\"*\"]}}"));
            String session = hello.getAsJsonObject("result")
                    .get("session_id").getAsString();

            JsonObject lookalike = new JsonObject();
            lookalike.addProperty("command", "ping");
            lookalike.addProperty("note", "please subscribe later");
            lookalike.addProperty("session_id", session);
            lookalike.addProperty("token", "stream-secret");
            assertEquals("pong", exchange(port, lookalike)
                    .get("result").getAsString());

            assertEquals("session_required", errorCode(exchange(port,
                    parse("{\"command\":\"subscribe\",\"topics\":[\"*\"]}"))));
            assertEquals("session_token_mismatch", errorCode(exchange(port, parse(
                    "{\"command\":\"subscribe\",\"topics\":[\"*\"]," +
                    "\"session_id\":\"" + session + "\",\"token\":\"wrong\"}"))));

            JsonObject limitedHello = exchange(port, parse(
                    "{\"command\":\"hello\",\"token\":\"stream-secret\"," +
                    "\"capabilities\":{\"accept_events\":[\"image.*\"]}}"));
            String limitedSession = limitedHello.getAsJsonObject("result")
                    .get("session_id").getAsString();
            assertEquals("event_subscription_forbidden", errorCode(exchange(
                    port, subscription(limitedSession, "stream-secret", "job.*"))));

            stream = openSubscription(port, session, "stream-secret", "*");
            JsonObject ack = readFrame(stream);
            assertEquals("subscribed", ack.get("event").getAsString());
            assertEquals(1, server.getActiveSubscriberCount());
        } finally {
            if (stream != null) stream.close();
            server.stop();
            restoreProperty("imagejai.tcp.requireToken", previous);
            resetPosture();
        }
    }

    @Test
    public void expiredSessionCannotSubscribe() throws Exception {
        FakeClock clock = new FakeClock();
        final AtomicInteger ids = new AtomicInteger();
        SessionCapsRegistry<TCPCommandServer.AgentCaps> registry =
                new SessionCapsRegistry<TCPCommandServer.AgentCaps>(
                        4, 1000L, clock, clock,
                        new SessionCapsRegistry.IdSource() {
                            @Override
                            public String nextId() {
                                return String.format("%032d", ids.incrementAndGet());
                            }
                        });
        TCPCommandServer server = new TCPCommandServer(
                0, null, null, null, null, registry);
        server.setServerTokenForTest("expiry-secret");
        try {
            int port = startAndAwait(server);
            JsonObject hello = exchange(port, parse(
                    "{\"command\":\"hello\",\"token\":\"expiry-secret\"," +
                    "\"capabilities\":{\"accept_events\":[\"*\"]}}"));
            String session = hello.getAsJsonObject("result")
                    .get("session_id").getAsString();
            clock.nanos = 1_000_000_000L;

            JsonObject response = exchange(port, parse(
                    "{\"command\":\"subscribe\",\"topics\":[\"*\"]," +
                    "\"session_id\":\"" + session + "\"," +
                    "\"token\":\"expiry-secret\"}"));
            assertEquals("session_expired", errorCode(response));
        } finally {
            server.stop();
        }
    }

    @Test
    public void streamFramesUsePrivacyEnvelopeAndAuditOnlyMetadata()
            throws Exception {
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        PostureController.getInstance().configure(settings);
        Path auditPath = Files.createTempDirectory("imagejai-stream-audit")
                .resolve("audit.csv");
        AuditLog auditLog = new AuditLog(auditPath);
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        server.setServerTokenForTest("privacy-secret");
        server.setAuditLogForTest(auditLog);
        Socket stream = null;
        String session = "";
        try {
            int port = startAndAwait(server);
            JsonObject hello = exchange(port, parse(
                    "{\"command\":\"hello\",\"token\":\"privacy-secret\"," +
                    "\"capabilities\":{\"accept_events\":[\"*\"]}}"));
            session = hello.getAsJsonObject("result")
                    .get("session_id").getAsString();
            stream = openSubscription(port, session, "privacy-secret", "*");
            assertGoverned(readFrame(stream));

            JsonObject image = new JsonObject();
            image.addProperty("title", "Subject Alpha");
            image.addProperty("path", "C:\\private\\Subject Alpha.tif");
            EventBus.getInstance().publish("image.opened", image);
            JsonObject imageFrame = readEvent(stream, "image.opened");

            JsonObject dialog = new JsonObject();
            dialog.addProperty("title", "Subject Alpha dialog");
            dialog.addProperty("text", "Subject Alpha requires review");
            EventBus.getInstance().publish("dialog.appeared", dialog);
            JsonObject dialogFrame = readEvent(stream, "dialog.appeared");

            JsonObject macro = new JsonObject();
            macro.addProperty("macro_id", 7);
            macro.addProperty("preview", "open(\"C:\\\\private\\\\Subject Alpha.tif\")");
            EventBus.getInstance().publish("macro.started", macro);
            JsonObject macroFrame = readEvent(stream, "macro.started");

            JsonObject job = new JsonObject();
            job.addProperty("job_id", "j-7");
            job.addProperty("preview", "Subject Alpha macro");
            JsonObject result = new JsonObject();
            result.addProperty("output", "Subject Alpha result");
            job.add("result", result);
            EventBus.getInstance().publish("job.completed", job);
            JsonObject jobFrame = readEvent(stream, "job.completed");

            String outbound = imageFrame.toString() + dialogFrame + macroFrame + jobFrame;
            assertFalse(outbound, outbound.contains("Subject Alpha"));
            assertFalse(outbound, outbound.contains("C:\\private"));
            assertFalse(jobFrame.getAsJsonObject("data").has("result"));
            assertTrue(jobFrame.getAsJsonObject("data")
                    .get("result_available").getAsBoolean());
            assertGoverned(imageFrame);
            assertGoverned(dialogFrame);
            assertGoverned(macroFrame);
            assertGoverned(jobFrame);

            List<AuditRow> rows = auditLog.recent(20);
            AuditRow opened = findAudit(rows, "subscribe.open");
            assertNotNull(opened);
            assertTrue(opened.sessionId().startsWith("session-"));
            assertFalse(opened.sessionId().equals(session));
            assertFalse(opened.toCsvLine().contains("Subject Alpha"));
            assertFalse(opened.redactedPayloadJson().contains("Subject Alpha"));
        } finally {
            server.stop();
            if (stream != null) stream.close();
            awaitSubscribers(server, 0);
            AuditRow closed = findAudit(auditLog.recent(20), "subscribe.close");
            assertNotNull(closed);
            assertFalse(closed.toCsvLine().contains("Subject Alpha"));
            auditLog.flushForTest();
            auditLog.shutdownAndAwait(1000L);
            resetPosture();
        }
    }

    @Test
    public void subscriberAndQueueLimitsRemainBounded() throws Exception {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        server.setServerTokenForTest("capacity-secret");
        List<Socket> streams = new ArrayList<Socket>();
        try {
            int port = startAndAwait(server);
            JsonObject hello = exchange(port, parse(
                    "{\"command\":\"hello\",\"token\":\"capacity-secret\"," +
                    "\"capabilities\":{\"accept_events\":[\"*\"]}}"));
            String session = hello.getAsJsonObject("result")
                    .get("session_id").getAsString();
            for (int i = 0; i < 8; i++) {
                Socket stream = openSubscription(
                        port, session, "capacity-secret", "*");
                streams.add(stream);
                assertEquals("subscribed", readFrame(stream)
                        .get("event").getAsString());
            }
            assertEquals(8, server.getActiveSubscriberCount());

            JsonObject rejected = exchange(port, subscription(
                    session, "capacity-secret", "*"));
            assertEquals("subscriber_capacity", errorCode(rejected));

            java.util.concurrent.LinkedBlockingDeque<JsonObject> queue =
                    new java.util.concurrent.LinkedBlockingDeque<JsonObject>(256);
            for (int i = 0; i < 400; i++) {
                JsonObject frame = eventFrame("job.progress", "job_id", "j-1", i);
                server.offerSubscriberFrame(
                        queue, frame, TCPCommandServer.DEFAULT_CAPS, null);
            }
            assertEquals("same-identity progress replaces in place", 1, queue.size());
            for (int i = 0; i < 300; i++) {
                server.offerSubscriberFrame(queue,
                        eventFrame("job.completed", "job_id", "j-" + i, i),
                        TCPCommandServer.DEFAULT_CAPS, null);
            }
            assertTrue(queue.size() <= 256);
        } finally {
            for (Socket stream : streams) stream.close();
            server.stop();
        }
    }
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

    private static int startAndAwait(TCPCommandServer server) throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final int[] port = new int[1];
        final String[] error = new String[1];
        server.start(new TCPCommandServer.ServerListener() {
            @Override
            public void onServerStarted(int boundPort) {
                port[0] = boundPort;
                started.countDown();
            }

            @Override
            public void onServerStopped() {
            }

            @Override
            public void onClientConnected(String clientInfo) {
            }

            @Override
            public void onCommandReceived(String command) {
            }

            @Override
            public void onError(String message) {
                error[0] = message;
                started.countDown();
            }
        });
        assertTrue("server did not bind", started.await(5, TimeUnit.SECONDS));
        if (error[0] != null) throw new AssertionError(error[0]);
        return port[0];
    }

    private static JsonObject exchange(int port, JsonObject request) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            writer.println(request.toString());
            String line = reader.readLine();
            assertNotNull("server closed without a response", line);
            return parse(line);
        }
    }

    private static Socket openSubscription(int port, String session,
                                           String token, String topic)
            throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(5000);
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8), true);
        writer.println(subscription(session, token, topic).toString());
        return socket;
    }

    private static JsonObject subscription(String session, String token,
                                           String topic) {
        JsonObject request = new JsonObject();
        request.addProperty("command", "subscribe");
        com.google.gson.JsonArray topics = new com.google.gson.JsonArray();
        topics.add(topic);
        request.add("topics", topics);
        request.addProperty("session_id", session);
        request.addProperty("token", token);
        return request;
    }

    private static JsonObject readFrame(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertNotNull("stream closed without a frame", line);
        return parse(line);
    }

    private static JsonObject readEvent(Socket socket, String event) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        for (int i = 0; i < 20; i++) {
            String line = reader.readLine();
            assertNotNull("stream closed before " + event, line);
            JsonObject frame = parse(line);
            if (event.equals(frame.has("event")
                    ? frame.get("event").getAsString() : "")) {
                return frame;
            }
        }
        throw new AssertionError("event not received: " + event);
    }

    private static void assertGoverned(JsonObject frame) {
        assertTrue(frame.toString(), frame.has("_governance"));
        assertEquals("Pseudonymised", frame.getAsJsonObject("_governance")
                .get("posture").getAsString());
    }

    private static AuditRow findAudit(List<AuditRow> rows, String command) {
        for (AuditRow row : rows) {
            if (command.equals(row.command())) return row;
        }
        return null;
    }

    private static void awaitSubscribers(TCPCommandServer server, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000L;
        while (server.getActiveSubscriberCount() != expected
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(expected, server.getActiveSubscriberCount());
    }

    private static JsonObject eventFrame(String event, String identityKey,
                                         String identity, long seq) {
        JsonObject frame = new JsonObject();
        frame.addProperty("event", event);
        JsonObject data = new JsonObject();
        data.addProperty(identityKey, identity);
        data.addProperty("progress", seq);
        frame.add("data", data);
        frame.addProperty("ts", seq);
        frame.addProperty("seq", seq);
        return frame;
    }

    private static String errorCode(JsonObject response) {
        assertFalse(response.toString(), response.get("ok").getAsBoolean());
        assertTrue(response.get("error").isJsonObject());
        return response.getAsJsonObject("error").get("code").getAsString();
    }

    private static JsonObject parse(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    private static void resetPosture() {
        Settings reset = new Settings();
        reset.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        PostureController.getInstance().configure(reset);
    }

    private static final class FakeClock
            implements SessionCapsRegistry.Ticker, SessionCapsRegistry.WallClock {
        long nanos;
        long wallMillis = 1_700_000_000_000L;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long currentTimeMillis() {
            return wallMillis;
        }
    }
}
