package imagejai.terminal;

import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import ij.IJ;
import imagejai.engine.AgentLaunchSpec;

import javax.swing.JComponent;
import java.io.File;
import java.io.IOException;

/**
 * Owns the embedded terminal widget and its backing PTY process.
 */
public final class EmbeddedPty {
    private static final int INITIAL_COLUMNS = 120;
    private static final int INITIAL_ROWS = 32;

    private final PtyProcess process;
    private final ImageJAITtyConnector connector;
    private final JediTermWidget widget;

    public EmbeddedPty(AgentLaunchSpec spec) throws IOException {
        configurePtyNativeFolder();

        PtyProcessBuilder builder = new PtyProcessBuilder(spec.agentCommand.toArray(new String[0]))
                .setDirectory(spec.workingDir.getAbsolutePath())
                .setEnvironment(spec.env)
                .setRedirectErrorStream(true)
                .setInitialColumns(INITIAL_COLUMNS)
                .setInitialRows(INITIAL_ROWS);

        process = builder.start();
        connector = new ImageJAITtyConnector(process);
        widget = new JediTermWidget(INITIAL_COLUMNS, INITIAL_ROWS, new ImageJAITermSettingsProvider());
        widget.setTtyConnector(connector);
        widget.start();

        IJ.log("[ImageJAI-Term] Started embedded PTY: " + String.join(" ", spec.agentCommand));
    }

    public JComponent component() {
        return widget;
    }

    public TtyConnector connector() {
        return connector;
    }

    public PtyProcess process() {
        return process;
    }

    public void write(String text) throws IOException {
        connector.write(text);
    }

    public void interrupt() throws IOException {
        connector.write(new byte[] { 0x03 });
    }

    public void closeWidget() {
        widget.close();
    }

    private static void configurePtyNativeFolder() {
        if (System.getProperty("pty4j.tmpdir") != null
                && System.getProperty("pty4j.preferred.native.folder") != null) {
            return;
        }

        File folder = new File(System.getProperty("java.io.tmpdir"), "imagejai-pty4j-native");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            IJ.log("[ImageJAI-Term] Could not create pty4j native temp folder: "
                    + folder.getAbsolutePath());
            return;
        }

        System.setProperty("pty4j.tmpdir", folder.getAbsolutePath());
        System.setProperty("pty4j.preferred.native.folder", folder.getAbsolutePath());
        IJ.log("[ImageJAI-Term] pty4j native extraction folder: " + folder.getAbsolutePath());
    }

    private static final class ImageJAITermSettingsProvider extends DefaultSettingsProvider {
        @Override
        public java.awt.Font getTerminalFont() {
            java.awt.Font font = new java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 14);
            if ("JetBrains Mono".equals(font.getFamily())) {
                return font;
            }
            return super.getTerminalFont();
        }

        @Override
        public float getTerminalFontSize() {
            return 14.0f;
        }

        @Override
        public boolean useAntialiasing() {
            return true;
        }

        @Override
        public boolean scrollToBottomOnTyping() {
            return true;
        }
    }
}
