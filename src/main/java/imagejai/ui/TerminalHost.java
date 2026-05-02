package imagejai.ui;

import ij.IJ;
import imagejai.engine.EmbeddedAgentSession;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Owns the terminal widget for the currently embedded agent session.
 */
public class TerminalHost extends JPanel {
    private static final Color BG = new Color(20, 20, 24);
    private static final Color BORDER = new Color(45, 45, 54);

    private EmbeddedAgentSession session;
    private JComponent terminalComponent;

    public TerminalHost() {
        super(new BorderLayout());
        setBackground(BG);
        setBorder(BorderFactory.createLineBorder(BORDER));
        showPlaceholder();
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (terminalComponent != null) {
                    terminalComponent.revalidate();
                }
            }
        });
    }

    public void attachSession(EmbeddedAgentSession newSession) {
        removeAll();
        session = newSession;
        terminalComponent = newSession.component();
        add(terminalComponent, BorderLayout.CENTER);
        revalidate();
        repaint();
        IJ.log("[ImageJAI-Term] Attached embedded terminal for " + newSession.info().name);
    }

    public void clearSession(EmbeddedAgentSession expected) {
        if (expected != null && session != expected) {
            return;
        }
        session = null;
        terminalComponent = null;
        removeAll();
        showPlaceholder();
        revalidate();
        repaint();
    }

    public void requestTerminalFocus() {
        if (terminalComponent == null) {
            return;
        }
        terminalComponent.requestFocusInWindow();
        for (Component child : terminalComponent.getComponents()) {
            if (child.requestFocusInWindow()) {
                return;
            }
        }
    }

    private void showPlaceholder() {
        JLabel label = new JLabel("No embedded agent running", SwingConstants.CENTER);
        label.setForeground(new Color(130, 130, 140));
        add(label, BorderLayout.CENTER);
    }
}
