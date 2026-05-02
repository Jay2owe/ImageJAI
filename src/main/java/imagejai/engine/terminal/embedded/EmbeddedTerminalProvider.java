package imagejai.engine.terminal.embedded;

import imagejai.engine.AgentLaunchSpec;
import imagejai.engine.terminal.TerminalProvider;

import javax.swing.JComponent;
import java.io.IOException;
import java.io.InputStream;

/**
 * Java 11+ embedded terminal backend. This class is loaded only by reflection.
 */
public final class EmbeddedTerminalProvider implements TerminalProvider {
    private final AgentLaunchSpec spec;
    private EmbeddedPty pty;

    public EmbeddedTerminalProvider(AgentLaunchSpec spec) {
        this.spec = spec;
    }

    @Override
    public void start() throws IOException {
        pty = new EmbeddedPty(spec);
    }

    @Override
    public boolean isEmbedded() {
        return true;
    }

    @Override
    public String notice() {
        return "";
    }

    @Override
    public JComponent component() {
        return pty == null ? null : pty.component();
    }

    @Override
    public void write(String text) throws IOException {
        if (pty != null) {
            pty.write(text);
        }
    }

    @Override
    public void interrupt() throws IOException {
        if (pty != null) {
            pty.interrupt();
        }
    }

    @Override
    public InputStream output() {
        return pty == null ? null : pty.process().getInputStream();
    }

    @Override
    public boolean isAlive() {
        return pty != null && pty.process().isRunning();
    }

    @Override
    public int exitValue() {
        if (pty == null) {
            return -1;
        }
        try {
            return pty.process().exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            return -1;
        }
    }

    @Override
    public String readScrollback(int lineLimit) {
        return pty == null ? "" : pty.readScrollback(lineLimit);
    }

    @Override
    public void resize(int columns, int rows) {
        if (pty != null) {
            pty.resize(columns, rows);
        }
    }

    @Override
    public void dispose() {
        if (pty != null) {
            if (pty.process().isRunning()) {
                pty.process().destroyForcibly();
            }
            pty.closeWidget();
        }
    }
}
