package imagejai;

import ij.IJ;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import imagejai.config.Constants;
import imagejai.config.Settings;
import imagejai.engine.CommandEngine;
import imagejai.engine.ExplorationEngine;
import imagejai.engine.PipelineBuilder;
import imagejai.engine.StateInspector;
import imagejai.engine.TCPCommandServer;
import imagejai.ui.ChatPanel;
import imagejai.ui.SettingsDialog;

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

        // First-run: show settings dialog
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
        conversationLoop = new ConversationLoop(chatPanel, settings);
        chatPanel.addChatListener(conversationLoop);

        // Start TCP command server if enabled
        if (settings.tcpServerEnabled) {
            startTcpServer(settings, chatPanel);
        }

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
     * Create and start the TCP command server with a listener that
     * reports status and activity to the chat panel.
     */
    private static void startTcpServer(Settings settings, final ChatPanel panel) {
        CommandEngine engine = new CommandEngine();
        StateInspector inspector = new StateInspector();
        PipelineBuilder pipeline = new PipelineBuilder(engine);
        ExplorationEngine exploration = new ExplorationEngine(engine);

        tcpServer = new TCPCommandServer(settings.tcpPort, engine, inspector, pipeline, exploration);
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
