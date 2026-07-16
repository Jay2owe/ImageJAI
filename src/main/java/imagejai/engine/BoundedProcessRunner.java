package imagejai.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs one owned subprocess with fixed lifetime and retained-output bounds. */
final class BoundedProcessRunner {
    private static final long GRACEFUL_STOP_MS = 250L;
    private static final long FORCED_STOP_MS = 2000L;
    private static final long DRAIN_JOIN_MS = 2000L;

    private BoundedProcessRunner() {}

    static Result run(List<String> command, long timeoutMs, int maxOutputBytes)
            throws IOException, InterruptedException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("process command is required");
        }
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("process timeout must be positive");
        }
        if (maxOutputBytes < 0) {
            throw new IllegalArgumentException("process output bound cannot be negative");
        }

        ProcessBuilder builder = new ProcessBuilder(new ArrayList<String>(command));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        OutputCollector collector = new OutputCollector(
                process.getInputStream(), maxOutputBytes);
        Thread drainer = new Thread(collector, "ImageJAI-ProcessOutput");
        drainer.setDaemon(true);
        try {
            drainer.start();
        } catch (RuntimeException startFailure) {
            forceTerminateTree(process);
            closeQuietly(process.getInputStream());
            throw startFailure;
        }

        boolean timedOut = false;
        boolean terminated = true;
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                timedOut = true;
                terminated = terminateTree(process);
            }
            awaitCollector(drainer, process.getInputStream());
        } catch (InterruptedException interrupted) {
            forceTerminateTree(process);
            closeQuietly(process.getInputStream());
            drainer.interrupt();
            throw interrupted;
        } finally {
            if (process.isAlive()) {
                forceTerminateTree(process);
            }
        }

        OutputCollector.Snapshot output = collector.snapshot();
        if (!output.complete && !timedOut) {
            throw new IOException("subprocess output drainer did not terminate");
        }
        if (output.failure != null && !timedOut) {
            throw output.failure;
        }
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        return new Result(exitCode, timedOut, terminated && !process.isAlive(),
                output.complete, output.text, output.totalBytes,
                output.totalBytes > maxOutputBytes, process.pid());
    }

    private static void awaitCollector(Thread drainer, InputStream processOutput)
            throws InterruptedException {
        drainer.join(DRAIN_JOIN_MS);
        if (!drainer.isAlive()) return;
        closeQuietly(processOutput);
        drainer.interrupt();
        drainer.join(GRACEFUL_STOP_MS);
    }

    private static boolean terminateTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = descendants(process);
        for (ProcessHandle child : descendants) child.destroy();
        process.destroy();
        if (!process.waitFor(GRACEFUL_STOP_MS, TimeUnit.MILLISECONDS)) {
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) child.destroyForcibly();
            }
            process.destroyForcibly();
            process.waitFor(FORCED_STOP_MS, TimeUnit.MILLISECONDS);
        }
        for (ProcessHandle child : descendants) {
            if (child.isAlive()) child.destroyForcibly();
        }
        return !process.isAlive() && noneAlive(descendants);
    }

    private static void forceTerminateTree(Process process) {
        for (ProcessHandle child : descendants(process)) {
            try { child.destroyForcibly(); } catch (RuntimeException ignored) {}
        }
        try { process.destroyForcibly(); } catch (RuntimeException ignored) {}
    }

    private static List<ProcessHandle> descendants(Process process) {
        List<ProcessHandle> handles = new ArrayList<ProcessHandle>();
        try {
            process.toHandle().descendants().forEach(handles::add);
        } catch (RuntimeException ignored) {
            // The direct owned process is still terminated below.
        }
        return handles;
    }

    private static boolean noneAlive(List<ProcessHandle> handles) {
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) return false;
        }
        return true;
    }

    private static void closeQuietly(InputStream input) {
        try { input.close(); } catch (IOException ignored) {}
    }

    static final class Result {
        final int exitCode;
        final boolean timedOut;
        final boolean terminated;
        final boolean outputComplete;
        final String output;
        final long totalOutputBytes;
        final boolean outputTruncated;
        final long pid;

        private Result(int exitCode, boolean timedOut, boolean terminated,
                       boolean outputComplete, String output,
                       long totalOutputBytes, boolean outputTruncated, long pid) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.terminated = terminated;
            this.outputComplete = outputComplete;
            this.output = output;
            this.totalOutputBytes = totalOutputBytes;
            this.outputTruncated = outputTruncated;
            this.pid = pid;
        }
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream input;
        private final int maxOutputBytes;
        private final ByteArrayOutputStream retained;
        private long totalBytes;
        private boolean complete;
        private IOException failure;

        private OutputCollector(InputStream input, int maxOutputBytes) {
            this.input = input;
            this.maxOutputBytes = maxOutputBytes;
            this.retained = new ByteArrayOutputStream(Math.min(8192, maxOutputBytes));
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (InputStream stream = input) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    record(buffer, read);
                }
            } catch (IOException error) {
                synchronized (this) {
                    failure = error;
                }
            } finally {
                synchronized (this) {
                    complete = true;
                }
            }
        }

        private synchronized void record(byte[] buffer, int length) {
            totalBytes = Long.MAX_VALUE - totalBytes < length
                    ? Long.MAX_VALUE : totalBytes + length;
            int remaining = maxOutputBytes - retained.size();
            if (remaining > 0) {
                retained.write(buffer, 0, Math.min(remaining, length));
            }
        }

        private synchronized Snapshot snapshot() {
            return new Snapshot(
                    new String(retained.toByteArray(), StandardCharsets.UTF_8),
                    totalBytes, complete, failure);
        }

        private static final class Snapshot {
            final String text;
            final long totalBytes;
            final boolean complete;
            final IOException failure;

            private Snapshot(String text, long totalBytes, boolean complete,
                             IOException failure) {
                this.text = text;
                this.totalBytes = totalBytes;
                this.complete = complete;
                this.failure = failure;
            }
        }
    }
}
