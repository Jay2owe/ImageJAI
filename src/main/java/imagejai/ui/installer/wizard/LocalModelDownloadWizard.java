package imagejai.ui.installer.wizard;

import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ProviderEntry;
import imagejai.engine.picker.ProviderRegistry;
import imagejai.install.ProcessRunner;
import imagejai.ui.installer.ProviderCredentials;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Install shape #4 — pick a curated Ollama model and run {@code ollama pull}
 * in a SwingWorker. Sizes come from
 * {@code agent/providers/ollama_model_sizes.json}; rows show the model id,
 * estimated download size, and a "Pull" button.
 */
public class LocalModelDownloadWizard implements InstallerWizard {

    private static final String SIZES_RESOURCE = "/agent/providers/ollama_model_sizes.json";

    private final ProviderRegistry registry;
    private final ProviderCredentials credentials;
    private final ModelPullRunner pullRunner;

    /** SwingWorker boundary — separated so tests can stub the {@code ollama pull} call. */
    public interface ModelPullRunner {
        /** Synchronously pull a model and pipe progress lines to {@code log}. */
        boolean pull(String modelId, java.util.function.Consumer<String> log) throws IOException;
    }

    public LocalModelDownloadWizard(ProviderRegistry registry, ProviderCredentials credentials) {
        this(registry, credentials, defaultPullRunner());
    }

    public LocalModelDownloadWizard(ProviderRegistry registry,
                                    ProviderCredentials credentials,
                                    ModelPullRunner pullRunner) {
        this.registry = registry;
        this.credentials = credentials;
        this.pullRunner = pullRunner;
    }

    @Override
    public String providerKey() {
        return "ollama";
    }

    @Override
    public boolean showAndSave(Component parent) {
        Frame owner = parent == null ? null
                : (Frame) SwingUtilities.getAncestorOfClass(Frame.class, parent);
        final JDialog dialog = new JDialog(owner,
                "Pull Ollama models", Dialog.ModalityType.DOCUMENT_MODAL);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 14, 10, 14));

        content.add(new JLabel("<html><b>Local Ollama models</b><br>"
                + "Pick the models you want available locally — each is "
                + "downloaded once and cached by Ollama.</html>"), BorderLayout.NORTH);

        JPanel rows = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        Map<String, String> sizes = loadSizes();
        ProviderEntry ollama = registry == null ? null : registry.provider("ollama");
        List<ModelEntry> ollamaModels = ollama == null
                ? Collections.<ModelEntry>emptyList()
                : new ArrayList<ModelEntry>(ollama.models());
        if (ollamaModels.isEmpty()) {
            JLabel none = new JLabel("(no curated Ollama models in registry)");
            c.gridy = 0;
            rows.add(none, c);
        }
        final JTextArea logArea = new JTextArea(8, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        for (int i = 0; i < ollamaModels.size(); i++) {
            ModelEntry entry = ollamaModels.get(i);
            c.gridy = i;
            c.gridx = 0; c.weightx = 0.0;
            rows.add(new JLabel(entry.modelId()), c);
            c.gridx = 1; c.weightx = 1.0;
            String size = sizes.getOrDefault(entry.modelId(), "size unknown");
            JLabel sizeLabel = new JLabel("~" + size);
            sizeLabel.setForeground(new java.awt.Color(120, 120, 130));
            rows.add(sizeLabel, c);
            c.gridx = 2; c.weightx = 0.0;
            JButton pullBtn = new JButton("Pull");
            pullBtn.addActionListener(e -> runPull(dialog, entry.modelId(), logArea, pullBtn));
            rows.add(pullBtn, c);
        }

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(new JScrollPane(rows), BorderLayout.NORTH);
        center.add(new JScrollPane(logArea), BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton done = new JButton("Done");
        done.addActionListener(e -> dialog.dispose());
        buttons.add(done);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        Dimension preferred = dialog.getPreferredSize();
        dialog.setSize(Math.max(preferred.width, 540), Math.max(preferred.height, 360));
        dialog.setLocationRelativeTo(parent);
        // Persisting an OLLAMA_API_BASE here keeps the proxy in sync after the
        // user has launched the runtime once. Idempotent if already set.
        try {
            Map<String, String> entries = credentials == null
                    ? new LinkedHashMap<String, String>()
                    : credentials.read("ollama");
            if (!entries.containsKey("OLLAMA_API_BASE")) {
                entries.put("OLLAMA_API_BASE", "http://localhost:11434");
                if (credentials != null) {
                    credentials.saveEntries("ollama", entries);
                }
            }
        } catch (IOException ignored) {
            // Best-effort — don't block the wizard if we can't write.
        }
        dialog.setVisible(true);
        return true;
    }

    private void runPull(final JDialog dialog,
                         final String modelId,
                         final JTextArea logArea,
                         final JButton button) {
        button.setEnabled(false);
        logArea.append("[pull] " + modelId + " starting…\n");
        new SwingWorker<Boolean, String>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                return pullRunner.pull(modelId, line -> publish(line));
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String chunk : chunks) {
                    logArea.append(chunk + "\n");
                }
            }

            @Override
            protected void done() {
                try {
                    boolean ok = get();
                    logArea.append("[pull] " + modelId + (ok ? " complete\n" : " failed\n"));
                } catch (Exception ex) {
                    logArea.append("[pull] " + modelId + " errored: " + ex.getMessage() + "\n");
                }
                button.setEnabled(true);
            }
        }.execute();
    }

    private static ModelPullRunner defaultPullRunner() {
        return (modelId, log) -> {
            String ollama = ProcessRunner.findOnPath("ollama");
            if (ollama == null) {
                log.accept("ollama not on PATH — install from https://ollama.com/download");
                return false;
            }
            ProcessBuilder pb = new ProcessBuilder(ollama, "pull", modelId);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.accept(line);
                }
            }
            try {
                return process.waitFor() == 0;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw new IOException("interrupted while waiting for ollama pull", ie);
            }
        };
    }

    private static Map<String, String> loadSizes() {
        Map<String, String> sizes = new LinkedHashMap<String, String>();
        try (InputStream in = LocalModelDownloadWizard.class.getResourceAsStream(SIZES_RESOURCE)) {
            if (in == null) {
                return sizes;
            }
            String json = readAll(in);
            // Tiny ad-hoc parser — file is hand-curated, no nested structures.
            Pattern entry = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = entry.matcher(json);
            while (m.find()) {
                sizes.put(m.group(1), m.group(2));
            }
        } catch (IOException ignored) {
            // Resource-load failure isn't fatal; rows just show "size unknown".
        }
        return sizes;
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                in, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, n);
            }
        }
        return out.toString();
    }
}
