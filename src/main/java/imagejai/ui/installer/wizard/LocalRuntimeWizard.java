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
import javax.swing.SwingWorker;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Install shape #3 — local runtime ± cloud account. Used by Ollama Local
 * (just needs the daemon to be reachable) and Ollama Cloud (authentication is
 * managed by {@code ollama signin}). Detects the {@code ollama} binary on PATH
 * and surfaces a download link if missing.
 *
 * <p>Local-flow saves the daemon URL after a {@code GET /api/tags} probe with
 * a 2-second timeout (Phase E risk: "Ollama daemon URL trust"). Cloud-flow is
 * informational: ImageJAI neither receives nor stores the Ollama sign-in
 * token because there is no authenticated candidate-token verification API.
 */
public class LocalRuntimeWizard implements InstallerWizard {

    public static final String OLLAMA_INSTALL_URL = "https://ollama.com/download";
    public static final String DEFAULT_LOCAL_DAEMON_URL = "http://localhost:11434";
    /** Sanity-check timeout per Phase E risks. */
    public static final int DAEMON_PROBE_TIMEOUT_MS = 2000;
    /** Compatibility validation budget for programmatic credential callers. */
    public static final int VERIFY_TIMEOUT_MS = 4000;

    /** Per-provider local-runtime facts so this one wizard serves every
     *  keyless local daemon (Ollama, LM Studio, Jan, llama.cpp, vLLM). */
    private static final class RuntimeMeta {
        final String displayName;
        final String defaultUrl;
        final String healthPath;
        final String installUrl;
        RuntimeMeta(String displayName, String defaultUrl, String healthPath, String installUrl) {
            this.displayName = displayName;
            this.defaultUrl = defaultUrl;
            this.healthPath = healthPath;
            this.installUrl = installUrl;
        }
    }

    private static final Map<String, RuntimeMeta> RUNTIME_META;
    static {
        Map<String, RuntimeMeta> m = new LinkedHashMap<String, RuntimeMeta>();
        // Ollama uses its native /api/tags health check; the OpenAI-compatible
        // servers default to an OpenAI base (…/v1) and check /v1/models.
        m.put("ollama", new RuntimeMeta("Ollama (local)",
                "http://localhost:11434", "/api/tags", OLLAMA_INSTALL_URL));
        m.put("ollama-cloud", new RuntimeMeta("Ollama Cloud",
                "http://localhost:11434", "/api/tags", OLLAMA_INSTALL_URL));
        m.put("lmstudio", new RuntimeMeta("LM Studio (local)",
                "http://localhost:1234/v1", "/models", "https://lmstudio.ai"));
        m.put("jan", new RuntimeMeta("Jan (local)",
                "http://localhost:1337/v1", "/models", "https://jan.ai"));
        m.put("llamacpp", new RuntimeMeta("llama.cpp (local)",
                "http://localhost:8080/v1", "/models", "https://github.com/ggml-org/llama.cpp"));
        m.put("vllm", new RuntimeMeta("vLLM (local)",
                "http://localhost:8000/v1", "/models", "https://docs.vllm.ai"));
        RUNTIME_META = Collections.unmodifiableMap(m);
    }

    private static RuntimeMeta metaFor(String providerKey) {
        RuntimeMeta rm = RUNTIME_META.get(providerKey);
        return rm != null ? rm : RUNTIME_META.get("ollama");
    }

    private static String healthPathFor(String providerKey) {
        return metaFor(providerKey).healthPath;
    }

    /**
     * Normalise the URL the user typed before probing/saving. OpenAI-compatible
     * local servers must end in {@code /v1} (what LiteLLM's {@code *_API_BASE}
     * and the {@code /models} health check assume); Ollama keeps its native root
     * URL. Tolerant of a user who types the host root without {@code /v1}.
     */
    private String normaliseRuntimeUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (isOllamaFamily()) {
            return trimmed;
        }
        return trimmed.endsWith("/v1") ? trimmed : trimmed + "/v1";
    }

    /** True for the Ollama family, where a CLI binary on PATH is detectable. */
    private boolean isOllamaFamily() {
        return "ollama".equals(providerKey) || "ollama-cloud".equals(providerKey);
    }

    static String cloudSignInInstructions() {
        return "Run <code>ollama signin</code> in a terminal. Sign-in is managed "
                + "by Ollama; ImageJAI does not receive, verify, or store the cloud token.";
    }

    private final String providerKey;
    private final boolean cloudFlow;
    private final ProviderCredentials credentials;
    private final OllamaProbe probe;
    private final DaemonProbe daemonProbe;
    private final CredentialVerifier verifier;

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
                defaultDaemonProbe(healthPathFor(providerKey)),
                CredentialVerifier.noop());
    }

    /**
     * Constructor retaining the credential verifier for compatibility and
     * programmatic validation. The Ollama Cloud UI itself never collects a
     * token unless a future authenticated candidate-token API is available.
     */
    public LocalRuntimeWizard(String providerKey,
                              ProviderCredentials credentials,
                              CredentialVerifier verifier) {
        this(providerKey, credentials,
                () -> ProcessRunner.findOnPath("ollama") != null,
                defaultDaemonProbe(healthPathFor(providerKey)), verifier);
    }

    public LocalRuntimeWizard(String providerKey,
                              ProviderCredentials credentials,
                              OllamaProbe probe) {
        this(providerKey, credentials, probe,
                defaultDaemonProbe(healthPathFor(providerKey)),
                CredentialVerifier.noop());
    }

    public LocalRuntimeWizard(String providerKey,
                              ProviderCredentials credentials,
                              OllamaProbe probe,
                              DaemonProbe daemonProbe) {
        this(providerKey, credentials, probe, daemonProbe, CredentialVerifier.noop());
    }

    public LocalRuntimeWizard(String providerKey,
                              ProviderCredentials credentials,
                              OllamaProbe probe,
                              DaemonProbe daemonProbe,
                              CredentialVerifier verifier) {
        this.providerKey = providerKey;
        this.cloudFlow = "ollama-cloud".equals(providerKey);
        this.credentials = credentials;
        this.probe = probe;
        this.daemonProbe = daemonProbe == null
                ? defaultDaemonProbe(healthPathFor(providerKey)) : daemonProbe;
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
        final RuntimeMeta meta = metaFor(providerKey);
        final JDialog dialog = new JDialog(owner,
                "Set up " + (cloudFlow ? "Ollama Cloud" : meta.displayName),
                Dialog.ModalityType.DOCUMENT_MODAL);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 14, 10, 14));

        // Only the Ollama family has a detectable CLI binary on PATH; for the
        // other local servers we assume present and validate by probing on save.
        boolean runtimePresent = isOllamaFamily() ? probe.ollamaInstalled() : true;
        StringBuilder header = new StringBuilder("<html><b>");
        header.append(cloudFlow ? "Ollama Cloud" : meta.displayName).append("</b><br>");
        if (isOllamaFamily()) {
            if (runtimePresent) {
                header.append("Ollama runtime detected.");
            } else {
                header.append("<font color='#a04030'>Ollama runtime is not installed.</font>");
            }
        } else {
            header.append("Start the local server, then pick a loaded model.");
        }
        header.append(cloudFlow
                ? "<br>" + cloudSignInInstructions()
                : "<br>No API key needed. Server URL defaults to <code>" + meta.defaultUrl + "</code>.");
        header.append("</html>");
        content.add(new JLabel(header.toString()), BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        // Local flow: daemon URL field. Ollama owns Cloud authentication, so
        // the cloud flow deliberately has no token-entry or persistence UI.
        final JTextField urlField = new JTextField(meta.defaultUrl, 22);
        if (!cloudFlow) {
            c.gridx = 0; c.gridy = 0;
            body.add(new JLabel("Daemon URL:"), c);
            c.gridx = 1; c.weightx = 1.0;
            body.add(urlField, c);
        }

        final JLabel statusLine = new JLabel(cloudFlow
                ? "No Ollama Cloud credential will be saved by ImageJAI." : " ");
        statusLine.setFont(statusLine.getFont().deriveFont(11f));
        c.gridx = 0; c.gridy = 1; c.gridwidth = 2; c.weightx = 1.0;
        body.add(statusLine, c);

        content.add(body, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton install = new JButton(
                isOllamaFamily() ? "Install Ollama…" : "Get " + meta.displayName + "…");
        install.setEnabled(isOllamaFamily() ? !runtimePresent : true);
        install.addActionListener(e -> openUrl(meta.installUrl));
        JButton cancel = new JButton("Cancel");
        final boolean[] saved = new boolean[] { false };
        final SwingWorker<?, ?>[] active = new SwingWorker[1];
        cancel.addActionListener(e -> {
            if (active[0] != null) active[0].cancel(true);
            dialog.dispose();
        });
        JButton save = new JButton(cloudFlow ? "Done" : "Save");
        save.addActionListener(e -> {
            final Map<String, String> entries = new LinkedHashMap<String, String>();
            final String runtimeUrl;
            if (cloudFlow) {
                // ollama signin persists its own authenticated state. Closing
                // this informational flow must not imply that a pasted value
                // was verified or write an OLLAMA_API_KEY file.
                saved[0] = true;
                dialog.dispose();
                return;
            }

            String url = urlField.getText().trim();
            if (url.isEmpty()) {
                setError(statusLine, "Daemon URL cannot be empty.");
                return;
            }
            url = normaliseRuntimeUrl(url);
            runtimeUrl = url;
            String urlEnv = ProviderCredentials.ENV_VAR_FOR_PROVIDER.get(providerKey);
            entries.put(urlEnv != null ? urlEnv : "OLLAMA_API_BASE", url);
            save.setEnabled(false);
            statusLine.setText("Checking serverâ€¦");
            active[0] = new SwingWorker<DaemonResult, Void>() {
                @Override protected DaemonResult doInBackground() {
                    DaemonResult result = daemonProbe.probe(
                            runtimeUrl, DAEMON_PROBE_TIMEOUT_MS);
                    if (!result.ok || isCancelled()) return result;
                    try {
                        credentials.saveEntries(providerKey, entries);
                        return result;
                    } catch (IOException failure) {
                        return new DaemonResult(false, 0, "credential save failed ("
                                + failure.getClass().getSimpleName() + ")");
                    }
                }

                @Override protected void done() {
                    if (isCancelled()) return;
                    active[0] = null;
                    try {
                        DaemonResult result = get();
                        if (result.ok) {
                            saved[0] = true;
                            dialog.dispose();
                        } else {
                            save.setEnabled(true);
                            setError(statusLine, "Could not reach " + runtimeUrl
                                    + meta.healthPath + ": " + result.message);
                        }
                    } catch (Exception failure) {
                        save.setEnabled(true);
                        setError(statusLine, "Setup failed ("
                                + failure.getClass().getSimpleName() + ")");
                    }
                }
            };
            active[0].execute();
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

    /** Package-visible seam used to prove that rejection never reaches disk.
     * The UI no longer collects cloud tokens, but keeping the transaction seam
     * protects callers compiled against the earlier wizard implementation. */
    CredentialVerifier.ValidationWorker cloudValidationWorker(
            String candidate, CredentialVerifier.Completion completion) {
        return new CredentialVerifier.ValidationWorker(
                providerKey, candidate, VERIFY_TIMEOUT_MS, verifier,
                (key, accepted) -> {
                    if (credentials == null) {
                        throw new IOException("credential store unavailable");
                    }
                    Map<String, String> entries = new LinkedHashMap<String, String>();
                    entries.put("OLLAMA_API_KEY", accepted);
                    credentials.saveEntries(key, entries);
                }, completion);
    }

    private static DaemonProbe defaultDaemonProbe(String healthPath) {
        final String path = (healthPath == null || healthPath.isEmpty())
                ? "/api/tags" : healthPath;
        return (url, timeoutMs) -> {
            HttpURLConnection conn = null;
            try {
                String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                URL endpoint = new URL(base + path);
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
