package imagejai.ui;

import imagejai.config.Settings;
import imagejai.engine.AgentLauncher;
import imagejai.engine.GsdDetector;
import imagejai.install.MiniLmDownloader;
import imagejai.install.ProcessRunner;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings tab for installing optional local models and external CLI agents.
 */
public class InstallerPanel extends JPanel {

    private static final long VERSION_TIMEOUT_MS = 2500L;

    private final Settings settings;
    private final ProcessRunner processRunner;
    private final Map<String, JTextField> commandFields = new LinkedHashMap<String, JTextField>();
    private final Map<String, JLabel> statusLabels = new LinkedHashMap<String, JLabel>();
    private JCheckBox claudeUseGsdFlag;

    public InstallerPanel(Settings settings) {
        this(settings, new ProcessRunner());
    }

    InstallerPanel(Settings settings, ProcessRunner processRunner) {
        super(new BorderLayout(8, 8));
        this.settings = settings;
        this.processRunner = processRunner;
        buildUI();
        refreshStatuses();
    }

    public void saveToSettings() {
        settings.claudeInstallCommand = field("claude", settings.claudeInstallCommand);
        settings.codexInstallCommand = field("codex", settings.codexInstallCommand);
        settings.aiderInstallCommand = field("aider", settings.aiderInstallCommand);
        settings.geminiInstallCommand = field("gemini", settings.geminiInstallCommand);
        settings.ollamaInstallUrl = field("ollamaUrl", settings.ollamaInstallUrl);
        settings.gsdInstallCommand = field("gsd", settings.gsdInstallCommand);
        settings.gsdInstallDocsUrl = field("gsdDocs", settings.gsdInstallDocsUrl);
        settings.gsdSkillsPath = field("gsdPath", settings.gsdSkillsPath);
        settings.claudeUseGsdFlag = claudeUseGsdFlag.isSelected();
    }

    private void buildUI() {
        JPanel rows = new JPanel(new GridBagLayout());
        rows.setBorder(new EmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        addHeader(rows, c);
        int row = 1;
        row = addStaticRow(rows, c, row, AgentLauncher.LOCAL_ASSISTANT_NAME, "\u2713 ready", "Built in");
        row = addMiniLmRow(rows, c, row);
        row = addAgentRow(rows, c, row, "Claude Code", "claude", "claude",
                settings.claudeInstallCommand, "npm", "https://nodejs.org/en/download",
                "Install via npm");
        row = addAgentRow(rows, c, row, "Codex CLI", "codex", "codex",
                settings.codexInstallCommand, "npm", "https://nodejs.org/en/download",
                "Install via npm");
        row = addOllamaRow(rows, c, row);
        row = addAgentRow(rows, c, row, "Aider", "aider", "aider",
                settings.aiderInstallCommand, "pip", "https://www.python.org/downloads/",
                "Install via pip");
        addAgentRow(rows, c, row, "Gemini CLI", "gemini", "gemini",
                settings.geminiInstallCommand, "npm", "https://nodejs.org/en/download",
                "Install via npm");

        add(new JScrollPane(rows), BorderLayout.CENTER);
        add(buildEditableSettings(), BorderLayout.SOUTH);
    }

    private void addHeader(JPanel rows, GridBagConstraints c) {
        c.gridy = 0;
        c.gridx = 0;
        c.weightx = 0.35;
        rows.add(new JLabel("Agent / model"), c);
        c.gridx = 1;
        c.weightx = 0.15;
        rows.add(new JLabel("Status"), c);
        c.gridx = 2;
        c.weightx = 0.50;
        rows.add(new JLabel("Action"), c);
    }

    private int addStaticRow(JPanel rows, GridBagConstraints c, int row,
                             String name, String status, String actionText) {
        c.gridy = row;
        c.gridx = 0;
        rows.add(new JLabel(name), c);
        c.gridx = 1;
        JLabel statusLabel = new JLabel(status);
        rows.add(statusLabel, c);
        statusLabels.put(name, statusLabel);
        c.gridx = 2;
        rows.add(new JLabel(actionText), c);
        return row + 1;
    }

    private int addMiniLmRow(JPanel rows, GridBagConstraints c, int row) {
        c.gridy = row;
        c.gridx = 0;
        rows.add(new JLabel("Local Assistant - semantic boost (MiniLM)"), c);
        c.gridx = 1;
        JLabel status = new JLabel(settings.miniLmInstalled ? "\u2713 ready" : "not downloaded");
        statusLabels.put("minilm", status);
        rows.add(status, c);
        c.gridx = 2;
        JButton download = new JButton("Download");
        download.addActionListener(e -> downloadMiniLm());
        rows.add(download, c);
        return row + 1;
    }

    private int addAgentRow(JPanel rows, GridBagConstraints c, int row,
                            String name, String commandName, String key,
                            String installCommand, String runtimeCommand,
                            String runtimeUrl, String actionText) {
        c.gridy = row;
        c.gridx = 0;
        rows.add(new JLabel(name), c);
        c.gridx = 1;
        JLabel status = new JLabel("checking...");
        statusLabels.put(key, status);
        rows.add(status, c);
        c.gridx = 2;
        JButton action = new JButton(actionText);
        action.addActionListener(e -> {
            if ("claude".equals(key)) {
                handleClaudeGsdPrompt();
            }
            runInstallCommand(name, field(key, installCommand), runtimeCommand, runtimeUrl);
        });
        rows.add(action, c);
        commandFields.put(key, new JTextField(installCommand, 34));
        commandFields.put(key + ".cmd", new JTextField(commandName, 12));
        return row + 1;
    }

    private int addOllamaRow(JPanel rows, GridBagConstraints c, int row) {
        c.gridy = row;
        c.gridx = 0;
        rows.add(new JLabel("Ollama"), c);
        c.gridx = 1;
        JLabel status = new JLabel("checking...");
        statusLabels.put("ollama", status);
        rows.add(status, c);
        c.gridx = 2;
        JButton action = new JButton("Install Ollama CLI");
        action.addActionListener(e -> openInstallerUrl("Ollama installer (~250 MB)", field("ollamaUrl", settings.ollamaInstallUrl)));
        rows.add(action, c);
        commandFields.put("ollamaUrl", new JTextField(settings.ollamaInstallUrl, 34));
        commandFields.put("ollama.cmd", new JTextField("ollama", 12));
        return row + 1;
    }

    private JPanel buildEditableSettings() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Packager-editable install settings"),
                new EmptyBorder(4, 8, 6, 8)));

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0.0;
        int row = 0;
        row = addField(grid, c, row, "Claude command:", "claude");
        row = addField(grid, c, row, "Codex command:", "codex");
        row = addField(grid, c, row, "Aider command:", "aider");
        row = addField(grid, c, row, "Gemini command:", "gemini");
        row = addField(grid, c, row, "Ollama download URL:", "ollamaUrl");

        commandFields.put("gsd", new JTextField(settings.gsdInstallCommand, 34));
        commandFields.put("gsdDocs", new JTextField(settings.gsdInstallDocsUrl, 34));
        commandFields.put("gsdPath", new JTextField(settings.gsdSkillsPath, 34));
        row = addField(grid, c, row, "GSD install command:", "gsd");
        row = addField(grid, c, row, "GSD docs URL:", "gsdDocs");
        row = addField(grid, c, row, "GSD skills path:", "gsdPath");

        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        claudeUseGsdFlag = new JCheckBox("Use Claude GSD speed flag when GSD is installed");
        claudeUseGsdFlag.setSelected(settings.claudeUseGsdFlag);
        grid.add(claudeUseGsdFlag, c);

        wrapper.add(grid, BorderLayout.CENTER);
        return wrapper;
    }

    private int addField(JPanel grid, GridBagConstraints c, int row, String label, String key) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0.0;
        grid.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1.0;
        grid.add(commandFields.get(key), c);
        return row + 1;
    }

    private void refreshStatuses() {
        setStatus(AgentLauncher.LOCAL_ASSISTANT_NAME, "\u2713 ready");
        setStatus("minilm", settings.miniLmInstalled ? "\u2713 ready" : "not downloaded");
        checkCommandAsync("claude", "claude");
        checkCommandAsync("codex", "codex");
        checkCommandAsync("ollama", "ollama");
        checkCommandAsync("aider", "aider");
        checkCommandAsync("gemini", "gemini");
    }

    private void checkCommandAsync(final String key, final String commandName) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ProcessRunner.VersionResult result =
                        processRunner.checkVersion(commandName, VERSION_TIMEOUT_MS);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(key, result.installed ? "\u2713 ready" : "not installed");
                    }
                });
            }
        }, "ImageJAI " + key + " status").start();
    }

    private void runInstallCommand(String name,
                                   String commandTemplate,
                                   String runtimeCommand,
                                   String runtimeUrl) {
        List<String> command = absoluteCommand(commandTemplate);
        if (command.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No installer command is configured for " + name + ".",
                    "Installer command missing",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (ProcessRunner.findOnPath(runtimeCommand) == null) {
            openInstallerUrl(runtimeCommand + " is required", runtimeUrl);
            return;
        }
        processRunner.runWithLogDialog(this, name + " installer", command);
    }

    private List<String> absoluteCommand(String commandTemplate) {
        List<String> command = ProcessRunner.parseCommand(commandTemplate);
        if (command.isEmpty()) return command;
        String executable = ProcessRunner.findOnPath(command.get(0));
        if (executable != null) {
            command.set(0, executable);
        }
        return command;
    }

    private void downloadMiniLm() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Download the MiniLM semantic model (~23 MB)?\n\n"
                        + "The file is verified by SHA-256 before it is used.",
                "Download MiniLM",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        final JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "MiniLM download", Dialog.ModalityType.MODELESS);
        final JTextArea log = new JTextArea(10, 56);
        log.setEditable(false);
        JButton cancel = new JButton("Cancel");
        final boolean[] keepGoing = new boolean[] {true};
        cancel.addActionListener(e -> keepGoing[0] = false);
        dialog.add(new JScrollPane(log), BorderLayout.CENTER);
        dialog.add(cancel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    MiniLmDownloader downloader = new MiniLmDownloader(settings.miniLmModelSha256);
                    Path dest = downloader.download(() -> keepGoing[0]);
                    settings.miniLmInstalled = true;
                    settings.save();
                    SwingUtilities.invokeLater(() -> {
                        log.append("Downloaded and verified: " + dest + "\n");
                        setStatus("minilm", "\u2713 ready");
                    });
                } catch (final Exception e) {
                    SwingUtilities.invokeLater(() -> log.append("Download failed: " + e.getMessage() + "\n"));
                }
            }
        }, "ImageJAI MiniLM download").start();
    }

    private void handleClaudeGsdPrompt() {
        boolean installed = GsdDetector.isInstalled(settings);
        if (installed) {
            JOptionPane.showMessageDialog(this,
                    "Claude Code will launch with --dangerously-skip-permissions "
                            + "(faster, unlocks full potential).",
                    "GSD detected",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int choice = JOptionPane.showConfirmDialog(this,
                "Install GSD to skip permission prompts and run Claude at full speed?",
                "GSD not detected",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        String command = field("gsd", settings.gsdInstallCommand);
        if (command.trim().isEmpty()) {
            openInstallerUrl("GSD install docs", field("gsdDocs", settings.gsdInstallDocsUrl));
        } else {
            processRunner.runWithLogDialog(this, "GSD installer", absoluteCommand(command));
        }
    }

    private void openInstallerUrl(String title, String url) {
        int choice = JOptionPane.showConfirmDialog(this,
                title + "\n\nOpen this page?\n" + url,
                "Open installer page",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "URL: " + url);
        }
    }

    private String field(String key, String fallback) {
        JTextField field = commandFields.get(key);
        if (field == null) return fallback == null ? "" : fallback;
        String value = field.getText().trim();
        return value.isEmpty() ? (fallback == null ? "" : fallback) : value;
    }

    private void setStatus(String key, String text) {
        JLabel label = statusLabels.get(key);
        if (label != null) label.setText(text);
    }
}
