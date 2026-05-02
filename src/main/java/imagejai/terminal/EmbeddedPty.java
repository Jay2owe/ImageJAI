package imagejai.terminal;

import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
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
import ij.Prefs;
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
    private static final String PREF_FONT_SIZE = "ai.assistant.terminal.fontsize";
    private static final int MIN_FONT_SIZE = 8;
    private static final int MAX_FONT_SIZE = 28;
    private static final int DEFAULT_FONT_SIZE = 14;

    private final PtyProcess process;
    private final ImageJAITtyConnector connector;
    private final JediTermWidget widget;
    private final ImageJAITermSettingsProvider settingsProvider;

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
        settingsProvider = new ImageJAITermSettingsProvider();
        widget = new JediTermWidget(INITIAL_COLUMNS, INITIAL_ROWS, settingsProvider);
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
                } else if (e.getKeyCode() == KeyEvent.VK_EQUALS
                        || e.getKeyCode() == KeyEvent.VK_PLUS
                        || e.getKeyCode() == KeyEvent.VK_ADD) {
                    changeFontSize(panel, 1);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_MINUS
                        || e.getKeyCode() == KeyEvent.VK_SUBTRACT) {
                    changeFontSize(panel, -1);
                    e.consume();
                }
            }
        });
    }

    private void changeFontSize(TerminalPanel panel, int delta) {
        int next = clamp(Math.round(settingsProvider.getTerminalFontSize()) + delta);
        if (next == Math.round(settingsProvider.getTerminalFontSize())) {
            return;
        }
        settingsProvider.setTerminalFontSize(next);
        Prefs.set(PREF_FONT_SIZE, next);
        reinitFont(panel);
        IJ.log("[ImageJAI-Term] Terminal font size set to " + next + " pt");
    }

    private static int clamp(int value) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, value));
    }

    private static void reinitFont(TerminalPanel panel) {
        try {
            java.lang.reflect.Method method =
                    TerminalPanel.class.getDeclaredMethod("reinitFontAndResize");
            method.setAccessible(true);
            method.invoke(panel);
        } catch (ReflectiveOperationException e) {
            IJ.log("[ImageJAI-Term] Could not apply terminal font size live: "
                    + e.getMessage());
            panel.revalidate();
            panel.repaint();
        }
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
        private static final int BG_MAIN = 0x1e1e23;
        private static final int BG_MESSAGES = 0x19191e;
        private static final int BG_INPUT = 0x282830;
        private static final int BORDER = 0x3c3c46;
        private static final int ACCENT = 0x00c8ff;
        private static final int TEXT = 0xe6e6e6;
        private static final int TEXT_MUTED = 0x787882;
        private static final ColorPalette CHAT_PALETTE = new ChatAlignedColorPalette();
        private float fontSize = clamp((int) Math.round(Prefs.get(PREF_FONT_SIZE, DEFAULT_FONT_SIZE)));

        @Override
        public java.awt.Font getTerminalFont() {
            java.awt.Font font = new java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN,
                    Math.round(fontSize));
            if ("JetBrains Mono".equals(font.getFamily())) {
                return font;
            }
            return super.getTerminalFont();
        }

        @Override
        public float getTerminalFontSize() {
            return fontSize;
        }

        void setTerminalFontSize(float fontSize) {
            this.fontSize = clamp(Math.round(fontSize));
        }

        @Override
        public ColorPalette getTerminalColorPalette() {
            return CHAT_PALETTE;
        }

        @Override
        public TerminalColor getDefaultForeground() {
            return rgb(TEXT);
        }

        @Override
        public TerminalColor getDefaultBackground() {
            return rgb(BG_MAIN);
        }

        @Override
        public TextStyle getDefaultStyle() {
            return new TextStyle(getDefaultForeground(), getDefaultBackground());
        }

        @Override
        public TextStyle getSelectionColor() {
            return new TextStyle(rgb(BG_MAIN), rgb(ACCENT));
        }

        @Override
        public TextStyle getFoundPatternColor() {
            return new TextStyle(rgb(TEXT), rgb(BORDER));
        }

        @Override
        public TextStyle getHyperlinkColor() {
            return new TextStyle(rgb(ACCENT), rgb(BG_MAIN));
        }

        @Override
        public boolean useAntialiasing() {
            return true;
        }

        @Override
        public boolean scrollToBottomOnTyping() {
            return true;
        }

        private static TerminalColor rgb(int hex) {
            return TerminalColor.rgb((hex >> 16) & 0xff, (hex >> 8) & 0xff, hex & 0xff);
        }

        private static final class ChatAlignedColorPalette extends ColorPalette {
            private static final com.jediterm.core.Color[] COLORS = colors(
                    BG_MAIN,      // black -> ChatPanel BG_MAIN
                    0xaa2828,     // red
                    0x7fc97f,     // green
                    0xb48200,     // yellow / amber
                    0x3266a8,     // blue
                    0x9a70c9,     // magenta
                    ACCENT,       // cyan / accent
                    TEXT,         // white / foreground
                    BG_INPUT,     // bright black -> input surface
                    0xd14f4f,
                    0xa8d8a8,
                    0xd6a933,
                    0x5694d8,
                    0xb58be0,
                    ACCENT,
                    0xffffff);

            @Override
            protected com.jediterm.core.Color getForegroundByColorIndex(int index) {
                return colorAt(index);
            }

            @Override
            protected com.jediterm.core.Color getBackgroundByColorIndex(int index) {
                if (index == 0) {
                    return new com.jediterm.core.Color(BG_MAIN);
                }
                if (index == 8) {
                    return new com.jediterm.core.Color(BG_MESSAGES);
                }
                return colorAt(index);
            }

            private static com.jediterm.core.Color colorAt(int index) {
                if (index < 0) {
                    return new com.jediterm.core.Color(TEXT_MUTED);
                }
                return COLORS[index % COLORS.length];
            }

            private static com.jediterm.core.Color[] colors(int... values) {
                com.jediterm.core.Color[] out = new com.jediterm.core.Color[values.length];
                for (int i = 0; i < values.length; i++) {
                    out[i] = new com.jediterm.core.Color(values[i]);
                }
                return out;
            }
        }
    }
}
