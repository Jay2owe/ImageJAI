package imagejai.engine;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BoundedProcessRunnerTest {
    @Test
    public void retainsOnlyTheDeclaredOutputPrefix() throws Exception {
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                fixtureCommand("output"), 5000L, 64);

        assertEquals(0, result.exitCode);
        assertFalse(result.timedOut);
        assertTrue(result.terminated);
        assertTrue(result.outputComplete);
        assertTrue(result.outputTruncated);
        assertTrue(result.totalOutputBytes >= 4096L);
        assertEquals(64, result.output.getBytes("UTF-8").length);
    }

    @Test
    public void timeoutTerminatesTheOwnedProcess() throws Exception {
        long started = System.nanoTime();
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                fixtureCommand("sleep"), 200L, 64);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(result.timedOut);
        assertTrue(result.terminated);
        assertTrue(result.outputComplete);
        assertTrue(elapsedMs < 5000L);
        assertFalse(ProcessHandle.of(result.pid)
                .map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    public void interruptionAlsoTerminatesTheOwnedProcess() throws Exception {
        Path pidFile = Files.createTempFile("imagejai-runner-pid", ".txt");
        Files.deleteIfExists(pidFile);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread runner = new Thread(() -> {
            try {
                BoundedProcessRunner.run(
                        fixtureCommand("sleep", pidFile.toString()), 60000L, 64);
            } catch (Throwable caught) {
                failure.set(caught);
            }
        }, "BoundedProcessRunnerTest-Caller");
        runner.start();

        long deadline = System.nanoTime() + 5_000_000_000L;
        String pidText = "";
        while (pidText.isEmpty() && System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                pidText = new String(
                        Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim();
            }
            if (!pidText.isEmpty()) break;
            Thread.sleep(10L);
        }
        assertFalse("fixture did not publish its pid", pidText.isEmpty());
        long pid = Long.parseLong(pidText);

        runner.interrupt();
        runner.join(5000L);
        assertFalse("runner thread leaked", runner.isAlive());
        assertTrue(failure.get() instanceof InterruptedException);
        long terminationDeadline = System.nanoTime() + 2_000_000_000L;
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < terminationDeadline) {
            Thread.sleep(10L);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        Files.deleteIfExists(pidFile);
    }

    private static List<String> fixtureCommand(String action) {
        return fixtureCommand(action, null);
    }

    private static List<String> fixtureCommand(String action, String argument) {
        String executable = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        if (argument == null) {
            return Arrays.asList(executable, "-cp", System.getProperty("java.class.path"),
                    Fixture.class.getName(), action);
        }
        return Arrays.asList(executable, "-cp", System.getProperty("java.class.path"),
                Fixture.class.getName(), action, argument);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            if (args.length > 0 && "output".equals(args[0])) {
                StringBuilder text = new StringBuilder(4096);
                for (int index = 0; index < 4096; index++) text.append('x');
                System.out.print(text.toString());
                return;
            }
            if (args.length > 1) {
                Files.write(Paths.get(args[1]),
                        Long.toString(ProcessHandle.current().pid())
                                .getBytes(StandardCharsets.UTF_8));
            }
            Thread.sleep(60000L);
        }
    }
}
