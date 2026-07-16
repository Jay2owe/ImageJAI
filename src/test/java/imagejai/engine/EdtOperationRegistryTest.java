package imagejai.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EdtOperationRegistryTest {

    @Test
    public void queuedCancellationIsNotTerminalUntilEdtWrapperExits()
            throws Exception {
        final CountDownLatch blockerEntered = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        blockEdt(blockerEntered, releaseBlocker);
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS));

        EdtOperationRegistry registry = registry(2, 4, 1024, 128);
        AtomicInteger executions = new AtomicInteger();
        try {
            EdtOperationRegistry.Operation operation = registry.admit(
                    "owner-a",
                    () -> GuiActionDispatcher.queueSwingAction(executions::incrementAndGet),
                    () -> result("unexpected"));

            assertEquals(EdtOperationRegistry.STATE_QUEUED, operation.state());
            assertTrue(registry.cancel("owner-a", operation.id()));
            assertFalse(operation.awaitTerminal(50L));
            JsonObject pending = registry.operationStatus("owner-a", operation.id());
            assertEquals(EdtOperationRegistry.STATE_QUEUED,
                    pending.get("state").getAsString());
            assertFalse(pending.get("terminal").getAsBoolean());
            assertFalse(pending.get("retry_safe").getAsBoolean());

            releaseBlocker.countDown();
            assertTrue(operation.awaitTerminal(2_000L));
            JsonObject failed = registry.operationStatus("owner-a", operation.id());
            assertEquals(EdtOperationRegistry.STATE_FAILED,
                    failed.get("state").getAsString());
            assertTrue(failed.get("terminal").getAsBoolean());
            assertTrue(failed.get("retry_safe").getAsBoolean());
            assertEquals(0, executions.get());
        } finally {
            releaseBlocker.countDown();
            registry.shutdown();
            registry.awaitQuiescence(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void startedBlockedActionRemainsRunningThenCompletes() throws Exception {
        EdtOperationRegistry registry = registry(2, 4, 1024, 128);
        CountDownLatch actionEntered = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        try {
            EdtOperationRegistry.Operation operation = registry.admit(
                    "owner-a",
                    () -> GuiActionDispatcher.queueSwingAction(() -> {
                        actionEntered.countDown();
                        awaitUninterruptibly(releaseAction);
                    }),
                    () -> result("done"));

            assertTrue(actionEntered.await(2, TimeUnit.SECONDS));
            assertTrue(operation.hasStarted());
            assertEquals(EdtOperationRegistry.STATE_RUNNING, operation.state());
            assertFalse("started EDT work must not claim safe cancellation",
                    registry.cancel("owner-a", operation.id()));
            assertFalse(operation.awaitTerminal(50L));

            releaseAction.countDown();
            assertTrue(operation.awaitTerminal(2_000L));
            JsonObject complete = registry.operationStatus("owner-a", operation.id());
            assertEquals(EdtOperationRegistry.STATE_COMPLETED,
                    complete.get("state").getAsString());
            assertEquals("done", complete.getAsJsonObject("result")
                    .get("value").getAsString());
            assertFalse(complete.get("retry_safe").getAsBoolean());
        } finally {
            releaseAction.countDown();
            registry.shutdown();
            registry.awaitQuiescence(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void statusAndCancellationAreOwnerScoped() throws Exception {
        EdtOperationRegistry registry = registry(2, 4, 1024, 128);
        try {
            EdtOperationRegistry.Operation operation = registry.admit(
                    "owner-a",
                    () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                    () -> result("visible"));
            assertTrue(operation.awaitTerminal(2_000L));

            assertNull(registry.operationStatus("owner-b", operation.id()));
            assertFalse(registry.cancel("owner-b", operation.id()));
            assertNull(registry.operationStatus("owner-a", repeat('x', 100_000)));
            assertFalse(registry.cancel("owner-a", "edt_bad!characters"));
            assertEquals("visible", registry.operationStatus("owner-a", operation.id())
                    .getAsJsonObject("result").get("value").getAsString());
        } finally {
            registry.shutdown();
            registry.awaitQuiescence(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void activeAndRetainedCapsPrecedeQueueingAndEvictOldestTerminal()
            throws Exception {
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        blockEdt(blockerEntered, releaseBlocker);
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS));

        EdtOperationRegistry registry = registry(1, 2, 1024, 128);
        AtomicInteger rejectedStarterCalls = new AtomicInteger();
        try {
            EdtOperationRegistry.Operation first = registry.admit(
                    "owner",
                    () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                    () -> result("first"));
            try {
                registry.admit("owner", () -> {
                    rejectedStarterCalls.incrementAndGet();
                    return GuiActionDispatcher.queueSwingAction(() -> { });
                }, () -> result("rejected"));
                throw new AssertionError("active capacity accepted a second action");
            } catch (RejectedExecutionException expected) {
                assertTrue(expected.getMessage().contains("active capacity"));
            }
            assertEquals(0, rejectedStarterCalls.get());

            releaseBlocker.countDown();
            assertTrue(first.awaitTerminal(2_000L));
            EdtOperationRegistry.Operation second = registry.admit(
                    "owner",
                    () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                    () -> result("second"));
            assertTrue(second.awaitTerminal(2_000L));
            Thread.sleep(2L);
            EdtOperationRegistry.Operation third = registry.admit(
                    "owner",
                    () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                    () -> result("third"));
            assertTrue(third.awaitTerminal(2_000L));

            assertTrue(registry.retainedCount() <= 2);
            assertNull("oldest terminal record was not evicted",
                    registry.operationStatus("owner", first.id()));
            assertTrue(registry.operationStatus("owner", third.id())
                    .get("terminal").getAsBoolean());
        } finally {
            releaseBlocker.countDown();
            registry.shutdown();
            registry.awaitQuiescence(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shutdownRejectsAdmissionWithoutLyingAboutQueuedCompletion()
            throws Exception {
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        blockEdt(blockerEntered, releaseBlocker);
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS));

        EdtOperationRegistry registry = registry(1, 2, 1024, 128);
        try {
            EdtOperationRegistry.Operation operation = registry.admit(
                    "owner",
                    () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                    () -> result("late"));
            registry.shutdown();
            assertFalse(operation.isTerminal());
            assertFalse(registry.awaitQuiescence(50, TimeUnit.MILLISECONDS));
            try {
                registry.admit("owner",
                        () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                        () -> result("rejected"));
                throw new AssertionError("shutdown registry accepted an action");
            } catch (RejectedExecutionException expected) {
                assertTrue(expected.getMessage().contains("stopped"));
            }

            releaseBlocker.countDown();
            assertTrue(operation.awaitTerminal(2_000L));
            assertTrue(registry.awaitQuiescence(2, TimeUnit.SECONDS));
            assertEquals(EdtOperationRegistry.STATE_FAILED, operation.state());
        } finally {
            releaseBlocker.countDown();
            registry.shutdown();
        }
    }

    @Test
    public void retainedResultsAndErrorsAreBounded() throws Exception {
        EdtOperationRegistry registry = registry(2, 4, 128, 8);
        try {
            EdtOperationRegistry.Operation largeResult = registry.admit(
                    "owner",
                    () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                    () -> result(repeat('x', 512)));
            assertTrue(largeResult.awaitTerminal(2_000L));
            JsonObject resultStatus = largeResult.toJson();
            assertEquals(EdtOperationRegistry.STATE_FAILED,
                    resultStatus.get("state").getAsString());
            assertFalse(resultStatus.has("result"));
            assertTrue(resultStatus.get("result_truncated").getAsBoolean());
            assertTrue(resultStatus.get("result_original_bytes").getAsLong() > 128L);
            assertEquals(8, resultStatus.get("error").getAsString().length());

            EdtOperationRegistry.Operation failure = registry.admit(
                    "owner",
                    () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                    () -> { throw new IllegalStateException("12345678901234567890"); });
            assertTrue(failure.awaitTerminal(2_000L));
            JsonObject failedStatus = failure.toJson();
            assertEquals(EdtOperationRegistry.STATE_FAILED,
                    failedStatus.get("state").getAsString());
            assertEquals(8, failedStatus.get("error").getAsString().length());
        } finally {
            registry.shutdown();
            registry.awaitQuiescence(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void deferredCompletionStaysActiveAndCompletesExactlyOnceWhenReady()
            throws Exception {
        EdtOperationRegistry registry = registry(2, 4, 1024, 128);
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        EdtOperationRegistry.CompletionSupplier deferred =
                new EdtOperationRegistry.CompletionSupplier() {
                    @Override public boolean isReady() {
                        probes.incrementAndGet();
                        return ready.get();
                    }

                    @Override public JsonObject complete() {
                        completions.incrementAndGet();
                        return result("deferred");
                    }
                };
        try {
            EdtOperationRegistry.Operation operation = registry.admit(
                    "owner", () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                    deferred);
            SwingUtilities.invokeAndWait(() -> { });
            assertFalse(operation.awaitTerminal(50L));
            assertEquals(1, registry.activeCount());
            assertTrue(probes.get() > 0);
            assertEquals(0, completions.get());

            // Shutdown must keep observing an already-exited wrapper whose
            // bounded asynchronous result is not published yet.
            registry.shutdown();
            assertFalse(registry.awaitQuiescence(50, TimeUnit.MILLISECONDS));
            ready.set(true);
            assertTrue(operation.awaitTerminal(2_000L));
            assertEquals(1, completions.get());
            assertEquals("deferred", operation.toJson().getAsJsonObject("result")
                    .get("value").getAsString());
            assertTrue(registry.awaitQuiescence(2, TimeUnit.SECONDS));
        } finally {
            ready.set(true);
            registry.shutdown();
            registry.awaitQuiescence(2, TimeUnit.SECONDS);
        }
    }

    @Test
    public void neverReadyCompletionFailsBoundedlyAndShutdownQuiesces()
            throws Exception {
        EdtOperationRegistry registry = new EdtOperationRegistry(
                1, 2, 60_000L, 5L, 1024, 128, 40L);
        try {
            EdtOperationRegistry.Operation operation = registry.admit(
                    "owner", () -> GuiActionDispatcher.queueSwingAction(() -> { }),
                    new EdtOperationRegistry.CompletionSupplier() {
                        @Override public boolean isReady() { return false; }
                        @Override public JsonObject complete() {
                            throw new AssertionError("complete called while not ready");
                        }
                    });
            SwingUtilities.invokeAndWait(() -> { });
            registry.shutdown();
            assertTrue(operation.awaitTerminal(2_000L));
            assertEquals(EdtOperationRegistry.STATE_FAILED, operation.state());
            assertTrue(operation.toJson().get("error").getAsString()
                    .contains("readiness deadline"));
            assertTrue(registry.awaitQuiescence(2, TimeUnit.SECONDS));
        } finally {
            registry.shutdown();
        }
    }

    private static EdtOperationRegistry registry(int active, int retained,
                                                  int resultBytes,
                                                  int errorChars) {
        return new EdtOperationRegistry(active, retained, 60_000L, 5L,
                resultBytes, errorChars);
    }

    private static JsonObject result(String value) {
        JsonObject result = new JsonObject();
        result.addProperty("value", value);
        return result;
    }

    private static void blockEdt(CountDownLatch entered, CountDownLatch release) {
        SwingUtilities.invokeLater(() -> {
            entered.countDown();
            awaitUninterruptibly(release);
        });
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
