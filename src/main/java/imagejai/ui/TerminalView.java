package imagejai.ui;

import imagejai.engine.EmbeddedAgentSession;
import imagejai.config.Settings;
import imagejai.terminal.ApprovalPolicy;
import imagejai.terminal.PromptWatcher;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;

/**
 * Embedded-terminal card: rail on the left, terminal host in the center.
 */
public class TerminalView extends JPanel {
    private final TerminalHost terminalHost;
    private final TerminalToolbar toolbar;
    private PromptWatcher promptWatcher;

    public TerminalView(Settings settings) {
        super(new BorderLayout(8, 0));
        setBackground(new Color(30, 30, 35));

        terminalHost = new TerminalHost();
        toolbar = new TerminalToolbar(new Runnable() {
            @Override
            public void run() {
                requestTerminalFocus();
            }
        });
        JPanel terminalStack = new JPanel(new BorderLayout());
        terminalStack.setOpaque(false);
        terminalStack.add(toolbar, BorderLayout.NORTH);
        terminalStack.add(terminalHost, BorderLayout.CENTER);
        add(new LeftRail(settings), BorderLayout.WEST);
        add(terminalStack, BorderLayout.CENTER);
    }

    public void attachSession(EmbeddedAgentSession session) {
        stopPromptWatcher();
        terminalHost.attachSession(session);
        toolbar.attachSession(session);
        promptWatcher = new PromptWatcher(
                session.terminalWidget(),
                ApprovalPolicy.loadForAgent(session.info()),
                new PromptWatcher.RawWriter() {
                    @Override
                    public void writeRaw(String text) {
                        session.writeRaw(text);
                    }
                },
                new PromptWatcher.Listener() {
                    @Override
                    public void onAutoConfirm(String promptText) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                toolbar.clearPendingPrompt();
                                requestTerminalFocus();
                            }
                        });
                    }

                    @Override
                    public void onEscalate(final String promptText) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                toolbar.clearPendingPrompt();
                                toolbar.showEscalationModal(promptText);
                            }
                        });
                    }

                    @Override
                    public void onPending(final String promptText) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                toolbar.showPendingPrompt(promptText);
                                requestTerminalFocus();
                            }
                        });
                    }

                    @Override
                    public void onPromptCleared() {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                toolbar.clearPendingPrompt();
                            }
                        });
                    }

                    @Override
                    public void onUrlSeen(final String url) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                toolbar.showCopyUrl(url);
                            }
                        });
                    }
                });
        promptWatcher.start();
    }

    public void clearSession(EmbeddedAgentSession session) {
        stopPromptWatcher();
        toolbar.clearSession(session);
        terminalHost.clearSession(session);
    }

    public void requestTerminalFocus() {
        terminalHost.requestTerminalFocus();
    }

    private void stopPromptWatcher() {
        if (promptWatcher != null) {
            promptWatcher.stop();
            promptWatcher = null;
        }
    }
}
