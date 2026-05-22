package imagejai.ui;

import imagejai.engine.security.OutboundPromptScrubber;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Non-modal prompt-scrubber notification shown inside the launcher panel.
 */
public final class PseudonymisationToast extends JPanel
        implements OutboundPromptScrubber.Notifier {
    private static final Color BG = new Color(42, 37, 28);
    private static final Color BORDER = new Color(124, 95, 44);
    private static final Color TEXT = new Color(242, 226, 190);

    private final JLabel title;
    private final JLabel detail;
    private final Timer hideTimer;

    public PseudonymisationToast() {
        super(new BorderLayout(8, 0));
        setBackground(BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(6, 8, 6, 8)));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
        text.setOpaque(false);
        title = new JLabel("Pseudonymised before send:");
        title.setForeground(TEXT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        detail = new JLabel("");
        detail.setForeground(TEXT);
        detail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        text.add(title);
        text.add(detail);
        add(text, BorderLayout.CENTER);

        JButton ok = new JButton("OK");
        ok.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        ok.setMargin(new java.awt.Insets(1, 6, 1, 6));
        ok.addActionListener(e -> dismiss());
        add(ok, BorderLayout.EAST);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dismiss();
            }
        });

        hideTimer = new Timer(5000, e -> dismiss());
        hideTimer.setRepeats(false);
        setVisible(false);
    }

    @Override
    public void pseudonymised(final int replacementCount) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                showMessage(replacementCount + " sensitive substring(s) replaced.");
            }
        });
    }

    @Override
    public void pseudonymised(final List<OutboundPromptScrubber.Replacement> replacements) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (replacements == null || replacements.isEmpty()) {
                    showMessage("Sensitive substring replaced.");
                    return;
                }
                OutboundPromptScrubber.Replacement first = replacements.get(0);
                String message = "'" + clip(first.original()) + "' -> '"
                        + clip(first.token()) + "'";
                if (replacements.size() > 1) {
                    message += " (+" + (replacements.size() - 1) + " more)";
                }
                showMessage(message);
            }
        });
    }

    @Override
    public void sentRaw() {
    }

    private void showMessage(String message) {
        detail.setText(message == null ? "" : message);
        setVisible(true);
        revalidate();
        repaint();
        hideTimer.restart();
    }

    private void dismiss() {
        hideTimer.stop();
        setVisible(false);
    }

    private static String clip(String value) {
        String v = value == null ? "" : value;
        return v.length() <= 48 ? v : v.substring(0, 45) + "...";
    }

    String detailTextForTest() {
        return detail.getText();
    }
}
