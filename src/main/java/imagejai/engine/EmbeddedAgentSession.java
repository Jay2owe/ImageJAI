package imagejai.engine;

import com.jediterm.terminal.ui.JediTermWidget;
import ij.IJ;
import imagejai.terminal.EmbeddedPty;

import javax.swing.JComponent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Live embedded PTY session for an AI CLI agent.
 */
public final class EmbeddedAgentSession implements AgentSession {
    private final AgentLauncher.AgentInfo info;
    private final EmbeddedPty pty;

    public EmbeddedAgentSession(AgentLauncher.AgentInfo info, AgentLaunchSpec spec) throws IOException {
        this.info = info;
        this.pty = new EmbeddedPty(spec);
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
}
