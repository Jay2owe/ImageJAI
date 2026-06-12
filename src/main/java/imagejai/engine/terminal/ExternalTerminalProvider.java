package imagejai.engine.terminal;

import ij.IJ;
import imagejai.engine.AgentLaunchSpec;

import javax.swing.JComponent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Detached OS terminal backend. The plugin only owns the initial spawn.
 */
public final class ExternalTerminalProvider implements TerminalProvider {
    private final AgentLaunchSpec spec;
    private final String notice;
    private boolean spawned;

    public ExternalTerminalProvider(AgentLaunchSpec spec) {
        this(spec, "");
    }

    public ExternalTerminalProvider(AgentLaunchSpec spec, String notice) {
        this.spec = spec;
        this.notice = notice == null ? "" : notice;
    }

    @Override
    public void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(spec.agentCommand);
        pb.directory(spec.workingDir);
        pb.environment().putAll(spec.env);
        pb.start();
        spawned = true;
        IJ.log("[AgentLauncher] Launched external terminal: " + spec.info.name
                + " (" + spec.info.command + ")");
    }

    @Override
    public boolean isEmbedded() {
        return false;
    }

    @Override
    public String notice() {
        return notice;
    }

    @Override
    public JComponent component() {
        return null;
    }

    @Override
    public void write(String text) {
        IJ.log("[AgentLauncher] write ignored - external terminal is detached");
    }

    @Override
    public void interrupt() {
        IJ.log("[AgentLauncher] interrupt unsupported on detached external terminal");
    }

    @Override
    public InputStream output() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public boolean isAlive() {
        return false;
    }

    @Override
    public int exitValue() {
        return spawned ? 0 : -1;
    }

    @Override
    public String readScrollback(int lineLimit) {
        return "";
    }

    @Override
    public void resize(int columns, int rows) {
        // Detached terminal size is controlled by the OS terminal emulator.
    }

    @Override
    public void dispose() {
        IJ.log("[AgentLauncher] destroy unsupported on detached external terminal");
    }
}
