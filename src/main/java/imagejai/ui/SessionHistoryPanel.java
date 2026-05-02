package imagejai.ui;

import ij.IJ;
import ij.Prefs;
import imagejai.engine.SessionCodeJournal;
import imagejai.engine.SessionCodeJournal.Entry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * In-process rail view of the silent session-code journal.
 */
public final class SessionHistoryPanel extends JPanel implements SessionCodeJournal.Listener {
    public static final String PREF_COLLAPSED = "ai.assistant.history.collapsed";
    public static final String PREF_EXCLUDE_PLUMBING = "ai.assistant.history.excludePlumbing";

    private static final DateTimeFormatter ROW_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Color BG = new Color(34, 34, 40);
    private static final Color BG_DARK = new Color(28, 28, 33);
    private static final Color BORDER = new Color(55, 55, 64);
    private static final Color TEXT = new Color(230, 230, 235);
    private static final Color TEXT_MUTED = new Color(130, 130, 140);
    private static final Color ERROR_TEXT = new Color(255, 120, 120);

    private final TcpHotline tcpHotline;
    private final Runnable focusReturn;
    private final SessionCodeJournal journal = SessionCodeJournal.INSTANCE;
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    private final JPanel content = new JPanel();
    private final JButton collapseButton = iconButton();
    private final JButton menuButton = iconButton();
    private final JLabel toastLabel = new JLabel(" ");
    private final Timer refreshTimer;
    private final Timer toastTimer;
    private final Timer singleClickTimer;

    private Entry pendingSingleClick;
    private File workspace;
    private boolean collapsed;
    private boolean excludePlumbing;

    public SessionHistoryPanel(TcpHotline tcpHotline, File workspace, Runnable focusReturn) {
        this.tcpHotline = tcpHotline;
        this.workspace = workspace;
        this.focusReturn = focusReturn;
        this.collapsed = Prefs.get(PREF_COLLAPSED, false);
        this.excludePlumbing = Prefs.get(PREF_EXCLUDE_PLUMBING, false);

        if (Prefs.get(SessionCodeJournal.PREF_PERSIST, false)) {
            journal.loadFromIndexIfPresent();
        }

        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        refreshTimer = new Timer(250, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshNow();
            }
        });
        refreshTimer.setRepeats(false);

        toastTimer = new Timer(1000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toastLabel.setText(" ");
            }
        });
        toastTimer.setRepeats(false);

        singleClickTimer = new Timer(220, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Entry entry = pendingSingleClick;
                pendingSingleClick = null;
                if (entry != null) copyToClipboard(entry);
            }
        });
        singleClickTimer.setRepeats(false);

        add(header());
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        buildList();
        add(content);
        applyCollapsed();

        journal.addListener(this);
        refreshNow();
    }

    public void setWorkspace(File workspace) {
        this.workspace = workspace;
    }

    @Override
    public void removeNotify() {
        journal.removeListener(this);
        super.removeNotify();
    }

    @Override
    public void onChange() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                refreshTimer.restart();
            }
        });
    }

    private JPanel header() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("Session history");
        title.setForeground(TEXT_MUTED);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));

        collapseButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                collapsed = !collapsed;
                Prefs.set(PREF_COLLAPSED, collapsed);
                applyCollapsed();
            }
        });
        menuButton.setText("\u22EF");
        menuButton.setToolTipText("History options");
        menuButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showHeaderMenu(menuButton);
            }
        });

        header.add(collapseButton);
        header.add(Box.createHorizontalStrut(3));
        header.add(title);
        header.add(Box.createHorizontalGlue());
        header.add(menuButton);
        return header;
    }

    private void buildList() {
        list.setCellRenderer(new HistoryRowRenderer());
        list.setVisibleRowCount(5);
        list.setFixedCellHeight(22);
        list.setBackground(BG_DARK);
        list.setForeground(TEXT);
        list.setSelectionBackground(new Color(55, 65, 72));
        list.setSelectionForeground(TEXT);
        list.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        list.addMouseListener(new ClickDispatcher());

        JScrollPane scroll = new JScrollPane(list);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.setPreferredSize(new Dimension(160, 118));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));
        content.add(scroll);

        toastLabel.setForeground(TEXT_MUTED);
        toastLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        toastLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(Box.createVerticalStrut(2));
        content.add(toastLabel);
    }

    private void refreshNow() {
        List<Entry> entries = journal.snapshot();
        model.clear();
        for (Entry e : entries) {
            if (excludePlumbing && e.isPlumbingOnly()) continue;
            model.addElement(e);
        }
    }

    private void showHeaderMenu(Component owner) {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem clear = new JMenuItem("Clear history...");
        clear.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearHistory();
            }
        });
        popup.add(clear);

        JCheckBoxMenuItem filter = new JCheckBoxMenuItem("Filter: exclude plumbing", excludePlumbing);
        filter.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                excludePlumbing = filter.isSelected();
                Prefs.set(PREF_EXCLUDE_PLUMBING, excludePlumbing);
                refreshNow();
            }
        });
        popup.add(filter);

        JCheckBoxMenuItem persist = new JCheckBoxMenuItem(
                "Persist across restarts",
                Prefs.get(SessionCodeJournal.PREF_PERSIST, false));
        persist.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Prefs.set(SessionCodeJournal.PREF_PERSIST, persist.isSelected());
                if (persist.isSelected()) {
                    journal.loadFromIndexIfPresent();
                }
                refreshNow();
            }
        });
        popup.add(persist);
        popup.show(owner, 0, owner.getHeight());
    }

    private void clearHistory() {
        JCheckBox deleteFiles = new JCheckBox("Also delete saved journal files");
        deleteFiles.setOpaque(false);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(new JLabel("Clear the visible session history?"));
        panel.add(deleteFiles);
        int choice = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                panel,
                "Clear history",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        if (deleteFiles.isSelected()) {
            journal.clearRingAndDeleteFiles();
            toast("History and files cleared");
        } else {
            journal.clearRing();
            toast("History cleared");
        }
        refreshNow();
    }

    private void showRowMenu(final Entry entry, Component owner, int x, int y) {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem open = new JMenuItem("Open in editor");
        open.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openInEditor(entry);
            }
        });
        popup.add(open);

        JMenuItem save = new JMenuItem("Save to Macro sets...");
        save.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveToMacroSets(entry);
            }
        });
        popup.add(save);

        JMenuItem show = new JMenuItem("Show file");
        show.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showFile(entry);
            }
        });
        popup.add(show);

        JMenuItem remove = new JMenuItem("Remove from history");
        remove.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                journal.removeFromRing(entry.id);
                refreshNow();
                toast("Removed " + entry.name);
            }
        });
        popup.add(remove);

        popup.show(owner, x, y);
    }

    private void copyToClipboard(Entry entry) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(entry.code), null);
        toast("Copied " + entry.name);
    }

    private void rerun(final Entry entry) {
        toast("Running " + entry.name);
        focusTerminal();
        new SwingWorker<Void, Void>() {
            private String error;

            @Override
            protected Void doInBackground() {
                try {
                    tcpHotline.executeMacro(entry.code, "rail:history");
                    IJ.log("[ImageJAI-Term] Re-ran history entry: " + entry.name);
                } catch (IOException e) {
                    error = readableMessage(e);
                    IJ.log("[ImageJAI-Term] History re-run failed: " + entry.name + " - " + error);
                }
                return null;
            }

            @Override
            protected void done() {
                toast(error == null ? "Ran " + entry.name : error);
                focusTerminal();
            }
        }.execute();
    }

    private void openInEditor(Entry entry) {
        try {
            Path temp = Files.createTempFile("imagejai-history-", ".ijm");
            Files.write(temp, entry.code.getBytes(StandardCharsets.UTF_8));
            temp.toFile().deleteOnExit();
            openDesktop(temp.toFile());
            toast("Opened " + entry.name);
        } catch (IOException e) {
            toast(readableMessage(e));
            IJ.log("[ImageJAI-Term] Open history editor failed: " + readableMessage(e));
        }
    }

    private void saveToMacroSets(Entry entry) {
        String raw = JOptionPane.showInputDialog(
                SwingUtilities.getWindowAncestor(this),
                "Save as macro set:",
                entry.name);
        if (raw == null) return;
        String name = sanitizeFileStem(raw);
        if (name.isEmpty()) {
            toast("Enter a name");
            return;
        }
        try {
            File base = workspace != null ? workspace : new File(".");
            Path dir = base.toPath().resolve("agent").resolve("macro_sets");
            Files.createDirectories(dir);
            Path dest = dir.resolve(name.endsWith(".ijm") ? name : name + ".ijm");
            if (Files.exists(dest)) {
                int overwrite = JOptionPane.showConfirmDialog(
                        SwingUtilities.getWindowAncestor(this),
                        "Overwrite " + dest.getFileName() + "?",
                        "Save to Macro sets",
                        JOptionPane.OK_CANCEL_OPTION);
                if (overwrite != JOptionPane.OK_OPTION) return;
            }
            Files.write(dest, entry.code.getBytes(StandardCharsets.UTF_8));
            toast("Saved " + dest.getFileName());
            IJ.log("[ImageJAI-Term] Saved history entry to macro set: " + dest.toAbsolutePath());
        } catch (IOException e) {
            toast(readableMessage(e));
            IJ.log("[ImageJAI-Term] Save history macro set failed: " + readableMessage(e));
        }
    }

    private void showFile(Entry entry) {
        Path file = journal.filePathFor(entry);
        if (file == null || file.getParent() == null || !Files.isDirectory(file.getParent())) {
            toast("File unavailable");
            return;
        }
        try {
            openDesktop(file.getParent().toFile());
            toast("Opened journal folder");
        } catch (IOException e) {
            toast(readableMessage(e));
            IJ.log("[ImageJAI-Term] Show history file failed: " + readableMessage(e));
        }
    }

    private void openDesktop(File file) throws IOException {
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop open is not supported");
        }
        Desktop.getDesktop().open(file);
    }

    private void toast(String message) {
        toastLabel.setText(shortText(message));
        toastLabel.setToolTipText(message);
        toastTimer.restart();
    }

    private void applyCollapsed() {
        content.setVisible(!collapsed);
        collapseButton.setText(collapsed ? "\u25B8" : "\u25BE");
        collapseButton.setToolTipText(collapsed ? "Expand history" : "Collapse history");
        revalidate();
        repaint();
    }

    private void focusTerminal() {
        if (focusReturn != null) {
            focusReturn.run();
        }
    }

    private static JButton iconButton() {
        JButton button = new JButton();
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        button.setForeground(TEXT_MUTED);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static String rowText(Entry entry) {
        String prefix = entry.success ? "" : "\u26A0 ";
        return prefix + ROW_TIME.format(Instant.ofEpochMilli(entry.lastRunAtMs))
                + "  " + entry.name
                + "  \u00D7" + entry.runCount
                + "  " + lineCount(entry.code) + "L";
    }

    private static int lineCount(String code) {
        if (code == null || code.isEmpty()) return 0;
        return code.split("\\r?\\n", -1).length;
    }

    private static String sanitizeFileStem(String raw) {
        if (raw == null) return "";
        String name = raw.trim().replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        return name;
    }

    private static String shortText(String text) {
        if (text == null || text.trim().isEmpty()) return " ";
        String compact = text.trim();
        return compact.length() <= 28 ? compact : compact.substring(0, 25) + "...";
    }

    private static String readableMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }

    private final class HistoryRowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            Entry entry = (Entry) value;
            label.setText(rowText(entry));
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            label.setForeground(entry.success ? TEXT : ERROR_TEXT);
            label.setToolTipText(entry.success ? entry.name : entry.failureMessage);
            return label;
        }
    }

    private final class ClickDispatcher extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            maybePopup(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            maybePopup(e);
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (!SwingUtilities.isLeftMouseButton(e)) return;
            int index = list.locationToIndex(e.getPoint());
            if (index < 0) return;
            Entry entry = model.getElementAt(index);
            if (e.getClickCount() >= 2) {
                singleClickTimer.stop();
                pendingSingleClick = null;
                rerun(entry);
            } else if (e.getClickCount() == 1) {
                pendingSingleClick = entry;
                singleClickTimer.restart();
            }
        }

        private void maybePopup(MouseEvent e) {
            if (!e.isPopupTrigger()) return;
            int index = list.locationToIndex(e.getPoint());
            if (index < 0) return;
            list.setSelectedIndex(index);
            showRowMenu(model.getElementAt(index), list, e.getX(), e.getY());
        }
    }
}
