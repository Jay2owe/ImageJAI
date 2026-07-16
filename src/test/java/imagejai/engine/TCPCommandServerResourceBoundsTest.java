package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TCPCommandServerResourceBoundsTest {

    @Test
    public void utf8ReaderAcceptsExactByteLimitNotCharacterLimit() throws Exception {
        TCPCommandServer.RequestLine line = TCPCommandServer.readUtf8Line(
                new ByteArrayInputStream("éé\n".getBytes(StandardCharsets.UTF_8)), 4);

        assertEquals("éé", line.text);
        assertEquals(4, line.byteCount);
    }

    @Test(expected = TCPCommandServer.RequestTooLargeException.class)
    public void utf8ReaderRejectsMultibyteRequestBeforeDecodeAllocation() throws Exception {
        TCPCommandServer.readUtf8Line(new ByteArrayInputStream(
                "ééa\n".getBytes(StandardCharsets.UTF_8)), 4);
    }

    @Test(expected = CharacterCodingException.class)
    public void utf8ReaderRejectsMalformedInput() throws Exception {
        TCPCommandServer.readUtf8Line(
                new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28, '\n'}), 8);
    }

    @Test
    public void utf8ReaderExcludesCrLfFramingFromExactPayloadLimit() throws Exception {
        byte[] framed = "éé\r\n".getBytes(StandardCharsets.UTF_8);
        TCPCommandServer.RequestLine line = TCPCommandServer.readUtf8Line(
                new ByteArrayInputStream(framed), 4);

        assertEquals("éé", line.text);
        assertEquals(4, line.byteCount);
    }

    @Test
    public void compoundBinaryBudgetIsAppliedBeforeBase64Allocation() {
        assertEquals(0, TCPCommandServer.maxBinaryBytesForCompoundBudget(2048L,
                TCPCommandServer.MAX_CAPTURE_PNG_BYTES));
        int bounded = TCPCommandServer.maxBinaryBytesForCompoundBudget(
                TCPCommandServer.MAX_BATCH_RESPONSE_BYTES,
                TCPCommandServer.MAX_CAPTURE_PNG_BYTES);
        assertTrue(bounded < TCPCommandServer.MAX_CAPTURE_PNG_BYTES);
        assertTrue(4L * ((bounded + 2L) / 3L) + 2048L
                <= TCPCommandServer.MAX_BATCH_RESPONSE_BYTES);
    }

    @Test
    public void batchCountAndDepthAreBounded() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        try {
            JsonObject tooMany = new JsonObject();
            tooMany.addProperty("command", "batch");
            JsonArray commands = new JsonArray();
            for (int i = 0; i <= TCPCommandServer.MAX_BATCH_COMMANDS; i++) {
                JsonObject ping = new JsonObject();
                ping.addProperty("command", "ping");
                commands.add(ping);
            }
            tooMany.add("commands", commands);
            assertFalse(server.dispatch(tooMany, new TCPCommandServer.AgentCaps())
                    .get("ok").getAsBoolean());

            JsonObject nested = pingBatch();
            for (int i = 0; i < TCPCommandServer.MAX_BATCH_DEPTH; i++) {
                JsonObject parent = new JsonObject();
                parent.addProperty("command", "batch");
                JsonArray one = new JsonArray();
                one.add(nested);
                parent.add("commands", one);
                nested = parent;
            }
            String response = server.dispatch(nested, new TCPCommandServer.AgentCaps())
                    .toString();
            assertTrue(response.contains("nesting depth exceeds"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void boundedUtf8PropertyDoesNotSplitSurrogatePairs() {
        JsonObject out = new JsonObject();
        TCPCommandServer.addBoundedUtf8Property(out, "value", "A😀B", 5L);

        assertEquals("A😀", out.get("value").getAsString());
        assertTrue(out.get("value_truncated").getAsBoolean());
        assertEquals(6L, out.get("value_original_bytes").getAsLong());
        assertEquals(5L, out.get("value_returned_bytes").getAsLong());
    }

    @Test
    public void deeplyNestedValidJsonGetsStructuredWireErrorAndServerSurvives()
            throws Exception {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        server.setServerTokenForTest("test-token-with-at-least-thirty-two-characters");
        try {
            server.start(null);
            awaitRunning(server);
            StringBuilder request = new StringBuilder();
            request.append("{\"command\":\"ping\",\"unknown\":");
            for (int i = 0; i < 4096; i++) request.append('[');
            request.append('0');
            for (int i = 0; i < 4096; i++) request.append(']');
            request.append('}');

            JsonObject rejected = rawExchange(server.getPort(), request.toString());
            assertFalse(rejected.get("ok").getAsBoolean());
            assertEquals("invalid_request", rejected.getAsJsonObject("error")
                    .get("code").getAsString());

            JsonObject ping = rawExchange(server.getPort(), "{\"command\":\"ping\"}");
            assertTrue("worker must survive parser/shape rejection",
                    ping.get("ok").getAsBoolean());
            assertEquals("pong", ping.get("result").getAsString());
        } finally {
            server.stop();
        }
    }

    @Test
    public void unknownCommandAndExcessTopLevelFieldsAreStructuredInvalidRequests() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        try {
            JsonObject unknown = new JsonObject();
            unknown.addProperty("command", "definitely_not_a_command");
            assertEquals("invalid_request", server.dispatch(unknown,
                    new TCPCommandServer.AgentCaps()).getAsJsonObject("error")
                    .get("code").getAsString());

            JsonObject fields = new JsonObject();
            fields.addProperty("command", "ping");
            for (int i = 0; i < TCPCommandServer.MAX_REQUEST_TOP_LEVEL_FIELDS; i++) {
                fields.addProperty("unknown_" + i, i);
            }
            JsonObject rejected = server.dispatch(fields,
                    new TCPCommandServer.AgentCaps());
            assertEquals("invalid_request", rejected.getAsJsonObject("error")
                    .get("code").getAsString());

            JsonObject singleUnknown = new JsonObject();
            singleUnknown.addProperty("command", "ping");
            singleUnknown.addProperty("unused", "ignored-before-this-fix");
            JsonObject unknownField = server.dispatch(singleUnknown,
                    new TCPCommandServer.AgentCaps());
            assertEquals("invalid_request", unknownField.getAsJsonObject("error")
                    .get("code").getAsString());
            assertTrue(unknownField.getAsJsonObject("error").get("message")
                    .getAsString().contains("Unknown field 'unused'"));

            JsonObject knownFramingFields = new JsonObject();
            knownFramingFields.addProperty("command", "ping");
            knownFramingFields.addProperty("session_id", "session");
            knownFramingFields.addProperty("token", "token");
            knownFramingFields.addProperty("if_none_match", "digest");
            knownFramingFields.addProperty("force", true);
            assertTrue(server.dispatch(knownFramingFields,
                    new TCPCommandServer.AgentCaps()).get("ok").getAsBoolean());

            JsonObject pixelsWithTimeout = new JsonObject();
            pixelsWithTimeout.addProperty("command", "get_pixels");
            pixelsWithTimeout.addProperty("timeout_ms", 1000);
            assertEquals(null, TCPCommandServer.validateRequestShape(pixelsWithTimeout));

            JsonObject rewindWithTimeout = new JsonObject();
            rewindWithTimeout.addProperty("command", "rewind");
            rewindWithTimeout.addProperty("timeout_ms", 1000);
            assertEquals(null, TCPCommandServer.validateRequestShape(rewindWithTimeout));
        } finally {
            server.stop();
        }
    }

    @Test
    public void connectionFloodNeverExceedsGlobalWorkerAndQueueCaps() throws Exception {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        server.setServerTokenForTest("test-token-with-at-least-thirty-two-characters");
        List<Socket> clients = new ArrayList<Socket>();
        try {
            server.start(null);
            long readyDeadline = System.nanoTime() + 5_000_000_000L;
            while (!server.isRunning() && System.nanoTime() < readyDeadline) {
                Thread.sleep(10L);
            }
            assertTrue(server.isRunning());

            int attempts = TCPCommandServer.MAX_CONNECTION_WORKERS
                    + TCPCommandServer.CONNECTION_QUEUE_CAPACITY + 24;
            for (int i = 0; i < attempts; i++) {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        server.getPort()), 1000);
                clients.add(socket);
            }
            long rejectedDeadline = System.nanoTime() + 5_000_000_000L;
            while (server.getRejectedConnectionCount() == 0L
                    && System.nanoTime() < rejectedDeadline) {
                Thread.sleep(10L);
            }

            assertTrue(server.getActiveConnectionWorkerCount()
                    <= TCPCommandServer.MAX_CONNECTION_WORKERS);
            assertTrue(server.getQueuedConnectionCount()
                    <= TCPCommandServer.CONNECTION_QUEUE_CAPACITY);
            assertTrue("flood must produce an observable rejection",
                    server.getRejectedConnectionCount() > 0L);
        } finally {
            for (Socket client : clients) {
                try { client.close(); } catch (Exception ignored) { }
            }
            server.stop();
        }
    }

    @Test
    public void stopClosesClientBlockedBeforeFirstRequestByte() throws Exception {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        server.setServerTokenForTest("test-token-with-at-least-thirty-two-characters");
        Socket client = new Socket();
        try {
            server.start(null);
            awaitRunning(server);
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                    server.getPort()), 1000);
            client.setSoTimeout(1500);
            long trackedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (server.getTrackedClientSocketCountForTest() == 0
                    && System.nanoTime() < trackedDeadline) Thread.sleep(5L);
            assertEquals(1, server.getTrackedClientSocketCountForTest());

            server.stop();

            assertEquals(0, server.getTrackedClientSocketCountForTest());
            try {
                assertEquals(-1, client.getInputStream().read());
            } catch (IOException closed) {
                assertTrue(client.isClosed() || closed.getMessage() != null);
            }
        } finally {
            try { client.close(); } catch (IOException ignored) { }
            server.stop();
        }
    }

    @Test
    public void stopBeforeServerThreadBindsNeverLeavesConfiguredPortOpen()
            throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(
                0, 50, InetAddress.getLoopbackAddress())) {
            port = reservation.getLocalPort();
        }
        final CountDownLatch beforeBind = new CountDownLatch(1);
        final CountDownLatch releaseBind = new CountDownLatch(1);
        TCPCommandServer server = new TCPCommandServer(port, null, null, null, null);
        server.setServerTokenForTest("test-token-with-at-least-thirty-two-characters");
        server.setBeforeBindHookForTest(new Runnable() {
            @Override public void run() {
                beforeBind.countDown();
                try { releaseBind.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        try {
            server.start(null);
            assertTrue(beforeBind.await(2, TimeUnit.SECONDS));
            server.stop();
            releaseBind.countDown();
            Thread.sleep(50L);

            try (ServerSocket probe = new ServerSocket(
                    port, 50, InetAddress.getLoopbackAddress())) {
                assertEquals(port, probe.getLocalPort());
            }
            assertFalse(server.isRunning());
        } finally {
            releaseBind.countDown();
            server.stop();
        }
    }

    private static JsonObject pingBatch() {
        JsonObject batch = new JsonObject();
        batch.addProperty("command", "batch");
        JsonArray commands = new JsonArray();
        JsonObject ping = new JsonObject();
        ping.addProperty("command", "ping");
        commands.add(ping);
        batch.add("commands", commands);
        return batch;
    }

    private static void awaitRunning(TCPCommandServer server) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (!server.isRunning() && System.nanoTime() < deadline) Thread.sleep(10L);
        assertTrue(server.isRunning());
    }

    private static JsonObject rawExchange(int port, String request) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            assertTrue("server must return a JSON response", line != null);
            return com.google.gson.JsonParser.parseString(line).getAsJsonObject();
        }
    }
}
