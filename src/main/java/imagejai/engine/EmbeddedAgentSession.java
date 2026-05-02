package imagejai.engine;

import com.jediterm.terminal.ui.JediTermWidget;
import ij.ImagePlus;
import ij.IJ;
import ij.WindowManager;
import ij.io.FileInfo;
import imagejai.config.Settings;
import imagejai.terminal.AgentRegistry;
import imagejai.terminal.EmbeddedPty;

import javax.swing.JComponent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Live embedded PTY session for an AI CLI agent.
 */
public final class EmbeddedAgentSession implements AgentSession {
    private static final int SCROLLBACK_LINES = 1000;
    private static final DateTimeFormatter LOG_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final AgentLauncher.AgentInfo info;
    private final EmbeddedPty pty;
    private final File workingDir;
    private boolean scrollbackPersisted;

    public EmbeddedAgentSession(AgentLauncher.AgentInfo info, AgentLaunchSpec spec) throws IOException {
        this.info = info;
        this.pty = new EmbeddedPty(spec);
        this.workingDir = spec.workingDir;
    }

    public JComponent component() {
        return pty.component();
    }

    public JediTermWidget terminalWidget() {
        return pty.widget();
    }

    @Override
    public AgentLauncher.AgentInfo info() {
        return info;
    }

    @Override
    public void writeInput(String s) {
        try {
            pty.write((s == null ? "" : s) + "\r");
        } catch (IOException e) {
            IJ.log("[ImageJAI-Term] Failed to write input: " + e.getMessage());
        }
    }

    public void writeRaw(String s) {
        try {
            pty.write(s == null ? "" : s);
        } catch (IOException e) {
            IJ.log("[ImageJAI-Term] Failed to write raw input: " + e.getMessage());
        }
    }

    @Override
    public InputStream output() {
        return pty.process().getInputStream();
    }

    @Override
    public boolean isAlive() {
        return pty.process().isRunning();
    }

    @Override
    public int exitValue() {
        try {
            return pty.process().exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            return -1;
        }
    }

    @Override
    public void interrupt() {
        try {
            pty.interrupt();
        } catch (IOException e) {
            IJ.log("[ImageJAI-Term] Failed to interrupt session: " + e.getMessage());
        }
    }

    @Override
    public void destroy() {
        persistScrollbackIfEnabled();

        try {
            pty.write(new String(new byte[] { 0x04 }, StandardCharsets.UTF_8));
        } catch (IOException e) {
            IJ.log("[ImageJAI-Term] Failed to send EOF: " + e.getMessage());
        }

        try {
            if (!pty.process().waitFor(2, TimeUnit.SECONDS)) {
                pty.process().destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pty.process().destroyForcibly();
        } finally {
            pty.closeWidget();
        }
    }

    public synchronized void persistScrollbackIfEnabled() {
        if (scrollbackPersisted) {
            return;
        }
        scrollbackPersisted = true;

        Settings settings = Settings.load();
        if (!settings.persistScrollback) {
            return;
        }

        String text = AgentRegistry.readScrollback(terminalWidget(), SCROLLBACK_LINES);
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        Path dir = resolveLogDir();
        if (dir == null) {
            IJ.log("[ImageJAI-Term] Could not resolve AI_Exports/.session/log for scrollback");
            return;
        }

        String agent = AgentRegistry.agentId(info).replaceAll("[^A-Za-z0-9_.-]+", "_");
        String timestamp = LocalDateTime.now().format(LOG_TS);
        Path file = dir.resolve(agent + "_" + timestamp + ".log");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            IJ.log("[ImageJAI-Term] Persisted terminal scrollback: " + file);
        } catch (IOException e) {
            IJ.log("[ImageJAI-Term] Failed to persist terminal scrollback: " + e.getMessage());
        }
    }

    private Path resolveLogDir() {
        Path root = imageDirectory(WindowManager.getCurrentImage());
        if (root == null) {
            int[] ids = WindowManager.getIDList();
            if (ids != null) {
                for (int id : ids) {
                    root = imageDirectory(WindowManager.getImage(id));
                    if (root != null) {
                        break;
                    }
                }
            }
        }
        if (root == null && workingDir != null) {
            root = workingDir.toPath();
        }
        return root == null ? null : root.resolve("AI_Exports").resolve(".session").resolve("log");
    }

    private static Path imageDirectory(ImagePlus imp) {
        if (imp == null) {
            return null;
        }
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi == null || fi.directory == null || fi.directory.trim().isEmpty()) {
            return null;
        }
        return Paths.get(fi.directory);
    }
}
