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
 */
public class BrowserAuthWizard implements InstallerWizard {

    private final String providerKey;
    private final String displayName;
    private final String signupUrl;
    private final String cliHint;
    private final String envVarName;
    private final ProviderCredentials credentials;

    public BrowserAuthWizard(String providerKey,
                             String displayName,
                             String signupUrl,
                             String cliHint,
                             ProviderCredentials credentials) {
        this.providerKey = providerKey;
        this.displayName = displayName;
        this.signupUrl = signupUrl;
        this.cliHint = cliHint;
        this.envVarName = ProviderCredentials.ENV_VAR_FOR_PROVIDER.get(providerKey);
        this.credentials = credentials;
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
                JOptionPane.showMessageDialog(dialog,
                        "Paste the token from the sign-in flow first.",
                        "Missing token", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                credentials.saveApiKey(providerKey, value);
                saved[0] = true;
                dialog.dispose();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog,
                        "Could not save token: " + ex.getMessage(),
                        "Save failed", JOptionPane.ERROR_MESSAGE);
            }
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
