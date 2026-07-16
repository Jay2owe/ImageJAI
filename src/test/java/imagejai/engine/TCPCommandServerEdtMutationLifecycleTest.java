package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Truthfulness and owner/command scoping for tracked EDT mutations. */
public class TCPCommandServerEdtMutationLifecycleTest {

    @Test
    public void malformedOperationIdsAreRejectedBeforeLookupOrActionValidation() {
        TCPCommandServer server = newServer();
        try {
            JsonObject malformed = server.dispatch(parse(
                    "{\"command\":\"interact_dialog\",\"operation_id\":\"edt_bad!\"}"),
                    caps("malformed-session"));
            assertErrorCode(malformed, "invalid_operation_id");
            JsonObject oversized = server.dispatch(parse(
                    "{\"command\":\"open_image\",\"operation_id\":\"edt_"
                            + repeat('x', 10_000) + "\"}"), caps("malformed-session"));
            assertErrorCode(oversized, "invalid_operation_id");
        } finally {
            server.stop();
        }
    }

    @Test
    public void oldServerGenerationCannotTearDownRapidRestart() throws Exception {
        TCPCommandServer server = newServer();
        CountDownLatch firstBeforeBind = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        server.setBeforeBindHookForTest(() -> {
            if (invocations.incrementAndGet() == 1) {
                firstBeforeBind.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            server.start(null);
            assertTrue(firstBeforeBind.await(2, TimeUnit.SECONDS));
            server.stop();
            server.start(null);
            awaitRunning(server, 3000L);

            releaseFirst.countDown();
            Thread.sleep(100L);
            assertTrue("old runServer finally tore down the restarted server",
                    server.isRunning());
        } finally {
            releaseFirst.countDown();
            server.stop();
        }
    }

    @Test
    public void bindFailureCleansConsoleAndReactiveWatcherWithoutExplicitStop()
            throws Exception {
        java.net.ServerSocket blocker = new java.net.ServerSocket(
                0, 50, java.net.InetAddress.getLoopbackAddress());
        TCPCommandServer server = new TCPCommandServer(
                blocker.getLocalPort(), null, null, null, null);
        CountDownLatch error = new CountDownLatch(1);
        TCPCommandServer.ServerListener listener =
                new TCPCommandServer.ServerListener() {
                    @Override public void onServerStarted(int port) { }
                    @Override public void onServerStopped() { }
                    @Override public void onClientConnected(String clientInfo) { }
                    @Override public void onCommandReceived(String command) { }
                    @Override public void onError(String message) {
                        error.countDown();
                    }
                };
        try {
            server.start(listener);
            assertTrue("bind failure was not reported",
                    error.await(3, TimeUnit.SECONDS));
            long deadline = System.currentTimeMillis() + 3000L;
            while ((ConsoleCapture.isInstalled()
                    || hasLiveThread("imagej-ai-reactive-watcher"))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertFalse("console tee leaked after bind failure",
                    ConsoleCapture.isInstalled());
            assertFalse(server.isRunning());
            assertFalse("reactive watcher leaked after bind failure",
                    hasLiveThread("imagej-ai-reactive-watcher"));
        } finally {
            server.stop();
            blocker.close();
            ConsoleCapture.uninstall();
        }
    }

    @Test
    public void startedOpenReturnsHandleAndPublishesResultOnlyAfterEdtExit()
            throws Exception {
        TCPCommandServer server = newServer();
        TCPCommandServer.AgentCaps caps = caps("open-session");
        Path requested = Paths.get("slow-open.tif").toAbsolutePath().normalize();
        AtomicReference<List<ImageGraph.ImageRef>> open =
                new AtomicReference<List<ImageGraph.ImageRef>>(
                        new ArrayList<ImageGraph.ImageRef>());
        AtomicReference<ImagePlus> active = new AtomicReference<ImagePlus>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.openImagesForTest = open::get;
        server.currentImageForTest = active::get;
        server.openImageOperationForTest = (path, series) -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test release timed out");
            }
            ImagePlus image = new ImagePlus("slow-open.tif", new ByteProcessor(1, 1));
            ImageGraph.ImageRef ref = new ImageGraph.ImageRef(image,
                    ImageGraph.stableIdentity(image), 1, image.getTitle(),
                    requested.toString());
            active.set(image);
            open.set(Collections.singletonList(ref));
        };
        try {
            JsonObject initial = server.dispatch(parse(
                    "{\"command\":\"open_image\",\"path\":\"slow-open.tif\","
                            + "\"timeout_ms\":25}"), caps);
            assertTrue("open never started on EDT", started.await(2, TimeUnit.SECONDS));
            String id = assertInProgress(initial, "running");
            JsonObject friction = server.dispatch(parse(
                    "{\"command\":\"get_friction_log\"}"), caps);
            assertEquals(0, friction.getAsJsonObject("result")
                    .get("total").getAsInt());

            JsonObject early = poll(server, caps, "open_image", id);
            assertFalse(early.getAsJsonObject("result").get("terminal").getAsBoolean());

            // An id cannot be polled through a different command, even in the
            // same session, and polling happens before required action fields.
            JsonObject wrongCommand = server.dispatch(parse(
                    "{\"command\":\"interact_dialog\",\"operation_id\":\""
                            + id + "\"}"), caps);
            assertErrorCode(wrongCommand, "operation_unknown");

            release.countDown();
            JsonObject status = awaitTerminal(server, caps, "open_image", id);
            assertEquals("completed", status.get("state").getAsString());
            assertTrue(status.getAsJsonObject("result").get("ok").getAsBoolean());
            assertTrue(status.getAsJsonObject("result").getAsJsonObject("result")
                    .get("opened").getAsBoolean());
        } finally {
            release.countDown();
            server.stop();
        }
    }

    @Test
    public void startedDialogReturnsHandleAndPollNeedsNoOriginalAction()
            throws Exception {
        TCPCommandServer server = newServer();
        TCPCommandServer.AgentCaps caps = caps("dialog-session");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.dialogInteractionForTest = ignored -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted", interrupted);
            }
            JsonObject result = new JsonObject();
            result.addProperty("clicked", "OK");
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.add("result", result);
            return response;
        };
        try {
            JsonObject initial = server.dispatch(parse(
                    "{\"command\":\"interact_dialog\",\"action\":\"click_button\","
                            + "\"target\":\"OK\",\"timeout_ms\":25}"), caps);
            assertTrue("dialog click never started on EDT",
                    started.await(2, TimeUnit.SECONDS));
            String id = assertInProgress(initial, "running");
            JsonObject early = poll(server, caps, "interact_dialog", id);
            assertFalse(early.getAsJsonObject("result").get("terminal").getAsBoolean());

            release.countDown();
            JsonObject status = awaitTerminal(
                    server, caps, "interact_dialog", id);
            assertTrue(status.get("terminal").getAsBoolean());
            assertEquals("OK", status.getAsJsonObject("result")
                    .getAsJsonObject("result").get("clicked").getAsString());
        } finally {
            release.countDown();
            server.stop();
        }
    }

    @Test
    public void openWaitsNonblockingForImagePublishedAfterEdtWrapperExit()
            throws Exception {
        TCPCommandServer server = newServer();
        TCPCommandServer.AgentCaps caps = caps("async-open-session");
        Path requested = Paths.get("async-open.tif").toAbsolutePath().normalize();
        AtomicReference<List<ImageGraph.ImageRef>> open =
                new AtomicReference<List<ImageGraph.ImageRef>>(
                        new ArrayList<ImageGraph.ImageRef>());
        AtomicReference<ImagePlus> active = new AtomicReference<ImagePlus>();
        CountDownLatch openerReturned = new CountDownLatch(1);
        server.openImagesForTest = open::get;
        server.currentImageForTest = active::get;
        server.openImageOperationForTest = (path, series) -> {
            openerReturned.countDown();
            Thread publisher = new Thread(() -> {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ImagePlus image = new ImagePlus("async-open.tif",
                        new ByteProcessor(1, 1));
                ImageGraph.ImageRef ref = new ImageGraph.ImageRef(image,
                        ImageGraph.stableIdentity(image), 1, image.getTitle(),
                        requested.toString());
                active.set(image);
                open.set(Collections.singletonList(ref));
            }, "async-open-test-publisher");
            publisher.setDaemon(true);
            publisher.start();
        };
        try {
            JsonObject initial = server.dispatch(parse(
                    "{\"command\":\"open_image\",\"path\":\"async-open.tif\","
                            + "\"timeout_ms\":25}"), caps);
            assertTrue(openerReturned.await(2, TimeUnit.SECONDS));
            String id = assertInProgress(initial, "running");
            JsonObject early = poll(server, caps, "open_image", id)
                    .getAsJsonObject("result");
            assertFalse(early.get("terminal").getAsBoolean());

            JsonObject status = awaitTerminal(server, caps, "open_image", id);
            assertEquals("completed", status.get("state").getAsString());
            JsonObject original = status.getAsJsonObject("result");
            assertTrue(original.toString(), original.get("ok").getAsBoolean());
            JsonObject opened = original.getAsJsonObject("result");
            assertTrue(opened.get("opened").getAsBoolean());
            assertTrue(opened.get("image_id").getAsString().startsWith("img-"));
            assertTrue(opened.get("title").getAsString().endsWith(".tif"));
            assertFalse("raw title leaked through pseudonymisation",
                    "async-open.tif".equals(opened.get("title").getAsString()));
        } finally {
            server.stop();
        }
    }

    private static JsonObject awaitTerminal(TCPCommandServer server,
                                            TCPCommandServer.AgentCaps caps,
                                            String command, String id)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        JsonObject status;
        do {
            JsonObject response = poll(server, caps, command, id);
            status = response.has("operation")
                    ? response.getAsJsonObject("operation")
                    : response.getAsJsonObject("result");
            if (status.get("terminal").getAsBoolean()) return status;
            Thread.sleep(10L);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("operation did not become terminal: " + status);
    }

    private static JsonObject poll(TCPCommandServer server,
                                   TCPCommandServer.AgentCaps caps,
                                   String command, String id) {
        JsonObject response = server.dispatch(parse("{\"command\":\"" + command
                + "\",\"operation_id\":\"" + id + "\"}"), caps);
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        return response;
    }

    private static String assertInProgress(JsonObject response, String state) {
        assertErrorCode(response, "operation_in_progress");
        JsonObject operation = response.getAsJsonObject("operation");
        assertFalse(operation.get("terminal").getAsBoolean());
        assertFalse(operation.get("retry_safe").getAsBoolean());
        assertEquals(state, operation.get("state").getAsString());
        return operation.get("operation_id").getAsString();
    }

    private static void assertErrorCode(JsonObject response, String code) {
        assertFalse(response.toString(), response.get("ok").getAsBoolean());
        assertEquals(code, response.getAsJsonObject("error").get("code").getAsString());
    }

    private static TCPCommandServer.AgentCaps caps(String session) {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.sessionId = session;
        return caps;
    }

    private static TCPCommandServer newServer() {
        return new TCPCommandServer(0, null, null, null, null);
    }

    private static JsonObject parse(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }

    private static void awaitRunning(TCPCommandServer server, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!server.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("server did not bind", server.isRunning());
    }

    private static boolean hasLiveThread(String name) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName()) && thread.isAlive()) return true;
        }
        return false;
    }
}
