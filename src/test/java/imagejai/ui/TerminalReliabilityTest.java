package imagejai.engine;

import imagejai.engine.security.OutboundPromptScrubber;
import imagejai.engine.security.PathTokenMap;
import imagejai.terminal.ApprovalPolicy;
import imagejai.terminal.PromptWatcher;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerminalReliabilityTest {

    @Test
    public void concurrentSessionsKeepPartialPromptBuffersIsolated() {
        String left = "left-" + UUID.randomUUID();
        String right = "right-" + UUID.randomUUID();
        PathTokenMap map = PathTokenMap.getInstance();
        String leftToken = map.tokenForSensitiveText(left, "test");
        String rightToken = map.tokenForSensitiveText(right, "test");
        OutboundPromptScrubber first = OutboundPromptScrubber.createSessionScrubber();
        OutboundPromptScrubber second = OutboundPromptScrubber.createSessionScrubber();

        first.filter(left.getBytes(StandardCharsets.UTF_8));
        second.filter(right.getBytes(StandardCharsets.UTF_8));
        String firstEnter = new String(first.filter(new byte[] {'\r'}), StandardCharsets.UTF_8);
        String secondEnter = new String(second.filter(new byte[] {'\r'}), StandardCharsets.UTF_8);

        assertTrue(firstEnter.contains(leftToken));
        assertFalse(firstEnter.contains(rightToken));
        assertTrue(secondEnter.contains(rightToken));
        assertFalse(secondEnter.contains(leftToken));
    }

    @Test
    public void watcherReportsLifecycleAndRetriesFailedAutoConfirm() {
        AtomicReference<String> tail =
                new AtomicReference<String>("Press Enter to confirm paste?");
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger pending = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();
        ApprovalPolicy policy = ApprovalPolicy.loadForAgent(
                new AgentLauncher.AgentInfo("Claude Code", "claude", "", "", ""));
        PromptWatcher watcher = new PromptWatcher(
                limit -> tail.get(), policy,
                text -> {
                    if (writes.incrementAndGet() == 1) throw new IOException("synthetic PTY failure");
                },
                new ListenerAdapter() {
                    @Override public void onAutoConfirm(String promptText) { confirmed.incrementAndGet(); }
                    @Override public void onPending(String promptText) { pending.incrementAndGet(); }
                    @Override public void onWatcherFailure(String operation, String errorType) {
                        failures.incrementAndGet();
                    }
                    @Override public void onWatcherState(PromptWatcher.State state) {
                        if (state == PromptWatcher.State.RUNNING) running.incrementAndGet();
                        if (state == PromptWatcher.State.STOPPED) stopped.incrementAndGet();
                    }
                });

        watcher.start();
        watcher.pollNow();
        assertEquals(0, confirmed.get());
        assertEquals(1, pending.get());
        assertEquals(1, failures.get());
        assertEquals(1L, watcher.failureCount());
        watcher.pollNow();
        watcher.stop();

        assertEquals(2, writes.get());
        assertEquals(1, confirmed.get());
        assertEquals(1, running.get());
        assertEquals(1, stopped.get());
        assertEquals(PromptWatcher.State.STOPPED, watcher.state());
    }

    @Test
    public void watcherStartStopIsIdempotentAndStoppedPollingIsInert()
            throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();
        final PromptWatcher watcher = new PromptWatcher(
                limit -> {
                    reads.incrementAndGet();
                    return "Proceed?";
                }, null, text -> { }, new ListenerAdapter() {
                    @Override public void onWatcherState(PromptWatcher.State state) {
                        if (state == PromptWatcher.State.RUNNING) running.incrementAndGet();
                        if (state == PromptWatcher.State.STOPPED) stopped.incrementAndGet();
                    }
                });

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                watcher.start();
                watcher.start();
                watcher.stop();
                watcher.stop();
            }
        });
        watcher.pollNow();

        assertEquals(1, running.get());
        assertEquals(1, stopped.get());
        assertEquals(0, reads.get());
        assertEquals(PromptWatcher.State.STOPPED, watcher.state());
    }

    @Test
    public void boundedLaunchDrainTimesOutWithoutPipeDeadlock() throws Exception {
        String java = new File(System.getProperty("java.home"),
                "bin" + File.separator + (isWindows() ? "java.exe" : "java")).getAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder(Arrays.asList(
                java, "-cp", System.getProperty("java.class.path"),
                SlowProcess.class.getName()));
        builder.redirectErrorStream(true);

        long started = System.nanoTime();
        AgentLauncher.ProcessResult result =
                AgentLauncher.runBoundedProcess(builder, 350L, 4096);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(result.timedOut);
        assertEquals(-1, result.exitCode);
        assertTrue(result.output.getBytes(StandardCharsets.UTF_8).length <= 4096);
        assertTrue("timeout should be bounded, elapsed=" + elapsedMs, elapsedMs < 3000L);
    }

    @Test
    public void unixShellQuotingContainsApostrophesWithoutBreakingArgument() {
        assertEquals("'C:/Lab'\"'\"'s data'",
                AgentLauncher.shellQuote("C:/Lab's data"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static final class SlowProcess {
        public static void main(String[] args) throws Exception {
            byte[] chunk = new byte[8192];
            Arrays.fill(chunk, (byte) 'x');
            for (int i = 0; i < 40; i++) System.out.write(chunk);
            System.out.flush();
            Thread.sleep(5000L);
        }
    }

    private abstract static class ListenerAdapter implements PromptWatcher.Listener {
        @Override public void onAutoConfirm(String promptText) { }
        @Override public void onEscalate(String promptText) { }
        @Override public void onPending(String promptText) { }
        @Override public void onPromptCleared() { }
        @Override public void onUrlSeen(String url) { }
    }
}
