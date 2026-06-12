package imagejai.ui.installer.wizard;

import imagejai.ui.installer.ProviderCredentials;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.IOException;
import java.net.URI;

/**
 * Install shape #1 — single API key paste field. Used by every provider whose
 * signup gives the user a literal token to paste (Groq, Cerebras, OpenRouter,
 * Mistral, Together, HuggingFace, DeepSeek, xAI, Perplexity, plus Gemini's
 * AI-Studio fallback).
 *
 * <p>Save flow per Phase E acceptance: persist the key, then synchronously
 * call the {@link CredentialVerifier} with a 4 s timeout. On success the
 * dialog disposes; on failure the error message renders inline in red and
 * the dialog stays open for a retry.
 */
public class PureApiKeyWizard implements InstallerWizard {

    /** 4 s budget per {@code 06 §4.4} manual-refresh timeout. */
    public static final int VERIFY_TIMEOUT_MS = 4000;

    protected final String providerKey;
    protected final String displayName;
    protected final String signupUrl;
    protected final String envVarName;
    protected final ProviderCredentials credentials;
    protected final CredentialVerifier verifier;

    public PureApiKeyWizard(String providerKey,
                            String displayName,
                            String signupUrl,
                            ProviderCredentials credentials) {
        this(providerKey, displayName, signupUrl, credentials, CredentialVerifier.noop());
    }

    public PureApiKeyWizard(String providerKey,
                            String displayName,
                            String signupUrl,
                            ProviderCredentials credentials,
                            CredentialVerifier verifier) {
        this.providerKey = providerKey;
        this.displayName = displayName;
        this.signupUrl = signupUrl;
        this.envVarName = ProviderCredentials.ENV_VAR_FOR_PROVIDER.get(providerKey);
        this.credentials = credentials;
        this.verifier = verifier == null ? CredentialVerifier.noop() : verifier;
    }

    @Override
    public String providerKey() {
        return providerKey;
    }

    @Override
    public boolean showAndSave(Component parent) {
        Frame owner = parent == null ? null
                : (Frame) SwingUtilities.getAncestorOfClass(Frame.class, parent);
        final JDialog dialog = new JDialog(owner,
                "Set up " + displayName, Dialog.ModalityType.DOCUMENT_MODAL);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 14, 10, 14));

        StringBuilder header = new StringBuilder();
        header.append("<html><b>").append(displayName).append("</b><br>");
        header.append(headerSubtext());
        header.append("</html>");
        content.add(new JLabel(header.toString()), BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0;
        body.add(new JLabel("API key:"), c);
        c.gridx = 1; c.weightx = 1.0;
        final JPasswordField keyField = new JPasswordField(28);
        body.add(keyField, c);

        c.gridx = 2; c.weightx = 0.0;
        JButton paste = new JButton("Paste");
        paste.setMargin(new Insets(2, 8, 2, 8));
        paste.addActionListener(e -> pasteFromClipboard(keyField));
        body.add(paste, c);

        c.gridx = 0; c.gridy = 1; c.gridwidth = 3;
        JLabel envHint = new JLabel(
                "<html><i>Saved as " + envVarName
                + " in &lt;config&gt;/secrets/" + providerKey
                + ".env. Loaded by the LiteLLM proxy at startup.</i></html>");
        envHint.setFont(envHint.getFont().deriveFont(11f));
        body.add(envHint, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 3;
        final JLabel statusLine = new JLabel(" ");
        statusLine.setFont(statusLine.getFont().deriveFont(11f));
        body.add(statusLine, c);

        content.add(body, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton getKeyBtn = new JButton("Get key…");
        getKeyBtn.setEnabled(signupUrl != null && !signupUrl.isEmpty());
        getKeyBtn.addActionListener(e -> openUrl(signupUrl));
        JButton cancel = new JButton("Cancel");
        final boolean[] saved = new boolean[] { false };
        cancel.addActionListener(e -> dialog.dispose());
        JButton save = new JButton("Save & test");
        save.addActionListener(e -> {
            String value = new String(keyField.getPassword()).trim();
            if (value.isEmpty()) {
                setError(statusLine, "Please paste your API key first.");
                return;
            }
            try {
                credentials.saveApiKey(providerKey, value);
            } catch (IOException ex) {
                setError(statusLine, "Could not save key: " + ex.getMessage());
                return;
            }
            setBusy(statusLine, "Verifying…");
            // Synchronous call honouring the 4 s budget — see VERIFY_TIMEOUT_MS.
            CredentialVerifier.Result result;
            try {
                result = verifier.verify(providerKey, VERIFY_TIMEOUT_MS);
            } catch (RuntimeException re) {
                result = CredentialVerifier.Result.failure(
                        "verifier threw " + re.getClass().getSimpleName()
                                + (re.getMessage() == null ? "" : ": " + re.getMessage()));
            }
            if (result == null || !result.ok) {
                String msg = result == null ? "verifier returned null" : result.message;
                setError(statusLine, "Verification failed: " + msg);
                return;
            }
            saved[0] = true;
            dialog.dispose();
        });
        buttons.add(getKeyBtn);
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(cancel);
        buttons.add(save);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.getRootPane().setDefaultButton(save);
        dialog.setContentPane(content);
        dialog.pack();
        Dimension preferred = dialog.getPreferredSize();
        dialog.setSize(Math.max(preferred.width, 460), preferred.height);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return saved[0];
    }

    /** Subclasses override for "billing required" verbiage. */
    protected String headerSubtext() {
        return "Paste your API key. Stored locally — never uploaded.";
    }

    private static void setError(JLabel label, String message) {
        label.setForeground(new Color(0xa0, 0x30, 0x30));
        label.setText(message);
    }

    private static void setBusy(JLabel label, String message) {
        label.setForeground(new Color(0x30, 0x30, 0x30));
        label.setText(message);
    }

    private static void pasteFromClipboard(JPasswordField field) {
        try {
            Transferable contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
                if (text != null) {
                    field.setText(text.trim());
                }
            }
        } catch (Exception ignored) {
            // Clipboard access can throw on locked sessions — silently no-op.
        }
    }

    private static void openUrl(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "Open this URL manually:\n" + url,
                    "Browser unavailable", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
