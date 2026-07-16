package imagejai;

import ij.IJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import imagejai.config.Constants;
import imagejai.config.Settings;
import imagejai.engine.AgentLauncher;
import imagejai.engine.BillingFailureListener;
import imagejai.engine.CommandEngine;
import imagejai.engine.DialogWatcher;
import imagejai.engine.EventBus;
import imagejai.engine.ExplorationEngine;
import imagejai.engine.ImageMonitor;
import imagejai.engine.LiteLlmProxyService;
import imagejai.engine.MutationCoordinator;
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
import imagejai.ui.picker.BillingFailureDialog;
import imagejai.ui.picker.BudgetCeilingDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

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
    private static boolean billingListenerRegistered;
    private static volatile Settings budgetSettings;
    private static volatile boolean budgetDialogOpen;
    private static volatile boolean billingDialogOpen;
    private static MutationCoordinator mutationCoordinator;
    private static boolean terminalFontsRegistered;
    private static boolean shutdownHookRegistered;

    private static final BudgetCeilingTracker.BreachListener BUDGET_BREACH_LISTENER =
            new BudgetCeilingTracker.BreachListener() {
                @Override
                public void onCeilingBreached(double totalUsd, double ceilingUsd) {
                    showBudgetCeilingDialog(totalUsd, ceilingUsd);
                }
            };

    private static final BillingFailureListener BILLING_FAILURE_LISTENER =
            new BillingFailureListener() {
                @Override
                public void onBillingFailure(String provider, int status, String message) {
                    showBillingFailureDialog(provider, status, message);
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

        // One mutation owner spans TCP, legacy chat, and Local Assistant.
        mutationCoordinator = new MutationCoordinator();

        // Create root panel and wire conversation loop
        rootPanel = new AiRootPanel(settings, mutationCoordinator);
        chatPanel = new ChatPanel(rootPanel.chatView());
        
        // Keep one live loop even when chat starts unconfigured: a later
        // transactional Settings save can rebuild that same backend exactly
        // once instead of requiring the plugin window to be reopened.
        conversationLoop = new ConversationLoop(rootPanel, settings, mutationCoordinator);
        rootPanel.addChatListener(conversationLoop);
        rootPanel.addConversationClearListener(conversationLoop::clearHistory);
        rootPanel.setBackendRefreshListener(conversationLoop::refreshBackend);
        if (!settings.hasApiKey() && !localAssistantSelected) {
            rootPanel.appendMessage("assistant", "AI Assistant is running in TCP-only mode. " +
                    "To use chat features, please configure an API key in Settings.");
        }

        // Set up agent launcher â€” find the agent workspace directory
        String agentWorkspace = findAgentWorkspace();
        // Phase A: the LiteLLM proxy autostarts on Fiji boot. It can run from
        // bundled jar resources (LiteLlmProxyService extracts proxy.py + config
        // when agentWorkspace is null), so start it independently of external
        // CLI-agent workspace discovery — otherwise a jar-only Fiji deploy never
        // gets a proxy. The CLI AgentLauncher still needs a real workspace.
        startLiteLlmProxy(agentWorkspace, settings);
        if (agentWorkspace != null) {
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
            startTcpServer(settings, rootPanel, rootPanel.chatController(),
                    mutationCoordinator);
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
                if (mutationCoordinator != null) {
                    mutationCoordinator.shutdown();
                    mutationCoordinator = null;
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
        if (!billingListenerRegistered) {
            liteLlmProxyService.addBillingFailureListener(BILLING_FAILURE_LISTENER);
            billingListenerRegistered = true;
        }
        liteLlmProxyService.startAsync();
    }

    /**
     * Live LiteLLM proxy port (4000-4010) for the agent launcher to export to
     * the Python provider client as {@code IMAGEJAI_LITELLM_PORT}; 0 when the
     * sidecar is not running. Closes the gap where the client hard-coded 4000.
     */
    public static int liteLlmProxyPort() {
        LiteLlmProxyService svc = liteLlmProxyService;
        // Only export the port once readiness has confirmed it; before that the
        // field may still hold the stale default 4000 while the sidecar is
        // actually binding 4001-4010. Returning 0 makes the Python client scan
        // for the live port instead of trusting a stale value.
        return svc != null && svc.isReady() ? svc.getPort() : 0;
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

    private static synchronized boolean markBillingDialogOpen() {
        if (billingDialogOpen) {
            return false;
        }
        billingDialogOpen = true;
        return true;
    }

    private static synchronized void markBillingDialogClosed() {
        billingDialogOpen = false;
    }

    /**
     * Surface an upstream billing/auth failure (401/402/429) from the proxy as
     * the Phase H {@link BillingFailureDialog}. Coalesces repeats so a burst of
     * rejected calls in one session does not stack dialogs.
     */
    private static void showBillingFailureDialog(final String provider,
                                                 final int status,
                                                 final String message) {
        if (!markBillingDialogOpen()) {
            IJ.log("[ImageJAI-Billing] failure dialog already open; suppressing duplicate");
            return;
        }
        final String display = billingProviderDisplay(provider);
        final String body = (message != null && !message.isEmpty())
                ? message
                : ("HTTP " + status);
        final URI consoleUri = billingConsoleUri(provider);
        Runnable show = new Runnable() {
            @Override
            public void run() {
                try {
                    Frame owner = chatFrame != null ? chatFrame : IJ.getInstance();
                    BillingFailureDialog dialog =
                            new BillingFailureDialog(owner, display, body, consoleUri);
                    BillingFailureDialog.Result result = dialog.showAndAwait();
                    handleBillingDialogResult(result, display);
                } catch (Throwable t) {
                    IJ.log("[ImageJAI-Billing] could not show billing dialog: " + t.getMessage());
                } finally {
                    markBillingDialogClosed();
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }

    private static void handleBillingDialogResult(BillingFailureDialog.Result result,
                                                  String display) {
        AiRootPanel panel = rootPanel;
        String message;
        if (result == BillingFailureDialog.Result.SWITCH_MODEL) {
            message = display + " refused the request. Pick a free model "
                    + "(Gemini Flash, Groq, or Ollama) from the model picker to continue.";
        } else if (result == BillingFailureDialog.Result.OPEN_CONSOLE) {
            message = "Opened the " + display + " billing console. Add credit or a payment "
                    + "method, then re-run.";
        } else {
            message = display + " billing failure dismissed. The paid session is paused.";
        }
        IJ.log("[ImageJAI-Billing] " + message);
        if (panel != null) {
            panel.appendMessage("assistant", message);
        }
    }

    private static String billingProviderDisplay(String provider) {
        if (provider == null || provider.isEmpty()) {
            return "The provider";
        }
        switch (provider) {
            case "anthropic": return "Anthropic";
            case "openai": return "OpenAI";
            case "gemini": case "google": case "vertex_ai": return "Google Gemini";
            case "groq": return "Groq";
            case "cerebras": return "Cerebras";
            case "mistral": return "Mistral";
            case "deepseek": return "DeepSeek";
            case "xai": return "xAI";
            case "perplexity": return "Perplexity";
            case "openrouter": return "OpenRouter";
            case "together": case "together_ai": return "Together AI";
            case "huggingface": return "Hugging Face";
            case "github-models": case "github": return "GitHub Models";
            default: return provider;
        }
    }

    private static URI billingConsoleUri(String provider) {
        String url = null;
        if (provider != null) {
            switch (provider) {
                case "anthropic": url = "https://console.anthropic.com/settings/billing"; break;
                case "openai": url = "https://platform.openai.com/account/billing"; break;
                case "gemini": case "google": case "vertex_ai":
                    url = "https://aistudio.google.com/app/apikey"; break;
                case "groq": url = "https://console.groq.com/settings/billing"; break;
                case "cerebras": url = "https://cloud.cerebras.ai/"; break;
                case "mistral": url = "https://console.mistral.ai/billing/"; break;
                case "deepseek": url = "https://platform.deepseek.com/usage"; break;
                case "xai": url = "https://console.x.ai/"; break;
                case "perplexity": url = "https://www.perplexity.ai/settings/api"; break;
                case "openrouter": url = "https://openrouter.ai/credits"; break;
                case "together": case "together_ai":
                    url = "https://api.together.ai/settings/billing"; break;
                case "huggingface": url = "https://huggingface.co/settings/billing"; break;
                case "github-models": case "github":
                    url = "https://github.com/settings/billing"; break;
                default: url = null;
            }
        }
        if (url == null) {
            return null;
        }
        try {
            return new URI(url);
        } catch (Exception e) {
            return null;
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
                                       ChatPanelController controller,
                                       MutationCoordinator coordinator) {
        CommandEngine engine = new CommandEngine();
        StateInspector inspector = new StateInspector();
        PipelineBuilder pipeline = new PipelineBuilder(engine);
        ExplorationEngine exploration = new ExplorationEngine(engine);

        tcpServer = new TCPCommandServer(settings.tcpPort, engine, inspector,
                pipeline, exploration, coordinator);
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
