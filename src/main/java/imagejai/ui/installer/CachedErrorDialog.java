package imagejai.ui.installer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;

/**
 * Modal shown when a provider's status icon is ✗ (cached error). Shows the
 * most recent error message and offers a "Retry now" button that re-runs the
 * provider's discovery probe per docs/multi_provider/06_tier_safety.md §4.3.
 *
 * <p>The retry probe is supplied by the caller (Phase G owns real
 * {@code /models} discovery; Phase E surfaces the dialog hookup). When retry
 * succeeds the dialog closes; on failure the message updates in place.
 */
public class CachedErrorDialog {

    /** Caller-supplied retry probe — returns null on success, error message on failure. */
    public interface RetryProbe {
        String probe();
    }

    /** Caller-supplied "open the installer for this provider" hook. */
    public interface ReconfigureAction {
        void reconfigure();
    }

    private final String providerDisplayName;
    private final String lastError;
    private final RetryProbe retry;
    private final ReconfigureAction reconfigure;

    public CachedErrorDialog(String providerDisplayName,
                             String lastError,
                             RetryProbe retry,
                             ReconfigureAction reconfigure) {
        this.providerDisplayName = providerDisplayName;
        this.lastError = lastError == null ? "(no cached error)" : lastError;
        this.retry = retry;
        this.reconfigure = reconfigure;
    }

    public void show(Component parent) {
        Frame owner = parent == null ? null
                : (Frame) SwingUtilities.getAncestorOfClass(Frame.class, parent);
        final JDialog dialog = new JDialog(owner,
                providerDisplayName + " is not responding",
                Dialog.ModalityType.DOCUMENT_MODAL);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 14, 10, 14));

        JLabel header = new JLabel("<html><b>" + providerDisplayName
                + " is not responding.</b><br>"
                + "ImageJAI cached the most recent error from this provider.</html>");
        content.add(header, BorderLayout.NORTH);

        final JTextArea body = new JTextArea(lastError, 6, 56);
        body.setEditable(false);
        body.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new java.awt.Color(220, 200, 200)),
                new EmptyBorder(6, 8, 6, 8)));
        content.add(new JScrollPane(body), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton retryBtn = new JButton("Retry now");
        retryBtn.addActionListener(e -> runRetry(dialog, retryBtn, body));
        JButton reconfigureBtn = new JButton("Reconfigure key");
        reconfigureBtn.setEnabled(reconfigure != null);
        reconfigureBtn.addActionListener(e -> {
            if (reconfigure != null) {
                reconfigure.reconfigure();
            }
            dialog.dispose();
        });
        JButton close = new JButton("Pick another");
        close.addActionListener(e -> dialog.dispose());
        buttons.add(retryBtn);
        buttons.add(reconfigureBtn);
        buttons.add(close);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        Dimension preferred = dialog.getPreferredSize();
        dialog.setSize(Math.max(preferred.width, 480), preferred.height);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private void runRetry(final JDialog dialog,
                          final JButton retryBtn,
                          final JTextArea body) {
        if (retry == null) {
            body.setText("(no retry probe available)");
            return;
        }
        retryBtn.setEnabled(false);
        body.setText("Retrying…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    return retry.probe();
                } catch (Exception ex) {
                    return ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
            }

            @Override
            protected void done() {
                retryBtn.setEnabled(true);
                String result;
                try {
                    result = get();
                } catch (Exception ex) {
                    result = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
                if (result == null) {
                    body.setText("Retry succeeded — provider is reachable again.");
                    dialog.dispose();
                } else {
                    body.setText(result);
                }
            }
        }.execute();
    }
}
