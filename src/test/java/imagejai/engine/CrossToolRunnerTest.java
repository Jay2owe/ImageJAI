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
    public void timeoutTerminatesParentAndDescendant() throws Exception {
        Path childPidFile = Files.createTempFile("imagejai-child-", ".pid");
        Files.deleteIfExists(childPidFile);
        try {
            CrossToolRunner.ToolResult result = CrossToolRunner.runCommand(
                    fixtureCommand("parent", childPidFile.toString()), null, 800L);
            assertFalse(result.success);
            assertTrue(result.timedOut);
            assertTrue(result.stderr.contains("timed out"));

            long deadline = System.currentTimeMillis() + 3000L;
            while (!Files.exists(childPidFile) && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            assertTrue("fixture must expose the descendant pid", Files.exists(childPidFile));
            long childPid = Long.parseLong(new String(
                    Files.readAllBytes(childPidFile), StandardCharsets.UTF_8).trim());
            while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            assertFalse("timed-out descendant must not survive",
                    ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            Files.deleteIfExists(childPidFile);
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
            if ("parent".equals(args[0])) {
                Process child = new ProcessBuilder(fixtureCommand("child")).start();
                Files.write(Paths.get(args[1]),
                        Long.toString(child.pid()).getBytes(StandardCharsets.UTF_8));
                Thread.sleep(60_000L);
            }
        }
    }
}
