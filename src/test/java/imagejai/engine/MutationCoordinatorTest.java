package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MutationCoordinatorTest {

    private static final long WAIT_MS = 3_000L;

    @Test
    public void concurrentMutationBodiesNeverOverlap() throws Exception {
        try (TestRig rig = new TestRig(2)) {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondEntered = new CountDownLatch(1);

            MutationCoordinator.Handle<String> first = rig.coordinator.submit(
                    request("s1", 0L, () -> {
                        int now = active.incrementAndGet();
                        maxActive.updateAndGet(old -> Math.max(old, now));
                        firstEntered.countDown();
                        awaitIgnoringInterrupts(releaseFirst);
                        active.decrementAndGet();
                        return "one";
                    }));
            assertTrue(firstEntered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            MutationCoordinator.Handle<String> second = rig.coordinator.submit(
                    request("s2", 0L, () -> {
                        int now = active.incrementAndGet();
                        maxActive.updateAndGet(old -> Math.max(old, now));
                        secondEntered.countDown();
                        active.decrementAndGet();
                        return "two";
                    }));

            assertFalse("second body must wait on the mutation monitor",
                    secondEntered.await(100L, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertEquals(MutationCoordinator.State.SUCCEEDED,
                    first.awaitCompletion().state());
            assertEquals(MutationCoordinator.State.SUCCEEDED,
                    second.awaitCompletion().state());
            assertEquals(1, maxActive.get());
        }
    }

    @Test
    public void cancelledWorkerRemainsNonterminalAndOwnsLockUntilItReallyExits()
            throws Exception {
        try (TestRig rig = new TestRig(2)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch nextEntered = new CountDownLatch(1);
            AtomicInteger cancellationActions = new AtomicInteger();

            MutationCoordinator.Request<String> stubborn =
                    MutationCoordinator.Request.<String>builder()
                            .ownerSession("owner")
                            .sourceKind("test")
                            .cancellationAction(() -> cancellationActions.incrementAndGet())
                            .operation(() -> {
                                entered.countDown();
                                awaitIgnoringInterrupts(release);
                                return "late";
                            }).build();
            MutationCoordinator.Handle<String> first = rig.coordinator.submit(stubborn);
            assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(first.cancel());
            assertEquals(MutationCoordinator.State.CANCEL_REQUESTED, first.state());
            assertFalse(first.isWorkerExited());
            assertFalse(first.isTerminal());

            MutationCoordinator.Handle<String> next = rig.coordinator.submit(
                    request("next", 0L, () -> {
                        nextEntered.countDown();
                        return "next";
                    }));
            assertFalse(nextEntered.await(100L, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(MutationCoordinator.State.CANCELLED,
                    first.awaitCompletion().state());
            assertTrue(first.isWorkerExited());
            assertFalse(first.workerForTest().isAlive());
            assertTrue(nextEntered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertEquals(MutationCoordinator.State.SUCCEEDED,
                    next.awaitCompletion().state());
            assertEquals(1, cancellationActions.get());
        }
    }

    @Test
    public void timedOutWorkerIsNotTerminalUntilExit() throws Exception {
        try (TestRig rig = new TestRig(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger cancellationActions = new AtomicInteger();
            MutationCoordinator.Handle<String> handle = rig.coordinator.submit(
                    MutationCoordinator.Request.<String>builder()
                            .ownerSession("owner")
                            .sourceKind("test")
                            .timeoutMs(30L)
                            .cancellationAction(() -> cancellationActions.incrementAndGet())
                            .operation(() -> {
                                entered.countDown();
                                awaitIgnoringInterrupts(release);
                                return "late";
                            }).build());
            assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            waitForState(handle, MutationCoordinator.State.TIMEOUT_REQUESTED);
            assertFalse(handle.isTerminal());
            assertFalse(handle.isWorkerExited());
            try {
                rig.coordinator.submit(request("next", 0L, () -> "next"));
                fail("timed-out live worker must retain its capacity permit");
            } catch (RejectedExecutionException expected) {
                assertTrue(expected.getMessage().contains("capacity"));
            }
            release.countDown();
            assertEquals(MutationCoordinator.State.TIMED_OUT,
                    handle.awaitCompletion().state());
            assertEquals(1, cancellationActions.get());
        }
    }

    @Test
    public void timeoutCoversAfterMutationWithoutAbortingAnotherMacro()
            throws Exception {
        try (TestRig rig = new TestRig(1)) {
            CountDownLatch afterEntered = new CountDownLatch(1);
            CountDownLatch releaseAfter = new CountDownLatch(1);
            AtomicInteger cancellationActions = new AtomicInteger();
            AtomicInteger completions = new AtomicInteger();
            MutationCoordinator.Lifecycle<String> lifecycle =
                    new MutationCoordinator.Lifecycle<String>() {
                @Override public void afterMutation(MutationCoordinator.Outcome<String> outcome) {
                    afterEntered.countDown();
                    awaitIgnoringInterrupts(releaseAfter);
                }
                @Override public void onCompletion(MutationCoordinator.Completion<String> c) {
                    completions.incrementAndGet();
                }
            };
            MutationCoordinator.Handle<String> handle = rig.coordinator.submit(
                    MutationCoordinator.Request.<String>builder()
                            .ownerSession("owner")
                            .sourceKind("test")
                            .timeoutMs(30L)
                            .provenanceEnabled(true)
                            .lifecycle(lifecycle)
                            .cancellationAction(() -> cancellationActions.incrementAndGet())
                            .operation(() -> "mutated")
                            .build());
            assertTrue(afterEntered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            waitForState(handle, MutationCoordinator.State.TIMEOUT_REQUESTED);
            assertFalse(handle.isTerminal());
            assertEquals("global abort is only for the active mutation body",
                    0, cancellationActions.get());
            try {
                rig.coordinator.submit(request("next", 0L, () -> "next"));
                fail("afterMutation still owns capacity and serialization");
            } catch (RejectedExecutionException expected) {
                assertTrue(expected.getMessage().contains("capacity"));
            }
            releaseAfter.countDown();
            assertEquals(MutationCoordinator.State.TIMED_OUT,
                    handle.awaitCompletion().state());
            assertEquals(1, completions.get());
        }
    }

    @Test
    public void capacityRejectsBeforeCreatingAnyAdditionalThread() throws Exception {
        try (TestRig rig = new TestRig(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            MutationCoordinator.Handle<String> first = rig.coordinator.submit(
                    request("one", 0L, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupts(release);
                        return "one";
                    }));
            assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            int threadsBefore = rig.workersCreated.get();
            try {
                rig.coordinator.submit(request("two", 0L, () -> "two"));
                fail("capacity overflow should reject");
            } catch (RejectedExecutionException expected) {
                assertTrue(expected.getMessage().contains("capacity"));
            }
            assertEquals(threadsBefore, rig.workersCreated.get());
            release.countDown();
            first.awaitCompletion();
        }
    }

    @Test
    public void shutdownRejectsBeforeCreatingWorkerAndWaitsForExistingExit()
            throws Exception {
        TestRig rig = new TestRig(2);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            MutationCoordinator.Handle<String> running = rig.coordinator.submit(
                    request("one", 0L, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupts(release);
                        return "late";
                    }));
            assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            int before = rig.workersCreated.get();
            rig.coordinator.shutdown();
            assertTrue(running.isCancellationRequested());
            assertFalse(running.isTerminal());
            try {
                rig.coordinator.submit(request("two", 0L, () -> "two"));
                fail("submit after shutdown should reject");
            } catch (RejectedExecutionException expected) {
                assertTrue(expected.getMessage().contains("stopped"));
            }
            assertEquals(before, rig.workersCreated.get());
            release.countDown();
            assertEquals(MutationCoordinator.State.CANCELLED,
                    running.awaitCompletion().state());
        } finally {
            rig.close();
        }
    }

    @Test
    public void queuedCancellationNeverInvokesGlobalCancellationAction()
            throws Exception {
        try (TestRig rig = new TestRig(2)) {
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger wrongAbort = new AtomicInteger();
            MutationCoordinator.Handle<String> first = rig.coordinator.submit(
                    request("first", 0L, () -> {
                        firstEntered.countDown();
                        awaitIgnoringInterrupts(releaseFirst);
                        return "first";
                    }));
            assertTrue(firstEntered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            MutationCoordinator.Handle<String> queued = rig.coordinator.submit(
                    MutationCoordinator.Request.<String>builder()
                            .ownerSession("queued")
                            .sourceKind("test")
                            .cancellationAction(() -> wrongAbort.incrementAndGet())
                            .operation(() -> "must-not-run")
                            .build());
            assertTrue(queued.cancel());
            assertEquals(0, wrongAbort.get());
            releaseFirst.countDown();
            first.awaitCompletion();
            assertEquals(MutationCoordinator.State.CANCELLED,
                    queued.awaitCompletion().state());
            assertEquals(0, wrongAbort.get());
        }
    }

    @Test
    public void cryptographicIdsAreLongUniqueAndNonSequential() throws Exception {
        try (TestRig rig = new TestRig(1)) {
            Set<String> ids = new HashSet<String>();
            String previous = null;
            for (int i = 0; i < 40; i++) {
                MutationCoordinator.Handle<String> handle = rig.coordinator.submit(
                        request("owner", 0L, () -> "ok"));
                handle.awaitCompletion();
                assertTrue(handle.id().matches("j_[A-Za-z0-9_-]{32}"));
                assertTrue(ids.add(handle.id()));
                if (previous != null) assertNotEquals(previous, handle.id());
                previous = handle.id();
            }
        }
    }

    @Test
    public void jobRegistryEnforcesOwnerForLookupListAndCancellation()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CommandEngine fake = new CommandEngine() {
            @Override public ExecutionResult executeMacroOnCurrentThread(
                    String code, DoubleConsumer callback) {
                entered.countDown();
                awaitIgnoringInterrupts(release);
                return ExecutionResult.success("ok", null,
                        Collections.<String>emptyList(), 1L);
            }
        };
        MutationCoordinator coordinator = new MutationCoordinator(1, new Object());
        JobRegistry registry = new JobRegistry(fake, coordinator);
        try {
            JobRegistry.Job job = registry.submit("run();", "session-a", 0L,
                    false, false, false, null);
            assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertNull(registry.get("session-b", job.id));
            assertFalse(registry.cancel("session-b", job.id));
            assertTrue(registry.list("session-b").isEmpty());
            assertNotNull(registry.get("session-a", job.id));
            assertEquals(1, registry.list("session-a").size());
            assertTrue(registry.cancel("session-a", job.id));
            assertFalse(JobRegistry.isTerminal(job.state));
            release.countDown();
            job.handle.awaitCompletion();
            assertEquals(JobRegistry.STATE_CANCELLED, job.state);
        } finally {
            release.countDown();
            registry.shutdown();
        }
    }

    @Test
    public void tcpJobCommandsCannotCrossSessionBoundaries() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CommandEngine fake = new CommandEngine() {
            @Override public ExecutionResult executeMacroOnCurrentThread(
                    String code, DoubleConsumer callback) {
                entered.countDown();
                awaitIgnoringInterrupts(release);
                return ExecutionResult.success("ok", null,
                        Collections.<String>emptyList(), 1L);
            }
        };
        TCPCommandServer server = new TCPCommandServer(0, fake, null, null, null);
        TCPCommandServer.AgentCaps owner = new TCPCommandServer.AgentCaps();
        owner.sessionId = "session-owner";
        TCPCommandServer.AgentCaps stranger = new TCPCommandServer.AgentCaps();
        stranger.sessionId = "session-stranger";
        try {
            JsonObject submitted = server.dispatch(parseJson(
                    "{\"command\":\"execute_macro_async\","
                            + "\"code\":\"run('Blobs');\"}"), owner);
            assertTrue(submitted.get("ok").getAsBoolean());
            String jobId = submitted.getAsJsonObject("result")
                    .get("job_id").getAsString();
            assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));

            JsonObject foreignStatus = server.dispatch(parseJson(
                    "{\"command\":\"job_status\",\"job_id\":\"" + jobId + "\"}"), stranger);
            assertFalse(foreignStatus.get("ok").getAsBoolean());
            JsonObject foreignCancel = server.dispatch(parseJson(
                    "{\"command\":\"job_cancel\",\"job_id\":\"" + jobId + "\"}"), stranger);
            assertFalse(foreignCancel.get("ok").getAsBoolean());
            assertFalse(server.getJobRegistry().get(owner.sessionId, jobId)
                    .handle.isCancellationRequested());

            JsonObject ownCancel = server.dispatch(parseJson(
                    "{\"command\":\"job_cancel\",\"job_id\":\"" + jobId + "\"}"), owner);
            assertTrue(ownCancel.get("ok").getAsBoolean());
            assertTrue(ownCancel.getAsJsonObject("result")
                    .get("cancelled").getAsBoolean());
            release.countDown();
            assertEquals(MutationCoordinator.State.CANCELLED,
                    server.getJobRegistry().get(owner.sessionId, jobId)
                            .handle.awaitCompletion().state());
        } finally {
            release.countDown();
            server.stop();
        }
    }

    @Test
    public void pipelineDelegatesAcrossOwnedMonitorWithoutDeadlockOrInterleaving()
            throws Exception {
        CountDownLatch pipelineEntered = new CountDownLatch(1);
        CountDownLatch releasePipeline = new CountDownLatch(1);
        CountDownLatch externalEntered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CommandEngine fake = new CommandEngine() {
            @Override public ExecutionResult executeMacroOnCurrentThread(
                    String code, DoubleConsumer callback) {
                calls.incrementAndGet();
                if (code.contains("pipeline-one")) {
                    pipelineEntered.countDown();
                    awaitIgnoringInterrupts(releasePipeline);
                }
                if (code.contains("external")) externalEntered.countDown();
                return ExecutionResult.success(code, null,
                        Collections.<String>emptyList(), 1L);
            }
        };
        PipelineBuilder pipelineBuilder = new PipelineBuilder(fake);
        TCPCommandServer server = new TCPCommandServer(
                0, fake, null, pipelineBuilder, null);
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.sessionId = "pipeline-owner";
        caps.fuzzyMatch = false;
        caps.graphDelta = false;
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<JsonObject> pipeline = callers.submit(() -> server.dispatch(parseJson(
                    "{\"command\":\"run_pipeline\",\"steps\":["
                            + "{\"description\":\"one\",\"code\":\"pipeline-one;\"},"
                            + "{\"description\":\"two\",\"code\":\"pipeline-two;\"}]}"), caps));
            assertTrue(pipelineEntered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            Future<ExecutionResult> external = callers.submit(
                    () -> fake.executeMacro("external;"));
            assertFalse("external mutation must wait for the whole pipeline monitor",
                    externalEntered.await(100L, TimeUnit.MILLISECONDS));
            releasePipeline.countDown();
            JsonObject response = pipeline.get(WAIT_MS, TimeUnit.MILLISECONDS);
            assertTrue(response.toString(), response.get("ok").getAsBoolean());
            assertEquals("completed", response.getAsJsonObject("result")
                    .get("status").getAsString());
            assertTrue(externalEntered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(external.get(WAIT_MS, TimeUnit.MILLISECONDS).isSuccess());
            assertEquals(3, calls.get());
        } finally {
            releasePipeline.countDown();
            callers.shutdownNow();
            server.stop();
        }
    }

    @Test
    public void lifecycleHooksRunExactlyOnceForSuccessFailureAndCancel()
            throws Exception {
        try (TestRig rig = new TestRig(1)) {
            assertHookCounts(rig, () -> "ok", false);
            assertHookCounts(rig, () -> { throw new IllegalStateException("boom"); }, false);

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            HookCounters counters = new HookCounters();
            AtomicReference<MutationCoordinator.Handle<String>> admitted =
                    new AtomicReference<MutationCoordinator.Handle<String>>();
            MutationCoordinator.Handle<String> cancelled = rig.coordinator.submit(
                    hookRequest(counters, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupts(release);
                        return "late";
                    }), admitted::set);
            assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(cancelled.cancel());
            release.countDown();
            assertEquals(MutationCoordinator.State.CANCELLED,
                    cancelled.awaitCompletion().state());
            counters.assertExpected();
            assertFalse(admitted.get().workerForTest().isAlive());
        }
    }

    @Test
    public void safetyRejectionNeverRunsMutationOrUndoButStillFinalizesProvenance()
            throws Exception {
        try (TestRig rig = new TestRig(1)) {
            AtomicInteger safety = new AtomicInteger();
            AtomicInteger undo = new AtomicInteger();
            AtomicInteger operation = new AtomicInteger();
            AtomicInteger provenance = new AtomicInteger();
            AtomicInteger completion = new AtomicInteger();
            MutationCoordinator.Lifecycle<String> lifecycle =
                    new MutationCoordinator.Lifecycle<String>() {
                @Override public void checkSafety() throws Exception {
                    safety.incrementAndGet();
                    throw new MutationCoordinator.SafetyException("blocked");
                }
                @Override public void beforeMutation() { undo.incrementAndGet(); }
                @Override public void afterMutation(MutationCoordinator.Outcome<String> o) {
                    provenance.incrementAndGet();
                }
                @Override public void onCompletion(MutationCoordinator.Completion<String> c) {
                    completion.incrementAndGet();
                }
            };
            MutationCoordinator.Handle<String> handle = rig.coordinator.submit(
                    MutationCoordinator.Request.<String>builder()
                            .ownerSession("owner")
                            .sourceKind("test")
                            .safetyEnabled(true)
                            .undoEnabled(true)
                            .provenanceEnabled(true)
                            .lifecycle(lifecycle)
                            .operation(() -> { operation.incrementAndGet(); return "bad"; })
                            .build());
            assertEquals(MutationCoordinator.State.FAILED,
                    handle.awaitCompletion().state());
            assertEquals(1, safety.get());
            assertEquals(0, undo.get());
            assertEquals(0, operation.get());
            assertEquals(1, provenance.get());
            assertEquals(1, completion.get());
        }
    }

    @Test
    public void completionHookObservesAJoinedWorkerThread() throws Exception {
        try (TestRig rig = new TestRig(1)) {
            AtomicReference<MutationCoordinator.Handle<String>> admitted =
                    new AtomicReference<MutationCoordinator.Handle<String>>();
            AtomicBoolean hookSawExitedWorker = new AtomicBoolean();
            MutationCoordinator.Lifecycle<String> lifecycle =
                    new MutationCoordinator.Lifecycle<String>() {
                @Override public void onCompletion(
                        MutationCoordinator.Completion<String> completion) {
                    MutationCoordinator.Handle<String> handle = admitted.get();
                    hookSawExitedWorker.set(handle != null
                            && handle.isWorkerExited()
                            && !handle.workerForTest().isAlive());
                }
            };
            MutationCoordinator.Request<String> request =
                    MutationCoordinator.Request.<String>builder()
                            .ownerSession("owner")
                            .sourceKind("test")
                            .lifecycle(lifecycle)
                            .operation(() -> "ok")
                            .build();
            MutationCoordinator.Handle<String> handle =
                    rig.coordinator.submit(request, admitted::set);
            assertEquals(MutationCoordinator.State.SUCCEEDED,
                    handle.awaitCompletion().state());
            assertTrue(hookSawExitedWorker.get());
        }
    }

    private static void assertHookCounts(TestRig rig,
                                         MutationCoordinator.Operation<String> operation,
                                         boolean cancel) throws Exception {
        HookCounters counters = new HookCounters();
        MutationCoordinator.Handle<String> handle = rig.coordinator.submit(
                hookRequest(counters, operation));
        MutationCoordinator.Completion<String> completion = handle.awaitCompletion();
        assertEquals(cancel ? MutationCoordinator.State.CANCELLED
                        : (completion.error() == null
                            ? MutationCoordinator.State.SUCCEEDED
                            : MutationCoordinator.State.FAILED),
                completion.state());
        counters.assertExpected();
        assertFalse(handle.workerForTest().isAlive());
    }

    private static MutationCoordinator.Request<String> hookRequest(
            HookCounters counters, MutationCoordinator.Operation<String> operation) {
        return MutationCoordinator.Request.<String>builder()
                .ownerSession("owner")
                .sourceKind("test")
                .safetyEnabled(true)
                .undoEnabled(true)
                .provenanceEnabled(true)
                .lifecycle(counters)
                .operation(operation)
                .build();
    }

    private static MutationCoordinator.Request<String> request(
            String owner, long timeout, MutationCoordinator.Operation<String> operation) {
        return MutationCoordinator.Request.<String>builder()
                .ownerSession(owner)
                .sourceKind("test")
                .timeoutMs(timeout)
                .operation(operation)
                .build();
    }

    private static void waitForState(MutationCoordinator.Handle<?> handle,
                                     MutationCoordinator.State expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (System.nanoTime() < deadline) {
            if (handle.state() == expected) return;
            Thread.sleep(5L);
        }
        fail("expected state " + expected + " but got " + handle.state());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static JsonObject parseJson(String value) {
        return new JsonParser().parse(value).getAsJsonObject();
    }

    private static final class HookCounters
            implements MutationCoordinator.Lifecycle<String> {
        final AtomicInteger safety = new AtomicInteger();
        final AtomicInteger undo = new AtomicInteger();
        final AtomicInteger provenance = new AtomicInteger();
        final AtomicInteger completion = new AtomicInteger();

        @Override public void checkSafety() { safety.incrementAndGet(); }
        @Override public void beforeMutation() { undo.incrementAndGet(); }
        @Override public void afterMutation(MutationCoordinator.Outcome<String> o) {
            provenance.incrementAndGet();
        }
        @Override public void onCompletion(MutationCoordinator.Completion<String> c) {
            completion.incrementAndGet();
        }

        void assertExpected() {
            assertEquals(1, safety.get());
            assertEquals(1, undo.get());
            assertEquals(1, provenance.get());
            assertEquals(1, completion.get());
        }
    }

    private static final class TestRig implements AutoCloseable {
        final AtomicInteger workersCreated = new AtomicInteger();
        final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();
        final ExecutorService observers;
        final MutationCoordinator coordinator;

        TestRig(int capacity) {
            observers = Executors.newFixedThreadPool(capacity);
            coordinator = new MutationCoordinator(
                    capacity,
                    runnable -> {
                        Thread t = new Thread(runnable,
                                "test-mutation-" + workersCreated.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    },
                    scheduler,
                    observers,
                    System::currentTimeMillis,
                    new SecureRandom(),
                    new Object());
        }

        @Override public void close() {
            coordinator.shutdown();
            scheduler.shutdownNow();
            observers.shutdownNow();
        }
    }
}
