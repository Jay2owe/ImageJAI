package imagejai;

import ij.IJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import imagejai.config.Constants;
import imagejai.config.Settings;
import imagejai.engine.AgentLauncher;
import imagejai.engine.CommandEngine;
import imagejai.engine.DialogWatcher;
import imagejai.engine.EventBus;
import imagejai.engine.ExplorationEngine;
import imagejai.engine.ImageMonitor;
import imagejai.engine.LiteLlmProxyService;
import imagejai.engine.PipelineBuilder;
import imagejai.engine.PostureController;
import imagejai.engine.StateInspector;
import imagejai.engine.TCPCommandServer;
import imagejai.engine.budget.BudgetCeilingTracker;
import imagejai.engine.safeMode.SafeModeIndicator;
import imagejai.ui.AiRootPanel;
import imagejai.ui.ChatPanel;
import imagejai.ui.ChatPanelController;
import imagejai.ui.ChatSurface;
import imagejai.ui.PostureBanner;
import imagejai.ui.SettingsDialog;
import imagejai.ui.picker.BudgetCeilingDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;

/**
 * Main entry point for the ImageJ AI Assistant plugin.
 * Registered as a SciJava Command -- appears in Plugins menu.
 */
@Plugin(type = Command.class, menuPath = "Plugins>AI Assistant")
public class ImageJAIPlugin implements Command {

    private static JFrame chatFrame;
    private static AiRootPanel rootPanel;
    private static ChatPanel chatPanel;
    private static ConversationLoop conversationLoop;
    private static TCPCommandServer tcpServer;
    private static ImageMonitor imageMonitor;
    private static DialogWatcher dialogWatcher;
    private static LiteLlmProxyService liteLlmProxyService;
    private static BudgetCeilingTracker budgetCeilingTracker;
    private static boolean budgetTrackerRegistered;
    private static volatile Settings budgetSettings;
    private static volatile boolean budgetDialogOpen;
    private static boolean terminalFontsRegistered;
    private static boolean shutdownHookRegistered;

    private static final BudgetCeilingTracker.BreachListener BUDGET_BREACH_LISTENER =
            new BudgetCeilingTracker.BreachListener() {
                @Override
                public void onCeilingBreached(double totalUsd, double ceilingUsd) {
                    showBudgetCeilingDialog(totalUsd, ceilingUsd);
                }
            };

    @Override
    public void run() {
        // Ensure we're on the EDT
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::run);
            return;
        }

        registerBundledTerminalFonts();

        // If already open, bring to front
        if (chatFrame != null && chatFrame.isDisplayable()) {
            chatFrame.toFront();
            chatFrame.requestFocus();
            return;
        }

        // Load settings
        Settings settings = Settings.load();
        final PostureController postureController = PostureController.getInstance();
        postureController.configure(settings);
        postureController.setPresenter(new PostureBanner(new PostureBanner.OwnerProvider() {
            @Override
            public Frame owner() {
                return chatFrame != null ? chatFrame : IJ.getInstance();
            }
        }));

        boolean localAssistantSelected =
                AgentLauncher.LOCAL_ASSISTANT_NAME.equals(settings.getSelectedAgentName());

        // First-run: show settings dialog (if no key and not just TCP)
        if (settings.isFirstRun() && !localAssistantSelected) {
            Frame parent = IJ.getInstance();
            SettingsDialog dialog = new SettingsDialog(parent, settings);
            dialog.setVisible(true);
            if (!dialog.wasConfirmed()) {
                return; // User cancelled
            }
            settings.save();
        }

        // Create root panel and wire conversation loop
        rootPanel = new AiRootPanel(settings);
        chatPanel = new ChatPanel(rootPanel.chatView());
        
        if (settings.hasApiKey()) {
            conversationLoop = new ConversationLoop(rootPanel, settings);
            rootPanel.addChatListener(conversationLoop);
        } else if (!localAssistantSelected) {
            rootPanel.appendMessage("assistant", "AI Assistant is running in TCP-only mode. " +
                    "To use chat features, please configure an API key in Settings.");
        }

        // Set up agent launcher â€” find the agent workspace directory
        String agentWorkspace = findAgentWorkspace();
        if (agentWorkspace != null) {
            startLiteLlmProxy(agentWorkspace, settings);
            rootPanel.setAgentLauncher(new AgentLauncher(agentWorkspace, settings.tcpPort, settings));
        }

        // Start TCP command server if enabled
        if (settings.tcpServerEnabled) {
            // Stage 03 (embedded-agent-widget): pick the first free port in the
            // 7746..7750 window so two Fiji instances don't crash on a clash.
            // The chosen port is written back to settings.tcpPort so rail-
            // hotline buttons and any spawned agent env read the right value.
            int chosen = findFreeTcpPort(settings.tcpPort);
            if (chosen != settings.tcpPort) {
                IJ.log("[ImageJAI-TCP] :" + settings.tcpPort
                        + " busy; falling back to :" + chosen);
                settings.tcpPort = chosen;
            }
            startTcpServer(settings, rootPanel, rootPanel.chatController());
        }

        // Phase 2: start the event-bus publishers so dialog / image / memory
        // / results events flow to any connected subscribers, independent of
        // whether the chat panel or TCP is active.
        startEventPublishers();

        // Stage 07 (docs/safe_mode_v2/07_status-indicator-ui.md): mount the
        // toolbar dot + status-bar overlay once Fiji is up. Idempotent and
        // headless-safe so a CLI / test invocation that already started the
        // bus publishers above can call this without checking the mode.
        // The Stage-02 master toggle on AiRootPanel reaches the indicator
        // via {@link AiRootPanel#setSafeModeIndicator}.
        try {
            SafeModeIndicator indicator = SafeModeIndicator.getInstance();
            indicator.setMasterEnabled(settings.safeModeEnabled);
            indicator.installOnStartup();
            if (rootPanel != null) {
                rootPanel.setSafeModeIndicator(indicator);
            }
        } catch (Throwable t) {
            IJ.log("[ImageJAI-SafeMode] indicator install failed: " + t.getMessage());
        }

        chatFrame = new JFrame(Constants.PLUGIN_NAME + " v" + Constants.VERSION);
        chatFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        chatFrame.getContentPane().add(rootPanel);
        chatFrame.setSize(420, 600);
        chatFrame.setMinimumSize(new Dimension(350, 400));
        rootPanel.setFrame(chatFrame);
        registerAgentShutdownHook();

        // Clean up resources on close
        chatFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                // Stop TCP server if running
                if (tcpServer != null) {
                    tcpServer.stop();
                    tcpServer = null;
                }
                // Stop event publishers
                if (imageMonitor != null) {
                    imageMonitor.stop();
                    imageMonitor = null;
                }
                if (dialogWatcher != null) {
                    dialogWatcher.stop();
                    dialogWatcher = null;
                }
                if (rootPanel != null) {
                    rootPanel.shutdownSessions();
                }
                // Null out static references so they can be GC'd
                rootPanel = null;
                chatPanel = null;
                chatFrame = null;
                conversationLoop = null;
                System.out.println("[ImageJAI] Window closed, resources released.");
            }
        });

        // Position next to ImageJ window
        Frame ijFrame = IJ.getInstance();
        if (ijFrame != null) {
            Rectangle bounds = ijFrame.getBounds();
            chatFrame.setLocation(bounds.x + bounds.width + 10, bounds.y);
        }

        chatFrame.setVisible(true);
    }

    static synchronized void registerBundledTerminalFonts() {
        if (terminalFontsRegistered) {
            return;
        }

        try {
            registerFontResource("/fonts/JetBrainsMono-Regular.ttf");
            registerFontResource("/fonts/NotoEmoji-Regular.ttf");
            terminalFontsRegistered = true;
            IJ.log("[ImageJAI-Term] Registered bundled terminal fonts");
        } catch (Exception e) {
            IJ.log("[ImageJAI-Term] Failed to register bundled terminal fonts: " + e.getMessage());
        }
    }

    private static void registerFontResource(String resourcePath)
            throws FontFormatException, IOException {
        try (InputStream in = ImageJAIPlugin.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("missing resource " + resourcePath);
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        }
    }

    private static synchronized void registerAgentShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                AiRootPanel panel = rootPanel;
                if (panel != null) {
                    panel.shutdownSessions();
                }
                LiteLlmProxyService proxyService = liteLlmProxyService;
                if (proxyService != null) {
                    proxyService.shutdown();
                }
            }
        }, "ImageJAI-agent-shutdown-hook"));
        shutdownHookRegistered = true;
    }

    private static synchronized void startLiteLlmProxy(String agentWorkspace, Settings settings) {
        budgetSettings = settings;
        if (liteLlmProxyService == null) {
            liteLlmProxyService = new LiteLlmProxyService(agentWorkspace);
        }
        if (budgetCeilingTracker == null) {
            budgetCeilingTracker = new BudgetCeilingTracker(
                    settings != null && settings.budgetCeilingEnabled,
                    settings == null
                            ? BudgetCeilingTracker.DEFAULT_CEILING_USD
                            : settings.budgetCeilingUsd);
            budgetCeilingTracker.addListener(BUDGET_BREACH_LISTENER);
        } else {
            budgetCeilingTracker.setEnabled(settings != null && settings.budgetCeilingEnabled);
            if (settings != null) {
                budgetCeilingTracker.setCeilingUsd(settings.budgetCeilingUsd);
            }
        }
        budgetCeilingTracker.resetSession();
        if (!budgetTrackerRegistered) {
            liteLlmProxyService.addCostHeaderListener(budgetCeilingTracker);
            budgetTrackerRegistered = true;
        }
        liteLlmProxyService.startAsync();
    }

    private static synchronized boolean markBudgetDialogOpen() {
        if (budgetDialogOpen) {
            return false;
        }
        budgetDialogOpen = true;
        return true;
    }

    private static synchronized void markBudgetDialogClosed() {
        budgetDialogOpen = false;
    }

    private static void showBudgetCeilingDialog(final double totalUsd, final double ceilingUsd) {
        if (!markBudgetDialogOpen()) {
            IJ.log("[ImageJAI-Budget] ceiling already breached; dialog is already open");
            return;
        }
        Runnable show = new Runnable() {
            @Override
            public void run() {
                try {
                    Frame owner = chatFrame != null ? chatFrame : IJ.getInstance();
                    BudgetCeilingDialog dialog = new BudgetCeilingDialog(owner, totalUsd, ceilingUsd);
                    BudgetCeilingDialog.Result result = dialog.showAndAwait();
                    handleBudgetDialogResult(result, dialog.newCeilingUsd(), totalUsd);
                } catch (Throwable t) {
                    IJ.log("[ImageJAI-Budget] could not show budget dialog: " + t.getMessage());
                } finally {
                    markBudgetDialogClosed();
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }

    private static void handleBudgetDialogResult(BudgetCeilingDialog.Result result,
                                                 double requestedCeilingUsd,
                                                 double totalUsd) {
        BudgetCeilingTracker tracker = budgetCeilingTracker;
        Settings settings = budgetSettings;
        AiRootPanel panel = rootPanel;
        if (result == BudgetCeilingDialog.Result.RESUME) {
            double next = requestedCeilingUsd > totalUsd
                    ? requestedCeilingUsd
                    : Math.max(totalUsd * 2.0, BudgetCeilingTracker.DEFAULT_CEILING_USD);
            if (tracker != null) {
                tracker.setCeilingUsd(next);
            }
            if (settings != null) {
                settings.budgetCeilingEnabled = true;
                settings.budgetCeilingUsd = next;
                settings.save();
            }
            String message = String.format("Budget ceiling raised to $%.2f for this session.", next);
            IJ.log("[ImageJAI-Budget] " + message);
            if (panel != null) {
                panel.appendMessage("assistant", message);
            }
            return;
        }
        if (result == BudgetCeilingDialog.Result.SWITCH_FREE) {
            String message = "Budget ceiling reached. Pick a free model from the model picker to continue without paid calls.";
            IJ.log("[ImageJAI-Budget] " + message);
            if (panel != null) {
                panel.appendMessage("assistant", message);
            }
            return;
        }
        String message = "Budget ceiling reached. Close the current agent terminal to end the paid session.";
        IJ.log("[ImageJAI-Budget] " + message);
        if (panel != null) {
            panel.appendMessage("assistant", message);
        }
    }

    /**
     * Get the active ChatPanel instance (for use by conversation loop in Phase 2).
     *
     * @return the current ChatPanel, or null if the window is not open
     */
    public static ChatPanel getChatPanel() {
        return chatPanel;
    }

    /**
     * Get the active root panel.
     *
     * @return the current AiRootPanel, or null if the window is not open
     */
    public static AiRootPanel getRootPanel() {
        return rootPanel;
    }

    /**
     * Get the active chat frame.
     *
     * @return the current JFrame, or null if the window is not open
     */
    public static JFrame getChatFrame() {
        return chatFrame;
    }

    /**
     * Find the agent workspace directory. Looks for the 'agent' subdirectory
     * next to a checked-out ImageJAI project. Users can override discovery with
     * IMAGEJAI_AGENT_WORKSPACE or -Dimagejai.agent.workspace.
     */
    private static String findAgentWorkspace() {
        try {
            String override = System.getProperty("imagejai.agent.workspace");
            if (override == null || override.trim().isEmpty()) {
                override = System.getenv("IMAGEJAI_AGENT_WORKSPACE");
            }
            if (override != null && !override.trim().isEmpty()) {
                java.io.File dir = new java.io.File(override.trim());
                if (isAgentWorkspace(dir)) {
                    return dir.getAbsolutePath();
                }
            }

            java.io.File codeLocation = codeLocationDirectory();
            java.io.File home = new java.io.File(System.getProperty("user.home", ""));
            java.io.File cwd = new java.io.File(System.getProperty("user.dir", "."));
            java.io.File[] candidates = {
                new java.io.File(cwd, "agent"),
                new java.io.File(home, "ImageJAI/agent"),
                new java.io.File(codeLocation, "agent"),
                new java.io.File(parent(codeLocation), "agent"),
                new java.io.File(parent(parent(codeLocation)), "agent")
            };
            for (java.io.File candidate : candidates) {
                if (isAgentWorkspace(candidate)) {
                    return candidate.getAbsolutePath();
                }
            }

            IJ.log("[ImageJAI] Agent workspace not found. External CLI launchers "
                    + "are disabled until IMAGEJAI_AGENT_WORKSPACE points to an "
                    + "ImageJAI agent directory.");
            return null;
        } catch (Exception e) {
            System.err.println("[ImageJAI] Could not determine agent workspace: " + e.getMessage());
            return null;
        }
    }

    private static java.io.File codeLocationDirectory() {
        try {
            java.net.URL location = ImageJAIPlugin.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            java.io.File file = new java.io.File(location.toURI());
            return file.isFile() ? file.getParentFile() : file;
        } catch (Exception e) {
            return new java.io.File(System.getProperty("user.dir", "."));
        }
    }

    private static java.io.File parent(java.io.File file) {
        java.io.File parent = file == null ? null : file.getParentFile();
        return parent == null ? new java.io.File(".") : parent;
    }

    private static boolean isAgentWorkspace(java.io.File dir) {
        return dir != null
                && dir.isDirectory()
                && new java.io.File(dir, "ij.py").isFile();
    }

    /**
     * Phase 2: start the background publishers that feed {@link EventBus}.
     * Called once at plugin startup. Idempotent if called with publishers
     * already running.
     */
    private static void startEventPublishers() {
        try {
            if (imageMonitor == null) {
                imageMonitor = new ImageMonitor(new StateInspector());
                imageMonitor.start();
            }
            if (dialogWatcher == null) {
                dialogWatcher = new DialogWatcher(EventBus.getInstance());
                dialogWatcher.start();
            }
        } catch (Exception e) {
            System.err.println("[ImageJAI] Failed to start event publishers: " + e.getMessage());
        }
    }

    /**
     * Find the first free TCP port in the [preferred, preferred+4] window.
     * Returns the preferred port if it's free, else the first free in the
     * window, else the preferred port (caller will see the bind failure).
     */
    private static int findFreeTcpPort(int preferred) {
        for (int p = preferred; p <= preferred + 4; p++) {
            try (java.net.ServerSocket probe = new java.net.ServerSocket(p)) {
                probe.setReuseAddress(true);
                return p;
            } catch (java.io.IOException ignore) {
                // port busy â€” try next
            }
        }
        return preferred;
    }

    /**
     * Create and start the TCP command server with a listener that
     * reports status and activity to the chat panel.
     */
    private static void startTcpServer(Settings settings, final ChatSurface panel,
                                       ChatPanelController controller) {
        CommandEngine engine = new CommandEngine();
        StateInspector inspector = new StateInspector();
        PipelineBuilder pipeline = new PipelineBuilder(engine);
        ExplorationEngine exploration = new ExplorationEngine(engine);

        tcpServer = new TCPCommandServer(settings.tcpPort, engine, inspector, pipeline, exploration);
        // Phase 7: wire the chat panel as a ChatPanelController so external
        // gui_action commands can drive inline previews, toasts, ROI flashes,
        // markdown, and confirms. Safe even if the panel isn't visible â€” the
        // dispatcher's controller methods log+no-op in that case.
        // Stage 02: ChatSurface handles display updates; ChatPanelController
        // handles GUI actions. Keep both dependencies as interfaces so stage
        // 06 can split the concrete panel without a cast here.
        if (controller != null) {
            tcpServer.setChatPanelController(controller);
        }
        tcpServer.start(new TCPCommandServer.ServerListener() {
            @Override
            public void onServerStarted(int port) {
                panel.appendMessage("assistant",
                        "[TCP] Server listening on port " + port);
            }

            @Override
            public void onServerStopped() {
                panel.appendMessage("assistant", "[TCP] Server stopped.");
            }

            @Override
            public void onClientConnected(String clientInfo) {
                System.err.println("[ImageJAI-TCP] Client connected: " + clientInfo);
            }

            @Override
            public void onCommandReceived(String command) {
                panel.appendMessage("assistant",
                        "[External] " + command);
            }

            @Override
            public void onError(String error) {
                panel.appendMessage("assistant",
                        "[TCP] Error: " + error);
            }
        });
    }
}


