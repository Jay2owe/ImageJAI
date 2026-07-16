package imagejai.engine;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CrossToolRunnerTest {

    @Test
    public void outputIsUtf8AndBoundedWithExplicitTruncation() {
        CrossToolRunner.ToolResult result = CrossToolRunner.runCommand(
                fixtureCommand("flood"), null, 15_000L);

        assertTrue(result.success);
        assertTrue(result.stdout.startsWith("μ"));
        assertTrue(result.stdoutTruncated);
        assertTrue(result.stdout.endsWith(CrossToolRunner.TRUNCATION_MARKER));
        assertTrue(result.stdout.length() <= CrossToolRunner.MAX_CAPTURE_BYTES
                + CrossToolRunner.TRUNCATION_MARKER.length());
    }

    @Test
    public void timeoutIsReportedWithoutDependingOnFixtureStartupSpeed() {
        CrossToolRunner.ToolResult result = CrossToolRunner.runCommand(
                fixtureCommand("child"), null, 800L);

        assertFalse(result.success);
        assertTrue(result.timedOut);
        assertTrue(result.stderr.contains("timed out"));
    }

    @Test
    public void terminateProcessTreeKillsReadyParentAndDescendant() throws Exception {
        Path childPidFile = Files.createTempFile("imagejai-child-", ".pid");
        Path grandchildPidFile = Files.createTempFile("imagejai-grandchild-", ".pid");
        Files.deleteIfExists(childPidFile);
        Files.deleteIfExists(grandchildPidFile);
        Process parent = null;
        ProcessHandle child = null;
        ProcessHandle grandchild = null;
        try {
            parent = new ProcessBuilder(
                    fixtureCommand("parent", childPidFile.toString(),
                            grandchildPidFile.toString())).start();
            long childPid = awaitPid(childPidFile, 20_000L);
            long grandchildPid = awaitPid(grandchildPidFile, 20_000L);
            child = ProcessHandle.of(childPid).orElse(null);
            grandchild = ProcessHandle.of(grandchildPid).orElse(null);
            assertTrue("fixture descendant must be alive before termination",
                    child != null && child.isAlive());
            assertTrue("fixture grandchild must be alive before termination",
                    grandchild != null && grandchild.isAlive());

            CrossToolRunner.terminateProcessTree(parent);

            long deadline = System.currentTimeMillis() + 3000L;
            while ((parent.isAlive() || child.isAlive() || grandchild.isAlive())
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            assertFalse("terminated parent must not survive", parent.isAlive());
            assertFalse("terminated descendant must not survive", child.isAlive());
            assertFalse("terminated grandchild must not survive", grandchild.isAlive());
        } finally {
            if (parent != null && parent.isAlive()) {
                CrossToolRunner.terminateProcessTree(parent);
            }
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
            if (grandchild != null && grandchild.isAlive()) {
                grandchild.destroyForcibly();
            }
            Files.deleteIfExists(childPidFile);
            Files.deleteIfExists(grandchildPidFile);
        }
    }

    @Test
    public void mutationVariantSubmitsExactlyOnceToProvidedCoordinator() {
        final AtomicInteger submissions = new AtomicInteger();
        MutationCoordinator coordinator = new MutationCoordinator() {
            @Override public <T> Handle<T> submit(Request<T> request) {
                submissions.incrementAndGet();
                return super.submit(request);
            }
        };
        try {
            CrossToolRunner.ToolResult result = CrossToolRunner.runCommandMutation(
                    coordinator, "cross-tool-owner", fixtureCommand("echo"), null, 5000L);
            assertTrue(result.success);
            assertTrue(result.stdout.contains("μ-cross-tool"));
            assertTrue(submissions.get() == 1);
        } finally {
            coordinator.shutdown();
        }
    }

    private static String[] fixtureCommand(String... fixtureArgs) {
        String executable = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        String[] command = new String[4 + fixtureArgs.length];
        command[0] = executable;
        command[1] = "-cp";
        command[2] = System.getProperty("java.class.path");
        command[3] = ProcessFixture.class.getName();
        System.arraycopy(fixtureArgs, 0, command, 4, fixtureArgs.length);
        return command;
    }

    private static long awaitPid(Path pidFile, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        NumberFormatException lastParseFailure = null;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(pidFile)) {
                String value = new String(Files.readAllBytes(pidFile),
                        StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        lastParseFailure = e;
                    }
                }
            }
            Thread.sleep(25L);
        }
        AssertionError failure = new AssertionError(
                "fixture did not expose the descendant pid within " + timeoutMs + "ms");
        if (lastParseFailure != null) failure.initCause(lastParseFailure);
        throw failure;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    public static final class ProcessFixture {
        public static void main(String[] args) throws Exception {
            if ("flood".equals(args[0])) {
                System.out.write("μ".getBytes(StandardCharsets.UTF_8));
                byte[] chunk = new byte[8192];
                java.util.Arrays.fill(chunk, (byte) 'x');
                int remaining = CrossToolRunner.MAX_CAPTURE_BYTES + 2048;
                while (remaining > 0) {
                    int size = Math.min(remaining, chunk.length);
                    System.out.write(chunk, 0, size);
                    remaining -= size;
                }
                return;
            }
            if ("echo".equals(args[0])) {
                System.out.write("μ-cross-tool".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("child".equals(args[0])) {
                Thread.sleep(60_000L);
                return;
            }
            if ("middle".equals(args[0])) {
                Process grandchild = new ProcessBuilder(fixtureCommand("child")).start();
                publishPid(Paths.get(args[1]), grandchild.pid());
                Thread.sleep(60_000L);
                return;
            }
            if ("parent".equals(args[0])) {
                Process child = new ProcessBuilder(
                        fixtureCommand("middle", args[2])).start();
                publishPid(Paths.get(args[1]), child.pid());
                Thread.sleep(60_000L);
            }
        }

        private static void publishPid(Path target, long pid) throws Exception {
            Path temporary = target.resolveSibling(
                    target.getFileName().toString() + ".tmp-" + ProcessHandle.current().pid());
            Files.write(temporary, Long.toString(pid).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
