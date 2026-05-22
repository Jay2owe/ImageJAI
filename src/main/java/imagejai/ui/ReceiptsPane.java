package imagejai.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import imagejai.config.PrivacyPosture;
import imagejai.engine.security.AuditLog;
import imagejai.engine.security.AuditRow;
import imagejai.engine.security.PseudonymisationFilter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Collapsible live receipt table backed by the append-only audit log.
 */
public final class ReceiptsPane extends JPanel implements AuditLog.Listener {
    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Color BG = new Color(36, 36, 42);
    private static final Color BORDER = new Color(62, 62, 70);
    private static final Color TEXT = new Color(220, 220, 225);

    private final AuditLog auditLog;
    private final JButton toggle;
    private final JPanel content;
    private final JTable table;
    private final ReceiptTableModel model = new ReceiptTableModel();
    private final AutoCloseable subscription;
    private boolean expanded;

    public ReceiptsPane(AuditLog auditLog) {
        super(new BorderLayout(0, 0));
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

        table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 4) {
                    showReceiptModal(table.convertRowIndexToModel(row));
                }
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(58);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(54);

        content = new JPanel(new BorderLayout());
        content.setBackground(BG);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(4, 4, 4, 4)));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 128));
        content.add(scroll, BorderLayout.CENTER);
        add(content, BorderLayout.CENTER);

        subscription = this.auditLog.subscribeRecent(50, this);
        setExpanded(false);
    }

    public void dispose() {
        try {
            subscription.close();
        } catch (Exception ignore) {
        }
    }

    @Override
    public void auditRowsUpdated(final List<AuditRow> recentRows) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                model.setRows(recentRows);
            }
        });
    }

    private void setExpanded(boolean expanded) {
        this.expanded = expanded;
        toggle.setText(expanded ? "Receipts \u25BE" : "Receipts \u25B8");
        content.setVisible(expanded);
        revalidate();
        repaint();
    }

    private void showReceiptModal(int modelRow) {
        if (modelRow < 0 || modelRow >= model.getRowCount()) {
            return;
        }
        JComponent detail = createReceiptDetailPanel(model.receiptAt(modelRow));
        Window owner = SwingUtilities.getWindowAncestor(this);
        Frame frame = owner instanceof Frame ? (Frame) owner : null;
        final JDialog dialog = new JDialog(frame, "Outbound receipt", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.getContentPane().add(detail);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private JComponent createReceiptDetailPanel(Receipt receipt) {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel areas = new JPanel(new GridLayout(1, 2, 6, 0));
        JTextArea json = textArea(prettyJson(receipt.redactedJson));
        JTextArea fields = textArea(fieldsText(receipt.row));
        areas.add(wrap("Redacted JSON", json));
        areas.add(wrap("fields_redacted", fields));
        panel.add(areas, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            Window window = SwingUtilities.getWindowAncestor(panel);
            if (window != null) {
                window.dispose();
            }
        });
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(close, BorderLayout.EAST);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private static JComponent wrap(String title, JTextArea area) {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        JLabel label = new JLabel(title);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        panel.add(label, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(320, 240));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private static JTextArea textArea(String text) {
        JTextArea area = new JTextArea(text == null ? "" : text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        return area;
    }

    private static String fieldsText(AuditRow row) {
        if (row == null || row.fieldsRedacted().isEmpty()) {
            return "(none)";
        }
        StringBuilder out = new StringBuilder();
        for (String field : row.fieldsRedacted()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(field);
        }
        return out.toString();
    }

    private static String prettyJson(String json) {
        String capped = AuditRow.capRedactedPayload(json == null ? "" : json);
        try {
            JsonElement element = JsonParser.parseString(capped);
            return AuditRow.capRedactedPayload(PRETTY.toJson(element));
        } catch (Exception ignored) {
            return capped;
        }
    }

    private static String redactedPayload(AuditRow row) {
        if (row == null) {
            return "{}";
        }
        String payload = row.redactedPayloadJson();
        if (payload != null && !payload.trim().isEmpty()) {
            return scrubPayloadJson(payload, row);
        }

        JsonObject fallback = new JsonObject();
        fallback.addProperty("command", row.command());
        fallback.addProperty("posture", row.posture().label());
        fallback.addProperty("bytes_out", row.bytesOut());
        fallback.addProperty("redaction_applied", row.redactionApplied());
        JsonArray fields = new JsonArray();
        for (String field : row.fieldsRedacted()) {
            fields.add(field);
        }
        fallback.add("fields_redacted", fields);
        String notes = PseudonymisationFilter.getInstance().freeTextScrubString(row.notes());
        if (notes != null && !notes.trim().isEmpty()) {
            fallback.addProperty("notes", notes);
        }
        fallback.addProperty("receipt_note",
                "Full response body was not cached; this receipt stores redacted audit metadata only.");
        return AuditRow.capRedactedPayload(fallback.toString());
    }

    private static String scrubPayloadJson(String payload, AuditRow row) {
        try {
            JsonElement element = JsonParser.parseString(payload);
            if (element != null && element.isJsonObject()) {
                JsonObject copy = element.getAsJsonObject();
                PrivacyPosture posture = row.posture() == PrivacyPosture.STANDARD
                        ? PrivacyPosture.PSEUDONYMISED
                        : row.posture();
                if (posture != PrivacyPosture.STANDARD) {
                    PseudonymisationFilter.getInstance().apply(copy, row.command(),
                            posture, row.sessionId());
                }
                return AuditRow.capRedactedPayload(PRETTY.toJson(copy));
            }
        } catch (Exception ignored) {
        }
        String scrubbed = PseudonymisationFilter.getInstance()
                .freeTextScrubString(payload);
        return AuditRow.capRedactedPayload(scrubbed);
    }

    int rowCountForTest() {
        return model.getRowCount();
    }

    JComponent createReceiptDetailPanelForTest(int modelRow) {
        return createReceiptDetailPanel(model.receiptAt(modelRow));
    }

    String redactedJsonForTest(int modelRow) {
        return model.receiptAt(modelRow).redactedJson;
    }

    private static final class Receipt {
        final AuditRow row;
        final String redactedJson;

        Receipt(AuditRow row) {
            this.row = row;
            this.redactedJson = redactedPayload(row);
        }
    }

    private static final class ReceiptTableModel extends AbstractTableModel {
        private final String[] columns = {
                "time", "command", "bytes_out", "redacted", "show"
        };
        private final List<Receipt> rows = new ArrayList<Receipt>();

        void setRows(List<AuditRow> auditRows) {
            rows.clear();
            if (auditRows != null) {
                int start = Math.max(0, auditRows.size() - 50);
                for (int i = start; i < auditRows.size(); i++) {
                    rows.add(new Receipt(auditRows.get(i)));
                }
            }
            fireTableDataChanged();
        }

        Receipt receiptAt(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AuditRow row = rows.get(rowIndex).row;
            switch (columnIndex) {
                case 0:
                    return TIME.format(row.timestampUtc());
                case 1:
                    return row.command();
                case 2:
                    return row.bytesOut();
                case 3:
                    return row.redactionApplied() ? "yes" : "no";
                case 4:
                    return "[show]";
                default:
                    return "";
            }
        }
    }
}
