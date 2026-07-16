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
import java.awt.event.KeyEvent;

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

    interface SessionControl {
        boolean isAlive();
        EmbeddedAgentSession.WriteResult writeRaw(String text);
        void interrupt();
        void destroy();
        String displayName();
    }

    private EmbeddedAgentSession attachedSession;
    private SessionControl sessionControl;
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
        configureAccessibility();

        confirmButton.setVisible(false);
        cancelButton.setVisible(false);
        copyUrlButton.setVisible(false);

        confirmButton.addActionListener(e -> {
            EmbeddedAgentSession.WriteResult result = !hasLiveSession()
                    ? EmbeddedAgentSession.WriteResult.failure("No terminal session is attached.")
                    : sessionControl.writeRaw("\r");
            if (result.isSuccess()) {
                clearPendingPrompt();
                clearWriteFailure();
            } else {
                showWriteFailure(result);
            }
            refocus();
        });
        cancelButton.addActionListener(e -> {
            EmbeddedAgentSession.WriteResult result = !hasLiveSession()
                    ? EmbeddedAgentSession.WriteResult.failure("No terminal session is attached.")
                    : sessionControl.writeRaw("\u001b");
            if (result.isSuccess()) {
                clearPendingPrompt();
                clearWriteFailure();
            } else {
                showWriteFailure(result);
            }
            refocus();
        });
        interruptButton.addActionListener(e -> {
            if (hasLiveSession()) {
                sessionControl.interrupt();
            }
            updateControlState();
            refocus();
        });
        killButton.addActionListener(e -> {
            SessionControl current = sessionControl;
            if (!hasLiveSession() || current == null) {
                updateControlState();
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
                        + current.displayName());
            }
            updateControlState();
            refocus();
        });
        copyUrlButton.addActionListener(e -> {
            if (hasLiveSession() && latestUrl != null && !latestUrl.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(latestUrl), null);
            }
            updateControlState();
            refocus();
        });

        urlTimer = new Timer(URL_VISIBLE_MS, e -> hideCopyUrl());
        urlTimer.setRepeats(false);
        updateControlState();
    }

    public void attachSession(EmbeddedAgentSession newSession) {
        this.attachedSession = newSession;
        this.sessionControl = newSession == null ? null : new SessionControl() {
            @Override public boolean isAlive() { return newSession.isAlive(); }
            @Override public EmbeddedAgentSession.WriteResult writeRaw(String text) {
                return newSession.writeRaw(text);
            }
            @Override public void interrupt() { newSession.interrupt(); }
            @Override public void destroy() { newSession.destroy(); }
            @Override public String displayName() { return newSession.info().name; }
        };
        clearWriteFailure();
        clearPendingPrompt();
        hideCopyUrl();
        updateControlState();
    }

    public void clearSession(EmbeddedAgentSession expected) {
        if (expected != null && attachedSession != expected) {
            return;
        }
        attachedSession = null;
        sessionControl = null;
        clearWriteFailure();
        clearPendingPrompt();
        hideCopyUrl();
        updateControlState();
    }

    public void showPendingPrompt(String promptText) {
        pendingPrompt = promptText;
        confirmButton.setVisible(true);
        cancelButton.setVisible(true);
        updateControlState();
        revalidate();
        repaint();
    }

    public void clearPendingPrompt() {
        pendingPrompt = null;
        confirmButton.setVisible(false);
        cancelButton.setVisible(false);
        updateControlState();
        revalidate();
        repaint();
    }

    public void showCopyUrl(String url) {
        latestUrl = url;
        copyUrlButton.setToolTipText(url);
        copyUrlButton.setVisible(true);
        updateControlState();
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
        if (hasLiveSession()) {
            EmbeddedAgentSession.WriteResult write =
                    sessionControl.writeRaw(result == 0 ? "\r" : "\u001b");
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
        updateControlState();
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

    private void configureAccessibility() {
        getAccessibleContext().setAccessibleName("Terminal controls");
        getAccessibleContext().setAccessibleDescription(
                "Confirm, cancel, interrupt, or end the current terminal session.");
        configureButton(confirmButton, "Confirm terminal prompt",
                "Send Enter to confirm the pending terminal prompt.", KeyEvent.VK_C);
        configureButton(cancelButton, "Cancel terminal prompt",
                "Send Escape to deny or cancel the pending terminal prompt.", KeyEvent.VK_A);
        configureButton(interruptButton, "Interrupt terminal session",
                "Send an interrupt signal to the running agent.", KeyEvent.VK_I);
        configureButton(killButton, "Kill terminal session",
                "End the running embedded agent after confirmation.", KeyEvent.VK_K);
        configureButton(copyUrlButton, "Copy terminal URL",
                "Copy the sign-in URL reported by the running agent.", KeyEvent.VK_U);
    }

    private static void configureButton(JButton button, String name,
                                        String description, int mnemonic) {
        button.setFocusable(true);
        button.setMnemonic(mnemonic);
        button.getAccessibleContext().setAccessibleName(name);
        button.getAccessibleContext().setAccessibleDescription(description);
    }

    private boolean hasLiveSession() {
        try {
            return sessionControl != null && sessionControl.isAlive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void updateControlState() {
        boolean live = hasLiveSession();
        boolean hasPrompt = pendingPrompt != null;
        confirmButton.setEnabled(live && hasPrompt);
        cancelButton.setEnabled(live && hasPrompt);
        interruptButton.setEnabled(live);
        killButton.setEnabled(live);
        copyUrlButton.setEnabled(live && latestUrl != null && !latestUrl.isEmpty());
    }

    void attachSessionForTest(SessionControl control) {
        attachedSession = null;
        sessionControl = control;
        clearWriteFailure();
        clearPendingPrompt();
        hideCopyUrl();
        updateControlState();
    }

    JButton confirmButtonForTest() { return confirmButton; }
    JButton cancelButtonForTest() { return cancelButton; }
    JButton interruptButtonForTest() { return interruptButton; }
    JButton killButtonForTest() { return killButton; }
    JButton copyUrlButtonForTest() { return copyUrlButton; }
}
