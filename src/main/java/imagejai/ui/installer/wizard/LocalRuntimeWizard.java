package imagejai.ui.installer.wizard;

import imagejai.install.ProcessRunner;
import imagejai.ui.installer.ProviderCredentials;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
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
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Install shape #3 — local runtime ± cloud account. Used by Ollama Local
 * (just needs the daemon) and Ollama Cloud (daemon + browser sign-in via
 * {@code ollama signin}). Detects the {@code ollama} binary on PATH and
 * surfaces a download link if missing.
 */
public class LocalRuntimeWizard implements InstallerWizard {

    public static final String OLLAMA_INSTALL_URL = "https://ollama.com/download";

    private final String providerKey;
    private final boolean cloudFlow;
    private final ProviderCredentials credentials;
    private final OllamaProbe probe;

    /** For tests: lets us inject a stub probe for the {@code ollama} binary. */
    public interface OllamaProbe {
        boolean ollamaInstalled();
    }

    public LocalRuntimeWizard(String providerKey,
                              ProviderCredentials credentials) {
        this(providerKey, credentials, () -> ProcessRunner.findOnPath("ollama") != null);
    }

    public LocalRuntimeWizard(String providerKey,
                              ProviderCredentials credentials,
                              OllamaProbe probe) {
        this.providerKey = providerKey;
        this.cloudFlow = "ollama-cloud".equals(providerKey);
        this.credentials = credentials;
        this.probe = probe;
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
                cloudFlow ? "Set up Ollama Cloud" : "Set up Ollama (local)",
                Dialog.ModalityType.DOCUMENT_MODAL);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 14, 10, 14));

        boolean ollamaPresent = probe.ollamaInstalled();
        StringBuilder header = new StringBuilder("<html><b>");
        header.append(cloudFlow ? "Ollama Cloud" : "Ollama (local)").append("</b><br>");
        if (ollamaPresent) {
            header.append("Ollama runtime detected.");
        } else {
            header.append("<font color='#a04030'>Ollama runtime is not installed.</font>");
        }
        header.append(cloudFlow
                ? "<br>Sign in with <code>ollama signin</code> to enable cloud models, then paste the resulting token."
                : "<br>Configure the daemon URL — defaults to <code>http://localhost:11434</code>.");
        header.append("</html>");
        content.add(new JLabel(header.toString()), BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0;
        body.add(new JLabel(cloudFlow ? "Daemon URL:" : "Daemon URL:"), c);
        c.gridx = 1; c.weightx = 1.0;
        final JTextField urlField = new JTextField("http://localhost:11434", 22);
        body.add(urlField, c);

        final JTextField tokenField = new JTextField(22);
        if (cloudFlow) {
            c.gridx = 0; c.gridy = 1; c.weightx = 0.0;
            body.add(new JLabel("Cloud token:"), c);
            c.gridx = 1; c.weightx = 1.0;
            body.add(tokenField, c);
        }

        content.add(body, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton install = new JButton("Install Ollama…");
        install.setEnabled(!ollamaPresent);
        install.addActionListener(e -> openUrl(OLLAMA_INSTALL_URL));
        JButton cancel = new JButton("Cancel");
        final boolean[] saved = new boolean[] { false };
        cancel.addActionListener(e -> dialog.dispose());
        JButton save = new JButton("Save");
        save.addActionListener(e -> {
            String url = urlField.getText().trim();
            if (url.isEmpty()) {
                JOptionPane.showMessageDialog(dialog,
                        "Daemon URL cannot be empty.",
                        "Missing URL", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Map<String, String> entries = new LinkedHashMap<String, String>();
            entries.put("OLLAMA_API_BASE", url);
            if (cloudFlow) {
                String token = tokenField.getText().trim();
                if (token.isEmpty()) {
                    JOptionPane.showMessageDialog(dialog,
                            "Run 'ollama signin' in a terminal to obtain a "
                                    + "cloud token, then paste it here.",
                            "Missing cloud token", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                entries.put("OLLAMA_API_KEY", token);
            }
            try {
                credentials.saveEntries(providerKey, entries);
                saved[0] = true;
                dialog.dispose();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog,
                        "Could not save: " + ex.getMessage(),
                        "Save failed", JOptionPane.ERROR_MESSAGE);
            }
        });
        buttons.add(install);
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

    private static void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "Open this URL manually:\n" + url,
                    "Browser unavailable", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
