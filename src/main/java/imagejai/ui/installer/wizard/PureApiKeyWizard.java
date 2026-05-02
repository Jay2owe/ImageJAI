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
 */
public class PureApiKeyWizard implements InstallerWizard {

    protected final String providerKey;
    protected final String displayName;
    protected final String signupUrl;
    protected final String envVarName;
    protected final ProviderCredentials credentials;

    public PureApiKeyWizard(String providerKey,
                            String displayName,
                            String signupUrl,
                            ProviderCredentials credentials) {
        this.providerKey = providerKey;
        this.displayName = displayName;
        this.signupUrl = signupUrl;
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
                JOptionPane.showMessageDialog(dialog,
                        "Please paste your API key first.",
                        "Missing key", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                credentials.saveApiKey(providerKey, value);
                saved[0] = true;
                dialog.dispose();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog,
                        "Could not save key: " + ex.getMessage(),
                        "Save failed", JOptionPane.ERROR_MESSAGE);
            }
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
