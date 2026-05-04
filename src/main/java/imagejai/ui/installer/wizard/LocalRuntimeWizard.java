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
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Install shape #3 — local runtime ± cloud account. Used by Ollama Local
 * (just needs the daemon to be reachable) and Ollama Cloud (browser sign-in
 * via {@code ollama signin} surfaces a token the user pastes back). Detects
 * the {@code ollama} binary on PATH and surfaces a download link if missing.
 *
 * <p>Local-flow saves the daemon URL after a {@code GET /api/tags} probe with
 * a 2-second timeout (Phase E risk: "Ollama daemon URL trust"). Cloud-flow
 * saves only the cloud token — {@code OLLAMA_CLOUD_API_BASE} for the
 * proxy entry falls back to its {@code imagejai_default_api_base}
 * ({@code https://ollama.com}) so users don't need to type the cloud URL.
 */
public class LocalRuntimeWizard implements InstallerWizard {

    public static final String OLLAMA_INSTALL_URL = "https://ollama.com/download";
    public static final String DEFAULT_LOCAL_DAEMON_URL = "http://localhost:11434";
    /** Sanity-check timeout per Phase E risks. */
    public static final int DAEMON_PROBE_TIMEOUT_MS = 2000;

    private final String providerKey;
    private final boolean cloudFlow;
    private final ProviderCredentials credentials;
    private final OllamaProbe probe;
    private final DaemonProbe daemonProbe;

    /** For tests: lets us inject a stub probe for the {@code ollama} binary. */
    public interface OllamaProbe {
        boolean ollamaInstalled();
    }

    /** Result of the {@code GET /api/tags} sanity check. */
    public static final class DaemonResult {
        public final boolean ok;
        public final int httpCode;
        public final String message;

        public DaemonResult(boolean ok, int httpCode, String message) {
            this.ok = ok;
            this.httpCode = httpCode;
            this.message = message;
        }
    }

    /** Hits {@code <url>/api/tags} with a fixed timeout — pluggable for tests. */
    public interface DaemonProbe {
        DaemonResult probe(String url, int timeoutMs);
    }

    public LocalRuntimeWizard(String providerKey,
                              ProviderCredentials credentials) {
        this(providerKey, credentials,
                () -> ProcessRunner.findOnPath("ollama") != null,
                defaultDaemonProbe());
    }

    public LocalRuntimeWizard(String providerKey,
                              ProviderCredentials credentials,
                              OllamaProbe probe) {
        this(providerKey, credentials, probe, defaultDaemonProbe());
    }

    public LocalRuntimeWizard(String providerKey,
                              ProviderCredentials credentials,
                              OllamaProbe probe,
                              DaemonProbe daemonProbe) {
        this.providerKey = providerKey;
        this.cloudFlow = "ollama-cloud".equals(providerKey);
        this.credentials = credentials;
        this.probe = probe;
        this.daemonProbe = daemonProbe == null ? defaultDaemonProbe() : daemonProbe;
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

        // Local flow: daemon URL field. Cloud flow: token only — cloud's
        // api_base falls back to imagejai_default_api_base in litellm config.
        final JTextField urlField = new JTextField(DEFAULT_LOCAL_DAEMON_URL, 22);
        if (!cloudFlow) {
            c.gridx = 0; c.gridy = 0;
            body.add(new JLabel("Daemon URL:"), c);
            c.gridx = 1; c.weightx = 1.0;
            body.add(urlField, c);
        }

        final JTextField tokenField = new JTextField(22);
        if (cloudFlow) {
            c.gridx = 0; c.gridy = 0; c.weightx = 0.0;
            body.add(new JLabel("Cloud token:"), c);
            c.gridx = 1; c.weightx = 1.0;
            body.add(tokenField, c);
        }

        final JLabel statusLine = new JLabel(" ");
        statusLine.setFont(statusLine.getFont().deriveFont(11f));
        c.gridx = 0; c.gridy = 1; c.gridwidth = 2; c.weightx = 1.0;
        body.add(statusLine, c);

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
            Map<String, String> entries = new LinkedHashMap<String, String>();
            if (cloudFlow) {
                String token = tokenField.getText().trim();
                if (token.isEmpty()) {
                    setError(statusLine, "Run 'ollama signin' in a terminal to obtain a "
                            + "cloud token, then paste it here.");
                    return;
                }
                entries.put("OLLAMA_API_KEY", token);
            } else {
                String url = urlField.getText().trim();
                if (url.isEmpty()) {
                    setError(statusLine, "Daemon URL cannot be empty.");
                    return;
                }
                DaemonResult result = daemonProbe.probe(url, DAEMON_PROBE_TIMEOUT_MS);
                if (!result.ok) {
                    setError(statusLine, "Could not reach " + url + "/api/tags: "
                            + result.message);
                    return;
                }
                setOk(statusLine, "Daemon reachable (HTTP " + result.httpCode + ")");
                entries.put("OLLAMA_API_BASE", url);
            }
            try {
                credentials.saveEntries(providerKey, entries);
                saved[0] = true;
                dialog.dispose();
            } catch (IOException ex) {
                setError(statusLine, "Could not save: " + ex.getMessage());
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

    private static void setError(JLabel label, String message) {
        label.setForeground(new Color(0xa0, 0x30, 0x30));
        label.setText(message);
    }

    private static void setOk(JLabel label, String message) {
        label.setForeground(new Color(0x20, 0x70, 0x30));
        label.setText(message);
    }

    private static DaemonProbe defaultDaemonProbe() {
        return (url, timeoutMs) -> {
            HttpURLConnection conn = null;
            try {
                String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                URL endpoint = new URL(base + "/api/tags");
                conn = (HttpURLConnection) endpoint.openConnection();
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    return new DaemonResult(true, code, "ok");
                }
                return new DaemonResult(false, code, "HTTP " + code);
            } catch (Exception ex) {
                return new DaemonResult(false, 0, ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (RuntimeException ignored) { }
                }
            }
        };
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
