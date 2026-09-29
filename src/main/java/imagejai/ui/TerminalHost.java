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
    private static final Color BG = new Color(26, 26, 32);

    private EmbeddedAgentSession session;
    private JComponent terminalComponent;
    private JLabel writeStatus;

    public TerminalHost() {
        super(new BorderLayout());
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder());
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
        writeStatus = new JLabel(" ");
        writeStatus.setForeground(new Color(220, 105, 95));
        writeStatus.setVisible(false);
        add(terminalComponent, BorderLayout.CENTER);
        add(writeStatus, BorderLayout.SOUTH);
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
        writeStatus = null;
        removeAll();
        showPlaceholder();
        revalidate();
        repaint();
    }

    public boolean isSession(EmbeddedAgentSession expected) {
        return expected != null && session == expected;
    }

    /** Write without hiding failure from the caller or the user. */
    public EmbeddedAgentSession.WriteResult writeRaw(String text) {
        EmbeddedAgentSession.WriteResult result = session == null
                ? EmbeddedAgentSession.WriteResult.failure("No embedded terminal session is attached.")
                : session.writeRaw(text);
        showWriteResult(result);
        return result;
    }

    private void showWriteResult(EmbeddedAgentSession.WriteResult result) {
        if (writeStatus == null) return;
        boolean failed = result == null || !result.isSuccess();
        writeStatus.setText(failed
                ? (result == null ? "Terminal write failed. Retry." : result.message())
                : " ");
        writeStatus.setVisible(failed);
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
        label.setForeground(new Color(150, 150, 162));
        add(label, BorderLayout.CENTER);
    }
}
