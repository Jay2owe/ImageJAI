package uk.ac.ucl.imagej.ai;

import ij.IJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import uk.ac.ucl.imagej.ai.config.Constants;
import uk.ac.ucl.imagej.ai.config.Settings;
import uk.ac.ucl.imagej.ai.engine.AgentLauncher;
import uk.ac.ucl.imagej.ai.engine.CommandEngine;
import uk.ac.ucl.imagej.ai.engine.DialogWatcher;
import uk.ac.ucl.imagej.ai.engine.EventBus;
import uk.ac.ucl.imagej.ai.engine.ExplorationEngine;
import uk.ac.ucl.imagej.ai.engine.ImageMonitor;
import uk.ac.ucl.imagej.ai.engine.PipelineBuilder;
import uk.ac.ucl.imagej.ai.engine.StateInspector;
import uk.ac.ucl.imagej.ai.engine.TCPCommandServer;
import uk.ac.ucl.imagej.ai.ui.ChatPanel;
import uk.ac.ucl.imagej.ai.ui.SettingsDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Main entry point for the ImageJ AI Assistant plugin.
 * Registered as a SciJava Command -- appears in Plugins menu.
 */
@Plugin(type = Command.class, menuPath = "Plugins>AI Assistant")
public class ImageJAIPlugin implements Command {

    private static JFrame chatFrame;
    private static ChatPanel chatPanel;
    private static ConversationLoop conversationLoop;
    private static TCPCommandServer tcpServer;
    private static ImageMonitor imageMonitor;
    private static DialogWatcher dialogWatcher;

    @Override
    public void run() {
        // Ensure we're on the EDT
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::run);
            return;
        }

        // If already open, bring to front
        if (chatFrame != null && chatFrame.isDisplayable()) {
            chatFrame.toFront();
            chatFrame.requestFocus();
            return;
        }

        // Load settings
        Settings settings = Settings.load();

        // First-run: show settings dialog (if no key and not just TCP)
        if (settings.isFirstRun()) {
            Frame parent = IJ.getInstance();
            SettingsDialog dialog = new SettingsDialog(parent, settings);
            dialog.setVisible(true);
            if (!dialog.wasConfirmed()) {
                return; // User cancelled
            }
            settings.save();
        }

        // Create chat panel and wire conversation loop
        chatPanel = new ChatPanel(settings);
        
        if (settings.hasApiKey()) {
            conversationLoop = new ConversationLoop(chatPanel, settings);
            chatPanel.addChatListener(conversationLoop);
        } else {
            chatPanel.appendMessage("assistant", "AI Assistant is running in TCP-only mode. " +
                    "To use chat features, please configure an API key in Settings.");
        }

        // Set up agent launcher — find the agent workspace directory
        String agentWorkspace = findAgentWorkspace();
        if (agentWorkspace != null) {
            chatPanel.setAgentLauncher(new AgentLauncher(agentWorkspace, settings.tcpPort));
        }

        // Start TCP command server if enabled
        if (settings.tcpServerEnabled) {
            startTcpServer(settings, chatPanel);
        }

        // Phase 2: start the event-bus publishers so dialog / image / memory
        // / results events flow to any connected subscribers, independent of
        // whether the chat panel or TCP is active.
        startEventPublishers();

        chatFrame = new JFrame(Constants.PLUGIN_NAME + " v" + Constants.VERSION);
        chatFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        chatFrame.getContentPane().add(chatPanel);
        chatFrame.setSize(420, 600);
        chatFrame.setMinimumSize(new Dimension(350, 400));

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
                // Null out static references so they can be GC'd
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

    /**
     * Get the active ChatPanel instance (for use by conversation loop in Phase 2).
     *
     * @return the current ChatPanel, or null if the window is not open
     */
    public static ChatPanel getChatPanel() {
        return chatPanel;
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
     * next to the plugin's source project, or falls back to a default location.
     */
    private static String findAgentWorkspace() {
        // Try to find the agent/ directory relative to the plugin JAR location
        // The JAR is in Fiji/plugins/, the source project has an agent/ subdirectory
        try {
            // Check well-known project locations
            String[] candidates = {
                System.getProperty("user.home") + "/UK Dementia Research Institute Dropbox/Brancaccio Lab/Jamie/Experiments/ImageJAI/agent",
                System.getProperty("user.home") + "/ImageJAI/agent",
                System.getProperty("user.dir") + "/agent",
            };
            for (String candidate : candidates) {
                java.io.File dir = new java.io.File(candidate);
                if (dir.isDirectory() && new java.io.File(dir, "CLAUDE.md").exists()) {
                    return dir.getAbsolutePath();
                }
            }

            // Fallback: create a default workspace in user home
            java.io.File fallback = new java.io.File(System.getProperty("user.home"), ".imagej-ai/agent");
            if (!fallback.exists()) {
                fallback.mkdirs();
            }
            return fallback.getAbsolutePath();
        } catch (Exception e) {
            System.err.println("[ImageJAI] Could not determine agent workspace: " + e.getMessage());
            return null;
        }
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
     * Create and start the TCP command server with a listener that
     * reports status and activity to the chat panel.
     */
    private static void startTcpServer(Settings settings, final ChatPanel panel) {
        CommandEngine engine = new CommandEngine();
        StateInspector inspector = new StateInspector();
        PipelineBuilder pipeline = new PipelineBuilder(engine);
        ExplorationEngine exploration = new ExplorationEngine(engine);

        tcpServer = new TCPCommandServer(settings.tcpPort, engine, inspector, pipeline, exploration);
        // Phase 7: wire the chat panel as a ChatPanelController so external
        // gui_action commands can drive inline previews, toasts, ROI flashes,
        // markdown, and confirms. Safe even if the panel isn't visible — the
        // dispatcher's controller methods log+no-op in that case.
        tcpServer.setChatPanelController(panel);
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
