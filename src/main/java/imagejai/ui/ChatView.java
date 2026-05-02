package imagejai.ui;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Overlay;
import ij.gui.Roi;
import imagejai.config.Settings;
import imagejai.engine.AgentLauncher;
import imagejai.local.AssistantReply;
import imagejai.local.LocalAssistant;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main chat panel — message history, input field, status bar.
 * Provides ChatListener interface for Phase 2 conversation loop integration.
 * <p>
 * Phase 7: implements {@link ChatPanelController} so the external agent can
 * drive inline previews, toasts, markdown, ROI highlights, focus, and confirm
 * prompts via TCP.
 */
public class ChatView extends JPanel implements ChatPanelController, ChatSurface {

    /**
     * Callback interface for when the user sends a message.
     * The conversation loop (Phase 2) will implement this.
     */
    // Colors — dark theme palette
    private static final Color BG_MAIN = new Color(30, 30, 35);
    private static final Color BG_MESSAGES = new Color(25, 25, 30);
    private static final Color BG_INPUT = new Color(40, 40, 48);
    private static final Color BORDER_COLOR = new Color(60, 60, 70);
    private static final Color ACCENT = new Color(0, 200, 255);
    private static final Color TEXT_MUTED = new Color(120, 120, 130);
    private static final Color BTN_BG = new Color(0, 140, 200);

    private final Settings settings;
    private final LocalAssistant localAssistant;
    private final List<ChatPanel.ChatListener> listeners = new ArrayList<ChatPanel.ChatListener>();

    private JTextPane messageArea;
    private JScrollPane scrollPane;
    private JTextArea inputArea;
    private JButton sendBtn;
    private JLabel statusLabel;
    private JLabel thinkingLabel;
    private Timer thinkingTimer;
    private int thinkingDots = 0;

    // Phase 7: GUI_ACTION sentinels
    private JLabel toastLabel;
    private Timer toastTimer;
    private JPanel confirmHost;

    public ChatView(Settings settings) {
        this.settings = settings;
        this.localAssistant = new LocalAssistant();
        setLayout(new BorderLayout(0, 4));
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setBackground(BG_MAIN);

        // Phase 7: toast label sits above the header, hidden until used.
        toastLabel = new JLabel(" ");
        toastLabel.setOpaque(true);
        toastLabel.setBorder(new EmptyBorder(4, 8, 4, 8));
        toastLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        toastLabel.setVisible(false);

        add(toastLabel, BorderLayout.NORTH);

        // Message area
        messageArea = new JTextPane();
        messageArea.setContentType("text/html");
        messageArea.setEditable(false);
        messageArea.setBackground(BG_MESSAGES);
        messageArea.setForeground(Color.WHITE);
        messageArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        messageArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        initHtmlContent();

        scrollPane = new JScrollPane(messageArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        // Input area + status
        JPanel southPanel = new JPanel(new BorderLayout(0, 2));
        southPanel.setOpaque(false);

        JPanel inputPanel = createInputPanel();

        // Phase 7: confirm host sits above the input area; holds the most
        // recent confirm prompt's button row. Hidden until used.
        confirmHost = new JPanel();
        confirmHost.setOpaque(false);
        confirmHost.setLayout(new BoxLayout(confirmHost, BoxLayout.Y_AXIS));
        confirmHost.setBorder(new EmptyBorder(0, 0, 4, 0));
        confirmHost.setVisible(false);

        JPanel inputStack = new JPanel(new BorderLayout(0, 2));
        inputStack.setOpaque(false);
        inputStack.add(confirmHost, BorderLayout.NORTH);
        inputStack.add(inputPanel, BorderLayout.CENTER);
        southPanel.add(inputStack, BorderLayout.CENTER);

        // Thinking indicator
        thinkingLabel = new JLabel(" ");
        thinkingLabel.setForeground(ACCENT);
        thinkingLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        thinkingLabel.setBorder(new EmptyBorder(2, 2, 0, 0));
        thinkingLabel.setVisible(false);
        southPanel.add(thinkingLabel, BorderLayout.NORTH);

        // Status bar
        Settings.ModelConfig active = settings.getActiveConfig();
        statusLabel = new JLabel((active != null ? active.provider + " / " + active.model : "No Profile"));
        statusLabel.setForeground(TEXT_MUTED);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        statusLabel.setBorder(new EmptyBorder(2, 2, 0, 0));
        southPanel.add(statusLabel, BorderLayout.SOUTH);

        add(southPanel, BorderLayout.SOUTH);

        // Initial state of input (disabled if no API key)
        refreshInputState();

        // Thinking animation timer
        thinkingTimer = new Timer(400, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                thinkingDots = (thinkingDots % 3) + 1;
                StringBuilder sb = new StringBuilder("Thinking");
                for (int i = 0; i < thinkingDots; i++) {
                    sb.append('.');
                }
                thinkingLabel.setText(sb.toString());
            }
        });

        // Phase 7: toast fade timer (single-shot 3 s).
        toastTimer = new Timer(3000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toastLabel.setVisible(false);
                toastLabel.setText(" ");
                toastTimer.stop();
            }
        });
        toastTimer.setRepeats(false);

        // Welcome message
        appendMessage("assistant", "Hello! I'm your ImageJ AI Assistant. "
                + "I can help you analyze images, run macros, and more.\n\n"
                + "Try: \"Open the sample blobs image\" or \"What image do I have open?\"");
    }

    public void refreshInputState() {
        boolean inputEnabled = isLocalAssistantSelected() || settings.hasApiKey();
        inputArea.setEnabled(inputEnabled);
        sendBtn.setEnabled(inputEnabled);
        if (inputEnabled) {
            if ("API key required for chat...".equals(inputArea.getText())) {
                inputArea.setText("");
            }
        } else {
            inputArea.setText("API key required for chat...");
        }
        Settings.ModelConfig active = settings.getActiveConfig();
        if (active != null) {
            statusLabel.setText(active.provider + " / " + active.model);
        }
    }

    /**
     * Add a listener to be notified when the user sends a message.
     */
    public void addChatListener(ChatPanel.ChatListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a previously added listener.
     */
    public void removeChatListener(ChatPanel.ChatListener listener) {
        listeners.remove(listener);
    }

    /**
     * Append a message to the chat display.
     *
     * @param role    "user" or "assistant"
     * @param content the message text (plain text; newlines become line breaks)
     */
    public void appendMessage(final String role, final String content) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
                    boolean isUser = "user".equals(role);
                    String bubbleBg = isUser ? "#1a3a4a" : "#2a2a32";
                    String borderLeft = isUser ? "#00c8ff" : "#666670";
                    String labelColor = isUser ? "#00c8ff" : "#a0e0a0";
                    String label = isUser ? "You" : "AI";

                    String html = "<div style='"
                            + "background:" + bubbleBg + ";"
                            + "border-left:3px solid " + borderLeft + ";"
                            + "padding:6px 10px;"
                            + "margin:4px 0;"
                            + "'>"
                            + "<div style='color:" + labelColor + ";font-weight:bold;font-size:11px;"
                            + "margin-bottom:3px;'>" + label + "</div>"
                            + "<div style='color:#d8d8d8;font-size:13px;'>"
                            + escapeHtml(content).replace("\n", "<br>")
                            + "</div></div>";
                    doc.insertBeforeEnd(doc.getDefaultRootElement(), html);
                    scrollToBottom();
                } catch (Exception e) {
                    System.err.println("[ImageJAI] Failed to append message: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Append raw HTML content to the chat (for image previews, etc.).
     */
    public void appendHtml(final String html) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
                    doc.insertBeforeEnd(doc.getDefaultRootElement(), html);
                    scrollToBottom();
                } catch (Exception e) {
                    System.err.println("[ImageJAI] Failed to append HTML: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Show or hide the "thinking" indicator.
     */
    public void setThinking(final boolean thinking) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (thinking) {
                    thinkingDots = 0;
                    thinkingLabel.setText("Thinking...");
                    thinkingLabel.setVisible(true);
                    thinkingTimer.start();
                } else {
                    thinkingTimer.stop();
                    thinkingLabel.setVisible(false);
                }
            }
        });
    }

    /**
     * Enable or disable the input controls (during LLM processing).
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                boolean inputEnabled = enabled
                        && (isLocalAssistantSelected() || settings.hasApiKey());
                inputArea.setEnabled(inputEnabled);
                sendBtn.setEnabled(inputEnabled);
                if (inputEnabled) {
                    inputArea.requestFocusInWindow();
                }
            }
        });
    }

    /**
     * Update the status bar text.
     */
    public void setStatus(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText(text);
            }
        });
    }

    /**
     * Clear all messages and reset to welcome state.
     */
    public void clearConversation() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                initHtmlContent();
                appendMessage("assistant", "Conversation cleared. How can I help you?");
            }
        });
    }

    /**
     * Get the current settings reference.
     */
    public Settings getSettings() {
        return settings;
    }

    // -----------------------------------------------------------------------
    // Phase 7: ChatPanelController implementation
    // -----------------------------------------------------------------------

    /**
     * Append an inline image preview, prefixed with [agent], to the chat
     * thread. The image is loaded via a {@code file:} URL the JEditorPane HTML
     * renderer can resolve directly (no extra base64 step). Sized to fit the
     * existing preview width.
     */
    @Override
    public void inlineImage(final Path path) {
        if (path == null) {
            IJ.log("[ImageJAI-GUI] inlineImage: null path ignored");
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!isPanelLive()) {
                    IJ.log("[ImageJAI-GUI] inlineImage skipped (panel not visible): " + path);
                    return;
                }
                java.io.File file = path.toFile();
                if (!file.exists() || !file.isFile()) {
                    IJ.log("[ImageJAI-GUI] inlineImage: file not found: " + path);
                    return;
                }
                try {
                    String url = file.toURI().toURL().toString();
                    int maxWidth = ImagePreview.getMaxPreviewWidth();
                    String html = "<div style='"
                            + "background:#2a2a32;"
                            + "border-left:3px solid #ffaa55;"
                            + "padding:6px 10px;"
                            + "margin:4px 0;'>"
                            + "<div style='color:#ffaa55;font-weight:bold;font-size:11px;"
                            + "margin-bottom:3px;'>[agent] preview</div>"
                            + "<div style='color:#d8d8d8;font-size:11px;margin-bottom:4px;'>"
                            + escapeHtml(file.getName()) + "</div>"
                            + "<img src='" + escapeHtml(url) + "' width='" + maxWidth + "'>"
                            + "</div>";
                    HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
                    doc.insertBeforeEnd(doc.getDefaultRootElement(), html);
                    scrollToBottom();
                } catch (Exception ex) {
                    IJ.log("[ImageJAI-GUI] inlineImage failed: " + ex.getMessage());
                }
            }
        });
    }

    /**
     * Show a transient color-coded toast bar above the chat header. Replaces
     * any in-flight toast and resets the 3 s fade timer.
     */
    @Override
    public void toast(final String message, final String level) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!isPanelLive()) {
                    IJ.log("[ImageJAI-GUI] toast (" + level + "): " + message);
                    return;
                }
                Color bg;
                Color fg = Color.WHITE;
                String lvl = level == null ? "info" : level.toLowerCase();
                if ("warn".equals(lvl) || "warning".equals(lvl)) {
                    bg = new Color(180, 130, 0);   // amber
                } else if ("error".equals(lvl) || "err".equals(lvl)) {
                    bg = new Color(170, 40, 40);   // red
                } else {
                    bg = new Color(50, 90, 130);   // info / neutral
                }
                toastLabel.setBackground(bg);
                toastLabel.setForeground(fg);
                toastLabel.setText(message != null ? message : "");
                toastLabel.setVisible(true);
                if (toastTimer.isRunning()) toastTimer.restart();
                else toastTimer.start();
            }
        });
    }

    /**
     * Render a small Markdown snippet as an [agent]-prefixed bubble. The
     * source is HTML-escaped first; only a tiny subset of Markdown is
     * recognised (bold, italic, inline code, links, blank-line paragraph
     * breaks, single-newline soft breaks).
     */
    @Override
    public void showMarkdown(final String content) {
        if (content == null) return;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!isPanelLive()) {
                    IJ.log("[ImageJAI-GUI] showMarkdown skipped (panel not visible)");
                    return;
                }
                try {
                    String body = renderMarkdown(content);
                    String html = "<div style='"
                            + "background:#2a2a32;"
                            + "border-left:3px solid #aa88ff;"
                            + "padding:6px 10px;"
                            + "margin:4px 0;'>"
                            + "<div style='color:#aa88ff;font-weight:bold;font-size:11px;"
                            + "margin-bottom:3px;'>[agent]</div>"
                            + "<div style='color:#d8d8d8;font-size:13px;'>"
                            + body
                            + "</div></div>";
                    HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
                    doc.insertBeforeEnd(doc.getDefaultRootElement(), html);
                    scrollToBottom();
                } catch (Exception ex) {
                    IJ.log("[ImageJAI-GUI] showMarkdown failed: " + ex.getMessage());
                }
            }
        });
    }

    /**
     * Briefly flash a rectangular ROI on the named image: install the ROI on
     * an overlay, then toggle visibility three times via a Swing Timer. Leaves
     * the ROI installed so the user can keep working with it.
     */
    @Override
    public void highlightRoi(final String imageTitle, final int[] roiBounds) {
        if (imageTitle == null || roiBounds == null || roiBounds.length != 4) {
            IJ.log("[ImageJAI-GUI] highlightRoi: invalid arguments");
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                final ImagePlus imp = WindowManager.getImage(imageTitle);
                if (imp == null) {
                    IJ.log("[ImageJAI-GUI] highlightRoi: no image titled '" + imageTitle + "'");
                    return;
                }
                final Roi roi = new Roi(roiBounds[0], roiBounds[1], roiBounds[2], roiBounds[3]);
                roi.setStrokeColor(new Color(0, 200, 255));
                roi.setStrokeWidth(2);
                final Overlay overlay = imp.getOverlay() != null ? imp.getOverlay() : new Overlay();
                overlay.add(roi);
                imp.setOverlay(overlay);
                imp.draw();

                // Flash three times: 6 toggles at 200 ms.
                final int[] tickCounter = new int[]{0};
                final Timer flashTimer = new Timer(200, null);
                flashTimer.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        tickCounter[0]++;
                        boolean show = (tickCounter[0] % 2) == 0;
                        roi.setStrokeColor(show
                                ? new Color(0, 200, 255)
                                : new Color(0, 200, 255, 0));
                        imp.draw();
                        if (tickCounter[0] >= 6) {
                            flashTimer.stop();
                            roi.setStrokeColor(new Color(0, 200, 255));
                            imp.draw();
                        }
                    }
                });
                flashTimer.setRepeats(true);
                flashTimer.start();
            }
        });
    }

    /** Bring the named image's window to the front. */
    @Override
    public void focusImage(final String imageTitle) {
        if (imageTitle == null) return;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ImagePlus imp = WindowManager.getImage(imageTitle);
                if (imp == null) {
                    IJ.log("[ImageJAI-GUI] focusImage: no image titled '" + imageTitle + "'");
                    return;
                }
                ImageWindow win = imp.getWindow();
                if (win != null) {
                    win.toFront();
                    win.requestFocus();
                }
            }
        });
    }

    /**
     * Append a confirm prompt and a button row in the chat panel. Only the
     * first click fires {@code onChoice}; subsequent clicks are ignored and
     * the row is left visibly disabled with the chosen option as label.
     */
    @Override
    public void confirm(final String prompt, final List<String> options,
                        final Consumer<String> onChoice) {
        if (options == null || options.isEmpty()) {
            IJ.log("[ImageJAI-GUI] confirm: no options provided");
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!isPanelLive()) {
                    IJ.log("[ImageJAI-GUI] confirm skipped (panel not visible): " + prompt);
                    if (onChoice != null) onChoice.accept(null);
                    return;
                }

                // Append the prompt text as an agent-prefixed bubble.
                try {
                    String html = "<div style='"
                            + "background:#2a2a32;"
                            + "border-left:3px solid #ffcc55;"
                            + "padding:6px 10px;"
                            + "margin:4px 0;'>"
                            + "<div style='color:#ffcc55;font-weight:bold;font-size:11px;"
                            + "margin-bottom:3px;'>[agent] confirm</div>"
                            + "<div style='color:#d8d8d8;font-size:13px;'>"
                            + escapeHtml(prompt != null ? prompt : "").replace("\n", "<br>")
                            + "</div></div>";
                    HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
                    doc.insertBeforeEnd(doc.getDefaultRootElement(), html);
                    scrollToBottom();
                } catch (Exception ignore) {}

                // Replace any prior confirm row with the new one.
                confirmHost.removeAll();
                final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                row.setOpaque(false);
                final boolean[] resolved = new boolean[]{false};
                final List<JButton> buttons = new ArrayList<JButton>();
                for (final String opt : options) {
                    final JButton btn = new JButton(opt);
                    btn.setFocusPainted(false);
                    btn.setForeground(Color.WHITE);
                    btn.setBackground(BTN_BG);
                    btn.setBorderPainted(false);
                    btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    btn.addActionListener(new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            if (resolved[0]) return;
                            resolved[0] = true;
                            for (JButton b : buttons) {
                                b.setEnabled(false);
                            }
                            // Visually mark the chosen one.
                            btn.setText(opt + "  \u2713");
                            if (onChoice != null) {
                                try { onChoice.accept(opt); } catch (Throwable t) {
                                    IJ.log("[ImageJAI-GUI] confirm onChoice threw: " + t.getMessage());
                                }
                            }
                        }
                    });
                    buttons.add(btn);
                    row.add(btn);
                }
                confirmHost.add(row);
                confirmHost.setVisible(true);
                confirmHost.revalidate();
                confirmHost.repaint();
            }
        });
    }

    // Whether the panel can usefully render — used to fall back to IJ.log
    // when the plugin window isn't open (TCP-only mode).
    private boolean isPanelLive() {
        return messageArea != null && isDisplayable();
    }

    // -----------------------------------------------------------------------
    // Minimal Markdown -> HTML converter (no external libs).
    // Subset: bold (**text**), italic (*text*), inline code (`text`),
    // links ([text](http://url) — http(s) and file: only), paragraph breaks
    // on blank lines, soft breaks on single newlines.
    // -----------------------------------------------------------------------
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern MD_ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern MD_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern SAFE_URL = Pattern.compile("^(https?://|file://)[\\w./:%~?&=#+,@!*-]+$",
            Pattern.CASE_INSENSITIVE);

    static String renderMarkdown(String src) {
        if (src == null) return "";
        // 1) escape HTML special chars first
        String s = src.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
        // 2) inline code (do this before bold/italic so the contents aren't re-parsed)
        s = MD_CODE.matcher(s).replaceAll("<code style='background:#1d1d22;padding:1px 4px;border-radius:3px;'>$1</code>");
        // 3) links — validate URL via allow-list, fallback to literal text
        Matcher mLink = MD_LINK.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (mLink.find()) {
            String text = mLink.group(1);
            String url = mLink.group(2);
            String replacement;
            if (SAFE_URL.matcher(url).matches()) {
                replacement = "<a href='" + Matcher.quoteReplacement(url) + "' style='color:#7fc7ff;'>"
                        + Matcher.quoteReplacement(text) + "</a>";
            } else {
                replacement = Matcher.quoteReplacement(text);
            }
            mLink.appendReplacement(sb, replacement);
        }
        mLink.appendTail(sb);
        s = sb.toString();
        // 4) bold and italic
        s = MD_BOLD.matcher(s).replaceAll("<b>$1</b>");
        s = MD_ITALIC.matcher(s).replaceAll("<i>$1</i>");
        // 5) paragraphs / line breaks
        s = s.replace("\r\n", "\n");
        s = s.replace("\n\n", "<br><br>");
        s = s.replace("\n", "<br>");
        return s;
    }

    // --- Private helpers ---

    private void initHtmlContent() {
        messageArea.setText("<html><head><style>"
                + "body { font-family: sans-serif; margin: 4px; padding: 0; "
                + "background: #19191e; color: #d0d0d0; }"
                + "</style></head><body></body></html>");
    }

    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setOpaque(false);

        // JTextArea for multi-line input (Shift+Enter = newline, Enter = send)
        inputArea = new JTextArea(2, 20);
        inputArea.setBackground(BG_INPUT);
        inputArea.setForeground(Color.WHITE);
        inputArea.setCaretColor(ACCENT);
        inputArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(new EmptyBorder(6, 8, 6, 8));

        // Enter sends, Shift+Enter inserts newline
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) {
                        // Allow default behavior (insert newline)
                    } else {
                        e.consume();
                        sendMessage();
                    }
                }
            }
        });

        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        inputScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        inputScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Limit height: 2-5 visible rows
        inputScroll.setMinimumSize(new Dimension(100, 44));
        inputScroll.setPreferredSize(new Dimension(100, 52));

        sendBtn = new JButton("Send");
        sendBtn.setBackground(BTN_BG);
        sendBtn.setForeground(Color.WHITE);
        sendBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        sendBtn.setBorderPainted(false);
        sendBtn.setFocusPainted(false);
        sendBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendBtn.setPreferredSize(new Dimension(60, 0));
        sendBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });

        panel.add(inputScroll, BorderLayout.CENTER);
        panel.add(sendBtn, BorderLayout.EAST);
        return panel;
    }

    private void sendMessage() {
        boolean localAssistantSelected = isLocalAssistantSelected();
        if (!localAssistantSelected && !settings.hasApiKey()) return;
        
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;
        inputArea.setText("");

        appendMessage("user", text);

        if (localAssistantSelected) {
            AssistantReply reply = localAssistant.handle(text);
            appendMessage("assistant", reply.text());
            if (reply.macroEcho() != null && !reply.macroEcho().trim().isEmpty()) {
                appendMessage("assistant", "```\n" + reply.macroEcho() + "\n```");
            }
            return;
        }

        // Notify listeners (Phase 2 conversation loop)
        if (!listeners.isEmpty()) {
            for (ChatPanel.ChatListener listener : listeners) {
                listener.onUserMessage(text);
            }
        } else {
            // No listener wired yet — show placeholder
            appendMessage("assistant", "[Phase 2: LLM integration not yet wired]\n"
                    + "You said: " + text);
        }
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                JScrollBar vbar = scrollPane.getVerticalScrollBar();
                vbar.setValue(vbar.getMaximum());
            }
        });
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private boolean isLocalAssistantSelected() {
        return AgentLauncher.LOCAL_ASSISTANT_NAME.equals(settings.getSelectedAgentName());
    }

}
