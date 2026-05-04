package imagejai.ui;

import ij.IJ;
import ij.Prefs;
import imagejai.config.Settings;
import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;
import imagejai.engine.EmbeddedAgentSession;
import imagejai.engine.ExternalAgentSession;
import imagejai.engine.picker.AgentLaunchOrchestrator;
import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.NativeAgentLauncher;
import imagejai.engine.picker.ProviderRegistry;
import imagejai.engine.picker.ProxyAgentLauncher;
import imagejai.engine.safeMode.SafeModeIndicator;
import imagejai.engine.usage.UsageTracker;
import imagejai.ui.picker.MainNotificationCheck;
import imagejai.ui.picker.ModelPickerButton;
import imagejai.ui.picker.ProviderTierGate;
import imagejai.ui.picker.TierChangeBanner;

import javax.swing.JButton;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Window;
import java.awt.CardLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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

    private static final String PREF_WINDOW_SIZE_PREFIX = "ai.assistant.window.size.";
    private static final Dimension CHAT_SIZE = new Dimension(420, 600);
    private static final Dimension TERMINAL_SIZE = new Dimension(900, 700);

    private static final Color BG_MAIN = new Color(30, 30, 35);
    private static final Color ACCENT = new Color(0, 200, 255);
    private static final Color TEXT_MUTED = new Color(120, 120, 130);
    private static boolean terminalFallbackNoticeShown;

    private final Settings settings;
    private final ChatView chatView;
    private final TerminalView terminalView;
    private final CardLayout cardLayout;
    private final JPanel cards;
    private final List<AgentSession> liveSessions = new ArrayList<AgentSession>();

    private AgentLauncher agentLauncher;
    private SafeModeIndicator safeModeIndicator;
    private JCheckBox safeModeBox;
    private JComboBox<Settings.ModelConfig> profileSwitcher;
    private JComboBox<String> agentSelector;
    private ModelPickerButton modelPicker;
    private ProviderRegistry providerRegistry;
    private AgentLaunchOrchestrator launchOrchestrator;
    private ProviderTierGate tierGate;
    private UsageTracker usageTracker;
    private TierChangeBanner tierChangeBanner;
    private JPanel terminalFallbackNotice;
    private JButton agentBtn;
    private JFrame frame;
    private String currentCard = CARD_CHAT;
    private boolean applyingFrameSize;
    private List<AgentLauncher.AgentInfo> detectedAgents = new ArrayList<AgentLauncher.AgentInfo>();

    public AiRootPanel(Settings settings) {
        super(new BorderLayout(0, 6));
        this.settings = settings;
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setBackground(BG_MAIN);

        chatView = new ChatView(settings);
        terminalView = new TerminalView(settings, new File(System.getProperty("user.dir", ".")),
                new LeftRail.SessionRelauncher() {
                    @Override
                    public void relaunchEmbeddedSession(EmbeddedAgentSession oldSession) {
                        AiRootPanel.this.relaunchEmbeddedSession(oldSession);
                    }
                });

        cardLayout = new CardLayout();
        cards = new JPanel(cardLayout);
        cards.setOpaque(false);
        cards.add(chatView, CARD_CHAT);
        cards.add(terminalView, CARD_TERMINAL);

        // Top stack: header + tier-change banner (06 §7.4). Banner sits below
        // the header so it pushes the chat/terminal down without blocking the
        // play button when many notifications stack.
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(createHeader(), BorderLayout.NORTH);
        tierChangeBanner = new TierChangeBanner();
        tierChangeBanner.setDismissListener(n -> {
            if (settings.dismissedTierChangeBanners == null) {
                settings.dismissedTierChangeBanners = new java.util.LinkedHashSet<>();
            }
            settings.dismissedTierChangeBanners.add(n.key);
            settings.save();
        });
        terminalFallbackNotice = createTerminalFallbackNotice();
        JPanel notices = new JPanel();
        notices.setOpaque(false);
        notices.setLayout(new BoxLayout(notices, BoxLayout.Y_AXIS));
        notices.add(tierChangeBanner);
        notices.add(terminalFallbackNotice);
        top.add(notices, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(cards, BorderLayout.CENTER);
        showChatCard();
        runFirstRunFlipNoticeIfNeeded();
        runStartupTierChangeCheck();
    }

    public void setFrame(JFrame frame) {
        this.frame = frame;
        if (frame != null) {
            frame.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    rememberFrameSize();
                }
            });
        }
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
        if (launcher != null) {
            terminalView.setWorkspace(new File(launcher.getAgentWorkspace()));
        }
        launchOrchestrator = new AgentLaunchOrchestrator(
                launcher, new NativeAgentLauncher(), new ProxyAgentLauncher());
        refreshAgentSelectorAsync();
    }

    /**
     * Stage 07 (docs/safe_mode_v2/07_status-indicator-ui.md): wire the safe
     * mode toolbar / status-bar indicator. {@link ImageJAIPlugin} calls this
     * once the indicator has been mounted on the Fiji toolbar so that
     * flipping the Safe Mode checkbox in this header repaints the dot grey
     * (off) or restores the live colour (on) without waiting for the next
     * agent launch.
     */
    public void setSafeModeIndicator(SafeModeIndicator indicator) {
        this.safeModeIndicator = indicator;
        if (indicator != null && safeModeBox != null) {
            indicator.setMasterEnabled(safeModeBox.isSelected());
        }
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

        javax.swing.JLabel agentLabel = new javax.swing.JLabel("Agent:");
        agentLabel.setForeground(TEXT_MUTED);
        agentLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        leftPanel.add(agentLabel);

        agentSelector = new JComboBox<String>();
        agentSelector.setPreferredSize(new Dimension(140, 22));
        agentSelector.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        refreshAgentSelector(new ArrayList<AgentLauncher.AgentInfo>());
        agentSelector.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String selected = (String) agentSelector.getSelectedItem();
                if (selected != null) {
                    settings.setSelectedAgentName(selected);
                    chatView.refreshInputState();
                    updateLaunchButtonState();
                }
            }
        });

        providerRegistry = ProviderRegistry.loadBundled();
        usageTracker = UsageTracker.load();
        tierGate = new ProviderTierGate(settings);
        modelPicker = new ModelPickerButton(providerRegistry, settings);
        modelPicker.setTierGate(tierGate);
        modelPicker.setPreferredSize(new Dimension(220, 22));
        modelPicker.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        modelPicker.setSelectionListener(new ModelPickerButton.SelectionListener() {
            @Override
            public void onSelectionChanged(ModelEntry entry) {
                settings.selectedAgentName = entry.providerId() + ":" + entry.modelId();
                settings.save();
                chatView.refreshInputState();
                updateLaunchButtonState();
            }

            @Override
            public void onLaunchRequested(ModelEntry entry) {
                launchModelAsync(entry);
            }
        });
        modelPicker.setSettingsLink(new ModelPickerButton.SettingsLink() {
            @Override
            public void openMultiProviderSettings() {
                openSettings();
            }
        });
        modelPicker.setInstallerLink(new ModelPickerButton.InstallerLink() {
            @Override
            public void openInstallerForProvider(String providerId) {
                openSettings();
            }
        });

        if (settings.useMultiProviderPicker) {
            leftPanel.add(modelPicker);
        } else {
            leftPanel.add(agentSelector);
        }

        javax.swing.JLabel profileLabel = new javax.swing.JLabel("Profile:");
        profileLabel.setForeground(TEXT_MUTED);
        profileLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        leftPanel.add(profileLabel);

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

        // Safe-mode v2 stage 02: master-switch checkbox.
        // Per plan: docs/safe_mode_v2/02_master-switch-and-caps.md.
        // When unchecked, the next launched agent will see
        // {@code IMAGEJAI_SAFE_MODE=0} and pass {@code safe_mode=false}
        // in its hello handshake — legacy unguarded fast path.
        // Stage 07: also drives the toolbar / status-bar indicator's master
        // grey state so the biologist's at-a-glance signal matches the
        // checkbox without waiting for the next agent launch.
        safeModeBox = new JCheckBox("Safe Mode", settings.safeModeEnabled);
        safeModeBox.setOpaque(false);
        safeModeBox.setForeground(TEXT_MUTED);
        safeModeBox.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        safeModeBox.setFocusPainted(false);
        safeModeBox.setToolTipText(
                "<html>Block destructive ops + auto-snapshot before every macro."
              + "<br>Uncheck for a fast, unguarded session."
              + "<br>Applies to the next agent you launch.</html>");
        safeModeBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                boolean on = safeModeBox.isSelected();
                settings.safeModeEnabled = on;
                settings.save();
                if (safeModeIndicator != null) {
                    safeModeIndicator.setMasterEnabled(on);
                }
            }
        });
        leftPanel.add(safeModeBox);

        header.add(leftPanel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        buttons.setOpaque(false);

        String agentBtnTooltip = settings.useMultiProviderPicker
                ? "Re-run the last launched model"
                : "Launch selected external agent";
        agentBtn = createHeaderButton("\u25B6", agentBtnTooltip);
        agentBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (settings.useMultiProviderPicker) {
                    relaunchLastModel();
                } else {
                    launchSelectedAgentAsync();
                }
            }
        });
        updateLaunchButtonState();
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

    private JPanel createTerminalFallbackNotice() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        panel.setBackground(new Color(42, 37, 28));
        panel.setBorder(new EmptyBorder(3, 6, 3, 6));
        javax.swing.JLabel label = new javax.swing.JLabel(
                "Embedded terminal needs Java 11+ - launching agent in an external window.");
        label.setForeground(new Color(230, 210, 160));
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        JButton why = new JButton("Why?");
        why.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        why.setMargin(new Insets(1, 6, 1, 6));
        why.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showJavaCompatibilityDialog();
            }
        });
        panel.add(label);
        panel.add(why);
        panel.setVisible(false);
        return panel;
    }

    private void refreshAgentSelectorAsync() {
        if (agentSelector == null) {
            return;
        }
        if (agentLauncher == null) {
            refreshAgentSelector(new ArrayList<AgentLauncher.AgentInfo>());
            return;
        }

        new SwingWorker<List<AgentLauncher.AgentInfo>, Void>() {
            @Override
            protected List<AgentLauncher.AgentInfo> doInBackground() {
                return agentLauncher.detectAgents();
            }

            @Override
            protected void done() {
                try {
                    refreshAgentSelector(get());
                } catch (Exception ex) {
                    chatView.appendMessage("assistant",
                            "Could not detect agents: " + ex.getMessage());
                    refreshAgentSelector(new ArrayList<AgentLauncher.AgentInfo>());
                }
            }
        }.execute();
    }

    private void refreshAgentSelector(List<AgentLauncher.AgentInfo> agents) {
        detectedAgents = new ArrayList<AgentLauncher.AgentInfo>(agents);
        if (agentSelector == null) {
            updateLaunchButtonState();
            return;
        }

        ActionListener[] listeners = agentSelector.getActionListeners();
        for (ActionListener listener : listeners) {
            agentSelector.removeActionListener(listener);
        }

        agentSelector.removeAllItems();
        agentSelector.addItem(AgentLauncher.LOCAL_ASSISTANT_NAME);
        for (AgentLauncher.AgentInfo agent : detectedAgents) {
            agentSelector.addItem(agent.name);
        }

        String selected = settings.getSelectedAgentName();
        if (AgentLauncher.LOCAL_ASSISTANT_NAME.equals(selected) || findDetectedAgent(selected) != null) {
            agentSelector.setSelectedItem(selected);
        } else {
            agentSelector.setSelectedItem(AgentLauncher.LOCAL_ASSISTANT_NAME);
            if (agentLauncher != null) {
                settings.setSelectedAgentName(AgentLauncher.LOCAL_ASSISTANT_NAME);
            }
        }

        for (ActionListener listener : listeners) {
            agentSelector.addActionListener(listener);
        }
        updateLaunchButtonState();
    }

    private void updateLaunchButtonState() {
        if (agentBtn == null) {
            return;
        }
        if (settings.useMultiProviderPicker) {
            ModelEntry entry = providerRegistry == null
                    ? null
                    : providerRegistry.lookup(
                            settings.selectedProvider, settings.selectedModelId);
            boolean enabled = entry != null;
            agentBtn.setEnabled(enabled);
            agentBtn.setToolTipText(enabled
                    ? "Re-run " + entry.displayName()
                    : "Pick a model from the dropdown first.");
            return;
        }
        String selected = settings.getSelectedAgentName();
        AgentLauncher.AgentInfo agent = findDetectedAgent(selected);
        boolean externalSelected = agentLauncher != null && agent != null;
        agentBtn.setEnabled(externalSelected);
        agentBtn.setToolTipText(externalSelected
                ? "Launch " + selected
                : "Local Assistant is built in");
    }

    private AgentLauncher.AgentInfo findDetectedAgent(String name) {
        if (name == null) {
            return null;
        }
        for (AgentLauncher.AgentInfo agent : detectedAgents) {
            if (name.equals(agent.name)) {
                return agent;
            }
        }
        return null;
    }

    private void launchSelectedAgentAsync() {
        String selected = settings.getSelectedAgentName();
        if (AgentLauncher.LOCAL_ASSISTANT_NAME.equals(selected)) {
            return;
        }
        AgentLauncher.AgentInfo agent = findDetectedAgent(selected);
        if (agent == null) {
            chatView.appendMessage("assistant",
                    "Could not launch " + selected + ": agent is not detected on PATH.");
            updateLaunchButtonState();
            return;
        }
        launchAgentAsync(agent);
    }

    private void relaunchLastModel() {
        if (providerRegistry == null || launchOrchestrator == null) {
            return;
        }
        String prov = settings.selectedProvider;
        String mid  = settings.selectedModelId;
        if (prov == null || mid == null) {
            modelPicker.showPopup();
            return;
        }
        ModelEntry entry = providerRegistry.lookup(prov, mid);
        if (entry == null) {
            modelPicker.showPopup();
            return;
        }
        launchModelAsync(entry);
    }

    private void launchModelAsync(final ModelEntry entry) {
        if (entry == null || launchOrchestrator == null) {
            return;
        }
        if (usageTracker != null) {
            usageTracker.recordLaunch(entry.providerId(), entry.modelId(), java.time.LocalDate.now());
            usageTracker.recordSeenTier(entry.providerId(), entry.modelId(),
                    entry.tier() == null ? null : entry.tier().yamlValue(),
                    null, null);
            try {
                usageTracker.save();
            } catch (java.io.IOException ex) {
                IJ.log("[ImageJAI] Could not persist usage_tracking.json: " + ex.getMessage());
            }
        }
        final AgentLaunchOrchestrator.Transport transport =
                AgentLaunchOrchestrator.transportFor(entry);
        if (transport != AgentLaunchOrchestrator.Transport.CLI) {
            chatView.appendMessage("assistant",
                    "The " + entry.providerId() + " transport will land in a later "
                    + "phase of the multi-provider rollout — for now this picker "
                    + "row is informational.");
            return;
        }
        final AgentLauncher.Mode mode = settings.agentEmbeddedTerminal
                ? AgentLauncher.Mode.EMBEDDED
                : AgentLauncher.Mode.EXTERNAL;
        chatView.appendMessage("assistant", "Launching " + entry.displayName() + "...");
        new SwingWorker<AgentSession, Void>() {
            @Override
            protected AgentSession doInBackground() {
                return launchOrchestrator.launch(entry, mode);
            }

            @Override
            protected void done() {
                try {
                    AgentSession session = get();
                    if (session == null) {
                        chatView.appendMessage("assistant",
                                "Failed to launch " + entry.displayName()
                                        + " — see ImageJ Log.");
                        return;
                    }
                    handleLaunchedSession(session.info(), mode, session);
                } catch (Exception ex) {
                    chatView.appendMessage("assistant",
                            "Failed to launch " + entry.displayName()
                                    + ": " + ex.getMessage());
                }
            }
        }.execute();
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
            if (mode == AgentLauncher.Mode.EMBEDDED && session instanceof ExternalAgentSession) {
                ExternalAgentSession external = (ExternalAgentSession) session;
                if (external.isFallbackLaunch()) {
                    showTerminalFallbackNotice(external.notice());
                }
            }
            chatView.appendMessage("assistant", "Launched " + agent.name
                    + " in: " + agentLauncher.getAgentWorkspace());
        }
    }

    private void showTerminalFallbackNotice(String reason) {
        if (terminalFallbackNoticeShown || terminalFallbackNotice == null) {
            return;
        }
        terminalFallbackNoticeShown = true;
        terminalFallbackNotice.setToolTipText(reason == null || reason.isEmpty()
                ? null
                : reason);
        terminalFallbackNotice.setVisible(true);
        terminalFallbackNotice.revalidate();
        terminalFallbackNotice.repaint();
    }

    private void showJavaCompatibilityDialog() {
        JOptionPane.showMessageDialog(
                this,
                "This Fiji is running Java "
                        + System.getProperty("java.specification.version", "unknown")
                        + ".\n\n"
                        + "ImageJAI is built as Java 8 bytecode so Fiji can load it on "
                        + "older Zulu 8 installs. The embedded terminal backend "
                        + "is loaded only on Java 11 or newer.\n\n"
                        + "On Java 8, the selected agent still launches in a normal "
                        + "terminal window. Upgrade Fiji's Java runtime to Java 11+ "
                        + "to use the embedded terminal.",
                "ImageJAI Java compatibility",
                JOptionPane.INFORMATION_MESSAGE);
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
                session.persistScrollbackIfEnabled();
                synchronized (liveSessions) {
                    liveSessions.remove(session);
                }
                if (!terminalView.isSession(session)) {
                    IJ.log("[ImageJAI-Term] Replaced embedded agent exited with code "
                            + session.exitValue() + ": " + session.info().name);
                    return;
                }
                terminalView.clearSession(session);
                IJ.log("[ImageJAI-Term] Embedded agent exited with code "
                        + session.exitValue() + ": " + session.info().name);
                showChatCard();
            }
        });
        timer.start();
    }

    private void relaunchEmbeddedSession(final EmbeddedAgentSession oldSession) {
        if (oldSession == null || agentLauncher == null) {
            return;
        }
        final AgentLauncher.AgentInfo info = oldSession.info();
        terminalView.clearSession(oldSession);
        synchronized (liveSessions) {
            liveSessions.remove(oldSession);
        }

        new SwingWorker<AgentSession, Void>() {
            @Override
            protected AgentSession doInBackground() {
                try {
                    oldSession.destroy();
                    IJ.log("[ImageJAI-Term] Destroyed uncleared PTY before relaunch: "
                            + info.name);
                } catch (Exception ex) {
                    IJ.log("[ImageJAI-Term] Failed to destroy uncleared PTY: "
                            + ex.getMessage());
                }
                return agentLauncher.launch(info, AgentLauncher.Mode.EMBEDDED);
            }

            @Override
            protected void done() {
                try {
                    AgentSession fresh = get();
                    handleLaunchedSession(info, AgentLauncher.Mode.EMBEDDED, fresh);
                } catch (Exception ex) {
                    chatView.appendMessage("assistant",
                            "Failed to relaunch " + info.name + ": " + ex.getMessage());
                }
            }
        }.execute();
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
        applyingFrameSize = true;
        frame.setSize(savedSizeFor(currentCard));
        applyingFrameSize = false;
        frame.revalidate();
    }

    private void rememberFrameSize() {
        if (frame == null || applyingFrameSize) {
            return;
        }
        Dimension size = frame.getSize();
        if (size == null || size.width <= 0 || size.height <= 0) {
            return;
        }
        // Stored as "WxH" strings so ij.Prefs keeps chat and terminal sizes portable.
        Prefs.set(PREF_WINDOW_SIZE_PREFIX + currentCard, size.width + "x" + size.height);
    }

    private Dimension savedSizeFor(String card) {
        Dimension fallback = CARD_TERMINAL.equals(card) ? TERMINAL_SIZE : CHAT_SIZE;
        String value = Prefs.get(PREF_WINDOW_SIZE_PREFIX + card,
                fallback.width + "x" + fallback.height);
        return parseSize(value, fallback);
    }

    private static Dimension parseSize(String value, Dimension fallback) {
        if (value == null) {
            return fallback;
        }
        String[] parts = value.trim().toLowerCase().split("x", 2);
        if (parts.length != 2) {
            return fallback;
        }
        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            if (width < 240 || height < 240) {
                return fallback;
            }
            return new Dimension(width, height);
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    private void runFirstRunFlipNoticeIfNeeded() {
        if (settings.useMultiProviderPicker && !settings.multiProviderFlipNoticeShown) {
            IJ.log("[ImageJAI] Multi-provider model picker is now the default. "
                    + "Old picker available in Settings → Multi-Provider tab → "
                    + "Use legacy picker.");
            settings.multiProviderFlipNoticeShown = true;
            settings.save();
        }
    }

    private void runStartupTierChangeCheck() {
        if (tierChangeBanner == null || providerRegistry == null || usageTracker == null) {
            return;
        }
        try {
            java.util.List<MainNotificationCheck.ScheduledChange> changes =
                    MainNotificationCheck.scheduledChangesFor(providerRegistry);
            java.util.List<MainNotificationCheck.Notification> notifications =
                    MainNotificationCheck.run(usageTracker, providerRegistry, changes,
                            java.time.LocalDate.now());
            java.util.Set<String> dismissed = settings.dismissedTierChangeBanners == null
                    ? java.util.Collections.<String>emptySet()
                    : settings.dismissedTierChangeBanners;
            tierChangeBanner.setNotifications(
                    MainNotificationCheck.filterDismissed(notifications, dismissed));
        } catch (Exception ex) {
            IJ.log("[ImageJAI] Tier-change check failed: " + ex.getMessage());
        }
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
