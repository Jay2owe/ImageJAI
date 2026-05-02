package imagejai.engine;

import ij.IJ;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Agent session backed by a detached external terminal window
 * (today's {@code cmd.exe /c start} / {@code osascript} / Linux
 * terminal emulator path).
 *
 * <p>Because the spawned terminal is detached, this plugin never owns
 * the child process directly. All lifecycle methods are best-effort
 * and log a warning; callers must not rely on them beyond "did the
 * initial spawn succeed?".
 */
public final class ExternalAgentSession implements AgentSession {
    private final AgentLauncher.AgentInfo info;
    private final boolean spawned;
    private final String notice;

    public ExternalAgentSession(AgentLauncher.AgentInfo info, boolean spawned) {
        this(info, spawned, "");
    }

    public ExternalAgentSession(AgentLauncher.AgentInfo info, boolean spawned, String notice) {
        this.info = info;
        this.spawned = spawned;
        this.notice = notice == null ? "" : notice;
    }

    @Override public AgentLauncher.AgentInfo info() { return info; }

    public String notice() {
        return notice;
    }

    public boolean isFallbackLaunch() {
        return !notice.isEmpty();
    }

    @Override public void writeInput(String s) {
        IJ.log("[AgentLauncher] writeInput ignored — external terminal is detached");
    }

    @Override public InputStream output() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override public boolean isAlive() {
        // Detached: once the spawn returned, we have no way to know.
        // Report false to avoid blocking shutdown hooks / flip-back logic.
        return false;
    }

    @Override public int exitValue() {
        return spawned ? 0 : -1;
    }

    @Override public void interrupt() {
        IJ.log("[AgentLauncher] interrupt unsupported on detached external terminal");
    }

    @Override public void destroy() {
        IJ.log("[AgentLauncher] destroy unsupported on detached external terminal");
    }
}
