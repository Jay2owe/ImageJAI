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
import java.util.concurrent.atomic.AtomicBoolean;

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
    /**
     * True when the TCP server and the automation bridge were started by the
     * boot hook rather than by opening the panel. Their lifetime is then the
     * JVM's, not the panel's, so closing the window must not stop them.
     */
    private static volatile boolean bridgeStartedAtBoot;
    static volatile PluginSession activeSession;

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
        // Fiji's own UI may still be coming up — see awaitFijiUiThenRun.
        if (deferUntilFijiUiIsUp()) {
            return;
        }
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

        // A disposed frame can be reopened before its queued windowClosed
        // callback runs. Tear down that frame's owned resources synchronously
        // so its delayed callback cannot stop the new frame's TCP server.
        PluginSession previousSession = activeSession;
        if (previousSession != null) {
            previousSession.close();
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

        // One mutation owner spans TCP, legacy chat, and Local Assistant. When
        // the boot hook already started the server, it built the coordinator
        // the server is using; a second one would split mutation ownership in
        // half, which is exactly what this class exists to prevent.
        boolean adoptBootBridge = bridgeStartedAtBoot
                && tcpServer != null && tcpServer.isRunning();
        if (!adoptBootBridge || mutationCoordinator == null) {
            mutationCoordinator = new MutationCoordinator();
        }

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

        if (adoptBootBridge) {
            // The boot hook already published readiness on this port. Give the
            // running server the panel's controller so gui_action commands can
            // drive it, and leave the socket exactly where it is: rebinding
            // would invalidate the ready file a client is already using.
            settings.tcpPort = tcpServer.getPort();
            tcpServer.setChatPanelController(rootPanel.chatController());
            IJ.log("[ImageJAI-TCP] adopted the boot-started server on :"
                    + settings.tcpPort);
        } else if (settings.tcpServerEnabled) {
            if (imagejai.engine.automation.AutomationPolicy.current().isEnabled()) {
                // Automation is armed, so a boot bridge should already exist.
                // Starting a second server here would bind a second port and
                // republish the ready file, pointing an already-connected
                // client at a different instance. Say why it did not adopt.
                IJ.log("[ImageJAI-TCP] no boot bridge to adopt (startedAtBoot="
                        + bridgeStartedAtBoot + ", server="
                        + (tcpServer != null && tcpServer.isRunning()) + ")");
            }
            // Start TCP command server if enabled.
            // A harness-owned test instance must not land on the port a normal
            // Fiji is using, so the startup automation policy may pin one — and
            // 0 means "let the OS pick", with the real port published in the
            // ready file once the socket is bound.
            imagejai.engine.automation.AutomationPolicy automationPolicy =
                    imagejai.engine.automation.AutomationPolicy.current();
            if (automationPolicy.isEnabled() && automationPolicy.requestedPort() >= 0) {
                settings.tcpPort = automationPolicy.requestedPort();
                IJ.log("[ImageJAI-TCP] test automation pinned port :"
                        + settings.tcpPort);
            } else {
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
        final PluginSession session = new PluginSession(chatFrame, rootPanel,
                chatPanel, conversationLoop, tcpServer, mutationCoordinator,
                imageMonitor, dialogWatcher, !adoptBootBridge);
        activeSession = session;
        registerAgentShutdownHook();

        // Clean up resources on close
        chatFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                session.close();
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

    /**
     * Hold the panel back while Fiji is still building its own UI.
     *
     * <p>Fiji installs its look and feel from
     * {@code SwingLookAndFeelService.initLookAndFeel}, which calls
     * {@code SwingUtilities.updateComponentTreeUI} over every window <em>on the
     * {@code main} thread</em>, not the event thread. Any panel that already
     * exists is therefore having its component UIs uninstalled and reinstalled
     * by one thread while the event thread lays the same components out. The
     * result is a burst of uncaught {@code NullPointerException}s from inside
     * Swing — {@code BasicScrollBarUI.layoutVScrollbar} with a null arrow
     * button, {@code BasicComboBoxUI.getDisplaySize} with a null list — that no
     * amount of care inside this plugin can prevent, because the fields being
     * read are Swing's own and are legitimately null mid-reinstall.</p>
     *
     * <p>This only bites a command invoked <em>during</em> startup, e.g. by
     * {@code -run "AI Assistant"} on the Fiji command line. Opening the panel
     * from the menu on a running Fiji is unaffected and pays nothing here: the
     * main window is already showing, so this returns immediately.</p>
     *
     * @return true when the caller should return and let the retry take over
     */
    private boolean deferUntilFijiUiIsUp() {
        if (imageJMainWindowIsShowing()) {
            return false;
        }
        IJ.log("[ImageJAI] Fiji is still starting; deferring the AI Assistant "
                + "window until its UI has settled.");
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() { awaitFijiUiThenRun(); }
        }, "ImageJAI-startup-defer");
        waiter.setDaemon(true);
        waiter.start();
        return true;
    }

    /** How long the boot path waits for the listen socket to bind. */
    private static final long BOOT_BIND_WAIT_MS = 10_000L;

    /** Poll until the server reports a bound socket, or the budget runs out. */
    private static boolean awaitServerBound(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            TCPCommandServer server = tcpServer;
            if (server != null && server.isRunning()) return true;
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        TCPCommandServer server = tcpServer;
        return server != null && server.isRunning();
    }

    /** Bound on the deferral; after this the panel opens regardless. */
    private static final long FIJI_UI_WAIT_MS = 60_000L;
    /** How long the look and feel must hold still before the UI is "settled". */
    private static final long LAF_SETTLE_MS = 750L;

    private void awaitFijiUiThenRun() {
        long deadline = System.currentTimeMillis() + FIJI_UI_WAIT_MS;
        String lastLookAndFeel = null;
        long stableSince = 0L;
        while (System.currentTimeMillis() < deadline) {
            if (imageJMainWindowIsShowing()) {
                String current = String.valueOf(UIManager.getLookAndFeel());
                long now = System.currentTimeMillis();
                if (!current.equals(lastLookAndFeel)) {
                    // Still being swapped; restart the settle window rather
                    // than racing the thread doing the swapping.
                    lastLookAndFeel = current;
                    stableSince = now;
                } else if (now - stableSince >= LAF_SETTLE_MS) {
                    break;
                }
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        SwingUtilities.invokeLater(this::run);
    }

    /**
     * True when there is nothing left to wait for: ImageJ's main window is up,
     * or there is no ImageJ 1.x UI in this JVM to wait for in the first place.
     * The second case matters — otherwise a headless or embedded caller would
     * sit out the whole deferral budget waiting for a window that never comes.
     */
    private static boolean imageJMainWindowIsShowing() {
        try {
            if (GraphicsEnvironment.isHeadless()) return true;
            ij.ImageJ instance = IJ.getInstance();
            if (instance == null) return true;
            return instance.isShowing();
        } catch (Throwable noImageJ) {
            return true;
        }
    }

    private static synchronized void registerAgentShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                PluginSession session = activeSession;
                if (session != null) {
                    session.close();
                }
                // A boot-started server is not owned by any window, so nothing
                // above stops it. The socket and the ready file must still go
                // when the JVM does, or the next run authenticates a corpse.
                if (bridgeStartedAtBoot) {
                    TCPCommandServer server = tcpServer;
                    if (server != null) {
                        try {
                            server.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                    try {
                        imagejai.engine.automation.AutomationBridge bridge =
                                imagejai.engine.automation.AutomationBridge.shared();
                        if (bridge.isEnabled()) bridge.close();
                    } catch (Throwable ignored) {
                    }
                }
                LiteLlmProxyService proxyService = liteLlmProxyService;
                if (proxyService != null) {
                    proxyService.shutdown();
                }
            }
        }, "ImageJAI-agent-shutdown-hook"));
        shutdownHookRegistered = true;
    }

    /** Resources owned by one assistant window, closed exactly once. */
    static final class PluginSession implements AutoCloseable {
        private final JFrame frame;
        private final AiRootPanel panel;
        private final ChatPanel ownedChatPanel;
        private final ConversationLoop ownedConversationLoop;
        private final TCPCommandServer server;
        private final MutationCoordinator coordinator;
        private final ImageMonitor monitor;
        private final DialogWatcher watcher;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * False when the server, the mutation coordinator and the automation
         * bridge were started by the boot hook. They then outlive this window,
         * so closing it must leave the socket bound and the ready file in
         * place — a harness holding that session did not ask for it to end.
         */
        private volatile boolean ownsServer;

        PluginSession(JFrame frame, AiRootPanel panel, ChatPanel ownedChatPanel,
                      ConversationLoop ownedConversationLoop,
                      TCPCommandServer server, MutationCoordinator coordinator,
                      ImageMonitor monitor, DialogWatcher watcher,
                      boolean ownsServer) {
            this.frame = frame;
            this.panel = panel;
            this.ownedChatPanel = ownedChatPanel;
            this.ownedConversationLoop = ownedConversationLoop;
            this.server = server;
            this.coordinator = coordinator;
            this.monitor = monitor;
            this.watcher = watcher;
            this.ownsServer = ownsServer;
        }

        /** A console request can adopt the coordinator after this panel opened. */
        void releaseBridgeOwnership() {
            ownsServer = false;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            closeResource("TCP server", new Runnable() {
                @Override public void run() {
                    if (ownsServer && server != null) server.stop();
                }
            });
            closeResource("mutation coordinator", new Runnable() {
                @Override public void run() {
                    if (ownsServer && coordinator != null) coordinator.shutdown();
                }
            });
            closeResource("image monitor", new Runnable() {
                @Override public void run() {
                    if (monitor != null) monitor.stop();
                }
            });
            closeResource("dialog watcher", new Runnable() {
                @Override public void run() {
                    if (watcher != null) watcher.stop();
                }
            });
            closeResource("agent sessions", new Runnable() {
                @Override public void run() {
                    if (panel != null) panel.shutdownSessions();
                }
            });
            // Uninstall the test-mode event-queue instrumentation, stop the
            // heartbeat thread, drop UI identities, and remove the ready file.
            // A no-op on a normal Fiji, where nothing was ever installed.
            closeResource("automation bridge", new Runnable() {
                @Override public void run() {
                    if (!ownsServer) return;
                    imagejai.engine.automation.AutomationBridge bridge =
                            imagejai.engine.automation.AutomationBridge.shared();
                    if (bridge.isEnabled()) bridge.close();
                }
            });

            synchronized (ImageJAIPlugin.class) {
                // A delayed close event from an older frame owns only the
                // objects captured above. It must never clear a newer session.
                if (activeSession != this) {
                    return;
                }
                activeSession = null;
                if (ownsServer && tcpServer == server) tcpServer = null;
                if (ownsServer && mutationCoordinator == coordinator) {
                    mutationCoordinator = null;
                }
                if (imageMonitor == monitor) imageMonitor = null;
                if (dialogWatcher == watcher) dialogWatcher = null;
                if (rootPanel == panel) rootPanel = null;
                if (chatPanel == ownedChatPanel) chatPanel = null;
                if (conversationLoop == ownedConversationLoop) conversationLoop = null;
                if (chatFrame == frame) chatFrame = null;
            }
            System.out.println("[ImageJAI] Window closed, resources released.");
        }

        private static void closeResource(String name, Runnable action) {
            try {
                action.run();
            } catch (Throwable failure) {
                IJ.log("[ImageJAI] Failed to close " + name + ": "
                        + String.valueOf(failure.getMessage()));
            }
        }
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

            // Lab ZIP installs create ~/ImageJAI/agent via setup-python.ps1,
            // but a local/private JAR-only deployment used to leave every CLI
            // and Ollama Cloud row visible with no launcher behind it. Extract
            // the audited runtime bundled in the JAR as the final fallback.
            String bundled = imagejai.install.BundledAgentWorkspace.ensureInstalled();
            if (bundled != null) {
                return bundled;
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
     * Start the TCP command server during Fiji startup, before any UI exists,
     * when the startup automation policy is armed.
     *
     * <p>Called by {@link imagejai.engine.automation.AutomationBootstrapService}.
     * The panel is deliberately not constructed: an external harness needs a
     * socket and a ready file, not a chat window, and making it open the panel
     * to obtain one exposed it to every UI defect on the way. When the user
     * later opens the panel, {@link #run()} adopts this server rather than
     * starting a second one, and closing the panel leaves it running because
     * the panel never owned it.</p>
     *
     * @return true when a server is listening as a result of this call
     */
    public static synchronized boolean startBridgeAtBoot() {
        if (tcpServer != null && tcpServer.isRunning()) {
            return true;
        }
        imagejai.engine.automation.AutomationPolicy policy =
                imagejai.engine.automation.AutomationPolicy.current();
        if (!policy.isEnabled()) {
            return false;
        }
        Settings settings = Settings.load();
        if (!settings.tcpServerEnabled) {
            // Two keys, both the operator's: the JVM property arms automation,
            // the plugin setting permits the server at all. Refusing loudly is
            // better than quietly overriding a setting the user turned off.
            IJ.log("[ImageJAI-Automation] test automation is armed but "
                    + "tcpServerEnabled is false; the bridge will not start.");
            return false;
        }
        // The privacy posture governs every reply the server writes, so it has
        // to be read from the user's settings here as well as in run(). Leaving
        // it unconfigured makes PostureController fall back to a default
        // Settings object, which quietly overrides the configured posture for
        // as long as the panel stays closed.
        PostureController.getInstance().configure(settings);
        if (policy.requestedPort() >= 0) {
            settings.tcpPort = policy.requestedPort();
        } else {
            settings.tcpPort = findFreeTcpPort(settings.tcpPort);
        }
        if (mutationCoordinator == null) {
            mutationCoordinator = new MutationCoordinator();
        }
        bridgeStartedAtBoot = true;
        startTcpServer(settings, null, null, mutationCoordinator);
        // start() hands the bind to the accept thread, so isRunning() is still
        // false when it returns. Sampling it immediately reported failure for a
        // server that was about to come up perfectly, cleared the "started at
        // boot" flag, and let the panel start a second server on a second port
        // — republishing the ready file underneath an already-connected client.
        if (!awaitServerBound(BOOT_BIND_WAIT_MS)) {
            bridgeStartedAtBoot = false;
            IJ.log("[ImageJAI-TCP] the boot bridge did not bind within "
                    + BOOT_BIND_WAIT_MS + " ms.");
            return false;
        }
        // The panel may never be opened in an automated run, so the hook that
        // releases the socket and the ready file has to be armed from here too.
        registerAgentShutdownHook();
        return true;
    }

    /**
     * Open the ordinary authenticated TCP surface at the console user's
     * request, including when the Fiji main window is already running.
     * Test automation remains governed by its separate startup policy.
     */
    public static synchronized boolean startConsoleBridge(int requestedPort) {
        if (requestedPort < 1 || requestedPort > 65535) return false;
        if (tcpServer != null && tcpServer.isRunning()) {
            return tcpServer.getPort() == requestedPort;
        }
        Settings settings = Settings.load();
        settings.tcpPort = requestedPort;
        PostureController.getInstance().configure(settings);
        if (mutationCoordinator == null) {
            mutationCoordinator = new MutationCoordinator();
        }
        startTcpServer(settings, rootPanel,
                rootPanel == null ? null : rootPanel.chatController(),
                mutationCoordinator);
        if (!awaitServerBound(BOOT_BIND_WAIT_MS)) {
            if (tcpServer != null) tcpServer.stop();
            IJ.log("[ImageJAI-TCP] console request could not bind port :"
                    + requestedPort);
            return false;
        }
        bridgeStartedAtBoot = true;
        PluginSession session = activeSession;
        if (session != null) session.releaseBridgeOwnership();
        startEventPublishers();
        registerAgentShutdownHook();
        IJ.log("[ImageJAI-TCP] console request opened port :" + requestedPort);
        return true;
    }

    /**
     * Create and start the TCP command server with a listener that
     * reports status and activity to the chat panel.
     *
     * @param panel the chat surface to narrate to, or {@code null} when the
     *              server is started at boot with no UI
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
                narrate(panel, "[TCP] Server listening on port " + port);
            }

            @Override
            public void onServerStopped() {
                narrate(panel, "[TCP] Server stopped.");
            }

            @Override
            public void onClientConnected(String clientInfo) {
                System.err.println("[ImageJAI-TCP] Client connected: " + clientInfo);
            }

            @Override
            public void onCommandReceived(String command) {
                narrate(panel, "[External] " + command);
            }

            @Override
            public void onError(String error) {
                narrate(panel, "[TCP] Error: " + error);
            }
        });
    }

    /**
     * Report server activity to the chat panel, or to the console when the
     * server is running headless because it was started at boot.
     */
    private static void narrate(ChatSurface panel, String message) {
        if (panel != null) {
            panel.appendMessage("assistant", message);
        } else {
            System.err.println("[ImageJAI-TCP] " + message);
        }
    }
}
