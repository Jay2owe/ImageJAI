package imagejai.ui;

import imagejai.engine.EmbeddedAgentSession;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

/**
 * Embedded-terminal card: stage-07 rail stub on the left, terminal host in
 * the center.
 */
public class TerminalView extends JPanel {
    private final TerminalHost terminalHost;

    public TerminalView() {
        super(new BorderLayout(8, 0));
        setBackground(new Color(30, 30, 35));

        JPanel leftRailStub = new JPanel();
        leftRailStub.setLayout(new BoxLayout(leftRailStub, BoxLayout.Y_AXIS));
        leftRailStub.setBackground(new Color(34, 34, 40));
        leftRailStub.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(55, 55, 64)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        leftRailStub.setPreferredSize(new Dimension(160, 10));
        JLabel label = new JLabel("Session");
        label.setForeground(new Color(130, 130, 140));
        leftRailStub.add(label);

        terminalHost = new TerminalHost();
        add(leftRailStub, BorderLayout.WEST);
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
