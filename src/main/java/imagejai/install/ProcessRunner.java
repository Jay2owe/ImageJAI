package imagejai.install;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs installer/status commands through ProcessBuilder.
 * The confirmation and process-start seams keep installer tests side-effect free.
 */
public class ProcessRunner {

    public interface Confirmer {
        boolean confirm(String commandText);
    }

    public interface LogSink {
        void line(String line);
    }

    public interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    public static final class RunResult {
        public final boolean started;
        public final int exitCode;

        public RunResult(boolean started, int exitCode) {
            this.started = started;
            this.exitCode = exitCode;
        }
    }

    public static final class VersionResult {
        public final List<String> command;
        public final boolean installed;
        public final boolean timedOut;
        public final int exitCode;

        public VersionResult(List<String> command, boolean installed, boolean timedOut, int exitCode) {
            this.command = command;
            this.installed = installed;
            this.timedOut = timedOut;
            this.exitCode = exitCode;
        }
    }

    private final ProcessStarter starter;

    public ProcessRunner() {
        this(new ProcessStarter() {
            @Override
            public Process start(List<String> command) throws IOException {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                return pb.start();
            }
        });
    }

    public ProcessRunner(ProcessStarter starter) {
        this.starter = starter;
    }

    public RunResult runConfirmed(List<String> command,
                                  Confirmer confirmer,
                                  LogSink logSink) throws IOException, InterruptedException {
        String commandText = formatCommand(command);
        if (!confirmer.confirm(commandText)) {
            return new RunResult(false, -1);
        }

        Process process = starter.start(new ArrayList<String>(command));
        streamOutput(process, logSink);
        int exit = process.waitFor();
        return new RunResult(true, exit);
    }

    public VersionResult checkVersion(String command, long timeoutMillis) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(command);
        cmd.add("--version");
        try {
            Process process = starter.start(cmd);
            boolean done = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                return new VersionResult(cmd, false, true, -1);
            }
            int exit = process.exitValue();
            return new VersionResult(cmd, exit == 0, false, exit);
        } catch (Exception e) {
            return new VersionResult(cmd, false, false, -1);
        }
    }

    public void runWithLogDialog(final Component parent,
                                 final String title,
                                 final List<String> command) {
        final JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), title, Dialog.ModalityType.MODELESS);
        final JTextArea log = new JTextArea(14, 64);
        log.setEditable(false);
        dialog.add(new JScrollPane(log), BorderLayout.CENTER);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RunResult result = runConfirmed(command, new Confirmer() {
                        @Override
                        public boolean confirm(String commandText) {
                            int choice = JOptionPane.showConfirmDialog(parent,
                                    "Run this command?\n\n" + commandText,
                                    "Confirm installer command",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE);
                            return choice == JOptionPane.YES_OPTION;
                        }
                    }, new LogSink() {
                        @Override
                        public void line(final String line) {
                            SwingUtilities.invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    log.append(line);
                                    log.append("\n");
                                }
                            });
                        }
                    });
                    final String end = result.started
                            ? "Process exited with code " + result.exitCode
                            : "Installer command cancelled before launch.";
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            log.append(end);
                            log.append("\n");
                        }
                    });
                } catch (final Exception e) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            log.append("Error: " + e.getMessage() + "\n");
                        }
                    });
                }
            }
        }, "ImageJAI installer command");
        worker.setDaemon(true);
        dialog.setVisible(true);
        worker.start();
    }

    public static String findOnPath(String command) {
        String base = command == null ? "" : command.trim();
        if (base.isEmpty()) return null;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String path = System.getenv("PATH");
        if (path == null) return null;
        String[] exts = os.contains("win")
                ? new String[] {"", ".exe", ".cmd", ".bat"}
                : new String[] {""};
        for (String dir : path.split(File.pathSeparator)) {
            for (String ext : exts) {
                File file = new File(dir, base + ext);
                if (file.exists() && file.isFile()) {
                    return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    public static List<String> parseCommand(String command) {
        List<String> out = new ArrayList<String>();
        if (command == null) return out;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if ((ch == '"' || ch == '\'') && (!quoted || ch == quote)) {
                if (quoted) {
                    quoted = false;
                    quote = 0;
                } else {
                    quoted = true;
                    quote = ch;
                }
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    public static String formatCommand(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (String part : command) {
            if (sb.length() > 0) sb.append(' ');
            if (part.indexOf(' ') >= 0 || part.indexOf('\t') >= 0) {
                sb.append('"').append(part.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private static void streamOutput(Process process, LogSink logSink) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            logSink.line(line);
        }
    }
}
