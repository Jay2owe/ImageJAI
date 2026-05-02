package imagejai.terminal;

import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.SelectionUtil;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter;
import com.jediterm.terminal.model.hyperlinks.LinkInfo;
import com.jediterm.terminal.model.hyperlinks.LinkResult;
import com.jediterm.terminal.model.hyperlinks.LinkResultItem;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import ij.IJ;
import imagejai.engine.AgentLaunchSpec;

import javax.swing.JComponent;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        installUrlHyperlinks(widget);
        installKeyBindings(widget);
        widget.start();

        IJ.log("[ImageJAI-Term] Started embedded PTY: " + String.join(" ", spec.agentCommand));
    }

    public JComponent component() {
        return widget;
    }

    public JediTermWidget widget() {
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

    private void installKeyBindings(JediTermWidget terminalWidget) {
        TerminalPanel panel = terminalWidget.getTerminalPanel();
        panel.addCustomKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!e.isControlDown()) {
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_C) {
                    String selected = selectedText(panel);
                    if (selected != null && !selected.isEmpty()) {
                        Toolkit.getDefaultToolkit().getSystemClipboard()
                                .setContents(new StringSelection(selected), null);
                        clearSelection(panel);
                    } else {
                        try {
                            connector.write(new byte[] { 0x03 });
                        } catch (IOException ex) {
                            IJ.log("[ImageJAI-Term] Ctrl+C write failed: " + ex.getMessage());
                        }
                    }
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_V) {
                    String text = null;
                    try {
                        Object data = Toolkit.getDefaultToolkit().getSystemClipboard()
                                .getData(DataFlavor.stringFlavor);
                        text = data instanceof String ? (String) data : null;
                    } catch (Exception ex) {
                        IJ.log("[ImageJAI-Term] Clipboard paste failed: " + ex.getMessage());
                    }
                    if (text != null && !text.isEmpty()) {
                        try {
                            connector.write(text.getBytes(StandardCharsets.UTF_8));
                        } catch (IOException ex) {
                            IJ.log("[ImageJAI-Term] Ctrl+V write failed: " + ex.getMessage());
                        }
                    }
                    e.consume();
                }
            }
        });
    }

    private static String selectedText(TerminalPanel panel) {
        TerminalSelection selection = panel.getSelection();
        if (selection == null) {
            return null;
        }
        return SelectionUtil.getSelectionText(selection, panel.getTerminalTextBuffer());
    }

    private static void clearSelection(TerminalPanel panel) {
        try {
            Field selection = TerminalPanel.class.getDeclaredField("mySelection");
            selection.setAccessible(true);
            selection.set(panel, null);
            Field start = TerminalPanel.class.getDeclaredField("mySelectionStartPoint");
            start.setAccessible(true);
            start.set(panel, null);
            panel.repaint();
        } catch (ReflectiveOperationException e) {
            IJ.log("[ImageJAI-Term] Could not clear terminal selection: " + e.getMessage());
        }
    }

    private static void installUrlHyperlinks(JediTermWidget terminalWidget) {
        terminalWidget.addHyperlinkFilter(new HyperlinkFilter() {
            private final Pattern pattern = Pattern.compile("(https?://\\S+|file://\\S+)");

            @Override
            public LinkResult apply(String line) {
                Matcher matcher = pattern.matcher(line);
                List<LinkResultItem> items = new ArrayList<LinkResultItem>();
                while (matcher.find()) {
                    String url = cleanupUrl(matcher.group(1));
                    int end = matcher.start(1) + url.length();
                    items.add(new LinkResultItem(matcher.start(1), end, new LinkInfo(new Runnable() {
                        @Override
                        public void run() {
                            openUrl(url);
                        }
                    })));
                }
                return items.isEmpty() ? null : new LinkResult(items);
            }
        });
    }

    private static void openUrl(String url) {
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                IJ.log("[ImageJAI-Term] Desktop browse is not supported for URL: " + url);
                return;
            }
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            IJ.log("[ImageJAI-Term] Failed to open URL: " + e.getMessage());
        }
    }

    private static String cleanupUrl(String url) {
        String cleaned = url;
        while (cleaned.endsWith(".") || cleaned.endsWith(",")
                || cleaned.endsWith(")") || cleaned.endsWith("]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
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
