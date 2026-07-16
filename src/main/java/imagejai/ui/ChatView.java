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
import imagejai.local.AutocompleteChipRow;
import imagejai.local.ChatHistoryController;
import imagejai.local.LocalAssistant;
import imagejai.local.RankedPhrase;
import imagejai.engine.MutationCoordinator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/**
 * Main chat panel — message history, input field, status bar.
 * Provides ChatListener interface for Phase 2 conversation loop integration.
 * <p>
 * Phase 7: implements {@link ChatPanelController} so the external agent can
 * drive inline previews, toasts, markdown, ROI highlights, focus, and confirm
 * prompts via TCP.
 */
public class ChatView extends JPanel implements ChatPanelController, ChatSurface {

    public static final int MAX_TRANSCRIPT_ENTRIES = 500;
    public static final int MAX_TRANSCRIPT_CHARS = 1_000_000;
    public static final int MAX_TRANSCRIPT_FRAGMENT_CHARS = 128_000;
    public static final int MAX_INPUT_CHARS = 65_536;
    public static final int MAX_CHAT_LISTENERS = 64;
    public static final int MAX_PENDING_TRANSCRIPT_APPENDS = 128;
    public static final int MAX_CONFIRM_OPTIONS = 16;
    public static final int MAX_CONFIRM_OPTION_CHARS = 256;
    public static final int MAX_PENDING_CONFIRMATIONS = 32;
    public static final int MAX_VISIBLE_CONFIRMATIONS = 32;
    private static final int MAX_IMAGE_TITLE_CHARS = 1024;

    private static final ThreadPoolExecutor AUTOCOMPLETE_EXECUTOR =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(1),
                    new ThreadFactory() {
                        public Thread newThread(Runnable task) {
                            Thread thread = new Thread(task, "ImageJAI-IntentSuggestions");
                            thread.setDaemon(true);
                            return thread;
                        }
                    },
                    new ThreadPoolExecutor.DiscardOldestPolicy());

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
    private final CopyOnWriteArrayList<ChatPanel.ChatListener> listeners =
            new CopyOnWriteArrayList<ChatPanel.ChatListener>();
    private final CopyOnWriteArrayList<Runnable> conversationClearListeners =
            new CopyOnWriteArrayList<Runnable>();
    private final ArrayDeque<String> transcriptFragments = new ArrayDeque<String>();
    private int transcriptChars;
    private final AtomicLong droppedTranscriptEntries = new AtomicLong();
    private final AtomicLong rejectedChatListeners = new AtomicLong();
    private final AtomicLong rejectedClearListeners = new AtomicLong();
    private final AtomicLong chatListenerFailures = new AtomicLong();
    private final AtomicInteger pendingTranscriptAppends = new AtomicInteger();
    private final AtomicLong droppedPendingTranscriptAppends = new AtomicLong();
    private final AtomicLong localConversationGeneration = new AtomicLong();
    private final AtomicLong autocompleteGeneration = new AtomicLong();
    private final AtomicBoolean localAssistantBusy = new AtomicBoolean(false);
    private final AtomicLong localConfirmationCounter = new AtomicLong();
    private final Object confirmationLock = new Object();
    private final Map<String, PendingConfirmation> pendingConfirmations =
            new LinkedHashMap<String, PendingConfirmation>();
    private final ThreadPoolExecutor localAssistantExecutor =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(1),
                    new ThreadFactory() {
                        @Override public Thread newThread(Runnable task) {
                            Thread thread = new Thread(task, "ImageJAI-LocalAssistant");
                            thread.setDaemon(true);
                            return thread;
                        }
                    }, new ThreadPoolExecutor.AbortPolicy());
    private volatile Function<String, AssistantReply> localAssistantHandlerForTest;

    private JTextPane messageArea;
    private JScrollPane scrollPane;
    private JTextArea inputArea;
    private JButton sendBtn;
    private AutocompleteChipRow chipRow;
    private List<RankedPhrase> clarificationCandidates = new ArrayList<RankedPhrase>();
    private Timer autocompleteTimer;
    private JLabel statusLabel;
    private JLabel thinkingLabel;
    private Timer thinkingTimer;
    private int thinkingDots = 0;

    // Phase 7: GUI_ACTION sentinels
    private JLabel toastLabel;
    private Timer toastTimer;
    private JPanel confirmHost;

    private final class PendingConfirmation {
        final String id;
        final Consumer<String> onChoice;
        final AtomicBoolean terminal = new AtomicBoolean(false);
        volatile JPanel row;

        PendingConfirmation(String id, Consumer<String> onChoice) {
            this.id = id;
            this.onChoice = onChoice;
        }

        boolean resolve(String choice) {
            if (!terminal.compareAndSet(false, true)) return false;
            removePendingConfirmation(this);
            if (row != null) disableComponentTree(row);
            if (onChoice != null) {
                try {
                    onChoice.accept(choice);
                } catch (Throwable failure) {
                    IJ.log("[ImageJAI-GUI] confirm onChoice threw: "
                            + failure.getMessage());
                }
            }
            return true;
        }

        boolean cancel() {
            if (!terminal.compareAndSet(false, true)) return false;
            removePendingConfirmation(this);
            return true;
        }
    }

    public ChatView(Settings settings) {
        this(settings, new MutationCoordinator());
    }

    public ChatView(Settings settings, MutationCoordinator mutationCoordinator) {
        this.settings = settings;
        this.localAssistant = new LocalAssistant(settings, new ChatHistoryController() {
            public boolean canClear() {
                return !localAssistantBusy.get();
            }

            public void clear() {
                ChatView.this.clearConversation();
            }
        }, mutationCoordinator);
        setLayout(new BorderLayout(0, 4));
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setBackground(BG_MAIN);
        getAccessibleContext().setAccessibleName("AI chat");
        getAccessibleContext().setAccessibleDescription(
                "Read the transcript, enter a message, or activate a suggested response.");

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
        messageArea.setFocusable(true);
        messageArea.getAccessibleContext().setAccessibleName("Chat transcript");
        messageArea.getAccessibleContext().setAccessibleDescription(
                "Read-only conversation transcript. Links can be opened from the keyboard.");
        messageArea.addHyperlinkListener(new HyperlinkListener() {
            @Override
            public void hyperlinkUpdate(HyperlinkEvent e) {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                    handleChatLink(e.getDescription());
                }
            }
        });
        initHtmlContent();

        scrollPane = new JScrollPane(messageArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        // Input area + status
        JPanel southPanel = new JPanel(new BorderLayout(0, 2));
        southPanel.setOpaque(false);

        JPanel inputPanel = createInputPanel();

        // Phase 7: confirm host sits above the input area and holds a bounded
        // set of ID-keyed prompt rows. Hidden until used.
        confirmHost = new JPanel();
        confirmHost.setOpaque(false);
        confirmHost.setLayout(new BoxLayout(confirmHost, BoxLayout.Y_AXIS));
        confirmHost.setBorder(new EmptyBorder(0, 0, 4, 0));
        confirmHost.setVisible(false);

        JPanel inputStack = new JPanel(new BorderLayout(0, 2));
        inputStack.setOpaque(false);
        inputStack.add(confirmHost, BorderLayout.NORTH);
        inputStack.add(inputPanel, BorderLayout.CENTER);
        inputStack.add(chipRow, BorderLayout.SOUTH);
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
        updateAutocompleteChipsNow();
    }

    /**
     * Add a listener to be notified when the user sends a message.
     */
    public synchronized void addChatListener(ChatPanel.ChatListener listener) {
        if (listener == null || listeners.contains(listener)) return;
        if (listeners.size() >= MAX_CHAT_LISTENERS) {
            rejectedChatListeners.incrementAndGet();
            return;
        }
        listeners.add(listener);
    }

    /**
     * Remove a previously added listener.
     */
    public void removeChatListener(ChatPanel.ChatListener listener) {
        listeners.remove(listener);
    }

    public synchronized void addConversationClearListener(Runnable listener) {
        if (listener == null || conversationClearListeners.contains(listener)) return;
        if (conversationClearListeners.size() >= MAX_CHAT_LISTENERS) {
            rejectedClearListeners.incrementAndGet();
            return;
        }
        conversationClearListeners.add(listener);
    }

    public void removeConversationClearListener(Runnable listener) {
        conversationClearListeners.remove(listener);
    }

    /**
     * Append a message to the chat display.
     *
     * @param role    "user" or "assistant"
     * @param content the message text (plain text; newlines become line breaks)
     */
    public void appendMessage(final String role, final String content) {
        final String safeRole = "user".equals(role) ? "user" : "assistant";
        final String safeContent = boundedDisplayText(content);
        enqueueTranscriptAppend(new Runnable() {
            @Override
            public void run() {
                appendMessageNow(safeRole, safeContent);
            }
        });
    }

    @Override
    public void removeNotify() {
        invalidateAllConfirmations();
        Runnable clearConfirmationUi = new Runnable() {
            @Override public void run() {
                if (confirmHost != null) {
                    disableComponentTree(confirmHost);
                    confirmHost.removeAll();
                    confirmHost.setVisible(false);
                    confirmHost.revalidate();
                    confirmHost.repaint();
                }
            }
        };
        // Container.removeNotify may hold AWT's tree lock even when a legacy
        // caller invokes it off the EDT. Waiting for the EDT here would
        // deadlock. Callback state is already invalidated above; queue only
        // the Swing cleanup in that exceptional off-EDT lifecycle path.
        if (SwingUtilities.isEventDispatchThread()) clearConfirmationUi.run();
        else SwingUtilities.invokeLater(clearConfirmationUi);
        super.removeNotify();
    }

    /**
     * Append raw HTML content to the chat (for image previews, etc.).
     */
    public void appendHtml(final String html) {
        final String safeHtml = boundedHtmlFragment(html);
        enqueueTranscriptAppend(new Runnable() {
            @Override
            public void run() {
                appendHtmlNow(safeHtml);
            }
        });
    }

    private void appendMessageNow(String role, String content) {
        try {
            boolean isUser = "user".equals(role);
            String bubbleBg = isUser ? "#1a3a4a" : "#2a2a32";
            String borderLeft = isUser ? "#00c8ff" : "#666670";
            String labelColor = isUser ? "#00c8ff" : "#a0e0a0";
            String label = isUser ? "You" : "AI";
            String html = "<div style='background:" + bubbleBg
                    + ";border-left:3px solid " + borderLeft
                    + ";padding:6px 10px;margin:4px 0;'>"
                    + "<div style='color:" + labelColor
                    + ";font-weight:bold;font-size:11px;margin-bottom:3px;'>"
                    + label + "</div><div style='color:#d8d8d8;font-size:13px;'>"
                    + escapeHtml(boundedDisplayText(content)).replace("\n", "<br>")
                    + "</div></div>";
            appendTranscriptHtmlNow(html);
        } catch (Exception e) {
            System.err.println("[ImageJAI] Failed to append message: " + e.getMessage());
        }
    }

    private void appendHtmlNow(String html) {
        try {
            appendTranscriptHtmlNow(html);
        } catch (Exception e) {
            System.err.println("[ImageJAI] Failed to append HTML: " + e.getMessage());
        }
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
        // Control state must never be dropped when the display queue is full.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                boolean inputEnabled = enabled
                        && (isLocalAssistantSelected() || settings.hasApiKey());
                inputArea.setEnabled(inputEnabled);
                sendBtn.setEnabled(inputEnabled);
                if (inputEnabled) {
                    inputArea.requestFocusInWindow();
                } else {
                    clearAutocompleteChips();
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
        localConversationGeneration.incrementAndGet();
        localAssistant.clearConversation();
        invalidateAllConfirmations();
        for (Runnable listener
                : new ArrayList<Runnable>(conversationClearListeners)) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                chatListenerFailures.incrementAndGet();
                // One stale backend listener must not prevent the local reset.
            }
        }
        Runnable clearUi = new Runnable() {
            @Override
            public void run() {
                resetTranscriptAndHtml();
                clarificationCandidates.clear();
                if (confirmHost != null) {
                    disableComponentTree(confirmHost);
                    confirmHost.removeAll();
                    confirmHost.setVisible(false);
                    confirmHost.revalidate();
                    confirmHost.repaint();
                }
                if (inputArea != null) inputArea.setText("");
                clearAutocompleteChips();
                setThinking(false);
                setEnabled(true);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) clearUi.run();
        else SwingUtilities.invokeLater(clearUi);
    }

    public void clearRenderedHistory() {
        enqueueTranscriptAppend(new Runnable() {
            @Override
            public void run() {
                resetTranscriptAndHtml();
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
        enqueueTranscriptAppend(new Runnable() {
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
                    appendTranscriptHtmlNow(html);
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
        final String safeMessage = boundedDisplayText(message);
        final String safeLevel = boundedShortText(level, 32);
        enqueueTranscriptAppend(new Runnable() {
            @Override
            public void run() {
                if (!isPanelLive()) {
                    IJ.log("[ImageJAI-GUI] toast (" + safeLevel + "): " + safeMessage);
                    return;
                }
                Color bg;
                Color fg = Color.WHITE;
                String lvl = safeLevel.length() == 0 ? "info" : safeLevel.toLowerCase();
                if ("warn".equals(lvl) || "warning".equals(lvl)) {
                    bg = new Color(180, 130, 0);   // amber
                } else if ("error".equals(lvl) || "err".equals(lvl)) {
                    bg = new Color(170, 40, 40);   // red
                } else {
                    bg = new Color(50, 90, 130);   // info / neutral
                }
                toastLabel.setBackground(bg);
                toastLabel.setForeground(fg);
                toastLabel.setText(safeMessage);
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
        final String safeContent = boundedDisplayText(content);
        enqueueTranscriptAppend(new Runnable() {
            @Override
            public void run() {
                if (!isPanelLive()) {
                    IJ.log("[ImageJAI-GUI] showMarkdown skipped (panel not visible)");
                    return;
                }
                try {
                    String body = renderMarkdown(safeContent);
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
                    appendTranscriptHtmlNow(html);
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
        final String safeTitle = boundedShortText(imageTitle, MAX_IMAGE_TITLE_CHARS);
        final int[] safeBounds = roiBounds.clone();
        enqueueTranscriptAppend(new Runnable() {
            @Override
            public void run() {
                final ImagePlus imp = WindowManager.getImage(safeTitle);
                if (imp == null) {
                    IJ.log("[ImageJAI-GUI] highlightRoi: no image titled '" + safeTitle + "'");
                    return;
                }
                final Roi roi = new Roi(safeBounds[0], safeBounds[1],
                        safeBounds[2], safeBounds[3]);
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
        final String safeTitle = boundedShortText(imageTitle, MAX_IMAGE_TITLE_CHARS);
        enqueueTranscriptAppend(new Runnable() {
            @Override
            public void run() {
                ImagePlus imp = WindowManager.getImage(safeTitle);
                if (imp == null) {
                    IJ.log("[ImageJAI-GUI] focusImage: no image titled '" + safeTitle + "'");
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
     * Append an ID-keyed confirm prompt. Admission is recorded before Swing
     * rendering is queued, so a concurrent timeout can invalidate the prompt
     * even when the event-dispatch thread is blocked.
     */
    @Override
    public boolean confirm(final String confirmationId, final String prompt,
                           final List<String> options,
                           final Consumer<String> onChoice) {
        if (confirmationId == null || confirmationId.trim().isEmpty()
                || options == null || options.isEmpty()) {
            IJ.log("[ImageJAI-GUI] confirm: id and options are required");
            return false;
        }
        final String safePrompt = boundedDisplayText(prompt);
        final List<String> safeOptions = boundedConfirmOptions(options);
        if (safeOptions.isEmpty()) return false;

        final PendingConfirmation pending =
                new PendingConfirmation(confirmationId, onChoice);
        synchronized (confirmationLock) {
            if (pendingConfirmations.containsKey(confirmationId)
                    || pendingConfirmations.size() >= MAX_PENDING_CONFIRMATIONS) {
                return false;
            }
            pendingConfirmations.put(confirmationId, pending);
        }

        boolean admitted = enqueueTranscriptAppend(new Runnable() {
            @Override
            public void run() {
                if (pending.terminal.get()) return;
                if (!isPanelLive()) {
                    IJ.log("[ImageJAI-GUI] confirm skipped (panel not visible): "
                            + safePrompt);
                    pending.cancel();
                    return;
                }

                try {
                    String html = "<div style='"
                            + "background:#2a2a32;"
                            + "border-left:3px solid #ffcc55;"
                            + "padding:6px 10px;"
                            + "margin:4px 0;'>"
                            + "<div style='color:#ffcc55;font-weight:bold;font-size:11px;"
                            + "margin-bottom:3px;'>[agent] confirm</div>"
                            + "<div style='color:#d8d8d8;font-size:13px;'>"
                            + escapeHtml(safePrompt).replace("\n", "<br>")
                            + "</div></div>";
                    appendTranscriptHtmlNow(html);
                } catch (Exception ignore) { }

                if (!pending.terminal.get()) {
                    populateConfirmControls(pending, safeOptions);
                }
            }
        });
        if (!admitted) pending.cancel();
        return admitted;
    }

    /** Compatibility overload for local callers that do not cancel by ID. */
    public void confirm(final String prompt, final List<String> options,
                        final Consumer<String> onChoice) {
        confirm("local-confirm-" + localConfirmationCounter.incrementAndGet(),
                prompt, options, onChoice);
    }

    @Override
    public boolean cancelConfirmation(String confirmationId) {
        if (confirmationId == null) return false;
        final PendingConfirmation pending;
        synchronized (confirmationLock) {
            pending = pendingConfirmations.get(confirmationId);
        }
        if (pending == null || !pending.cancel()) return false;

        runOnEdtAndWait(new Runnable() {
            @Override public void run() {
                removeConfirmationRow(pending);
            }
        });
        return true;
    }

    private void populateConfirmControls(final PendingConfirmation pending,
                                         final List<String> options) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("confirmation controls require the EDT");
        }
        if (pending.terminal.get()) return;

        while (confirmHost.getComponentCount() >= MAX_VISIBLE_CONFIRMATIONS) {
            Component oldest = oldestResolvedConfirmationRow();
            if (oldest == null) oldest = confirmHost.getComponent(0);
            if (oldest instanceof JComponent) {
                Object value = ((JComponent) oldest).getClientProperty(
                        "imagejai.confirmation");
                if (value instanceof ChatView.PendingConfirmation) {
                    ((PendingConfirmation) value).cancel();
                }
            }
            disableComponentTree(oldest);
            confirmHost.remove(oldest);
        }

        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.putClientProperty("imagejai.confirmation", pending);
        pending.row = row;
        final List<JButton> buttons = new ArrayList<JButton>();
        for (final String opt : options) {
            final JButton btn = new JButton(opt);
            btn.getAccessibleContext().setAccessibleName("Choose " + opt);
            btn.getAccessibleContext().setAccessibleDescription(
                    "Choose this response for the agent confirmation prompt.");
            btn.setFocusPainted(false);
            btn.setForeground(Color.WHITE);
            btn.setBackground(BTN_BG);
            btn.setBorderPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    if (pending.resolve(opt)) {
                        for (JButton button : buttons) button.setEnabled(false);
                        btn.setText(opt + "  \u2713");
                    }
                }
            });
            buttons.add(btn);
            row.add(btn);
        }
        if (pending.terminal.get()) {
            disableComponentTree(row);
            return;
        }
        confirmHost.add(row);
        confirmHost.setVisible(true);
        confirmHost.revalidate();
        confirmHost.repaint();
    }

    private Component oldestResolvedConfirmationRow() {
        for (Component component : confirmHost.getComponents()) {
            if (!(component instanceof JComponent)) continue;
            Object value = ((JComponent) component).getClientProperty(
                    "imagejai.confirmation");
            if (value instanceof ChatView.PendingConfirmation
                    && ((PendingConfirmation) value).terminal.get()) {
                return component;
            }
        }
        return null;
    }

    private void removePendingConfirmation(PendingConfirmation pending) {
        synchronized (confirmationLock) {
            if (pendingConfirmations.get(pending.id) == pending) {
                pendingConfirmations.remove(pending.id);
            }
        }
    }

    private void removeConfirmationRow(PendingConfirmation pending) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("confirmation cleanup requires the EDT");
        }
        JPanel row = pending.row;
        if (row != null && row.getParent() == confirmHost) {
            disableComponentTree(row);
            confirmHost.remove(row);
        }
        if (confirmHost.getComponentCount() == 0) confirmHost.setVisible(false);
        confirmHost.revalidate();
        confirmHost.repaint();
    }

    private void invalidateAllConfirmations() {
        List<PendingConfirmation> snapshot;
        synchronized (confirmationLock) {
            snapshot = new ArrayList<PendingConfirmation>(pendingConfirmations.values());
            pendingConfirmations.clear();
        }
        for (PendingConfirmation pending : snapshot) {
            pending.terminal.compareAndSet(false, true);
        }
    }

    private static void disableComponentTree(Component component) {
        if (component == null) return;
        component.setEnabled(false);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                disableComponentTree(child);
            }
        }
    }

    private static void runOnEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during Swing cleanup", interrupted);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("Swing cleanup failed", cause);
        }
    }

    // Whether the panel can usefully render — used to fall back to IJ.log
    // when the plugin window isn't open (TCP-only mode).
    boolean isPanelLive() {
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

    private static final String HTML_HEAD = "<html><head><style>"
            + "body { font-family: sans-serif; margin: 4px; padding: 0; "
            + "background: #19191e; color: #d0d0d0; }"
            + "</style></head><body>";
    private static final String HTML_TAIL = "</body></html>";

    private void initHtmlContent() {
        messageArea.setText(HTML_HEAD + HTML_TAIL);
    }

    /** EDT-only bounded transcript append. */
    private void appendTranscriptHtmlNow(String html) throws Exception {
        String fragment = boundedHtmlFragment(html);
        boolean evicted = false;
        while (!transcriptFragments.isEmpty()
                && (transcriptFragments.size() >= MAX_TRANSCRIPT_ENTRIES
                || transcriptChars + fragment.length() > MAX_TRANSCRIPT_CHARS)) {
            String removed = transcriptFragments.removeFirst();
            transcriptChars -= removed.length();
            droppedTranscriptEntries.incrementAndGet();
            evicted = true;
        }
        transcriptFragments.addLast(fragment);
        transcriptChars += fragment.length();

        if (evicted) {
            renderTranscriptFromBuffer();
        } else {
            HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
            doc.insertBeforeEnd(doc.getDefaultRootElement(), fragment);
        }
        scrollToBottom();
    }

    private void renderTranscriptFromBuffer() {
        StringBuilder html = new StringBuilder(
                Math.min(MAX_TRANSCRIPT_CHARS + HTML_HEAD.length() + HTML_TAIL.length(),
                        transcriptChars + HTML_HEAD.length() + HTML_TAIL.length()));
        html.append(HTML_HEAD);
        for (String fragment : transcriptFragments) html.append(fragment);
        html.append(HTML_TAIL);
        messageArea.setText(html.toString());
    }

    private void resetTranscriptAndHtml() {
        transcriptFragments.clear();
        transcriptChars = 0;
        initHtmlContent();
    }

    private static String boundedDisplayText(String value) {
        String safe = value == null ? "" : value;
        if (safe.length() <= MAX_TRANSCRIPT_FRAGMENT_CHARS / 2) return safe;
        return safe.substring(0, MAX_TRANSCRIPT_FRAGMENT_CHARS / 2)
                + "\n[content truncated; original character count: "
                + safe.length() + "]";
    }

    private static String boundedHtmlFragment(String html) {
        String fragment = html == null ? "" : html;
        if (fragment.length() <= MAX_TRANSCRIPT_FRAGMENT_CHARS) return fragment;
        return "<div style='color:#ffaa55'>[content omitted: "
                + fragment.length() + " rendered characters exceeds the per-entry limit]"
                + "</div>";
    }

    private static String boundedShortText(String value, int maxChars) {
        String safe = value == null ? "" : value;
        if (safe.length() <= maxChars) return safe;
        int end = Math.max(0, maxChars);
        if (end > 0 && Character.isHighSurrogate(safe.charAt(end - 1))) end--;
        return safe.substring(0, end);
    }

    static List<String> boundedConfirmOptions(List<String> options) {
        List<String> bounded = new ArrayList<String>();
        if (options == null) return bounded;
        int count = Math.min(MAX_CONFIRM_OPTIONS, options.size());
        for (int i = 0; i < count; i++) {
            bounded.add(boundedShortText(options.get(i), MAX_CONFIRM_OPTION_CHARS));
        }
        return bounded;
    }

    private boolean enqueueTranscriptAppend(final Runnable append) {
        if (append == null) return false;
        if (SwingUtilities.isEventDispatchThread()) {
            append.run();
            return true;
        }
        while (true) {
            int pending = pendingTranscriptAppends.get();
            if (pending >= MAX_PENDING_TRANSCRIPT_APPENDS) {
                droppedPendingTranscriptAppends.incrementAndGet();
                return false;
            }
            if (pendingTranscriptAppends.compareAndSet(pending, pending + 1)) break;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                try {
                    append.run();
                } finally {
                    pendingTranscriptAppends.decrementAndGet();
                }
            }
        });
        return true;
    }

    public long droppedTranscriptEntryCount() {
        return droppedTranscriptEntries.get();
    }

    int transcriptEntryCountForTest() {
        return transcriptFragments.size();
    }

    int transcriptCharCountForTest() {
        return transcriptChars;
    }

    public long rejectedChatListenerCount() {
        return rejectedChatListeners.get();
    }

    public long rejectedClearListenerCount() {
        return rejectedClearListeners.get();
    }

    public long chatListenerFailureCount() {
        return chatListenerFailures.get();
    }

    public long droppedPendingTranscriptAppendCount() {
        return droppedPendingTranscriptAppends.get();
    }

    public int pendingTranscriptAppendCount() {
        return pendingTranscriptAppends.get();
    }

    long droppedTranscriptEntryCountForTest() {
        return droppedTranscriptEntryCount();
    }

    long rejectedChatListenerCountForTest() {
        return rejectedChatListenerCount();
    }

    long rejectedClearListenerCountForTest() {
        return rejectedClearListenerCount();
    }

    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setOpaque(false);

        // Multiline editor: Enter sends, Shift+Enter inserts a newline, and
        // normal Tab/Shift+Tab traversal leaves the editor. Ctrl+Space accepts
        // the first Local Assistant suggestion without taking Tab away from
        // keyboard-only users.
        inputArea = new JTextArea(2, 20);
        inputArea.setBackground(BG_INPUT);
        inputArea.setForeground(Color.WHITE);
        inputArea.setCaretColor(ACCENT);
        inputArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(new EmptyBorder(6, 8, 6, 8));
        inputArea.setFocusTraversalKeysEnabled(true);
        inputArea.setFocusTraversalKeys(
                KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
                java.util.Collections.singleton(
                        AWTKeyStroke.getAWTKeyStroke(KeyEvent.VK_TAB, 0)));
        inputArea.setFocusTraversalKeys(
                KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
                java.util.Collections.singleton(AWTKeyStroke.getAWTKeyStroke(
                        KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK)));
        inputArea.setToolTipText(
                "Enter sends; Shift+Enter adds a new line; Tab moves to the next control; Ctrl+Space accepts the first suggestion.");
        inputArea.getAccessibleContext().setAccessibleName("Chat message");
        inputArea.getAccessibleContext().setAccessibleDescription(
                "Multiline message editor. Enter sends, Shift+Enter adds a new line, Tab moves to the next control, and Control+Space accepts the first suggestion.");

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
        inputArea.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK),
                "acceptFirstSuggestion");
        inputArea.getActionMap().put("acceptFirstSuggestion", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (isLocalAssistantSelected() && chipRow != null) {
                    chipRow.acceptFirst();
                }
            }
        });
        installAutocompleteListener();

        chipRow = new AutocompleteChipRow(new Consumer<String>() {
            @Override
            public void accept(String phrase) {
                inputArea.setText(phrase);
                inputArea.requestFocusInWindow();
                inputArea.setCaretPosition(inputArea.getDocument().getLength());
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
        sendBtn.setMnemonic(KeyEvent.VK_S);
        sendBtn.getAccessibleContext().setAccessibleName("Send chat message");
        sendBtn.getAccessibleContext().setAccessibleDescription(
                "Send the current message. You can also press Enter in the editor.");
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

    private void installAutocompleteListener() {
        autocompleteTimer = new Timer(100, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateAutocompleteChipsNow();
            }
        });
        autocompleteTimer.setRepeats(false);
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleAutocompleteUpdate();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleAutocompleteUpdate();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleAutocompleteUpdate();
            }
        });
    }

    private void scheduleAutocompleteUpdate() {
        if (autocompleteTimer == null) {
            return;
        }
        autocompleteTimer.restart();
    }

    private void updateAutocompleteChipsNow() {
        if (chipRow == null || inputArea == null) {
            return;
        }
        if (!isLocalAssistantSelected() || !inputArea.isEnabled()) {
            clearAutocompleteChips();
            return;
        }
        String text = inputArea.getText();
        if (text == null || text.trim().length() == 0) {
            clearAutocompleteChips();
            return;
        }
        final String requestedText = text;
        final long generation = autocompleteGeneration.incrementAndGet();
        AUTOCOMPLETE_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final List<RankedPhrase> chips = localAssistant.topK(requestedText, 3);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != autocompleteGeneration.get()
                                || inputArea == null
                                || !requestedText.equals(inputArea.getText())
                                || !isLocalAssistantSelected()
                                || !inputArea.isEnabled()) {
                            return;
                        }
                        chipRow.setCandidates(chips);
                    }
                });
            }
        });
    }

    private void clearAutocompleteChips() {
        autocompleteGeneration.incrementAndGet();
        if (chipRow != null) {
            chipRow.setCandidates(new ArrayList<RankedPhrase>());
        }
    }

    private void sendMessage() {
        boolean localAssistantSelected = isLocalAssistantSelected();
        if (!localAssistantSelected && !settings.hasApiKey()) return;
        
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_INPUT_CHARS) {
            appendMessage("assistant", "Message not sent: input is " + text.length()
                    + " characters; the limit is " + MAX_INPUT_CHARS + ".");
            return;
        }
        if ("/clear".equalsIgnoreCase(text)) {
            clearConversation();
            return;
        }
        if (localAssistantSelected
                && !localAssistantBusy.compareAndSet(false, true)) {
            return;
        }
        inputArea.setText("");
        clearAutocompleteChips();

        appendMessage("user", text);

        if (localAssistantSelected) {
            final long generation = localConversationGeneration.get();
            final String localText = text;
            setEnabled(false);
            setThinking(true);
            try {
                localAssistantExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Function<String, AssistantReply> handler =
                                localAssistantHandlerForTest;
                        AssistantReply reply = handler == null
                                ? localAssistant.handle(localText)
                                : handler.apply(localText);
                        if (generation != localConversationGeneration.get()) return;
                        renderLocalReply(reply, generation);
                    } finally {
                        // Clear admission only when the actual worker exits.
                        // A conversation clear invalidates rendering but must
                        // not allow another queued backend call to accumulate.
                        localAssistantBusy.set(false);
                        if (generation == localConversationGeneration.get()) {
                            setThinking(false);
                            setEnabled(true);
                        }
                    }
                }
                });
            } catch (RejectedExecutionException rejected) {
                localAssistantBusy.set(false);
                setThinking(false);
                setEnabled(true);
                appendMessage("assistant", "Local Assistant is busy; try again shortly.");
            }
            return;
        }

        // Notify listeners (Phase 2 conversation loop)
        if (!listeners.isEmpty()) {
            for (ChatPanel.ChatListener listener : listeners) {
                try {
                    listener.onUserMessage(text);
                } catch (Throwable failure) {
                    chatListenerFailures.incrementAndGet();
                    IJ.log("[ImageJAI-GUI] chat listener failed: "
                            + failure.getClass().getSimpleName());
                }
            }
        } else {
            // No listener wired yet — show placeholder
            appendMessage("assistant", "[Phase 2: LLM integration not yet wired]\n"
                    + "You said: " + text);
        }
    }

    private void appendClarificationChips(List<RankedPhrase> candidates) {
        clarificationCandidates = candidates == null
                ? new ArrayList<RankedPhrase>()
                : new ArrayList<RankedPhrase>(candidates.subList(0, Math.min(2, candidates.size())));
        if (clarificationCandidates.isEmpty()) {
            return;
        }

        StringBuilder html = new StringBuilder();
        html.append("<div style='margin:0 0 6px 13px;padding:0;'>");
        for (int i = 0; i < clarificationCandidates.size(); i++) {
            RankedPhrase candidate = clarificationCandidates.get(i);
            html.append("<a href='ijai-chip:")
                    .append(i)
                    .append("' style='background:#323740;color:#dce1e6;")
                    .append("text-decoration:none;font-size:11px;padding:3px 7px;")
                    .append("margin-right:4px;'>")
                    .append(escapeHtml(candidate.phrase()))
                    .append("</a>");
        }
        html.append("</div>");
        appendHtml(html.toString());
    }

    private void renderLocalReply(final AssistantReply reply, final long generation) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (generation != localConversationGeneration.get()) return;
                if (reply.text() != null && !reply.text().trim().isEmpty()) {
                    appendMessageNow("assistant", reply.text());
                }
                if (reply.isClarifying() && !reply.clarificationCandidates().isEmpty()) {
                    appendClarificationChips(reply.clarificationCandidates());
                }
                if (reply.macroEcho() != null && !reply.macroEcho().trim().isEmpty()) {
                    appendMessageNow("assistant", "```\n" + reply.macroEcho() + "\n```");
                }
            }
        });
    }

    private void handleChatLink(String description) {
        if (description == null || !description.startsWith("ijai-chip:")) {
            return;
        }
        try {
            int index = Integer.parseInt(description.substring("ijai-chip:".length()));
            if (index < 0 || index >= clarificationCandidates.size()) {
                return;
            }
            inputArea.setText(clarificationCandidates.get(index).phrase());
            sendMessage();
        } catch (NumberFormatException ignored) {
            // Ignore malformed links in the chat transcript.
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

    String renderedHtmlForTest() {
        return messageArea.getText();
    }

    int pendingConfirmCountForTest() {
        return confirmHost == null ? 0 : confirmHost.getComponentCount();
    }

    int activeConfirmationCountForTest() {
        synchronized (confirmationLock) {
            return pendingConfirmations.size();
        }
    }

    LocalAssistant localAssistantForTest() {
        return localAssistant;
    }

    JTextArea inputAreaForTest() {
        return inputArea;
    }

    JButton sendButtonForTest() {
        return sendBtn;
    }

    void setLocalAssistantHandlerForTest(Function<String, AssistantReply> handler) {
        localAssistantHandlerForTest = handler;
    }

    void sendMessageForTest(String text) {
        inputArea.setText(text == null ? "" : text);
        sendMessage();
    }

    boolean localAssistantBusyForTest() {
        return localAssistantBusy.get();
    }

    int localAssistantQueuedTaskCountForTest() {
        return localAssistantExecutor.getQueue().size();
    }

    JTextPane messageAreaForTest() {
        return messageArea;
    }

    JPanel confirmHostForTest() {
        return confirmHost;
    }

    void populateConfirmControlsForTest(List<String> options, Consumer<String> onChoice) {
        populateConfirmControls(new PendingConfirmation(
                "test-confirm-" + localConfirmationCounter.incrementAndGet(), onChoice),
                boundedConfirmOptions(options));
    }

}
