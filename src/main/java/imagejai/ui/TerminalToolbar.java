package imagejai.ui;

import ij.IJ;
import imagejai.engine.EmbeddedAgentSession;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Toolbar for terminal prompt handling and session controls.
 */
public final class TerminalToolbar extends JPanel {
    private static final int URL_VISIBLE_MS = 30000;
    private static final Color BG = new Color(26, 26, 32);
    private static final Color BUTTON_BG = new Color(37, 37, 44);
    private static final Color BUTTON_BORDER = new Color(56, 56, 66);
    private static final Color TEXT = new Color(230, 230, 235);

    private final JButton confirmButton = new JButton("Confirm");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton interruptButton = new JButton("Interrupt");
    private final JButton killButton = new JButton("Kill session");
    private final JButton copyUrlButton = new JButton("Copy URL");
    private final JLabel writeStatus = new JLabel(" ");
    private final Runnable focusReturn;
    private final Timer urlTimer;

    private EmbeddedAgentSession session;
    private String pendingPrompt;
    private String latestUrl;

    public TerminalToolbar(Runnable focusReturn) {
        super(new BorderLayout());
        this.focusReturn = focusReturn;
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        buttons.setOpaque(false);
        buttons.add(confirmButton);
        buttons.add(cancelButton);
        buttons.add(interruptButton);
        buttons.add(killButton);
        buttons.add(copyUrlButton);
        add(buttons, BorderLayout.CENTER);
        writeStatus.setForeground(new Color(220, 105, 95));
        writeStatus.setVisible(false);
        add(writeStatus, BorderLayout.SOUTH);
        styleButton(confirmButton);
        styleButton(cancelButton);
        styleButton(interruptButton);
        styleButton(killButton);
        styleButton(copyUrlButton);

        confirmButton.setVisible(false);
        cancelButton.setVisible(false);
        copyUrlButton.setVisible(false);

        confirmButton.addActionListener(e -> {
            EmbeddedAgentSession.WriteResult result = session == null
                    ? EmbeddedAgentSession.WriteResult.failure("No terminal session is attached.")
                    : session.writeRaw("\r");
            if (result.isSuccess()) {
                clearPendingPrompt();
                clearWriteFailure();
            } else {
                showWriteFailure(result);
            }
            refocus();
        });
        cancelButton.addActionListener(e -> {
            EmbeddedAgentSession.WriteResult result = session == null
                    ? EmbeddedAgentSession.WriteResult.failure("No terminal session is attached.")
                    : session.writeRaw("\u001b");
            if (result.isSuccess()) {
                clearPendingPrompt();
                clearWriteFailure();
            } else {
                showWriteFailure(result);
            }
            refocus();
        });
        interruptButton.addActionListener(e -> {
            if (session != null) {
                session.interrupt();
            }
            refocus();
        });
        killButton.addActionListener(e -> {
            EmbeddedAgentSession current = session;
            if (current == null) {
                refocus();
                return;
            }
            int result = JOptionPane.showConfirmDialog(
                    SwingUtilities.getWindowAncestor(this),
                    "Kill the embedded agent session?",
                    "Kill session",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (result == JOptionPane.OK_OPTION) {
                current.destroy();
                IJ.log("[ImageJAI-Term] User killed embedded session: "
                        + current.info().name);
            }
            refocus();
        });
        copyUrlButton.addActionListener(e -> {
            if (latestUrl != null && !latestUrl.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(latestUrl), null);
            }
            refocus();
        });

        urlTimer = new Timer(URL_VISIBLE_MS, e -> hideCopyUrl());
        urlTimer.setRepeats(false);
    }

    public void attachSession(EmbeddedAgentSession newSession) {
        this.session = newSession;
        clearWriteFailure();
        clearPendingPrompt();
        hideCopyUrl();
    }

    public void clearSession(EmbeddedAgentSession expected) {
        if (expected != null && session != expected) {
            return;
        }
        session = null;
        clearWriteFailure();
        clearPendingPrompt();
        hideCopyUrl();
    }

    public void showPendingPrompt(String promptText) {
        pendingPrompt = promptText;
        confirmButton.setVisible(true);
        cancelButton.setVisible(true);
        revalidate();
        repaint();
    }

    public void clearPendingPrompt() {
        pendingPrompt = null;
        confirmButton.setVisible(false);
        cancelButton.setVisible(false);
        revalidate();
        repaint();
    }

    public void showCopyUrl(String url) {
        latestUrl = url;
        copyUrlButton.setToolTipText(url);
        copyUrlButton.setVisible(true);
        urlTimer.restart();
        revalidate();
        repaint();
    }

    public void showEscalationModal(String promptText) {
        JTextArea area = new JTextArea(promptText == null ? "" : promptText);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(520, 180));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("The agent is asking permission for:"), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        Object[] options = {"Allow", "Deny"};
        int result = JOptionPane.showOptionDialog(
                SwingUtilities.getWindowAncestor(this),
                panel,
                "The agent is asking permission",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]);
        if (session != null) {
            EmbeddedAgentSession.WriteResult write =
                    session.writeRaw(result == 0 ? "\r" : "\u001b");
            if (!write.isSuccess()) {
                showPendingPrompt(promptText);
                showWriteFailure(write);
            } else {
                clearWriteFailure();
            }
        }
        refocus();
    }

    public String pendingPrompt() {
        return pendingPrompt;
    }

    private void hideCopyUrl() {
        latestUrl = null;
        copyUrlButton.setVisible(false);
        urlTimer.stop();
        revalidate();
        repaint();
    }

    private void refocus() {
        if (focusReturn != null) {
            SwingUtilities.invokeLater(focusReturn);
        }
    }

    private void showWriteFailure(EmbeddedAgentSession.WriteResult result) {
        writeStatus.setText(result == null ? "Terminal write failed. Retry."
                : result.message());
        writeStatus.setVisible(true);
        revalidate();
        repaint();
    }

    private void clearWriteFailure() {
        writeStatus.setText(" ");
        writeStatus.setVisible(false);
    }

    private static void styleButton(JButton button) {
        button.setForeground(TEXT);
        button.setBackground(BUTTON_BG);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BUTTON_BORDER),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        button.setFocusPainted(false);
    }
}
