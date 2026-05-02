package imagejai.ui;

import ij.IJ;
import imagejai.config.Settings;
import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;
import imagejai.engine.EmbeddedAgentSession;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Window;
import java.awt.CardLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Plugin root panel: header controls plus a CardLayout body that swaps
 * between chat and embedded terminal.
 */
public class AiRootPanel extends JPanel implements ChatSurface {
    private static final String CARD_CHAT = "chat";
    private static final String CARD_TERMINAL = "terminal";

    private static final Dimension CHAT_SIZE = new Dimension(420, 600);
    private static final Dimension TERMINAL_SIZE = new Dimension(900, 700);

    private static final Color BG_MAIN = new Color(30, 30, 35);
    private static final Color ACCENT = new Color(0, 200, 255);
    private static final Color TEXT_MUTED = new Color(120, 120, 130);

    private final Settings settings;
    private final ChatView chatView;
    private final TerminalView terminalView;
    private final CardLayout cardLayout;
    private final JPanel cards;
    private final List<AgentSession> liveSessions = new ArrayList<AgentSession>();

    private AgentLauncher agentLauncher;
    private JComboBox<Settings.ModelConfig> profileSwitcher;
    private JFrame frame;
    private String currentCard = CARD_CHAT;

    public AiRootPanel(Settings settings) {
        super(new BorderLayout(0, 6));
        this.settings = settings;
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setBackground(BG_MAIN);

        chatView = new ChatView(settings);
        terminalView = new TerminalView(settings);

        cardLayout = new CardLayout();
        cards = new JPanel(cardLayout);
        cards.setOpaque(false);
        cards.add(chatView, CARD_CHAT);
        cards.add(terminalView, CARD_TERMINAL);

        add(createHeader(), BorderLayout.NORTH);
        add(cards, BorderLayout.CENTER);
        showChatCard();
    }

    public void setFrame(JFrame frame) {
        this.frame = frame;
        applyFrameSize();
    }

    public ChatView chatView() {
        return chatView;
    }

    public ChatPanelController chatController() {
        return chatView;
    }

    public void addChatListener(ChatPanel.ChatListener listener) {
        chatView.addChatListener(listener);
    }

    public void setAgentLauncher(AgentLauncher launcher) {
        agentLauncher = launcher;
    }

    public void refreshProfileSwitcher() {
        if (profileSwitcher == null) {
            return;
        }

        ActionListener[] listeners = profileSwitcher.getActionListeners();
        for (ActionListener listener : listeners) {
            profileSwitcher.removeActionListener(listener);
        }

        profileSwitcher.removeAllItems();
        for (Settings.ModelConfig config : settings.configs) {
            profileSwitcher.addItem(config);
        }
        profileSwitcher.setSelectedItem(settings.getActiveConfig());

        for (ActionListener listener : listeners) {
            profileSwitcher.addActionListener(listener);
        }
        chatView.refreshInputState();
    }

    public void shutdownSessions() {
        if (SwingUtilities.isEventDispatchThread()) {
            Thread shutdown = new Thread(new Runnable() {
                @Override
                public void run() {
                    shutdownSessionsNow();
                }
            }, "ImageJAI-agent-shutdown");
            shutdown.setDaemon(true);
            shutdown.start();
        } else {
            shutdownSessionsNow();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        chatView.setEnabled(enabled);
    }

    @Override
    public void setThinking(boolean thinking) {
        chatView.setThinking(thinking);
    }

    @Override
    public void appendMessage(String role, String text) {
        chatView.appendMessage(role, text);
    }

    @Override
    public void appendHtml(String html) {
        chatView.appendHtml(html);
    }

    @Override
    public void setStatus(String status) {
        chatView.setStatus(status);
    }

    private JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftPanel.setOpaque(false);

        javax.swing.JLabel title = new javax.swing.JLabel("AI Assistant");
        title.setForeground(ACCENT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        leftPanel.add(title);

        profileSwitcher = new JComboBox<Settings.ModelConfig>();
        profileSwitcher.setPreferredSize(new Dimension(150, 22));
        profileSwitcher.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (Settings.ModelConfig config : settings.configs) {
            profileSwitcher.addItem(config);
        }
        profileSwitcher.setSelectedItem(settings.getActiveConfig());
        profileSwitcher.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Settings.ModelConfig selected =
                        (Settings.ModelConfig) profileSwitcher.getSelectedItem();
                if (selected != null) {
                    settings.activeConfigId = selected.id;
                    settings.save();
                    chatView.refreshInputState();
                    chatView.appendMessage("assistant", "Switched to profile: " + selected.name);
                }
            }
        });
        leftPanel.add(profileSwitcher);
        header.add(leftPanel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        buttons.setOpaque(false);

        JButton agentBtn = createHeaderButton("\u25B6", "Launch AI Agent");
        agentBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showAgentLaunchMenuAsync(agentBtn, false);
            }
        });
        buttons.add(agentBtn);

        JButton clearBtn = createHeaderButton("\u2718", "Clear conversation");
        clearBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int result = JOptionPane.showConfirmDialog(
                        AiRootPanel.this,
                        "Clear the conversation history?",
                        "Clear Conversation",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                if (result == JOptionPane.YES_OPTION) {
                    chatView.clearConversation();
                }
            }
        });
        buttons.add(clearBtn);

        JButton settingsBtn = createHeaderButton("\u2699", "Settings");
        settingsBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSettings();
            }
        });
        buttons.add(settingsBtn);

        header.add(buttons, BorderLayout.EAST);
        header.setBorder(new EmptyBorder(0, 0, 2, 0));
        return header;
    }

    private JButton createHeaderButton(String symbol, String tooltip) {
        JButton button = new JButton(symbol);
        button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        button.setForeground(new Color(150, 150, 160));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(tooltip);
        button.setMargin(new Insets(0, 4, 0, 4));
        return button;
    }

    private void showAgentLaunchMenuAsync(final JButton anchor, final boolean rescan) {
        if (agentLauncher == null) {
            JOptionPane.showMessageDialog(this,
                    "Agent launcher not configured.\nEnable the TCP server in Settings first.",
                    "Agent Launcher", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        anchor.setEnabled(false);
        new SwingWorker<List<AgentLauncher.AgentInfo>, Void>() {
            @Override
            protected List<AgentLauncher.AgentInfo> doInBackground() {
                return rescan ? agentLauncher.rescanAgents() : agentLauncher.detectAgents();
            }

            @Override
            protected void done() {
                anchor.setEnabled(true);
                try {
                    showAgentLaunchMenu(anchor, get());
                } catch (Exception ex) {
                    chatView.appendMessage("assistant",
                            "Could not detect agents: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void showAgentLaunchMenu(final JButton anchor,
                                     final List<AgentLauncher.AgentInfo> agents) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(new Color(40, 40, 48));

        if (agents.isEmpty()) {
            JMenuItem noAgents = new JMenuItem("No AI agents detected on PATH");
            noAgents.setEnabled(false);
            noAgents.setForeground(TEXT_MUTED);
            menu.add(noAgents);

            menu.addSeparator();
            JMenuItem hint = new JMenuItem("Install: claude, aider, gemini...");
            hint.setEnabled(false);
            hint.setForeground(TEXT_MUTED);
            hint.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
            menu.add(hint);
        } else {
            JMenuItem header = new JMenuItem("Launch AI Agent");
            header.setEnabled(false);
            header.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            header.setForeground(ACCENT);
            menu.add(header);
            menu.addSeparator();

            for (final AgentLauncher.AgentInfo agent : agents) {
                JMenuItem item = new JMenuItem(agent.name + " - " + agent.description);
                item.setForeground(Color.WHITE);
                item.setBackground(new Color(40, 40, 48));
                item.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        launchAgentAsync(agent);
                    }
                });
                menu.add(item);
            }
        }

        menu.addSeparator();

        JMenuItem rescan = new JMenuItem("Rescan for agents...");
        rescan.setForeground(TEXT_MUTED);
        rescan.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showAgentLaunchMenuAsync(anchor, true);
            }
        });
        menu.add(rescan);

        JMenuItem openFolder = new JMenuItem("Open agent workspace folder");
        openFolder.setForeground(TEXT_MUTED);
        openFolder.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openAgentWorkspaceAsync();
            }
        });
        menu.add(openFolder);

        menu.show(anchor, 0, anchor.getHeight());
    }

    private void launchAgentAsync(final AgentLauncher.AgentInfo agent) {
        final AgentLauncher.Mode mode = settings.agentEmbeddedTerminal
                ? AgentLauncher.Mode.EMBEDDED
                : AgentLauncher.Mode.EXTERNAL;

        chatView.appendMessage("assistant", "Launching " + agent.name + "...");
        new SwingWorker<AgentSession, Void>() {
            @Override
            protected AgentSession doInBackground() {
                return agentLauncher.launch(agent, mode);
            }

            @Override
            protected void done() {
                try {
                    AgentSession session = get();
                    handleLaunchedSession(agent, mode, session);
                } catch (Exception ex) {
                    chatView.appendMessage("assistant",
                            "Failed to launch " + agent.name + ": " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void handleLaunchedSession(AgentLauncher.AgentInfo agent,
                                       AgentLauncher.Mode mode,
                                       AgentSession session) {
        if (session == null) {
            chatView.appendMessage("assistant", "Failed to launch " + agent.name
                    + ". Check the ImageJ log for details.");
            return;
        }

        synchronized (liveSessions) {
            liveSessions.add(session);
        }

        if (mode == AgentLauncher.Mode.EMBEDDED && session instanceof EmbeddedAgentSession) {
            EmbeddedAgentSession embedded = (EmbeddedAgentSession) session;
            terminalView.attachSession(embedded);
            showTerminalCard();
            watchSessionExit(embedded);
            chatView.appendMessage("assistant", "Launched " + agent.name
                    + " inside the plugin window.");
        } else {
            chatView.appendMessage("assistant", "Launched " + agent.name
                    + " in: " + agentLauncher.getAgentWorkspace());
        }
    }

    private void watchSessionExit(final EmbeddedAgentSession session) {
        final Timer timer = new Timer(750, null);
        timer.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (session.isAlive()) {
                    return;
                }
                timer.stop();
                synchronized (liveSessions) {
                    liveSessions.remove(session);
                }
                terminalView.clearSession(session);
                IJ.log("[ImageJAI-Term] Embedded agent exited with code "
                        + session.exitValue() + ": " + session.info().name);
                showChatCard();
            }
        });
        timer.start();
    }

    private void showTerminalCard() {
        currentCard = CARD_TERMINAL;
        cardLayout.show(cards, CARD_TERMINAL);
        applyFrameSize();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                terminalView.requestTerminalFocus();
            }
        });
    }

    private void showChatCard() {
        currentCard = CARD_CHAT;
        cardLayout.show(cards, CARD_CHAT);
        applyFrameSize();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                chatView.requestFocusInWindow();
            }
        });
    }

    private void applyFrameSize() {
        if (frame == null) {
            return;
        }
        frame.setSize(CARD_TERMINAL.equals(currentCard) ? TERMINAL_SIZE : CHAT_SIZE);
        frame.revalidate();
    }

    private void openSettings() {
        Window window = SwingUtilities.getWindowAncestor(this);
        Frame parent = window instanceof Frame ? (Frame) window : null;
        SettingsDialog dialog = new SettingsDialog(parent, settings);
        dialog.setVisible(true);
        if (dialog.wasConfirmed()) {
            settings.save();
            refreshProfileSwitcher();
        }
    }

    private void openAgentWorkspaceAsync() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                Desktop.getDesktop().open(new File(agentLauncher.getAgentWorkspace()));
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    chatView.appendMessage("assistant",
                            "Could not open folder: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void shutdownSessionsNow() {
        List<AgentSession> snapshot;
        synchronized (liveSessions) {
            snapshot = new ArrayList<AgentSession>(liveSessions);
            liveSessions.clear();
        }

        for (AgentSession session : snapshot) {
            try {
                session.destroy();
                IJ.log("[ImageJAI-Term] Destroyed agent session: " + session.info().name);
            } catch (Exception ex) {
                IJ.log("[ImageJAI-Term] Failed to destroy agent session: " + ex.getMessage());
            }
        }
    }
}
