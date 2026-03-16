package uk.ac.ucl.imagej.ai.ui;

import uk.ac.ucl.imagej.ai.config.Settings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Main chat panel — message history, input field, status bar.
 * Provides ChatListener interface for Phase 2 conversation loop integration.
 */
public class ChatPanel extends JPanel {

    /**
     * Callback interface for when the user sends a message.
     * The conversation loop (Phase 2) will implement this.
     */
    public interface ChatListener {
        void onUserMessage(String text);
    }

    // Colors — dark theme palette
    private static final Color BG_MAIN = new Color(30, 30, 35);
    private static final Color BG_MESSAGES = new Color(25, 25, 30);
    private static final Color BG_INPUT = new Color(40, 40, 48);
    private static final Color BORDER_COLOR = new Color(60, 60, 70);
    private static final Color ACCENT = new Color(0, 200, 255);
    private static final Color TEXT_MUTED = new Color(120, 120, 130);
    private static final Color BTN_BG = new Color(0, 140, 200);

    private final Settings settings;
    private final List<ChatListener> listeners = new ArrayList<ChatListener>();

    private JComboBox<Settings.ModelConfig> profileSwitcher;
    private JTextPane messageArea;
    private JScrollPane scrollPane;
    private JTextArea inputArea;
    private JButton sendBtn;
    private JLabel statusLabel;
    private JLabel thinkingLabel;
    private Timer thinkingTimer;
    private int thinkingDots = 0;

    public ChatPanel(Settings settings) {
        this.settings = settings;
        setLayout(new BorderLayout(0, 4));
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setBackground(BG_MAIN);

        // Header
        JPanel header = createHeader();
        add(header, BorderLayout.NORTH);

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
        southPanel.add(inputPanel, BorderLayout.CENTER);

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
        updateInputState();

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

        // Welcome message
        appendMessage("assistant", "Hello! I'm your ImageJ AI Assistant. "
                + "I can help you analyze images, run macros, and more.\n\n"
                + "Try: \"Open the sample blobs image\" or \"What image do I have open?\"");
    }

    private void updateInputState() {
        boolean hasKey = settings.hasApiKey();
        inputArea.setEnabled(hasKey);
        sendBtn.setEnabled(hasKey);
        if (hasKey) {
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
     * Refresh the profile switcher dropdown.
     */
    public void refreshProfileSwitcher() {
        if (profileSwitcher == null) return;
        
        // Remove action listener to prevent loops
        ActionListener[] al = profileSwitcher.getActionListeners();
        for (ActionListener a : al) profileSwitcher.removeActionListener(a);
        
        profileSwitcher.removeAllItems();
        for (Settings.ModelConfig c : settings.configs) {
            profileSwitcher.addItem(c);
        }
        profileSwitcher.setSelectedItem(settings.getActiveConfig());
        
        // Restore action listener
        for (ActionListener a : al) profileSwitcher.addActionListener(a);
        
        updateInputState();
    }

    /**
     * Add a listener to be notified when the user sends a message.
     */
    public void addChatListener(ChatListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a previously added listener.
     */
    public void removeChatListener(ChatListener listener) {
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
                inputArea.setEnabled(enabled && settings.hasApiKey());
                sendBtn.setEnabled(enabled && settings.hasApiKey());
                if (enabled && settings.hasApiKey()) {
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

    // --- Private helpers ---

    private void initHtmlContent() {
        messageArea.setText("<html><head><style>"
                + "body { font-family: sans-serif; margin: 4px; padding: 0; "
                + "background: #19191e; color: #d0d0d0; }"
                + "</style></head><body></body></html>");
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);

        // Title + Profile Switcher
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftPanel.setOpaque(false);
        
        JLabel title = new JLabel("AI Assistant");
        title.setForeground(ACCENT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        leftPanel.add(title);

        profileSwitcher = new JComboBox<Settings.ModelConfig>();
        profileSwitcher.setPreferredSize(new Dimension(150, 22));
        profileSwitcher.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (Settings.ModelConfig c : settings.configs) {
            profileSwitcher.addItem(c);
        }
        profileSwitcher.setSelectedItem(settings.getActiveConfig());
        profileSwitcher.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Settings.ModelConfig selected = (Settings.ModelConfig) profileSwitcher.getSelectedItem();
                if (selected != null) {
                    settings.activeConfigId = selected.id;
                    settings.save();
                    updateInputState();
                    appendMessage("assistant", "Switched to profile: " + selected.name);
                }
            }
        });
        leftPanel.add(profileSwitcher);
        header.add(leftPanel, BorderLayout.WEST);

        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        headerButtons.setOpaque(false);

        // Clear conversation button
        JButton clearBtn = createHeaderButton("\u2718", "Clear conversation"); // X mark
        clearBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int result = JOptionPane.showConfirmDialog(
                        ChatPanel.this,
                        "Clear the conversation history?",
                        "Clear Conversation",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                if (result == JOptionPane.YES_OPTION) {
                    clearConversation();
                }
            }
        });
        headerButtons.add(clearBtn);

        // Settings button
        JButton settingsBtn = createHeaderButton("\u2699", "Settings"); // Gear icon
        settingsBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSettings();
            }
        });
        headerButtons.add(settingsBtn);

        header.add(headerButtons, BorderLayout.EAST);
        header.setBorder(new EmptyBorder(0, 0, 6, 0));
        return header;
    }

    private JButton createHeaderButton(String symbol, String tooltip) {
        JButton btn = new JButton(symbol);
        btn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        btn.setForeground(new Color(150, 150, 160));
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText(tooltip);
        btn.setMargin(new Insets(0, 4, 0, 4));
        return btn;
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
        if (!settings.hasApiKey()) return;
        
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;
        inputArea.setText("");

        appendMessage("user", text);

        // Notify listeners (Phase 2 conversation loop)
        if (!listeners.isEmpty()) {
            for (ChatListener listener : listeners) {
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

    private void openSettings() {
        Frame parent = (Frame) SwingUtilities.getWindowAncestor(this);
        SettingsDialog dialog = new SettingsDialog(parent, settings);
        dialog.setVisible(true);
        if (dialog.wasConfirmed()) {
            settings.save();
            refreshProfileSwitcher();
        }
    }
}
