package imagejai.ui;

import imagejai.engine.EmbeddedAgentSession;
import imagejai.config.Settings;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;

/**
 * Embedded-terminal card: rail on the left, terminal host in the center.
 */
public class TerminalView extends JPanel {
    private final TerminalHost terminalHost;

    public TerminalView(Settings settings) {
        super(new BorderLayout(8, 0));
        setBackground(new Color(30, 30, 35));

        terminalHost = new TerminalHost();
        add(new LeftRail(settings), BorderLayout.WEST);
        add(terminalHost, BorderLayout.CENTER);
    }

    public void attachSession(EmbeddedAgentSession session) {
        terminalHost.attachSession(session);
    }

    public void clearSession(EmbeddedAgentSession session) {
        terminalHost.clearSession(session);
    }

    public void requestTerminalFocus() {
        terminalHost.requestTerminalFocus();
    }
}
