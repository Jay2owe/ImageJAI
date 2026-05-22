package imagejai.ui;

import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import imagejai.config.PrivacyPosture;
import imagejai.engine.PostureController;
import imagejai.engine.security.AuditLog;
import imagejai.engine.security.AuditRow;
import imagejai.engine.security.DataHandlingStatementGenerator;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Collapsible Data Governance configuration surface for the launcher panel.
 */
public final class ConfigurationPane extends JPanel
        implements PostureController.Listener, AuditLog.Listener {
    private static final Color BG = new Color(36, 36, 42);
    private static final Color BORDER = new Color(62, 62, 70);
    private static final Color TEXT = new Color(220, 220, 225);
    private static final Color MUTED = new Color(145, 145, 155);

    private final PostureController postureController;
    private final AuditLog auditLog;
    private final JButton toggle;
    private final JPanel content;
    private final JComboBox<PrivacyPosture> postureSelector;
    private final JLabel logPathLabel;
    private final JLabel statsLabel;
    private final AutoCloseable subscription;

    private boolean expanded;
    private boolean applyingPosture;

    public ConfigurationPane(PostureController postureController, AuditLog auditLog) {
        super(new BorderLayout(0, 0));
        this.postureController = postureController == null
                ? PostureController.getInstance()
                : postureController;
        this.auditLog = auditLog == null ? AuditLog.getInstance() : auditLog;
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        toggle = new JButton();
        toggle.setBorder(new EmptyBorder(3, 6, 3, 6));
        toggle.setContentAreaFilled(false);
        toggle.setFocusPainted(false);
        toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggle.setForeground(TEXT);
        toggle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        toggle.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        toggle.addActionListener(e -> setExpanded(!expanded));
        add(toggle, BorderLayout.NORTH);

        content = new JPanel(new GridBagLayout());
        content.setBackground(BG);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(6, 8, 6, 8)));

        postureSelector = new JComboBox<PrivacyPosture>(PrivacyPosture.values());
        postureSelector.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        postureSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean selected,
                                                          boolean focus) {
                Component c = super.getListCellRendererComponent(
                        list, value, index, selected, focus);
                if (value instanceof PrivacyPosture) {
                    setText(((PrivacyPosture) value).label());
                }
                return c;
            }
        });
        postureSelector.setToolTipText("<html>Privacy Posture for this folder. Changes are"
                + "<br>logged and routed through the Data Governance posture controller.</html>");
        postureSelector.addActionListener(e -> {
            if (applyingPosture) {
                return;
            }
            PrivacyPosture selected = (PrivacyPosture) postureSelector.getSelectedItem();
            this.postureController.requestPosture(selected, currentImageFolder(),
                    "Changed in Data Governance configuration pane");
        });

        logPathLabel = valueLabel("AI_Exports/imagejai_audit.csv");
        statsLabel = valueLabel("0 (0 pseudonymised, 0 visual overrides)");

        addRow(0, label("Posture for this folder:"), postureSelector);
        addRow(1, label("Audit log:"), auditLogRow());
        addRow(2, label("Outbound calls this session:"), statsLabel);
        addRow(3, label("Generate Data Handling Statement"), generateRow());
        addRow(4, label("Vendor terms summary"), vendorRow());

        add(content, BorderLayout.CENTER);
        this.postureController.addListener(this);
        subscription = this.auditLog.subscribeRecent(500, this);
        applyPosture(this.postureController.current());
        updateLogPath();
        setExpanded(false);
    }

    public void dispose() {
        postureController.removeListener(this);
        try {
            subscription.close();
        } catch (Exception ignore) {
        }
    }

    @Override
    public void postureChanged(PrivacyPosture from, final PrivacyPosture to, Path folder) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                applyPosture(to);
            }
        });
    }

    @Override
    public void auditRowsUpdated(final List<AuditRow> recentRows) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                updateStats(recentRows);
                updateLogPath();
            }
        });
    }

    private JPanel auditLogRow() {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        JButton open = smallButton("Open");
        open.setToolTipText("<html>Audit trail of outbound calls to the agent."
                + "<br>CSV format. Suitable for ethics applications.</html>");
        open.addActionListener(e -> openAuditLogAsync());
        row.add(logPathLabel, BorderLayout.CENTER);
        row.add(open, BorderLayout.EAST);
        return row;
    }

    private JPanel generateRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        JButton generate = smallButton("Generate");
        generate.setToolTipText("<html>Produces a printable statement summarising vendor terms,"
                + "<br>pseudonymisation scheme, and audit trail for this project.</html>");
        generate.addActionListener(e -> generateStatementAsync());
        row.add(generate);
        return row;
    }

    private JPanel vendorRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        JButton view = smallButton("View");
        view.setToolTipText("<html>View the vendor terms summary used by the Data Governance"
                + "<br>statement. Stage 07 expands this into the documentation pack.</html>");
        view.addActionListener(e -> JOptionPane.showMessageDialog(
                ConfigurationPane.this,
                "Vendor terms summary:\n\n"
                        + "Cloud agent CLIs are governed by their vendor terms. "
                        + "Use Pseudonymised posture to tokenise identifiers before send, "
                        + "or On-premises posture to restrict launches to local-binary agents.",
                "Vendor terms summary",
                JOptionPane.INFORMATION_MESSAGE));
        row.add(view);
        return row;
    }

    private void openAuditLogAsync() {
        new SwingWorker<Void, Void>() {
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    auditLog.open();
                } catch (Exception e) {
                    error = e;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    JOptionPane.showMessageDialog(
                            ConfigurationPane.this,
                            "Could not open the Data Governance audit log:\n"
                                    + error.getMessage(),
                            "Audit log",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    private void generateStatementAsync() {
        final Path folder = currentImageFolder();
        if (folder == null) {
            JOptionPane.showMessageDialog(
                    ConfigurationPane.this,
                    "Open an image from the project folder before generating the "
                            + "Data Handling Statement.",
                    "Generate Data Handling Statement",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        final PrivacyPosture posture = postureController.current();
        new SwingWorker<Path, Void>() {
            private Exception error;

            @Override
            protected Path doInBackground() {
                try {
                    return new DataHandlingStatementGenerator(folder, posture).generate();
                } catch (Exception e) {
                    error = e;
                    return null;
                }
            }

            @Override
            protected void done() {
                Path generated = null;
                try {
                    generated = get();
                } catch (Exception e) {
                    error = e;
                }
                if (error != null) {
                    JOptionPane.showMessageDialog(
                            ConfigurationPane.this,
                            "Could not generate the Data Handling Statement:\n"
                                    + error.getMessage(),
                            "Generate Data Handling Statement",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                JOptionPane.showMessageDialog(
                        ConfigurationPane.this,
                        "Generated Data Handling Statement:\n" + generated,
                        "Generate Data Handling Statement",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    private void addRow(int row, JComponent left, JComponent right) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = row;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(2, 0, 2, 10);
        content.add(left, gc);

        gc = new GridBagConstraints();
        gc.gridx = 1;
        gc.gridy = row;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(2, 0, 2, 0);
        content.add(right, gc);
    }

    private JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        return label;
    }

    private JLabel valueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        return label;
    }

    private JButton smallButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        button.setMargin(new Insets(1, 6, 1, 6));
        button.setFocusPainted(false);
        return button;
    }

    private void setExpanded(boolean expanded) {
        this.expanded = expanded;
        toggle.setText(expanded ? "Data Governance \u25BE" : "Data Governance \u25B8");
        content.setVisible(expanded);
        revalidate();
        repaint();
    }

    private void applyPosture(PrivacyPosture posture) {
        applyingPosture = true;
        try {
            postureSelector.setSelectedItem(posture == null
                    ? PrivacyPosture.defaultPosture()
                    : posture);
        } finally {
            applyingPosture = false;
        }
    }

    private void updateStats(List<AuditRow> rows) {
        int total = 0;
        int pseudonymised = 0;
        int visual = 0;
        if (rows != null) {
            for (AuditRow row : rows) {
                if (row == null) {
                    continue;
                }
                if (row.bytesOut() > 0) {
                    total++;
                    if (row.redactionApplied()) {
                        pseudonymised++;
                    }
                }
                String command = row.command();
                if (command != null
                        && (command.startsWith("visual.")
                        || "request_visual".equals(command))) {
                    visual++;
                }
            }
        }
        statsLabel.setText(total + " (" + pseudonymised
                + " pseudonymised, " + visual + " visual overrides)");
    }

    private void updateLogPath() {
        Path path = auditLog.csvPath();
        if (path == null) {
            logPathLabel.setText("AI_Exports/imagejai_audit.csv");
            logPathLabel.setToolTipText(null);
            return;
        }
        logPathLabel.setText("AI_Exports/" + AuditLog.FILE_NAME);
        logPathLabel.setToolTipText(path.toString());
    }

    private static Path currentImageFolder() {
        try {
            ImagePlus image = WindowManager.getCurrentImage();
            if (image == null) {
                return null;
            }
            FileInfo info = image.getOriginalFileInfo();
            if (info == null || info.directory == null
                    || info.directory.trim().isEmpty()) {
                return null;
            }
            return Paths.get(info.directory).toAbsolutePath().normalize();
        } catch (Throwable t) {
            return null;
        }
    }

    PrivacyPosture selectedPostureForTest() {
        return (PrivacyPosture) postureSelector.getSelectedItem();
    }

    void selectPostureForTest(PrivacyPosture posture) {
        postureSelector.setSelectedItem(posture);
    }

    String statsTextForTest() {
        return statsLabel.getText();
    }
}
