package imagejai.ui;

import imagejai.engine.AgentSession;
import imagejai.engine.security.Brief;
import imagejai.engine.security.BriefNudger;
import imagejai.engine.security.SelectionBroker;
import imagejai.engine.security.SeriesScanner;
import imagejai.engine.security.TagSuggestionEngine;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Local Swing browser for selecting files/series without sending real labels
 * or tag override rules to an agent.
 */
public final class BrowseFilesDialog extends JDialog {
    private static final Color BG = new Color(36, 36, 42);
    private static final Color TEXT = new Color(220, 220, 225);
    private static final Color MUTED = new Color(145, 145, 155);

    private final Path folder;
    private final String sessionId;
    private final AgentSession activeSession;
    private final SeriesScanner scanner;
    private final TagSuggestionEngine tagEngine;
    private final SelectionBroker broker;
    private final BriefNudger nudger;
    private final BrowserTableModel model;
    private final JTable table;
    private final TableRowSorter<BrowserTableModel> sorter;
    private final JTextField search;
    private final JTextField tag;
    private final JTextArea preview;
    private final JLabel status;
    private final JRadioButton embedded;
    private final JRadioButton clipboard;
    private final JRadioButton polling;

    public BrowseFilesDialog(Window owner, Path folder, String sessionId,
                             AgentSession activeSession) {
        this(owner, folder, sessionId, activeSession, new SeriesScanner(),
                new TagSuggestionEngine(), SelectionBroker.getInstance(),
                null);
    }

    BrowseFilesDialog(Window owner, Path folder, String sessionId,
                      AgentSession activeSession, SeriesScanner scanner,
                      TagSuggestionEngine tagEngine, SelectionBroker broker,
                      BriefNudger nudger) {
        super(owner, "Browse Files - " + (folder == null ? "" : folder.getFileName()),
                ModalityType.MODELESS);
        this.folder = folder;
        this.sessionId = Brief.normaliseSession(sessionId);
        this.activeSession = activeSession;
        this.scanner = scanner == null ? new SeriesScanner() : scanner;
        this.tagEngine = tagEngine == null ? new TagSuggestionEngine() : tagEngine;
        this.broker = broker == null ? SelectionBroker.getInstance() : broker;
        this.nudger = nudger == null ? new BriefNudger(null, new BriefNudger.Toast() {
            @Override
            public void show(String message) {
                showToast(message);
            }
        }) : nudger;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 8));
        getContentPane().setBackground(BG);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(10, 10, 0, 10));
        JLabel title = label("Folder: " + (folder == null ? "" : folder.toString()), true);
        top.add(title, BorderLayout.WEST);
        search = new JTextField();
        search.setToolTipText("Local search over filenames and labels. Not sent to the agent.");
        top.add(search, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        model = new BrowserTableModel();
        table = new JTable(model);
        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(22);
        sorter = new TableRowSorter<BrowserTableModel>(model);
        table.setRowSorter(sorter);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new GridBagLayout());
        bottom.setOpaque(false);
        bottom.setBorder(new EmptyBorder(0, 10, 10, 10));

        tag = new JTextField();
        preview = new JTextArea(3, 40);
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        preview.setBorder(BorderFactory.createLineBorder(new Color(62, 62, 70)));
        preview.setBackground(new Color(30, 30, 35));
        preview.setForeground(TEXT);
        status = label("Scanning...", false);

        embedded = new JRadioButton("Embedded terminal");
        clipboard = new JRadioButton("Clipboard");
        polling = new JRadioButton("TCP polling");
        for (JRadioButton radio : new JRadioButton[] {embedded, clipboard, polling}) {
            radio.setOpaque(false);
            radio.setForeground(TEXT);
            radio.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        }
        ButtonGroup group = new ButtonGroup();
        group.add(embedded);
        group.add(clipboard);
        group.add(polling);
        if (activeSession != null
                && activeSession.getClass().getName().endsWith("EmbeddedAgentSession")) {
            embedded.setSelected(true);
        } else {
            clipboard.setSelected(true);
        }

        addRow(bottom, 0, label("Suggested tag:", false), tag);
        addRow(bottom, 1, label("The agent will see:", false), new JScrollPane(preview));
        JPanel delivery = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        delivery.setOpaque(false);
        delivery.add(embedded);
        delivery.add(clipboard);
        delivery.add(polling);
        addRow(bottom, 2, label("Delivery:", false), delivery);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        JButton cancel = new JButton(new AbstractAction("Cancel") {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        JButton send = new JButton(new AbstractAction("Send to agent") {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendBrief();
            }
        });
        buttons.add(status);
        buttons.add(cancel);
        buttons.add(send);
        addRow(bottom, 3, new JLabel(""), buttons);
        add(bottom, BorderLayout.SOUTH);

        installListeners();
        setSize(920, 560);
        setLocationRelativeTo(owner);
        scanAsync();
    }

    private void installListeners() {
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applySearch(); }
            @Override public void removeUpdate(DocumentEvent e) { applySearch(); }
            @Override public void changedUpdate(DocumentEvent e) { applySearch(); }
        });
        table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting()) {
                    updateSelectionPreview();
                }
            }
        });
        tag.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updatePreviewOnly(); }
            @Override public void removeUpdate(DocumentEvent e) { updatePreviewOnly(); }
            @Override public void changedUpdate(DocumentEvent e) { updatePreviewOnly(); }
        });
    }

    private void scanAsync() {
        new SwingWorker<List<SeriesScanner.SeriesInfo>, Void>() {
            @Override
            protected List<SeriesScanner.SeriesInfo> doInBackground() {
                return scanner.scanFolder(folder);
            }

            @Override
            protected void done() {
                try {
                    model.setRows(rowsFor(get()));
                    status.setText(model.getRowCount() + " rows");
                } catch (Exception e) {
                    status.setText("Scan failed");
                }
            }
        }.execute();
    }

    private List<Row> rowsFor(List<SeriesScanner.SeriesInfo> infos) {
        List<Row> rows = new ArrayList<Row>();
        if (infos != null) {
            for (SeriesScanner.SeriesInfo info : infos) {
                rows.add(new Row(info, tagEngine.parseLabel(info.label(), folder)));
            }
        }
        return rows;
    }

    private void applySearch() {
        final String text = search.getText() == null ? "" : search.getText().trim();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
            return;
        }
        final Pattern pattern = Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE);
        sorter.setRowFilter(new RowFilter<BrowserTableModel, Integer>() {
            @Override
            public boolean include(RowFilter.Entry<? extends BrowserTableModel, ? extends Integer> entry) {
                Row row = model.rowAt(entry.getIdentifier());
                String haystack = row.info.label() + " "
                        + (row.info.file() == null ? "" : row.info.file().getFileName());
                return pattern.matcher(haystack).find();
            }
        });
    }

    private void updateSelectionPreview() {
        List<Row> rows = selectedRows();
        List<String> labels = new ArrayList<String>();
        for (Row row : rows) {
            labels.add(row.info.label());
        }
        tag.setText(tagEngine.suggest(labels, folder));
        updatePreviewOnly();
    }

    private void updatePreviewOnly() {
        List<String> tokens = selectedTokens();
        StringBuilder sb = new StringBuilder();
        if (tokens.isEmpty()) {
            sb.append("No selection.");
        } else {
            sb.append("tokens: ");
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(tokens.get(i));
            }
            String t = tag.getText() == null ? "" : tag.getText().trim();
            if (!t.isEmpty()) {
                sb.append("\ntag: ").append(t);
            }
        }
        preview.setText(sb.toString());
    }

    private void sendBrief() {
        List<Row> rows = selectedRows();
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select at least one file or series.",
                    "Browse Files", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> tokens = new ArrayList<String>();
        List<Object> items = new ArrayList<Object>();
        int commonChannels = -1;
        String commonDimensions = "";
        for (Row row : rows) {
            tokens.add(row.info.token());
            items.add(row.info.safeMetadata());
            if (commonChannels < 0) {
                commonChannels = row.info.sizeC();
            } else if (commonChannels != row.info.sizeC()) {
                commonChannels = 0;
            }
            String dims = row.info.dimensionsLabel();
            if (commonDimensions.isEmpty()) {
                commonDimensions = dims;
            } else if (!commonDimensions.equals(dims)) {
                commonDimensions = "mixed dimensions";
            }
        }
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("count", tokens.size());
        metadata.put("channels", Math.max(0, commonChannels));
        metadata.put("dimensions", commonDimensions);
        metadata.put("items", items);
        Brief brief = new Brief(sessionId, tokens, tag.getText(), metadata);
        broker.enqueue(brief);
        nudger.deliver(brief, selectedMechanism(), activeSession);
        showToast("Selection ready for the agent.");
        dispose();
    }

    private BriefNudger.NudgeMechanism selectedMechanism() {
        if (embedded.isSelected()) {
            return BriefNudger.NudgeMechanism.EMBEDDED_PTY;
        }
        if (polling.isSelected()) {
            return BriefNudger.NudgeMechanism.TCP_POLLING;
        }
        return BriefNudger.NudgeMechanism.CLIPBOARD;
    }

    private List<Row> selectedRows() {
        int[] selected = table.getSelectedRows();
        List<Row> rows = new ArrayList<Row>();
        for (int viewRow : selected) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            Row row = model.rowAt(modelRow);
            if (row != null && row.info.readable()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<String> selectedTokens() {
        List<String> out = new ArrayList<String>();
        for (Row row : selectedRows()) {
            out.add(row.info.token());
        }
        return out;
    }

    private void showToast(String message) {
        final JWindow toast = new JWindow(this);
        JLabel label = new JLabel(message);
        label.setOpaque(true);
        label.setBackground(new Color(42, 37, 28));
        label.setForeground(new Color(242, 226, 190));
        label.setBorder(new EmptyBorder(8, 10, 8, 10));
        toast.add(label);
        toast.pack();
        toast.setLocationRelativeTo(this);
        toast.setVisible(true);
        Timer timer = new Timer(2200, e -> toast.dispose());
        timer.setRepeats(false);
        timer.start();
    }

    private static void addRow(JPanel panel, int row, Component left, Component right) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = row;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(3, 0, 3, 8);
        panel.add(left, gc);

        gc = new GridBagConstraints();
        gc.gridx = 1;
        gc.gridy = row;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(3, 0, 3, 0);
        panel.add(right, gc);
    }

    private static JLabel label(String text, boolean strong) {
        JLabel label = new JLabel(text);
        label.setForeground(strong ? TEXT : MUTED);
        label.setFont(new Font(Font.SANS_SERIF, strong ? Font.BOLD : Font.PLAIN, 11));
        return label;
    }

    private static final class Row {
        final SeriesScanner.SeriesInfo info;
        final Map<String, String> tags;

        Row(SeriesScanner.SeriesInfo info, Map<String, String> tags) {
            this.info = info;
            this.tags = tags == null ? new LinkedHashMap<String, String>() : tags;
        }
    }

    private static final class BrowserTableModel extends AbstractTableModel {
        private final String[] columns = {"Token", "Label", "Timepoint", "Genotype",
                "Sex", "Condition", "Channels", "Dimensions", "Status"};
        private List<Row> rows = new ArrayList<Row>();

        void setRows(List<Row> rows) {
            this.rows = rows == null ? new ArrayList<Row>() : new ArrayList<Row>(rows);
            fireTableDataChanged();
        }

        Row rowAt(int index) {
            return index < 0 || index >= rows.size() ? null : rows.get(index);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            switch (columnIndex) {
                case 0: return row.info.token();
                case 1: return row.info.label();
                case 2: return row.tags.getOrDefault("timepoint", "");
                case 3: return row.tags.getOrDefault("genotype", "");
                case 4: return row.tags.getOrDefault("sex", "");
                case 5: return row.tags.getOrDefault("condition", "");
                case 6: return row.info.sizeC();
                case 7: return row.info.dimensionsLabel();
                case 8: return row.info.readable() ? "ready" : row.info.error();
                default: return "";
            }
        }
    }
}
