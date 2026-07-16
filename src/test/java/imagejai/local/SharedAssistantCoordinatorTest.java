package imagejai.local;

import imagejai.config.Settings;
import imagejai.engine.CommandEngine;
import imagejai.engine.ExplorationEngine;
import imagejai.engine.MutationCoordinator;
import imagejai.engine.PipelineBuilder;
import imagejai.engine.StateInspector;
import imagejai.engine.TCPCommandServer;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SharedAssistantCoordinatorTest {

    @Test
    public void tcpAndBothAssistantsUseOneCoordinatorAndCannotOverlap() throws Exception {
        MutationCoordinator shared = new MutationCoordinator();
        CommandEngine command = new CommandEngine();
        TCPCommandServer server = new TCPCommandServer(0, command,
                new StateInspector(), new PipelineBuilder(command),
                new ExplorationEngine(command), shared);
        Settings settings = new Settings();
        settings.safeModeEnabled = false;
        AssistantMutationExecutor legacy = new AssistantMutationExecutor(
                settings, "legacy", "legacy", shared);
        AssistantMutationExecutor local = new AssistantMutationExecutor(
                settings, "local", "local", shared);

        assertSame(shared, server.getMutationCoordinator());
        assertSame(shared, legacy.coordinatorForTest());
        assertSame(shared, local.coordinatorForTest());

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        Thread first = new Thread(() -> legacy.execute("// first shared mutation",
                () -> {
                    int now = active.incrementAndGet();
                    maximum.accumulateAndGet(now, Math::max);
                    firstEntered.countDown();
                    releaseFirst.await(2, TimeUnit.SECONDS);
                    active.decrementAndGet();
                    return "first";
                }, 5_000L));
        Thread second = new Thread(() -> local.execute("// second shared mutation",
                () -> {
                    int now = active.incrementAndGet();
                    maximum.accumulateAndGet(now, Math::max);
                    secondEntered.countDown();
                    active.decrementAndGet();
                    return "second";
                }, 5_000L));

        first.start();
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
        second.start();
        assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();
        first.join(2_000L);
        second.join(2_000L);

        assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
        assertEquals(1, maximum.get());
        shared.shutdown();
    }
}
