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
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.net.URI;

/**
 * Install shape #2 — provider expects the user to authenticate in a browser
 * (or via a CLI sub-command). After auth, the user pastes the resulting token
 * back. Used today by GitHub Models (which surfaces a PAT after
 * {@code gh auth login}) and Gemini (which surfaces an API key after
 * AI-Studio sign-in).
 *
 * <p>This is the paste-token fallback the Phase E plan calls out — the
 * "browser-auth localhost callback" path is unused for these providers
 * (no Phase-E provider exposes a redirect URL we can listen on), so the
 * port-collision risk in the register does not apply here.
 *
 * <p>Save flow per Phase E acceptance: persist the token, then synchronously
 * call the {@link CredentialVerifier} with a 4 s timeout. On success the
 * dialog disposes; on failure the error message renders inline in red.
 */
public class BrowserAuthWizard implements InstallerWizard {

    /** 4 s budget per {@code 06 §4.4} manual-refresh timeout. */
    public static final int VERIFY_TIMEOUT_MS = PureApiKeyWizard.VERIFY_TIMEOUT_MS;

    private final String providerKey;
    private final String displayName;
    private final String signupUrl;
    private final String cliHint;
    private final String envVarName;
    private final ProviderCredentials credentials;
    private final CredentialVerifier verifier;

    public BrowserAuthWizard(String providerKey,
                             String displayName,
                             String signupUrl,
                             String cliHint,
                             ProviderCredentials credentials) {
        this(providerKey, displayName, signupUrl, cliHint, credentials, CredentialVerifier.noop());
    }

    public BrowserAuthWizard(String providerKey,
                             String displayName,
                             String signupUrl,
                             String cliHint,
                             ProviderCredentials credentials,
                             CredentialVerifier verifier) {
        this.providerKey = providerKey;
        this.displayName = displayName;
        this.signupUrl = signupUrl;
        this.cliHint = cliHint;
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
                "Sign in to " + displayName, Dialog.ModalityType.DOCUMENT_MODAL);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 14, 10, 14));

        JLabel header = new JLabel("<html><b>" + displayName + " — sign in</b><br>"
                + "Sign in with your browser, copy the resulting token, paste it below.</html>");
        content.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.gridwidth = 3;
        JTextArea steps = new JTextArea(numberedSteps());
        steps.setEditable(false);
        steps.setOpaque(false);
        steps.setFont(steps.getFont().deriveFont(Font.PLAIN, 12f));
        body.add(steps, c);

        c.gridy = 1; c.gridwidth = 1;
        body.add(new JLabel("Token:"), c);
        c.gridx = 1; c.weightx = 1.0; c.gridwidth = 2;
        final JPasswordField tokenField = new JPasswordField(28);
        body.add(tokenField, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 3; c.weightx = 1.0;
        final JLabel statusLine = new JLabel(" ");
        statusLine.setFont(statusLine.getFont().deriveFont(11f));
        body.add(statusLine, c);

        content.add(body, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton openSignin = new JButton("Open sign-in page");
        openSignin.setEnabled(signupUrl != null && !signupUrl.isEmpty());
        openSignin.addActionListener(e -> openUrl(signupUrl));
        JButton cancel = new JButton("Cancel");
        final boolean[] saved = new boolean[] { false };
        cancel.addActionListener(e -> dialog.dispose());
        JButton save = new JButton("Save & test");
        save.addActionListener(e -> {
            String value = new String(tokenField.getPassword()).trim();
            if (value.isEmpty()) {
                setError(statusLine, "Paste the token from the sign-in flow first.");
                return;
            }
            try {
                credentials.saveApiKey(providerKey, value);
            } catch (IOException ex) {
                setError(statusLine, "Could not save token: " + ex.getMessage());
                return;
            }
            setBusy(statusLine, "Verifying…");
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
        buttons.add(openSignin);
        buttons.add(Box.createHorizontalStrut(12));
        buttons.add(cancel);
        buttons.add(save);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.getRootPane().setDefaultButton(save);
        dialog.setContentPane(content);
        dialog.pack();
        Dimension preferred = dialog.getPreferredSize();
        dialog.setSize(Math.max(preferred.width, 480), preferred.height);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return saved[0];
    }

    private String numberedSteps() {
        StringBuilder sb = new StringBuilder();
        sb.append("1. Click \"Open sign-in page\" — your default browser opens.\n");
        if (cliHint != null && !cliHint.isEmpty()) {
            sb.append("   (Or in a terminal: ").append(cliHint).append(")\n");
        }
        sb.append("2. Sign in and copy the token shown.\n");
        sb.append("3. Paste it below — saved locally as ").append(envVarName)
                .append(" in <config>/secrets/").append(providerKey).append(".env.\n");
        return sb.toString();
    }

    private static void setError(JLabel label, String message) {
        label.setForeground(new Color(0xa0, 0x30, 0x30));
        label.setText(message);
    }

    private static void setBusy(JLabel label, String message) {
        label.setForeground(new Color(0x30, 0x30, 0x30));
        label.setText(message);
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
