package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReactiveEngineTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private final List<ReactiveEngine> engines = new ArrayList<ReactiveEngine>();
    private final List<MutationCoordinator> coordinators =
            new ArrayList<MutationCoordinator>();

    @After
    public void cleanup() {
        for (ReactiveEngine engine : engines) engine.stop();
        for (MutationCoordinator coordinator : coordinators) coordinator.close();
    }

    @Test
    public void startStopIsIdempotentTerminatesResourcesAndSupportsRestart()
            throws Exception {
        Path rules = temporary.newFolder("lifecycle-rules").toPath();
        Rig rig = rig(rules, 1, 1000L, 3,
                fixedCapture(temporary.newFolder("lifecycle-output").toPath()
                        .resolve("AI_Exports"), new byte[] {1}, new AtomicInteger()),
                new NoopPolicy());

        rig.engine.start();
        rig.engine.start();
        ExecutorService firstExecutor = (ExecutorService) field(
                rig.engine, "actionExecutor");
        Thread firstWatcher = (Thread) field(rig.engine, "watchThread");
        assertNotNull(firstExecutor);
        assertNotNull(firstWatcher);

        rig.engine.stop();
        rig.engine.stop();
        assertTrue(firstExecutor.isShutdown());
        assertTrue("reactive executor survived stop",
                firstExecutor.awaitTermination(2, TimeUnit.SECONDS));
        firstWatcher.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse("reactive watch thread survived stop", firstWatcher.isAlive());
        assertNull(field(rig.engine, "actionExecutor"));
        assertNull(field(rig.engine, "watchThread"));

        rig.engine.start();
        ExecutorService restarted = (ExecutorService) field(
                rig.engine, "actionExecutor");
        assertNotNull(restarted);
        assertNotSame(firstExecutor, restarted);
        rig.engine.stop();
        assertTrue(restarted.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void queueSaturationRejectsBeforeSecondActionAllocation() throws Exception {
        Path rules = temporary.newFolder("queue-rules").toPath();
        writeRule(rules, "capture", true, "trigger.queue",
                actions(captureAction("queue")));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger captures = new AtomicInteger();
        ReactiveEngine.CaptureBackend backend = blockingCapture(
                temporary.newFolder("queue-output").toPath().resolve("AI_Exports"),
                captures, entered, release);
        Rig rig = rig(rules, 1, 1000L, 3, backend, new NoopPolicy());
        CountDownLatch saturated = diagnostic(rig.bus, "reactive.rejected",
                "queue_saturated");
        rig.engine.start();

        rig.bus.publish("trigger.queue");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        rig.bus.publish("trigger.queue");

        assertTrue(saturated.await(2, TimeUnit.SECONDS));
        assertEquals("rejected work must not reach the capture backend", 1,
                captures.get());
        release.countDown();
        awaitPermits(rig.engine, 1);
    }

    @Test
    public void queuedWorkExpiresBeforeExecution() throws Exception {
        Path rules = temporary.newFolder("stale-rules").toPath();
        writeRule(rules, "capture", true, "trigger.stale",
                actions(captureAction("stale")));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger captures = new AtomicInteger();
        AtomicLong now = new AtomicLong(100L);
        Rig rig = rig(rules, 2, 10L, 3,
                blockingCapture(temporary.newFolder("stale-output").toPath()
                        .resolve("AI_Exports"), captures, entered, release),
                new NoopPolicy(), now);
        CountDownLatch expired = diagnostic(rig.bus, "reactive.rejected", "expired");
        rig.engine.start();

        rig.bus.publish("trigger.stale");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        rig.bus.publish("trigger.stale");
        now.set(111L);
        release.countDown();

        assertTrue(expired.await(2, TimeUnit.SECONDS));
        assertEquals("expired queued action must never allocate a capture", 1,
                captures.get());
    }

    @Test
    public void disabledConfigurationAndQueuedDisableBothFailClosed() throws Exception {
        Path rules = temporary.newFolder("disabled-rules").toPath();
        writeRule(rules, "capture", false, "trigger.disabled",
                actions(captureAction("disabled")));
        final AtomicInteger captures = new AtomicInteger();
        ReactiveEngine.CaptureBackend backend = fixedCapture(
                temporary.newFolder("disabled-output").toPath().resolve("AI_Exports"),
                new byte[] {1, 2, 3}, captures);
        Rig rig = rig(rules, 2, 1000L, 3, backend, new NoopPolicy());
        CountDownLatch disabled = diagnostic(rig.bus, "reactive.rejected", "disabled");
        rig.engine.start();

        rig.bus.publish("trigger.disabled");
        assertTrue(disabled.await(2, TimeUnit.SECONDS));
        assertEquals(0, captures.get());

        writeRule(rules, "capture", true, "trigger.disabled",
                actions(captureAction("disabled")));
        rig.engine.reload();
        assertTrue("file enabled=true must be honored", rig.engine.getRules().get(0).enabled);
        assertTrue(rig.engine.setEnabled("capture", false));
        rig.engine.reload();
        assertFalse("explicit runtime override survives reload",
                rig.engine.getRules().get(0).enabled);
    }

    @Test
    public void cycleQuarantinesBeforeLaterMutationRuns() throws Exception {
        Path rules = temporary.newFolder("cycle-rules").toPath();
        JsonObject publish = new JsonObject();
        JsonObject payload = new JsonObject();
        payload.addProperty("topic", "trigger.cycle");
        publish.add("publish_event", payload);
        writeRule(rules, "cycle", true, "trigger.cycle",
                actions(publish, macroAction("run(\"Invert\");")));
        FakeCommandEngine command = new FakeCommandEngine();
        Rig rig = rig(rules, 4, 1000L, 3,
                fixedCapture(temporary.newFolder("cycle-output").toPath()
                        .resolve("AI_Exports"), new byte[] {1}, new AtomicInteger()),
                new NoopPolicy(), new AtomicLong(100L), command,
                new RecordingCoordinator());
        CountDownLatch quarantined = diagnostic(rig.bus, "reactive.quarantined",
                "cycle detected");
        rig.engine.start();

        rig.bus.publish("trigger.cycle");

        assertTrue(quarantined.await(2, TimeUnit.SECONDS));
        assertEquals(0, command.calls.get());
        assertTrue(rig.engine.getRules().get(0).runtimeQuarantined);
    }

    @Test
    public void repeatedActionFailuresQuarantineRule() throws Exception {
        Path rules = temporary.newFolder("failure-rules").toPath();
        writeRule(rules, "failure", true, "trigger.failure",
                actions(captureAction("failure")));
        final AtomicInteger calls = new AtomicInteger();
        ReactiveEngine.CaptureBackend failing = new ReactiveEngine.CaptureBackend() {
            @Override public ReactiveEngine.CaptureData capture() throws Exception {
                calls.incrementAndGet();
                throw new Exception("synthetic capture failure");
            }
        };
        Rig rig = rig(rules, 4, 1000L, 2, failing, new NoopPolicy());
        CountDownLatch firstFailure = diagnostic(rig.bus, "reactive.action_failed",
                "failure 1/2");
        CountDownLatch quarantined = diagnostic(rig.bus, "reactive.quarantined",
                "repeated action failures");
        rig.engine.start();

        rig.bus.publish("trigger.failure");
        assertTrue(firstFailure.await(2, TimeUnit.SECONDS));
        rig.bus.publish("trigger.failure");

        assertTrue(quarantined.await(2, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        rig.bus.publish("trigger.failure");
        assertEquals("quarantined rules must not reach actions again", 2, calls.get());
    }

    @Test
    public void macroUsesSharedCoordinatorSafetyUndoAndProvenance() throws Exception {
        Path rules = temporary.newFolder("macro-rules").toPath();
        writeRule(rules, "macro", true, "trigger.macro",
                actions(macroAction("run(\"Invert\");")));
        FakeCommandEngine command = new FakeCommandEngine();
        SpyPolicy policy = new SpyPolicy();
        RecordingCoordinator coordinator = new RecordingCoordinator();
        Rig rig = rig(rules, 2, 1000L, 3,
                fixedCapture(temporary.newFolder("macro-output").toPath()
                        .resolve("AI_Exports"), new byte[] {1}, new AtomicInteger()),
                policy, new AtomicLong(100L), command, coordinator);
        rig.engine.start();

        rig.bus.publish("trigger.macro");

        assertTrue(policy.completed.await(2, TimeUnit.SECONDS));
        assertEquals(1, command.calls.get());
        assertEquals(1, policy.safety.get());
        assertEquals(1, policy.before.get());
        assertEquals(1, policy.after.get());
        assertNotNull(coordinator.lastRequest.get());
        assertTrue(coordinator.lastRequest.get().safetyEnabled());
        assertTrue(coordinator.lastRequest.get().undoEnabled());
        assertTrue(coordinator.lastRequest.get().provenanceEnabled());
        assertEquals("reactive-macro", coordinator.lastRequest.get().sourceKind());
    }

    @Test
    public void capturesStayInAiExportsAndRejectOversizePayloads() throws Exception {
        Path rules = temporary.newFolder("capture-rules").toPath();
        writeRule(rules, "capture", true, "trigger.capture",
                actions(captureAction("../../unsafe name")));
        Path exports = temporary.newFolder("capture-output").toPath().resolve("AI_Exports");
        AtomicInteger calls = new AtomicInteger();
        Rig rig = rig(rules, 2, 1000L, 3,
                fixedCapture(exports, new byte[] {1, 2, 3, 4}, calls),
                new NoopPolicy());
        CountDownLatch written = diagnostic(rig.bus, "reactive.capture_written", "bytes=4");
        rig.engine.start();

        rig.bus.publish("trigger.capture");
        assertTrue(written.await(2, TimeUnit.SECONDS));
        List<Path> outputs;
        try (java.util.stream.Stream<Path> paths = Files.list(exports)) {
            outputs = paths.collect(java.util.stream.Collectors.toList());
        }
        assertEquals(1, outputs.size());
        assertEquals(exports.toAbsolutePath().normalize(), outputs.get(0).getParent());
        assertEquals(4L, Files.size(outputs.get(0)));

        Path oversizeRules = temporary.newFolder("oversize-rules").toPath();
        writeRule(oversizeRules, "oversize", true, "trigger.oversize",
                actions(captureAction("oversize")));
        Path oversizeExports = temporary.newFolder("oversize-output").toPath()
                .resolve("AI_Exports");
        Rig oversize = rig(oversizeRules, 2, 1000L, 1,
                fixedCapture(oversizeExports,
                        new byte[ReactiveEngine.MAX_CAPTURE_BYTES + 1],
                        new AtomicInteger()), new NoopPolicy());
        CountDownLatch failed = diagnostic(oversize.bus, "reactive.action_failed",
                "Capture exceeded");
        oversize.engine.start();
        oversize.bus.publish("trigger.oversize");
        assertTrue(failed.await(2, TimeUnit.SECONDS));
        assertFalse(Files.exists(oversizeExports));
    }

    @Test
    public void invalidActionIsQuarantinedAndReloadFailureIsObservable() throws Exception {
        Path rules = temporary.newFolder("reload-rules").toPath();
        JsonObject invalid = new JsonObject();
        invalid.addProperty("unknown_action", "x");
        writeRule(rules, "invalid", true, "trigger.invalid", actions(invalid));
        Rig rig = rig(rules, 2, 1000L, 3,
                fixedCapture(temporary.newFolder("reload-output").toPath()
                        .resolve("AI_Exports"), new byte[] {1}, new AtomicInteger()),
                new NoopPolicy());
        CountDownLatch reloadError = diagnostic(rig.bus, "reactive.reload_error",
                "unknown key");

        rig.engine.start();

        assertTrue(reloadError.await(2, TimeUnit.SECONDS));
        assertEquals(0, rig.engine.getRules().size());
        assertEquals(1, rig.engine.getQuarantined().size());
    }

    private Rig rig(Path rules, int capacity, long ttl, int failures,
                    ReactiveEngine.CaptureBackend backend,
                    ReactiveEngine.MutationPolicy policy) {
        return rig(rules, capacity, ttl, failures, backend, policy,
                new AtomicLong(100L));
    }

    private Rig rig(Path rules, int capacity, long ttl, int failures,
                    ReactiveEngine.CaptureBackend backend,
                    ReactiveEngine.MutationPolicy policy, final AtomicLong now) {
        return rig(rules, capacity, ttl, failures, backend, policy, now,
                new FakeCommandEngine(), new RecordingCoordinator());
    }

    private Rig rig(Path rules, int capacity, long ttl, int failures,
                    ReactiveEngine.CaptureBackend backend,
                    ReactiveEngine.MutationPolicy policy, final AtomicLong now,
                    CommandEngine command, RecordingCoordinator coordinator) {
        EventBus bus = new EventBus(new LongSupplier() {
            @Override public long getAsLong() { return now.get(); }
        });
        ReactiveEngine engine = new ReactiveEngine(bus, command, null, null,
                coordinator, rules, new LongSupplier() {
                    @Override public long getAsLong() { return now.get(); }
                }, capacity, ttl, failures, policy, backend);
        engines.add(engine);
        coordinators.add(coordinator);
        return new Rig(bus, engine);
    }

    private static CountDownLatch diagnostic(EventBus bus, final String topic,
                                             final String reasonFragment) {
        final CountDownLatch latch = new CountDownLatch(1);
        bus.subscribe(topic, new EventBus.Listener() {
            @Override public void onEvent(JsonObject frame) {
                JsonObject data = frame.getAsJsonObject("data");
                if (data != null && data.has("reason")
                        && data.get("reason").getAsString().contains(reasonFragment)) {
                    latch.countDown();
                }
            }
        });
        return latch;
    }

    private static ReactiveEngine.CaptureBackend fixedCapture(
            final Path exports, final byte[] bytes, final AtomicInteger calls) {
        return new ReactiveEngine.CaptureBackend() {
            @Override public ReactiveEngine.CaptureData capture() {
                calls.incrementAndGet();
                return new ReactiveEngine.CaptureData(bytes, exports);
            }
        };
    }

    private static ReactiveEngine.CaptureBackend blockingCapture(
            final Path exports, final AtomicInteger calls,
            final CountDownLatch entered, final CountDownLatch release) {
        return new ReactiveEngine.CaptureBackend() {
            @Override public ReactiveEngine.CaptureData capture() throws Exception {
                calls.incrementAndGet();
                entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
                return new ReactiveEngine.CaptureData(new byte[] {1}, exports);
            }
        };
    }

    private static void writeRule(Path dir, String name, boolean enabled,
                                  String topic, JsonArray actions) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("enabled", enabled);
        JsonObject when = new JsonObject();
        when.addProperty("event", topic);
        root.add("when", when);
        root.add("do", actions);
        Files.write(dir.resolve(name + ".json"), root.toString()
                .getBytes(StandardCharsets.UTF_8));
    }

    private static JsonArray actions(JsonObject... actions) {
        JsonArray array = new JsonArray();
        for (JsonObject action : actions) array.add(action);
        return array;
    }

    private static JsonObject captureAction(String name) {
        JsonObject action = new JsonObject();
        action.addProperty("capture", name);
        return action;
    }

    private static JsonObject macroAction(String code) {
        JsonObject action = new JsonObject();
        action.addProperty("execute_macro", code);
        return action;
    }

    private static void awaitPermits(ReactiveEngine engine, int expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (engine.availableActionPermitsForTest() == expected) return;
            Thread.sleep(5L);
        }
        assertEquals(expected, engine.availableActionPermitsForTest());
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class Rig {
        final EventBus bus;
        final ReactiveEngine engine;
        Rig(EventBus bus, ReactiveEngine engine) {
            this.bus = bus;
            this.engine = engine;
        }
    }

    private static class NoopPolicy implements ReactiveEngine.MutationPolicy {
        @Override public void checkSafety(String rule, String kind, String code) {}
        @Override public void beforeMutation(String rule, String kind, String code) {}
        @Override public void afterMutation(String rule, String kind, String code,
                                            MutationCoordinator.Outcome<?> outcome) {}
        @Override public void onCompletion(String rule, String kind, String code,
                                           MutationCoordinator.Completion<?> completion) {}
    }

    private static final class SpyPolicy extends NoopPolicy {
        final AtomicInteger safety = new AtomicInteger();
        final AtomicInteger before = new AtomicInteger();
        final AtomicInteger after = new AtomicInteger();
        final CountDownLatch completed = new CountDownLatch(1);

        @Override public void checkSafety(String rule, String kind, String code) {
            safety.incrementAndGet();
        }
        @Override public void beforeMutation(String rule, String kind, String code) {
            before.incrementAndGet();
        }
        @Override public void afterMutation(String rule, String kind, String code,
                                            MutationCoordinator.Outcome<?> outcome) {
            after.incrementAndGet();
        }
        @Override public void onCompletion(String rule, String kind, String code,
                                           MutationCoordinator.Completion<?> completion) {
            completed.countDown();
        }
    }

    private static final class FakeCommandEngine extends CommandEngine {
        final AtomicInteger calls = new AtomicInteger();
        @Override public ExecutionResult executeMacroOnCurrentThread(
                String code, DoubleConsumer callback) {
            calls.incrementAndGet();
            return ExecutionResult.success("", "", Collections.<String>emptyList(), 1L);
        }
    }

    private static final class RecordingCoordinator extends MutationCoordinator {
        final AtomicReference<MutationCoordinator.Request<?>> lastRequest =
                new AtomicReference<MutationCoordinator.Request<?>>();

        RecordingCoordinator() {
            super(8, new Object());
        }

        @Override public <T> MutationCoordinator.Handle<T> submit(
                MutationCoordinator.Request<T> request) {
            lastRequest.set(request);
            return super.submit(request);
        }
    }
}
