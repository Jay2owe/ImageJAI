package imagejai.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Optional integration for running external Python/R scripts from the
 * ImageJ AI assistant.
 * <p>
 * Uses {@link ProcessBuilder} to launch processes, writes script content
 * to temp files, captures stdout/stderr, and enforces timeouts.
 * <p>
 * Compiled for Java 11: process-tree control uses {@link ProcessHandle}.
 */
public class CrossToolRunner {

    private static final long DEFAULT_TIMEOUT_MS = 60000;
    static final int MAX_CAPTURE_BYTES = 1024 * 1024;
    static final String TRUNCATION_MARKER = "\n[output truncated by ImageJAI]";
    private static final long TERMINATION_GRACE_MS = 1000L;

    /** Result of an external tool invocation. */
    public static class ToolResult {
        public boolean success;
        public String stdout;
        public String stderr;
        public int exitCode;
        public long executionTimeMs;
        public boolean stdoutTruncated;
        public boolean stderrTruncated;
        public boolean timedOut;

        private ToolResult(boolean success, String stdout, String stderr,
                           int exitCode, long executionTimeMs,
                           boolean stdoutTruncated, boolean stderrTruncated,
                           boolean timedOut) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.executionTimeMs = executionTimeMs;
            this.stdoutTruncated = stdoutTruncated;
            this.stderrTruncated = stderrTruncated;
            this.timedOut = timedOut;
        }

        static ToolResult completed(StreamGobbler stdout, StreamGobbler stderr,
                                    int exitCode, long timeMs) {
            return new ToolResult(exitCode == 0, stdout.getOutput(), stderr.getOutput(),
                    exitCode, timeMs, stdout.wasTruncated(), stderr.wasTruncated(), false);
        }

        static ToolResult error(String message, long timeMs) {
            return new ToolResult(false, "", message, -1, timeMs,
                    false, false, false);
        }

        static ToolResult timeout(StreamGobbler stdout, StreamGobbler stderr,
                                  long timeoutMs, long timeMs) {
            String capturedErr = stderr.getOutput();
            String message = "Process timed out after " + timeoutMs + "ms";
            if (!capturedErr.isEmpty()) message = capturedErr + "\n" + message;
            return new ToolResult(false, stdout.getOutput(), message, -1, timeMs,
                    stdout.wasTruncated(), stderr.wasTruncated(), true);
        }
    }

    /**
     * Check if a tool is available on the system PATH.
     *
     * @param toolName command name (e.g. "python", "R", "Rscript")
     * @return true if the command can be executed
     */
    public static boolean isAvailable(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) {
            return false;
        }
        try {
            ProcessBuilder pb;
            if (isWindows()) {
                pb = new ProcessBuilder("where", toolName);
            } else {
                pb = new ProcessBuilder("which", toolName);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) terminateProcessTree(p);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Run a Python script and capture output.
     *
     * @param scriptContent Python script source code
     * @param workingDir    working directory for the process (may be null)
     * @param timeoutMs     timeout in milliseconds (0 for default)
     * @return tool result with stdout, stderr, exit code
     */
    public static ToolResult runPython(String scriptContent, String workingDir, long timeoutMs) {
        String python = findPython();
        if (python == null) {
            return ToolResult.error("Python not found on this system", 0);
        }
        return runScriptWithInterpreter(python, scriptContent, ".py", workingDir, timeoutMs);
    }

    /**
     * Run an R script and capture output.
     *
     * @param scriptContent R script source code
     * @param workingDir    working directory for the process (may be null)
     * @param timeoutMs     timeout in milliseconds (0 for default)
     * @return tool result with stdout, stderr, exit code
     */
    public static ToolResult runR(String scriptContent, String workingDir, long timeoutMs) {
        String rscript = findR();
        if (rscript == null) {
            return ToolResult.error("R/Rscript not found on this system", 0);
        }
        return runScriptWithInterpreter(rscript, scriptContent, ".R", workingDir, timeoutMs);
    }

    /**
     * Run any command and capture output.
     *
     * @param command    command array (executable + arguments)
     * @param workingDir working directory for the process (may be null)
     * @param timeoutMs  timeout in milliseconds (0 for default)
     * @return tool result with stdout, stderr, exit code
     */
    public static ToolResult runCommand(String[] command, String workingDir, long timeoutMs) {
        if (command == null || command.length == 0) {
            return ToolResult.error("Empty command", 0);
        }
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }

        long startTime = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null && !workingDir.isEmpty()) {
                File wd = new File(workingDir);
                if (wd.isDirectory()) {
                    pb.directory(wd);
                }
            }

            Process process = pb.start();
            StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
            StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
            stdoutGobbler.start();
            stderrGobbler.start();

            boolean finished;
            try {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                terminateProcessTree(process);
                Thread.currentThread().interrupt();
                long elapsed = System.currentTimeMillis() - startTime;
                return ToolResult.error("Command interrupted", elapsed);
            }
            long elapsed = System.currentTimeMillis() - startTime;

            if (!finished) {
                terminateProcessTree(process);
                joinQuietly(stdoutGobbler, TERMINATION_GRACE_MS);
                joinQuietly(stderrGobbler, TERMINATION_GRACE_MS);
                return ToolResult.timeout(stdoutGobbler, stderrGobbler,
                        timeoutMs, elapsed);
            }

            joinQuietly(stdoutGobbler, 5000L);
            joinQuietly(stderrGobbler, 5000L);

            int exitCode = process.exitValue();
            return ToolResult.completed(stdoutGobbler, stderrGobbler,
                    exitCode, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return ToolResult.error("Failed to run command: " + e.getMessage(), elapsed);
        }
    }

    /**
     * Get the path to a Python interpreter.
     * Checks python3, python, then common Windows locations.
     *
     * @return path to Python executable, or null if not found
     */
    public static String findPython() {
        // Try common names on PATH first
        if (isAvailable("python3")) {
            return "python3";
        }
        if (isAvailable("python")) {
            return "python";
        }

        // Windows-specific locations
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                File programsDir = new File(localAppData, "Programs" + File.separator + "Python");
                if (programsDir.isDirectory()) {
                    File[] children = programsDir.listFiles();
                    if (children != null) {
                        for (int i = 0; i < children.length; i++) {
                            File pythonExe = new File(children[i], "python.exe");
                            if (pythonExe.isFile()) {
                                return pythonExe.getAbsolutePath();
                            }
                        }
                    }
                }
            }

            // Check C:\Python*
            File cDrive = new File("C:\\");
            File[] roots = cDrive.listFiles();
            if (roots != null) {
                for (int i = 0; i < roots.length; i++) {
                    if (roots[i].getName().toLowerCase().startsWith("python")
                            && roots[i].isDirectory()) {
                        File pythonExe = new File(roots[i], "python.exe");
                        if (pythonExe.isFile()) {
                            return pythonExe.getAbsolutePath();
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get the path to R/Rscript.
     * Checks Rscript on PATH, then common Windows locations.
     *
     * @return path to Rscript executable, or null if not found
     */
    public static String findR() {
        if (isAvailable("Rscript")) {
            return "Rscript";
        }

        // Windows-specific: check Program Files
        if (isWindows()) {
            String[] programDirs = {
                    System.getenv("ProgramFiles"),
                    System.getenv("ProgramFiles(x86)")
            };
            for (int d = 0; d < programDirs.length; d++) {
                if (programDirs[d] == null) {
                    continue;
                }
                File rBase = new File(programDirs[d], "R");
                if (!rBase.isDirectory()) {
                    continue;
                }
                File[] versions = rBase.listFiles();
                if (versions == null) {
                    continue;
                }
                for (int i = 0; i < versions.length; i++) {
                    File rscript = new File(versions[i],
                            "bin" + File.separator + "Rscript.exe");
                    if (rscript.isFile()) {
                        return rscript.getAbsolutePath();
                    }
                }
            }
        }

        return null;
    }

    // ---- Private helpers ----

    /**
     * Run a script by writing it to a temp file and invoking an interpreter.
     */
    private static ToolResult runScriptWithInterpreter(String interpreter,
                                                        String scriptContent,
                                                        String extension,
                                                        String workingDir,
                                                        long timeoutMs) {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("imagej_ai_", extension);
            Writer writer = null;
            try {
                writer = new OutputStreamWriter(
                        Files.newOutputStream(tempFile.toPath()), StandardCharsets.UTF_8);
                writer.write(scriptContent);
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }

            String[] command = new String[]{ interpreter, tempFile.getAbsolutePath() };
            return runCommand(command, workingDir, timeoutMs);

        } catch (IOException e) {
            return ToolResult.error("Failed to create temp script file: " + e.getMessage(), 0);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /** Terminate descendants before their parent so they cannot be orphaned. */
    static void terminateProcessTree(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants = new ArrayList<ProcessHandle>();
        try {
            process.toHandle().descendants().forEach(descendants::add);
            // PIDs are allocation identifiers, not a process-tree ordering.  Killing a
            // parent before its child can orphan the child and make later discovery
            // unreliable, so take the ancestry depth while the tree is still intact.
            descendants.sort(Comparator
                    .comparingInt(CrossToolRunner::processAncestryDepth)
                    .reversed());
            for (ProcessHandle child : descendants) {
                try { child.destroy(); } catch (Throwable ignore) {}
            }
            for (ProcessHandle child : descendants) {
                try {
                    if (child.isAlive()) child.destroyForcibly();
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {
            // ProcessHandle discovery is best-effort on unusual JVMs.
        }
        try { process.destroy(); } catch (Throwable ignore) {}
        try {
            if (!process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            try { process.destroyForcibly(); } catch (Throwable ignore) {}
            Thread.currentThread().interrupt();
        } catch (Throwable ignore) {}
        for (ProcessHandle child : descendants) {
            try { if (child.isAlive()) child.destroyForcibly(); } catch (Throwable ignore) {}
        }
    }

    private static int processAncestryDepth(ProcessHandle process) {
        int depth = 0;
        ProcessHandle current = process;
        // A defensive cap protects against a pathological platform implementation.
        while (depth < 1024) {
            try {
                java.util.Optional<ProcessHandle> parent = current.parent();
                if (!parent.isPresent()) break;
                current = parent.get();
                depth++;
            } catch (Throwable ignored) {
                break;
            }
        }
        return depth;
    }

    /**
     * Run an external command that is part of an ImageJ mutation through the
     * server-owned coordinator. Plain analysis-only subprocesses may continue
     * to use {@link #runCommand(String[], String, long)}.
     */
    public static ToolResult runCommandMutation(MutationCoordinator coordinator,
                                                String ownerSession,
                                                final String[] command,
                                                final String workingDir,
                                                long timeoutMs) {
        if (coordinator == null) return ToolResult.error("Mutation coordinator is required", 0L);
        final long effectiveTimeout = timeoutMs <= 0L ? DEFAULT_TIMEOUT_MS : timeoutMs;
        final long started = System.currentTimeMillis();
        final String commandText = command == null ? "" : String.join(" ", command);
        MutationCoordinator.Handle<ToolResult> handle;
        try {
            handle = coordinator.submit(MutationCoordinator.Request.<ToolResult>builder()
                    .ownerSession(ownerSession)
                    .sourceKind("cross-tool")
                    .code(commandText)
                    .timeoutMs(effectiveTimeout)
                    .operation(new MutationCoordinator.Operation<ToolResult>() {
                        @Override public ToolResult run() {
                            return runCommand(command, workingDir, effectiveTimeout);
                        }
                    })
                    .build());
        } catch (IllegalArgumentException | RejectedExecutionException e) {
            return ToolResult.error("Mutation admission rejected: " + e.getMessage(),
                    System.currentTimeMillis() - started);
        }
        try {
            MutationCoordinator.Completion<ToolResult> completion = handle.awaitCompletion();
            if (completion.state() == MutationCoordinator.State.SUCCEEDED
                    && completion.result() != null) {
                return completion.result();
            }
            if (completion.state() == MutationCoordinator.State.TIMED_OUT) {
                return new ToolResult(false, "",
                        "Process timed out after " + effectiveTimeout + "ms", -1,
                        completion.elapsedMs(), false, false, true);
            }
            Throwable error = completion.error();
            return ToolResult.error(error == null
                            ? "Cross-tool mutation cancelled"
                            : "Cross-tool mutation failed: " + error.getMessage(),
                    completion.elapsedMs());
        } catch (InterruptedException e) {
            handle.cancel();
            Thread.currentThread().interrupt();
            return ToolResult.error("Cross-tool mutation interrupted",
                    System.currentTimeMillis() - started);
        }
    }

    private static void joinQuietly(Thread thread, long timeoutMs) {
        if (thread == null) return;
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Thread that reads an InputStream fully and captures the output.
     */
    private static class StreamGobbler extends Thread {
        private final InputStream inputStream;
        private final ByteArrayOutputStream buffer;
        private volatile boolean truncated;

        StreamGobbler(InputStream inputStream) {
            this.inputStream = inputStream;
            this.buffer = new ByteArrayOutputStream();
            setDaemon(true);
        }

        public void run() {
            try {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = inputStream.read(chunk)) >= 0) {
                    synchronized (buffer) {
                        int remaining = MAX_CAPTURE_BYTES - buffer.size();
                        if (remaining > 0) {
                            buffer.write(chunk, 0, Math.min(remaining, read));
                        }
                        if (read > remaining) truncated = true;
                    }
                }
            } catch (IOException e) {
                byte[] message = ("[stream read error: " + e.getMessage() + "]")
                        .getBytes(StandardCharsets.UTF_8);
                synchronized (buffer) {
                    int remaining = MAX_CAPTURE_BYTES - buffer.size();
                    if (!truncated && remaining > 0) {
                        buffer.write(message, 0, Math.min(remaining, message.length));
                        if (message.length > remaining) truncated = true;
                    }
                }
            } finally {
                try { inputStream.close(); } catch (IOException ignored) {}
            }
        }

        String getOutput() {
            final String captured;
            synchronized (buffer) {
                captured = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            return truncated ? captured + TRUNCATION_MARKER : captured;
        }

        boolean wasTruncated() { return truncated; }
    }

    /**
     * Check if running on Windows.
     */
    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().startsWith("windows");
    }
}
