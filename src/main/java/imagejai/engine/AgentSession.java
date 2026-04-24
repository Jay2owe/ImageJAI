package imagejai.engine;

import java.io.InputStream;

/**
 * Handle for a launched AI CLI agent — external detached terminal or
 * embedded PTY (added in a later stage). External detached sessions
 * treat lifecycle methods as best-effort; the process runs in a
 * separate terminal window the plugin does not own.
 */
public interface AgentSession {
    /** The agent descriptor that was launched. */
    AgentLauncher.AgentInfo info();

    /** Write input to the agent. Implementations handle appending CR. */
    void writeInput(String s);

    /**
     * Combined stdout+stderr byte stream. For detached external
     * sessions this is an always-empty stream.
     */
    InputStream output();

    /**
     * Whether the underlying process is still alive. For detached
     * external launches this returns false once the spawn returns.
     */
    boolean isAlive();

    /** Process exit value, or -1 while alive / unknown. */
    int exitValue();

    /** Send an interrupt (Ctrl+C equivalent). Best-effort on detached. */
    void interrupt();

    /** Terminate the session gracefully, then forcibly. Best-effort on detached. */
    void destroy();
}
