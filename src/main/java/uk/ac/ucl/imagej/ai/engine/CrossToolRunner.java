package uk.ac.ucl.imagej.ai.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Optional integration for running external Python/R scripts from the
 * ImageJ AI assistant.
 * <p>
 * Uses {@link ProcessBuilder} to launch processes, writes script content
 * to temp files, captures stdout/stderr, and enforces timeouts.
 * <p>
 * Java 8 compatible: uses a timer thread for timeout enforcement since
 * {@code Process.waitFor(long, TimeUnit)} is available in Java 8.
 */
public class CrossToolRunner {

    private static final long DEFAULT_TIMEOUT_MS = 60000;

    /** Result of an external tool invocation. */
    public static class ToolResult {
        public boolean success;
        public String stdout;
        public String stderr;
        public int exitCode;
        public long executionTimeMs;

        private ToolResult(boolean success, String stdout, String stderr,
                           int exitCode, long executionTimeMs) {
            this.success = success;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.executionTimeMs = executionTimeMs;
        }

        static ToolResult ok(String stdout, String stderr, int exitCode, long timeMs) {
            return new ToolResult(exitCode == 0, stdout, stderr, exitCode, timeMs);
        }

        static ToolResult error(String message, long timeMs) {
            return new ToolResult(false, "", message, -1, timeMs);
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
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
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

            boolean finished = waitForProcess(process, timeoutMs);
            long elapsed = System.currentTimeMillis() - startTime;

            if (!finished) {
                process.destroy();
                stdoutGobbler.join(1000);
                stderrGobbler.join(1000);
                return ToolResult.error("Process timed out after " + timeoutMs + "ms", elapsed);
            }

            stdoutGobbler.join(5000);
            stderrGobbler.join(5000);

            int exitCode = process.exitValue();
            return ToolResult.ok(stdoutGobbler.getOutput(), stderrGobbler.getOutput(),
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
            FileWriter writer = null;
            try {
                writer = new FileWriter(tempFile);
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

    /**
     * Wait for a process to complete with a timeout.
     * Uses Thread.sleep polling since Process.waitFor(long, TimeUnit) is
     * available in Java 8 but we keep it simple and portable.
     *
     * @return true if process exited, false if timed out
     */
    private static boolean waitForProcess(final Process process, long timeoutMs) {
        final boolean[] done = new boolean[]{ false };
        Thread waiter = new Thread(new Runnable() {
            public void run() {
                try {
                    process.waitFor();
                    done[0] = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        waiter.setDaemon(true);
        waiter.start();

        try {
            waiter.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return done[0];
    }

    /**
     * Thread that reads an InputStream fully and captures the output.
     */
    private static class StreamGobbler extends Thread {
        private final InputStream inputStream;
        private final StringBuilder buffer;

        StreamGobbler(InputStream inputStream) {
            this.inputStream = inputStream;
            this.buffer = new StringBuilder();
            setDaemon(true);
        }

        public void run() {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (buffer.length() > 0) {
                        buffer.append("\n");
                    }
                    buffer.append(line);
                }
            } catch (IOException e) {
                buffer.append("[stream read error: ").append(e.getMessage()).append("]");
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }
        }

        String getOutput() {
            return buffer.toString();
        }
    }

    /**
     * Check if running on Windows.
     */
    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().startsWith("windows");
    }
}
