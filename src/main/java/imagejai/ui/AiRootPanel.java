package imagejai.ui;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.io.FileInfo;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentRecommender;
import imagejai.engine.AgentSession;
import imagejai.engine.EmbeddedAgentSession;
import imagejai.engine.ExternalAgentSession;
import imagejai.engine.PostureController;
import imagejai.engine.PostureViolation;
import imagejai.engine.MutationCoordinator;
import imagejai.engine.picker.AgentLaunchOrchestrator;
import imagejai.engine.picker.MergeFunction;
import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ModelsCache;
import imagejai.engine.picker.ModelsLocalLoader;
import imagejai.engine.picker.ModelsYamlLoader;
import imagejai.engine.picker.ProviderDiscovery;
import imagejai.engine.picker.ProviderEntry;
import imagejai.engine.picker.ProviderRegistry;
import imagejai.engine.safeMode.SafeModeIndicator;
import imagejai.engine.security.AuditLog;
import imagejai.engine.security.OutboundPromptScrubber;
import imagejai.engine.usage.UsageTracker;
import imagejai.ui.picker.MainNotificationCheck;
import imagejai.ui.picker.ModelPickerButton;
import imagejai.ui.picker.ProviderTierGate;
import imagejai.ui.picker.TierChangeBanner;

import javax.swing.JButton;
import javax.swing.BoxLayout;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JFileChooser;
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
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plugin root panel: header controls plus a CardLayout body that swaps
 * between chat and embedded terminal.
 */
public class AiRootPanel extends JPanel implements ChatSurface {
    private static final String CARD_CHAT = "chat";
    private static final String CARD_TERMINAL = "terminal";
    private static final String CARD_WELCOME = "welcome";

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
    private JComboBox<String> agentSelector;
    private ModelPickerButton modelPicker;
    private ProviderRegistry providerRegistry;
    private AgentLaunchOrchestrator launchOrchestrator;
    private ProviderTierGate tierGate;
    private UsageTracker usageTracker;
    private TierChangeBanner tierChangeBanner;
    private JPanel terminalFallbackNotice;
    private ConfigurationPane configurationPane;
    private ReceiptsPane receiptsPane;
    private PseudonymisationToast pseudonymisationToast;
    private VisualOverrideNotice visualOverrideNotice;
    private AutoCloseable promptToastSubscription;
    private EgressIndicator egressIndicator;
    private WelcomePanel welcomePanel;
    private AgentRecommender.Recommendation recommendation;
    private CardLayout headerCardLayout;
    private JPanel headerCards;
    private JFrame frame;
    private String currentCard = CARD_CHAT;
    private boolean applyingFrameSize;
    private List<AgentLauncher.AgentInfo> detectedAgents = new ArrayList<AgentLauncher.AgentInfo>();
    private PostureController.Listener postureRefreshListener;
    private Runnable backendRefreshListener;
    private PostureBadge postureBadge;

    public AiRootPanel(Settings settings) {
        this(settings, new MutationCoordinator());
    }

    public AiRootPanel(Settings settings, MutationCoordinator mutationCoordinator) {
        super(new BorderLayout(0, 6));
        this.settings = settings;
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setBackground(BG_MAIN);

        chatView = new ChatView(settings, mutationCoordinator);
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
        welcomePanel = new WelcomePanel(new Runnable() {
            @Override
            public void run() {
                launchRecommended();
            }
        }, new Runnable() {
            @Override
            public void run() {
                openAgentPicker();
            }
        });
        cards.add(welcomePanel, CARD_WELCOME);
        pseudonymisationToast = new PseudonymisationToast();
        promptToastSubscription = OutboundPromptScrubber.getInstance()
                .addNotifier(pseudonymisationToast);
        JPanel toastRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        toastRow.setOpaque(false);
        toastRow.add(pseudonymisationToast);

        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.setOpaque(false);
        body.add(cards, BorderLayout.CENTER);
        body.add(toastRow, BorderLayout.SOUTH);

        // Top stack: header + tier-change banner (06 Â§7.4). Banner sits below
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
        configurationPane = new ConfigurationPane(PostureController.getInstance(),
                AuditLog.getInstance());
        receiptsPane = new ReceiptsPane(AuditLog.getInstance());
        visualOverrideNotice = new VisualOverrideNotice();
        notices.add(visualOverrideNotice);
        notices.add(configurationPane);
        notices.add(receiptsPane);
        top.add(notices, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        showWelcomeCard();
        runFirstRunFlipNoticeIfNeeded();
        runStartupTierChangeCheck();
        installPostureRefreshListener();
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

    public void addConversationClearListener(Runnable listener) {
        chatView.addConversationClearListener(listener);
    }

    public void setBackendRefreshListener(Runnable listener) {
        backendRefreshListener = listener;
    }

    public void setAgentLauncher(AgentLauncher launcher) {
        agentLauncher = launcher;
        if (launcher != null) {
            terminalView.setWorkspace(new File(launcher.getAgentWorkspace()));
        }
        // Pass null launchers so the orchestrator wires them with the CLI
        // launcher, giving the proxy/native paths the terminal machinery they
        // need to spawn the Python provider agent loop.
        launchOrchestrator = new AgentLaunchOrchestrator(
                launcher, null, null, this::buildLaunchEnv);
        refreshAgentSelectorAsync();
        injectCliAgentsAsync();
    }

    /**
     * Detect installed CLI agents off the EDT and add them to the picker as a
     * synthetic {@code "cli"} provider group. The cascading dropdown is
     * default-on, but the curated {@code models.yaml} only carries API
     * providers; without this, existing users of Claude Code / Aider / etc.
     * would lose access to their CLI agents, and a legacy
     * {@code cli:<command>} selection could not resolve to a launchable row.
     */
    private void injectCliAgentsAsync() {
        final AgentLauncher launcher = agentLauncher;
        if (launcher == null) {
            return;
        }
        new SwingWorker<ProviderEntry, Void>() {
            @Override
            protected ProviderEntry doInBackground() {
                return buildCliProviderEntry(launcher.detectAgents());
            }

            @Override
            protected void done() {
                try {
                    ProviderEntry cli = get();
                    if (cli == null || providerRegistry == null) {
                        return;
                    }
                    providerRegistry = providerRegistry.withProvider(cli);
                    if (modelPicker != null) {
                        modelPicker.setRegistry(providerRegistry);
                    }
                    recomputeRecommendation();
                } catch (Exception ignore) {
                    // Detection failure leaves the API-only picker in place.
                }
            }
        }.execute();
    }

    /**
     * Build the synthetic {@code "cli"} provider from detected CLI agents. Each
     * agent becomes a {@link ModelEntry} whose {@code modelId} is the agent's
     * command, so {@link AgentLaunchOrchestrator} (transport {@code CLI}) can
     * resolve and launch it, and legacy {@code cli:<command>} selections map
     * straight onto a row. Returns {@code null} when no agents are detected.
     */
    private static ProviderEntry buildCliProviderEntry(List<AgentLauncher.AgentInfo> agents) {
        if (agents == null || agents.isEmpty()) {
            return null;
        }
        List<ModelEntry> models = new ArrayList<ModelEntry>();
        for (AgentLauncher.AgentInfo agent : agents) {
            if (agent == null || agent.command == null || agent.command.trim().isEmpty()) {
                continue;
            }
            models.add(new ModelEntry(
                    "cli",
                    agent.command,
                    agent.name == null ? agent.command : agent.name,
                    agent.description == null ? "" : agent.description,
                    ModelEntry.Tier.FREE,
                    0,
                    false,
                    ModelEntry.Reliability.HIGH,
                    false,
                    true,
                    "Installed CLI agent (launched in a terminal)."));
        }
        if (models.isEmpty()) {
            return null;
        }
        return new ProviderEntry("cli", "CLI agents",
                ProviderEntry.Status.READY, "", models);
    }

    private void installPostureRefreshListener() {
        if (postureRefreshListener == null) {
            postureRefreshListener = new PostureController.Listener() {
                @Override
                public void postureChanged(PrivacyPosture from, PrivacyPosture to, Path folder) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            refreshAgentSelectorAsync();
                        }
                    });
                }
            };
        }
        PostureController.getInstance().addListener(postureRefreshListener);
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
        if (indicator != null) {
            indicator.setMasterEnabled(settings.safeModeEnabled);
        }
    }

    public void refreshProfileSwitcher() {
        // The profile selector now lives in the working-header overflow menu
        // (rebuilt from settings each time it opens), so there is no combo box
        // to re-sync here -- just refresh the chat input state.
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
    public void addNotify() {
        super.addNotify();
        installPostureRefreshListener();
        if (postureBadge != null) postureBadge.attach();
    }

    @Override
    public void removeNotify() {
        disposeGovernanceUi();
        super.removeNotify();
    }

    private void disposeGovernanceUi() {
        if (postureRefreshListener != null) {
            PostureController.getInstance().removeListener(postureRefreshListener);
        }
        if (postureBadge != null) {
            postureBadge.dispose();
        }
        if (configurationPane != null) {
            configurationPane.dispose();
        }
        if (receiptsPane != null) {
            receiptsPane.dispose();
        }
        if (egressIndicator != null) {
            egressIndicator.dispose();
        }
        if (visualOverrideNotice != null) {
            visualOverrideNotice.dispose();
        }
        if (promptToastSubscription != null) {
            try {
                promptToastSubscription.close();
            } catch (Exception ignore) {
            }
            promptToastSubscription = null;
        }
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
        // The agent/model picker doubles as the working-state switch picker.
        agentSelector = new JComboBox<String>();
        agentSelector.setPreferredSize(new Dimension(180, 22));
        agentSelector.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        agentSelector.setToolTipText("<html>Agent CLI to launch."
                + "<br>In On-premises posture, only local-binary agents are selectable.</html>");
        refreshAgentSelector(new ArrayList<AgentLauncher.AgentInfo>());
        agentSelector.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String selected = (String) agentSelector.getSelectedItem();
                if (selected != null) {
                    settings.setSelectedAgentName(selected);
                    chatView.refreshInputState();
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
        modelPicker.setToolTipText("<html>Agent CLI to launch."
                + "<br>In On-premises posture, only local-binary agents are selectable.</html>");
        modelPicker.setSelectionListener(new ModelPickerButton.SelectionListener() {
            @Override
            public void onSelectionChanged(ModelEntry entry) {
                settings.selectedAgentName = entry.providerId() + ":" + entry.modelId();
                settings.save();
                chatView.refreshInputState();
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
                openSettingsForProvider(providerId);
            }
        });
        modelPicker.setPinListener(new ModelPickerButton.PinListener() {
            @Override
            public void onPinChanged(ModelEntry entry, boolean nowPinned) {
                persistPin(entry, nowPinned);
            }
        });
        if (settings.useMultiProviderPicker) {
            // Phase G cross-phase carry-over: feed the dropdown's â†» refresh
            // button a real RefreshTask backed by ProviderDiscovery + ModelsCache
            // so the user can refresh model lists at any time. Without this hook
            // the refresh button is permanently disabled (Phase G acceptance).
            modelPicker.setRefreshTask(buildRefreshTask());
            // Apply persisted pins/hides to the bundled list at startup so a
            // pinned favourite survives a restart even before any network
            // refresh (verifier #6); then honour refreshOnStartup by kicking a
            // background discovery so the dropdown isn't stuck on the bundled
            // snapshot (verifier #9).
            applyStartupOverrides();
            if (settings.refreshOnStartup) {
                triggerStartupRefresh();
            }
        }

        // --- Minimal header (Welcome / home state): title + settings gear. ---
        JPanel minHeader = new JPanel(new BorderLayout(4, 0));
        minHeader.setOpaque(false);
        javax.swing.JLabel minTitle = new javax.swing.JLabel("AI Assistant");
        minTitle.setForeground(ACCENT);
        minTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        minHeader.add(minTitle, BorderLayout.WEST);
        JButton minSettings = createHeaderButton("\u2699", "Settings");
        minSettings.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSettings();
            }
        });
        JPanel minRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        minRight.setOpaque(false);
        minRight.add(minSettings);
        minHeader.add(minRight, BorderLayout.EAST);

        // --- Working header (chat / embedded terminal): the switch picker on
        // the left; read-only status plus an overflow menu on the right. Each
        // side holds only a couple of items, so the row can never overlap (the
        // original first-open bug packed ~988px of controls into a ~404px
        // BorderLayout row). Secondary controls live in the overflow menu. ---
        JPanel workHeader = new JPanel(new BorderLayout(4, 0));
        workHeader.setOpaque(false);
        JPanel workLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        workLeft.setOpaque(false);
        workLeft.add(settings.useMultiProviderPicker ? modelPicker : agentSelector);
        workHeader.add(workLeft, BorderLayout.WEST);

        JPanel workRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        workRight.setOpaque(false);
        postureBadge = new PostureBadge(PostureController.getInstance());
        workRight.add(postureBadge);
        egressIndicator = new EgressIndicator();
        workRight.add(egressIndicator);
        JButton overflowBtn = createHeaderButton("\u22EF",
                "More: Browse Files, View Audit Log, Safe Mode, Profile, Settings");
        overflowBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showOverflowMenu((JButton) e.getSource());
            }
        });
        workRight.add(overflowBtn);
        workHeader.add(workRight, BorderLayout.EAST);

        // Swap minimal/working via a CardLayout so the persistent NORTH header
        // morphs with the body card (Welcome -> minimal, chat/terminal -> work).
        headerCardLayout = new CardLayout();
        headerCards = new JPanel(headerCardLayout);
        headerCards.setOpaque(false);
        headerCards.add(minHeader, "min");
        headerCards.add(workHeader, "work");
        headerCards.setBorder(new EmptyBorder(0, 0, 2, 0));
        return headerCards;
    }

    /** Show the minimal (Welcome) or working header to match the active body card. */
    private void setHeaderState(boolean welcome) {
        if (headerCardLayout == null || headerCards == null) {
            return;
        }
        headerCardLayout.show(headerCards, welcome ? "min" : "work");
    }

    /**
     * Build and show the working-state overflow menu. Holds the secondary
     * controls that used to crowd the header row -- Browse Files, View Audit
     * Log, Safe Mode, Profile, Clear, Settings -- rebuilt each time so the Safe
     * Mode tick and Profile selection reflect current settings.
     */
    private void showOverflowMenu(JButton owner) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem browse = new JMenuItem("Browse Files...");
        browse.setToolTipText("<html>Select files and series locally."
                + "<br>The agent receives only pseudonym tokens and your tag.</html>");
        browse.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openBrowseFilesDialog();
            }
        });
        menu.add(browse);

        JMenuItem audit = new JMenuItem("View Audit Log");
        audit.setToolTipText("<html>Audit trail of outbound calls to the agent."
                + "<br>CSV format. Suitable for ethics applications.</html>");
        audit.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openAuditLogAsync();
            }
        });
        menu.add(audit);

        menu.addSeparator();

        final AgentLauncher.AgentInfo resumeAgent = selectedCliAgent();
        final boolean resumeSupported = agentLauncher != null
                && agentLauncher.supportsResumeLatest(resumeAgent);
        if (resumeSupported) {
            JMenuItem resume = new JMenuItem("Resume selected CLI session");
            resume.setToolTipText("Resume the latest saved " + resumeAgent.name
                    + " session for this agent workspace.");
            resume.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    launchAgentAsync(resumeAgent, AgentLauncher.SessionAction.RESUME_LATEST);
                }
            });
            menu.add(resume);
            menu.addSeparator();
        }

        final JCheckBoxMenuItem safeMode =
                new JCheckBoxMenuItem("Safe Mode", settings.safeModeEnabled);
        safeMode.setToolTipText(
                "<html>Block destructive ops + auto-snapshot before every macro."
              + "<br>Uncheck for a fast, unguarded session."
              + "<br>Applies to the next agent you launch.</html>");
        safeMode.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                boolean on = safeMode.isSelected();
                settings.safeModeEnabled = on;
                settings.save();
                if (safeModeIndicator != null) {
                    safeModeIndicator.setMasterEnabled(on);
                }
            }
        });
        menu.add(safeMode);

        JMenu profile = new JMenu("Profile");
        if (settings.configs == null || settings.configs.isEmpty()) {
            JMenuItem none = new JMenuItem("(no profiles)");
            none.setEnabled(false);
            profile.add(none);
        } else {
            for (final Settings.ModelConfig config : settings.configs) {
                boolean active = config.id != null && config.id.equals(settings.activeConfigId);
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(config.name, active);
                item.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        settings.activeConfigId = config.id;
                        settings.save();
                        notifyBackendRefresh();
                        chatView.refreshInputState();
                        chatView.appendMessage("assistant",
                                "Switched to profile: " + config.name);
                    }
                });
                profile.add(item);
            }
        }
        menu.add(profile);

        menu.addSeparator();

        JMenuItem clear = new JMenuItem("Clear conversation");
        clear.addActionListener(new ActionListener() {
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
        menu.add(clear);

        JMenuItem settingsItem = new JMenuItem("Settings...");
        settingsItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSettings();
            }
        });
        menu.add(settingsItem);

        menu.show(owner, 0, owner.getHeight());
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

    private void openAuditLogAsync() {
        new SwingWorker<Void, Void>() {
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    AuditLog.getInstance().open();
                } catch (Exception e) {
                    error = e;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    JOptionPane.showMessageDialog(
                            AiRootPanel.this,
                            "Could not open the Data Governance audit log:\n"
                                    + error.getMessage(),
                            "View Audit Log",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    private void openBrowseFilesDialog() {
        Path folder = currentImageFolder();
        if (folder == null) {
            JFileChooser chooser = new JFileChooser(
                    agentLauncher == null ? new File(".") : new File(agentLauncher.getAgentWorkspace()));
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Choose folder to browse");
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            folder = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        }
        BrowseFilesDialog dialog = new BrowseFilesDialog(
                SwingUtilities.getWindowAncestor(this),
                folder,
                activeSessionId(),
                activeSession());
        dialog.setVisible(true);
    }

    private Path currentImageFolder() {
        try {
            ImagePlus image = WindowManager.getCurrentImage();
            if (image == null) {
                return null;
            }
            FileInfo info = image.getOriginalFileInfo();
            if (info == null || info.directory == null
                    || info.directory.trim().isEmpty()) {
                return null;
            }
            return new File(info.directory).toPath().toAbsolutePath().normalize();
        } catch (Throwable t) {
            return null;
        }
    }

    private AgentSession activeSession() {
        synchronized (liveSessions) {
            for (int i = liveSessions.size() - 1; i >= 0; i--) {
                AgentSession session = liveSessions.get(i);
                if (session != null) {
                    return session;
                }
            }
        }
        return null;
    }

    private String activeSessionId() {
        String id = agentLauncher == null ? "" : agentLauncher.lastSessionId();
        return id == null || id.trim().isEmpty() ? "default" : id.trim();
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
            recomputeRecommendation();
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
        recomputeRecommendation();
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

    private AgentLauncher.AgentInfo findDetectedAgentByCommand(String command) {
        if (command == null) {
            return null;
        }
        for (AgentLauncher.AgentInfo agent : detectedAgents) {
            if (agent != null && command.equals(agent.command)) {
                return agent;
            }
        }
        return null;
    }

    private AgentLauncher.AgentInfo selectedCliAgent() {
        if (settings.useMultiProviderPicker) {
            String provider = settings.selectedProvider == null
                    ? ""
                    : settings.selectedProvider.trim();
            if (!"cli".equals(provider)) {
                return null;
            }
            return findDetectedAgentByCommand(settings.selectedModelId);
        }

        String selected = agentSelector == null
                ? settings.getSelectedAgentName()
                : (String) agentSelector.getSelectedItem();
        return findDetectedAgent(selected);
    }

    private void rememberCliAgentSelection(AgentLauncher.AgentInfo agent) {
        if (agent == null) {
            return;
        }
        settings.selectedAgentName = agent.name == null || agent.name.trim().isEmpty()
                ? agent.command
                : agent.name;
        if (settings.useMultiProviderPicker) {
            settings.selectedProvider = "cli";
            settings.selectedModelId = agent.command;
        }
        settings.save();
        if (modelPicker != null) {
            modelPicker.refreshCaption();
        }
        chatView.refreshInputState();
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
                                        + " â€” see ImageJ Log.");
                        return;
                    }
                    handleLaunchedSession(session.info(), mode, session);
                } catch (Exception ex) {
                    reportLaunchFailure(entry.displayName(), ex);
                }
            }
        }.execute();
    }

    private void launchAgentAsync(final AgentLauncher.AgentInfo agent) {
        launchAgentAsync(agent, AgentLauncher.SessionAction.NEW_SESSION);
    }

    private void launchAgentAsync(final AgentLauncher.AgentInfo agent,
                                  final AgentLauncher.SessionAction sessionAction) {
        if (agent == null || agentLauncher == null) {
            return;
        }
        final AgentLauncher.SessionAction action = sessionAction == null
                ? AgentLauncher.SessionAction.NEW_SESSION
                : sessionAction;
        if (action == AgentLauncher.SessionAction.RESUME_LATEST
                && !agentLauncher.supportsResumeLatest(agent)) {
            chatView.appendMessage("assistant",
                    "Resume is not available for " + agent.name + ".");
            return;
        }
        final AgentLauncher.Mode mode = settings.agentEmbeddedTerminal
                ? AgentLauncher.Mode.EMBEDDED
                : AgentLauncher.Mode.EXTERNAL;

        rememberCliAgentSelection(agent);
        final boolean resume = action == AgentLauncher.SessionAction.RESUME_LATEST;
        chatView.appendMessage("assistant",
                (resume ? "Resuming latest " : "Launching ") + agent.name + "...");
        new SwingWorker<AgentSession, Void>() {
            @Override
            protected AgentSession doInBackground() {
                return agentLauncher.launch(agent, mode, action);
            }

            @Override
            protected void done() {
                try {
                    AgentSession session = get();
                    handleLaunchedSession(agent, mode, session,
                            resume ? "Resumed" : "Launched");
                } catch (Exception ex) {
                    reportLaunchFailure(agent.name, ex, resume ? "resume" : "launch");
                }
            }
        }.execute();
    }

    private void reportLaunchFailure(String displayName, Exception ex) {
        reportLaunchFailure(displayName, ex, "launch");
    }

    private void reportLaunchFailure(String displayName, Exception ex, String verb) {
        Throwable cause = ex == null ? null : ex.getCause();
        if (cause == null) {
            cause = ex;
        }
        String message = cause == null || cause.getMessage() == null
                ? String.valueOf(cause)
                : cause.getMessage();
        chatView.appendMessage("assistant",
                "Failed to " + verb + " " + displayName + ": " + message);
        if (cause instanceof PostureViolation) {
            JOptionPane.showMessageDialog(
                    AiRootPanel.this,
                    message,
                    "Privacy Posture",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void handleLaunchedSession(AgentLauncher.AgentInfo agent,
                                       AgentLauncher.Mode mode,
                                       AgentSession session) {
        handleLaunchedSession(agent, mode, session, "Launched");
    }

    private void handleLaunchedSession(AgentLauncher.AgentInfo agent,
                                       AgentLauncher.Mode mode,
                                       AgentSession session,
                                       String verb) {
        String pastTense = verb == null || verb.trim().isEmpty()
                ? "Launched"
                : verb.trim();
        String failureVerb = "Resumed".equalsIgnoreCase(pastTense)
                ? "resume"
                : "launch";
        if (session == null) {
            chatView.appendMessage("assistant", "Failed to " + failureVerb + " " + agent.name
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
            chatView.appendMessage("assistant", pastTense + " " + agent.name
                    + " inside the plugin window.");
        } else {
            if (mode == AgentLauncher.Mode.EMBEDDED && session instanceof ExternalAgentSession) {
                ExternalAgentSession external = (ExternalAgentSession) session;
                if (external.isFallbackLaunch()) {
                    showTerminalFallbackNotice(external.notice());
                }
            }
            chatView.appendMessage("assistant", pastTense + " " + agent.name
                    + " in: " + agentLauncher.getAgentWorkspace());
            showChatCard();
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
                        + "ImageJAI targets Java 11. The embedded terminal backend "
                        + "(pty4j / JediTerm) is loaded only on Java 11 or newer.\n\n"
                        + "On older runtimes the selected agent still launches in a "
                        + "normal terminal window. Upgrade Fiji's Java runtime to "
                        + "Java 11+ to use the embedded terminal.",
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
                showWelcomeCard();
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
        setHeaderState(false);
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
        setHeaderState(false);
        cardLayout.show(cards, CARD_CHAT);
        applyFrameSize();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                chatView.requestFocusInWindow();
            }
        });
    }

    /**
     * Show the Welcome "home" card. Shown on first open and whenever no agent
     * session is live (the rule that governs welcome vs working state).
     */
    private void showWelcomeCard() {
        currentCard = CARD_WELCOME;
        setHeaderState(true);
        cardLayout.show(cards, CARD_WELCOME);
        applyFrameSize();
        recomputeRecommendation();
    }

    /**
     * Launch the recommended agent from the Welcome CTA. The built-in Local
     * Assistant has no process, so it just reveals the chat surface; a detected
     * CLI agent goes through the normal embedded/external launch path.
     */
    private void launchRecommended() {
        AgentRecommender.Recommendation rec = recommendation;
        if (rec == null || rec.isLocalAssistant()) {
            settings.setSelectedAgentName(AgentLauncher.LOCAL_ASSISTANT_NAME);
            chatView.refreshInputState();
            showChatCard();
            return;
        }
        settings.setSelectedAgentName(rec.agent.name);
        chatView.refreshInputState();
        launchAgentAsync(rec.agent);
    }

    /**
     * Open a picker so the user can launch a specific assistant from the Welcome
     * card. Built as a self-anchored popup over the on-screen Welcome panel: the
     * header's model picker sits on a hidden CardLayout card while Welcome is
     * showing, so popping relative to it would throw IllegalComponentStateException.
     */
    private void openAgentPicker() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem local = new JMenuItem(AgentLauncher.LOCAL_ASSISTANT_NAME + " - built-in, offline");
        local.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                settings.setSelectedAgentName(AgentLauncher.LOCAL_ASSISTANT_NAME);
                chatView.refreshInputState();
                showChatCard();
            }
        });
        menu.add(local);

        if (detectedAgents != null && !detectedAgents.isEmpty()) {
            menu.addSeparator();
            for (final AgentLauncher.AgentInfo agent : detectedAgents) {
                JMenuItem item = new JMenuItem(agent.name);
                if (agent.description != null && !agent.description.trim().isEmpty()) {
                    item.setToolTipText(agent.description);
                }
                item.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        settings.setSelectedAgentName(agent.name);
                        chatView.refreshInputState();
                        launchAgentAsync(agent);
                    }
                });
                menu.add(item);

                if (agentLauncher != null && agentLauncher.supportsResumeLatest(agent)) {
                    JMenuItem resume = new JMenuItem("Resume latest " + agent.name + " session");
                    resume.setToolTipText("Resume the latest saved session for this agent workspace.");
                    resume.addActionListener(new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            launchAgentAsync(agent, AgentLauncher.SessionAction.RESUME_LATEST);
                        }
                    });
                    menu.add(resume);
                }
            }
        }

        if (settings.useMultiProviderPicker) {
            menu.addSeparator();
            JMenuItem more = new JMenuItem("More models & providers...");
            more.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    openSettings();
                }
            });
            menu.add(more);
        }

        // Anchor to a component that is actually on-screen, or no-op.
        java.awt.Component anchor = welcomePanel != null && welcomePanel.isShowing()
                ? welcomePanel
                : (isShowing() ? this : null);
        if (anchor != null) {
            menu.show(anchor, Math.max(0, anchor.getWidth() / 2 - 70), anchor.getHeight() / 2);
        }
    }

    /**
     * Recompute the recommended agent (posture-aware) and push it, the CTA
     * caption, and the posture footer into the Welcome card. Cheap; safe to call
     * after agent detection, on posture change, and on return to the home card.
     */
    private void recomputeRecommendation() {
        if (welcomePanel == null) {
            return;
        }
        boolean onPremises =
                PostureController.getInstance().current() == PrivacyPosture.ON_PREMISES;
        AgentRecommender.Recommendation rec = AgentRecommender.recommend(
                detectedAgents, settings.getSelectedAgentName(), onPremises);
        recommendation = rec;
        boolean noAgents = detectedAgents == null || detectedAgents.isEmpty();
        welcomePanel.setStartCaption(rec.isLocalAssistant() && noAgents
                ? "▶   Start with the built-in assistant"
                : "▶   Start analysing");
        welcomePanel.setRecommendation(rec.displayName, rec.reason);
        welcomePanel.setPostureText(postureFooterText());
    }

    private String postureFooterText() {
        PrivacyPosture posture = PostureController.getInstance().current();
        if (posture == PrivacyPosture.ON_PREMISES) {
            return "🔒 On-premises — your data stays on this machine";
        }
        if (posture == PrivacyPosture.PSEUDONYMISED) {
            return "🔒 Pseudonymised — identifiers tokenised before send";
        }
        return "Standard — cloud agents allowed";
    }

    private void applyFrameSize() {
        if (frame == null) {
            return;
        }
        applyingFrameSize = true;
        // Never open smaller than the frame's minimum, so a stale persisted size
        // (or the 240px parse floor) can't re-clip the content on a card switch.
        Dimension target = savedSizeFor(currentCard);
        Dimension min = frame.getMinimumSize();
        if (min != null) {
            target = new Dimension(
                    Math.max(target.width, min.width),
                    Math.max(target.height, min.height));
        }
        frame.setSize(target);
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
            applyConfirmedSettings(dialog.backendSettingsChanged());
        }
    }

    private void openSettingsForProvider(String providerId) {
        Window window = SwingUtilities.getWindowAncestor(this);
        Frame parent = window instanceof Frame ? (Frame) window : null;
        SettingsDialog dialog = new SettingsDialog(parent, settings);
        dialog.openWithProvider(providerId);
        if (dialog.wasConfirmed()) {
            applyConfirmedSettings(dialog.backendSettingsChanged());
        }
    }

    void applyConfirmedSettings(boolean backendChanged) {
        settings.save();
        if (backendChanged) notifyBackendRefresh();
        refreshProfileSwitcher();
    }

    private void notifyBackendRefresh() {
        Runnable listener = backendRefreshListener;
        if (listener != null) listener.run();
    }

    private void runFirstRunFlipNoticeIfNeeded() {
        if (settings.useMultiProviderPicker && !settings.multiProviderFlipNoticeShown) {
            IJ.log("[ImageJAI] Multi-provider model picker is now the default. "
                    + "Old picker available in Settings â†’ Multi-Provider tab â†’ "
                    + "Use legacy picker.");
            settings.multiProviderFlipNoticeShown = true;
            settings.save();
        }
    }

    /**
     * Build the {@link ModelPickerButton.RefreshTask} wired to production
     * {@link ProviderDiscovery} + {@link ModelsCache} + {@link MergeFunction}.
     *
     * <p>Phase G acceptance Â§3 calls for the dropdown's â†» button to fan out
     * over the fifteen provider {@code /models} endpoints with a 4 s
     * per-provider budget (06 Â§4.4), persist successful results to the 24 h
     * cache, fall back to cache for failed providers, and merge curated +
     * live + user overrides into the new dropdown contents.
     *
     * <p>Failed providers are reported via
     * {@link ModelPickerButton.RefreshOutcome#failedProviders} so the header
     * strip can render the "Couldn't reach â€¦ â€” using cached list" affordance
     * (05 Â§8.4) without a JOptionPane interrupt.
     */
    private ModelPickerButton.RefreshTask buildRefreshTask() {
        return new ModelPickerButton.RefreshTask() {
            @Override
            public ModelPickerButton.RefreshOutcome refresh() {
                return runRefreshOffEdt();
            }
        };
    }

    private ModelPickerButton.RefreshOutcome runRefreshOffEdt() {
        ProviderRegistry oldRegistry = providerRegistry == null
                ? ProviderRegistry.empty()
                : providerRegistry;

        Map<String, String> creds = readProviderCredentials();
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(creds);
        ProviderDiscovery discovery = new ProviderDiscovery(
                endpoints, ProviderDiscovery.defaultFetcher());

        ModelsCache cache = new ModelsCache(modelsCacheRoot());
        Instant fetchedAt = Instant.now();
        Duration timeout = Duration.ofMillis(4000);

        Map<String, MergeFunction.LiveResult> live =
                new LinkedHashMap<String, MergeFunction.LiveResult>();
        List<String> failed = new ArrayList<String>();
        Map<String, MergeFunction.LiveResult> discovered = discovery.discoverAll(timeout);

        for (String providerId : endpoints.keySet()) {
            MergeFunction.LiveResult result = discovered.get(providerId);
            if (result == null) {
                result = MergeFunction.LiveResult.failure("discovery returned no result");
            }
            if (result.successful()) {
                try {
                    cache.write(providerId, fetchedAt,
                            endpoints.get(providerId).url(), result.modelIds());
                } catch (Exception ex) {
                    IJ.log("[ImageJAI] Failed to write cache for "
                            + providerId + ": " + ex.getMessage());
                }
                live.put(providerId, result);
                // Discovery succeeded â€” clear any stale error so the âœ— status
                // icon reverts to âœ“ on the next render.
                settings.setLastError(providerId, null);
            } else {
                ModelsCache.Snapshot snap = cache.read(providerId);
                if (snap != null) {
                    Set<String> ids = new LinkedHashSet<String>(snap.modelIds());
                    live.put(providerId, MergeFunction.LiveResult.success(ids));
                } else {
                    live.put(providerId, result);
                }
                failed.add(providerId);
                // Persist the discovery failure reason so CachedErrorDialog
                // (Phase E Â§4.3) can render a meaningful body when the user
                // clicks the âœ— status icon. Settings.setLastError debounces
                // the write â€” keys never reach the JSON file because credentials
                // live in <imagej-ai>/secrets/<provider>.env, not config.json.
                String reason = discovery.lastErrorFor(providerId);
                settings.setLastError(providerId,
                        reason == null || reason.isEmpty()
                                ? "Provider did not respond within "
                                        + timeout.toMillis() + " ms"
                                : reason);
            }
        }

        List<ModelEntry> curated = loadCuratedEntries();
        Map<String, ModelsLocalLoader.Override> overrides = loadUserOverrides();
        LocalDate today = LocalDate.now();
        List<ModelEntry> merged = MergeFunction.merge(curated, live, overrides, today);
        List<ModelEntry> visible = MergeFunction.applyVisibility(merged, overrides, today);
        ProviderRegistry newRegistry = ProviderRegistry.fromMerged(visible, today);
        newRegistry = applyProviderStatuses(newRegistry, live, failed);
        // Re-inject the CLI agents the merge layer doesn't know about, so a
        // manual refresh doesn't drop them from the default-on dropdown.
        ProviderEntry cliProvider = buildCliProviderEntry(
                agentLauncher != null ? agentLauncher.detectAgents() : null);
        if (cliProvider != null) {
            newRegistry = newRegistry.withProvider(cliProvider);
        }

        int newCount = 0;
        int removedCount = 0;
        Set<String> oldKeys = collectModelKeys(oldRegistry);
        Set<String> newKeys = collectModelKeys(newRegistry);
        for (String k : newKeys) {
            if (!oldKeys.contains(k)) newCount++;
        }
        for (String k : oldKeys) {
            if (!newKeys.contains(k)) removedCount++;
        }

        // Update the cached registry on the panel so launch-button lookups
        // and tier-change checks see the same view as the popup.
        providerRegistry = newRegistry;
        return new ModelPickerButton.RefreshOutcome(
                newRegistry, newCount, removedCount, failed);
    }

    private ProviderRegistry applyProviderStatuses(ProviderRegistry base,
                                                   Map<String, MergeFunction.LiveResult> live,
                                                   List<String> failedProviders) {
        ProviderRegistry out = base == null ? ProviderRegistry.empty() : base;
        if (live == null) {
            return out;
        }
        Set<String> failed = new LinkedHashSet<String>();
        if (failedProviders != null) {
            failed.addAll(failedProviders);
        }
        for (Map.Entry<String, MergeFunction.LiveResult> e : live.entrySet()) {
            String providerId = e.getKey();
            ProviderEntry current = out.provider(providerId);
            if (current == null) {
                continue;
            }
            MergeFunction.LiveResult result = e.getValue();
            ProviderEntry.Status status = current.status();
            String lastError = current.lastError();
            if (failed.contains(providerId)) {
                boolean configured = settings != null && settings.hasCredentialsFor(providerId);
                status = configured
                        ? ProviderEntry.Status.UNAVAILABLE
                        : ProviderEntry.Status.NEEDS_SETUP;
                String reason = result == null ? "" : result.failureReason();
                if ((reason == null || reason.isEmpty()) && settings != null) {
                    reason = settings.lastErrorFor(providerId);
                }
                lastError = reason == null ? "" : reason;
            } else if (result != null && result.successful()) {
                status = ProviderEntry.Status.READY;
                lastError = "";
            }
            out = out.refreshProvider(providerId, new ProviderEntry(
                    providerId, current.displayName(), status, lastError, current.models()));
        }
        return out;
    }

    private Map<String, String> readProviderCredentials() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        imagejai.ui.installer.ProviderCredentials store;
        try {
            store = settings.providerCredentials();
        } catch (Exception ex) {
            return out;
        }
        if (store == null) {
            return out;
        }
        for (Map.Entry<String, String> e
                : imagejai.ui.installer.ProviderCredentials
                        .ENV_VAR_FOR_PROVIDER.entrySet()) {
            String providerId = e.getKey();
            String envName = e.getValue();
            try {
                Map<String, String> entries = store.read(providerId);
                String value = entries.get(envName);
                if (value != null && !value.isEmpty()) {
                    out.put(providerId, value);
                }
            } catch (Exception ignored) {
                // Missing or unreadable env file â€” provider just gets no auth header.
            }
        }
        return out;
    }

    private java.nio.file.Path modelsCacheRoot() {
        return imagejai.config.Settings.getConfigDir()
                .resolve("cache").resolve("models");
    }

    private List<ModelEntry> loadCuratedEntries() {
        try (InputStream in = ProviderRegistry.class
                .getResourceAsStream(ProviderRegistry.BUNDLED_RESOURCE)) {
            if (in == null) {
                IJ.log("[ImageJAI] Bundled model registry is missing; model list is incomplete");
                return java.util.Collections.emptyList();
            }
            return ModelsYamlLoader.loadFromStream(in);
        } catch (Exception ex) {
            IJ.log("[ImageJAI] Bundled model registry failed to load ("
                    + ex.getClass().getSimpleName() + ")");
            return java.util.Collections.emptyList();
        }
    }

    private Map<String, ModelsLocalLoader.Override> loadUserOverrides() {
        try {
            ModelsLocalLoader loader = new ModelsLocalLoader(
                    ModelsLocalLoader.resolveDefaultPath());
            Map<String, ModelsLocalLoader.Override> loaded = loader.loadAsMap();
            if (!loader.lastError().isEmpty()) {
                IJ.log("[ImageJAI] " + loader.lastError());
            }
            return loaded;
        } catch (Exception ex) {
            IJ.log("[ImageJAI] Model overrides failed to load ("
                    + ex.getClass().getSimpleName() + ")");
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * Extra environment merged into every native/proxy provider-agent launch.
     * Carries the live LiteLLM proxy port so the Python client targets the
     * actual sidecar port (verifier #2), and the active budget ceiling so the
     * native paths enforce it in-loop (verifier #1). Evaluated at launch time.
     */
    private Map<String, String> buildLaunchEnv() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        int port = imagejai.ImageJAIPlugin.liteLlmProxyPort();
        if (port > 0) {
            env.put("IMAGEJAI_LITELLM_PORT", Integer.toString(port));
        }
        if (settings != null && settings.budgetCeilingEnabled
                && settings.budgetCeilingUsd > 0.0) {
            env.put("IMAGEJAI_BUDGET_CEILING_USD",
                    Double.toString(settings.budgetCeilingUsd));
        }
        return env;
    }

    /** Persist a pin toggle to models_local.yaml and re-apply it immediately. */
    private void persistPin(ModelEntry entry, boolean nowPinned) {
        if (entry == null) {
            return;
        }
        try {
            ModelsLocalLoader loader = new ModelsLocalLoader(
                    ModelsLocalLoader.resolveDefaultPath());
            loader.setPinned(entry.providerId(), entry.modelId(), nowPinned);
        } catch (Exception ex) {
            IJ.log("[ImageJAI] Could not persist pin for " + entry.providerId()
                    + "/" + entry.modelId() + ": " + ex.getMessage());
        }
        applyStartupOverrides();
    }

    /**
     * Re-merge the bundled curated catalogue with persisted user overrides
     * (pins/hides) and push the result to the picker — no network. Makes pinned
     * favourites apply on startup and immediately after a pin toggle (#6).
     */
    private void applyStartupOverrides() {
        Map<String, ModelsLocalLoader.Override> overrides = loadUserOverrides();
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        List<ModelEntry> curated = loadCuratedEntries();
        if (curated.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        Map<String, MergeFunction.LiveResult> noLive =
                java.util.Collections.<String, MergeFunction.LiveResult>emptyMap();
        List<ModelEntry> merged = MergeFunction.merge(curated, noLive, overrides, today);
        List<ModelEntry> visible = MergeFunction.applyVisibility(merged, overrides, today);
        ProviderRegistry reg = ProviderRegistry.fromMerged(visible, today);
        ProviderEntry cliProvider = buildCliProviderEntry(
                agentLauncher != null ? agentLauncher.detectAgents() : null);
        if (cliProvider != null) {
            reg = reg.withProvider(cliProvider);
        }
        providerRegistry = reg;
        if (modelPicker != null) {
            modelPicker.setRegistry(reg);
        }
    }

    /**
     * Run a one-off discovery refresh in the background at startup (honours
     * {@code Settings.refreshOnStartup}, verifier #9), applying the outcome to
     * the picker on the EDT. Bundled models stay visible until it completes.
     */
    private void triggerStartupRefresh() {
        if (modelPicker != null) modelPicker.refreshAsync();
    }

    private static Set<String> collectModelKeys(ProviderRegistry registry) {
        Set<String> keys = new LinkedHashSet<String>();
        if (registry == null) {
            return keys;
        }
        for (imagejai.engine.picker.ProviderEntry provider : registry.providers()) {
            for (ModelEntry entry : provider.models()) {
                keys.add(entry.providerId() + " " + entry.modelId());
            }
        }
        return keys;
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
