package imagejai.ui;

import imagejai.engine.EmbeddedAgentSession;
import imagejai.config.Settings;
import imagejai.engine.terminal.ApprovalPolicy;
import imagejai.engine.terminal.PromptWatcher;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.io.File;

/**
 * Embedded-terminal card: rail on the left, terminal host in the center.
 */
public class TerminalView extends JPanel {
    private final TerminalHost terminalHost;
    private final TerminalToolbar toolbar;
    private final LeftRail leftRail;
    private PromptWatcher promptWatcher;

    public TerminalView(Settings settings, File workspace,
                        LeftRail.SessionRelauncher sessionRelauncher) {
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
        leftRail = new LeftRail(settings, workspace, sessionRelauncher, new Runnable() {
            @Override
            public void run() {
                requestTerminalFocus();
            }
        });
        add(leftRail, BorderLayout.WEST);
        add(terminalStack, BorderLayout.CENTER);
    }

    public void attachSession(EmbeddedAgentSession session) {
        stopPromptWatcher();
        terminalHost.attachSession(session);
        toolbar.attachSession(session);
        leftRail.attachSession(session);
        promptWatcher = new PromptWatcher(
                new PromptWatcher.ScrollbackReader() {
                    @Override
                    public String readScrollback(int lineLimit) {
                        return session.readScrollback(lineLimit);
                    }
                },
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

    public void setWorkspace(File workspace) {
        leftRail.setWorkspace(workspace);
    }

    public void clearSession(EmbeddedAgentSession session) {
        stopPromptWatcher();
        toolbar.clearSession(session);
        terminalHost.clearSession(session);
        leftRail.clearSession(session);
    }

    public boolean isSession(EmbeddedAgentSession session) {
        return terminalHost.isSession(session);
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
