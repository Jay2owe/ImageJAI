package imagejai.install;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProcessRunnerTest {

    @Test
    public void confirmBeforeRunBlocksProcessStart() throws Exception {
        RecordingStarter starter = new RecordingStarter(new FakeProcess(0, ""));
        ProcessRunner runner = new ProcessRunner(starter);

        ProcessRunner.RunResult result = runner.runConfirmed(
                Arrays.asList("npm", "i", "-g", "@openai/codex"),
                command -> false,
                line -> { });

        assertFalse(result.started);
        assertEquals(0, starter.started);
    }

    @Test
    public void confirmBeforeRunAllowsProcessAndStreamsOutput() throws Exception {
        RecordingStarter starter = new RecordingStarter(new FakeProcess(0, "one\ntwo\n"));
        ProcessRunner runner = new ProcessRunner(starter);
        List<String> lines = new ArrayList<String>();

        ProcessRunner.RunResult result = runner.runConfirmed(
                Arrays.asList("pip", "install", "aider-chat"),
                command -> command.contains("aider-chat"),
                lines::add);

        assertTrue(result.started);
        assertEquals(0, result.exitCode);
        assertEquals(1, starter.started);
        assertEquals(Arrays.asList("one", "two"), lines);
    }

    @Test
    public void statusDetectionRunsVersionCommand() {
        RecordingStarter starter = new RecordingStarter(new FakeProcess(0, "claude version\n"));
        ProcessRunner runner = new ProcessRunner(starter);

        ProcessRunner.VersionResult result = runner.checkVersion("claude", 500);

        assertTrue(result.installed);
        assertEquals(Arrays.asList("claude", "--version"), starter.lastCommand);
    }

    private static class RecordingStarter implements ProcessRunner.ProcessStarter {
        private final Process process;
        private int started;
        private List<String> lastCommand;

        RecordingStarter(Process process) {
            this.process = process;
        }

        @Override
        public Process start(List<String> command) {
            started++;
            lastCommand = new ArrayList<String>(command);
            return process;
        }
    }

    private static class FakeProcess extends Process {
        private final int exitCode;
        private final InputStream input;

        FakeProcess(int exitCode, String output) {
            this.exitCode = exitCode;
            this.input = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    // discard
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }
    }
}
