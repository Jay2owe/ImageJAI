package imagejai.engine.terminal;

import javax.swing.JComponent;
import java.io.IOException;
import java.io.InputStream;

/**
 * Java 8-safe terminal backend contract. Implementations may be an embedded
 * PTY/JediTerm widget or a detached external terminal window.
 */
public interface TerminalProvider {
    void start() throws IOException;

    boolean isEmbedded();

    String notice();

    JComponent component();

    void write(String text) throws IOException;

    void interrupt() throws IOException;

    InputStream output();

    boolean isAlive();

    int exitValue();

    String readScrollback(int lineLimit);

    void resize(int columns, int rows);

    void dispose();
}
