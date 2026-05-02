package imagejai.ui;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.Prefs;
import imagejai.config.Settings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;

/**
 * Left-side terminal rail for deterministic Fiji actions.
 */
public class LeftRail extends JPanel {
    public static final String PREF_COLLAPSED = "ai.assistant.rail.collapsed";
    public static final String PREF_FONT = "ai.assistant.rail.font";

    private static final String CLOSE_ALL_MACRO = "run('Close All');";
    private static final String RESET_ROI_MACRO = "roiManager('Reset');";
    private static final String Z_PROJECT_MACRO =
            "run('Z Project...', 'projection=[Max Intensity]');";

    private static final Color BG = new Color(34, 34, 40);
    private static final Color BG_DARK = new Color(28, 28, 33);
    private static final Color BORDER = new Color(55, 55, 64);
    private static final Color ACCENT = new Color(0, 200, 255);
    private static final Color TEXT = new Color(230, 230, 235);
    private static final Color TEXT_MUTED = new Color(130, 130, 140);

    private final TcpHotline tcpHotline;
    private final JPanel body;
    private final JButton collapseButton;
    private final JLabel titleLabel;
    private final JLabel statusLabel;
    private final Timer statusTimer;

    private boolean collapsed;
    private int railFontSize;

    public LeftRail(Settings settings) {
        this.tcpHotline = new TcpHotline(settings);
        this.collapsed = Prefs.get(PREF_COLLAPSED, false);
        this.railFontSize = clampFontSize((int) Math.round(Prefs.get(PREF_FONT, 12.0)));
        Prefs.set(PREF_FONT, railFontSize);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));

        collapseButton = createIconButton();
        titleLabel = new JLabel("Rail");
        body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        statusLabel = new JLabel(" ");
        statusLabel.setForeground(TEXT_MUTED);
        statusLabel.setFont(railFont());
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        statusTimer = new Timer(2500, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                statusLabel.setText(" ");
            }
        });
        statusTimer.setRepeats(false);

        add(createHeader());
        add(Box.createVerticalStrut(8));
        add(body);
        add(Box.createVerticalGlue());
        add(statusLabel);

        buildHotlineSection();
        applyCollapsedState(false);
    }

    public void setRailFontSize(int fontSize) {
        railFontSize = clampFontSize(fontSize);
        Prefs.set(PREF_FONT, railFontSize);
        applyFontRecursively(this, railFont());
        revalidate();
        repaint();
    }

    private JPanel createHeader() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        titleLabel.setForeground(ACCENT);
        titleLabel.setFont(railFont().deriveFont(Font.BOLD));
        titleLabel.setAlignmentY(Component.CENTER_ALIGNMENT);

        collapseButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setCollapsed(!collapsed);
            }
        });

        header.add(titleLabel);
        header.add(Box.createHorizontalGlue());
        header.add(collapseButton);
        return header;
    }

    private void buildHotlineSection() {
        body.add(sectionTitle("Fiji hotlines"));
        body.add(Box.createVerticalStrut(6));
        body.add(hotlineButton("Close all images", CLOSE_ALL_MACRO,
                new HotlineTask() {
                    @Override
                    public void run() throws IOException {
                        tcpHotline.executeMacro(CLOSE_ALL_MACRO);
                    }
                }));
        body.add(Box.createVerticalStrut(6));
        body.add(hotlineButton("Reset ROI Manager", RESET_ROI_MACRO,
                new HotlineTask() {
                    @Override
                    public void run() throws IOException {
                        tcpHotline.executeMacro(RESET_ROI_MACRO);
                    }
                }));
        body.add(Box.createVerticalStrut(6));
        body.add(hotlineButton("Z-project (max)", Z_PROJECT_MACRO,
                new HotlineTask() {
                    @Override
                    public void run() throws IOException {
                        JsonObject info = tcpHotline.getImageInfo();
                        if (!isStack(info)) {
                            throw new PreconditionException("Open a stack first.");
                        }
                        tcpHotline.executeMacro(Z_PROJECT_MACRO);
                    }
                }));
    }

    private JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT_MUTED);
        label.setFont(railFont().deriveFont(Font.BOLD));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JButton hotlineButton(final String label, String tooltip, final HotlineTask task) {
        final JButton button = new JButton(label);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        button.setMinimumSize(new Dimension(120, 28));
        button.setFont(railFont());
        button.setForeground(TEXT);
        button.setBackground(BG_DARK);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(tooltip);
        button.setMargin(new Insets(2, 6, 2, 6));
        button.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runHotline(label, button, task);
            }
        });
        return button;
    }

    private void runHotline(final String label, final JButton button, final HotlineTask task) {
        button.setEnabled(false);
        showStatus("Running " + label + "...");
        new SwingWorker<Void, Void>() {
            private String userMessage;
            private boolean ok;

            @Override
            protected Void doInBackground() {
                try {
                    task.run();
                    ok = true;
                    IJ.log("[ImageJAI-Term] Hotline completed: " + label);
                } catch (PreconditionException e) {
                    userMessage = e.getMessage();
                    IJ.log("[ImageJAI-Term] Hotline skipped: " + label + " - " + userMessage);
                } catch (Exception e) {
                    userMessage = readableMessage(e);
                    IJ.log("[ImageJAI-Term] Hotline failed: " + label + " - " + userMessage);
                }
                return null;
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                showStatus(ok ? "Done: " + label : userMessage);
            }
        }.execute();
    }

    private void setCollapsed(boolean value) {
        if (collapsed == value) {
            return;
        }
        collapsed = value;
        Prefs.set(PREF_COLLAPSED, collapsed);
        applyCollapsedState(true);
    }

    private void applyCollapsedState(boolean repaintNow) {
        body.setVisible(!collapsed);
        titleLabel.setVisible(!collapsed);
        statusLabel.setVisible(!collapsed);
        collapseButton.setText(collapsed ? "\u25B8" : "\u25BE");
        collapseButton.setToolTipText(collapsed ? "Expand rail" : "Collapse rail");
        setPreferredSize(collapsed ? new Dimension(36, 10) : new Dimension(180, 10));
        setMinimumSize(collapsed ? new Dimension(36, 10) : new Dimension(160, 10));
        if (repaintNow) {
            revalidate();
            repaint();
        }
    }

    private JButton createIconButton() {
        JButton button = new JButton();
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        button.setForeground(TEXT_MUTED);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private void showStatus(String message) {
        final String text = message == null || message.trim().isEmpty() ? " " : message;
        if (SwingUtilities.isEventDispatchThread()) {
            setStatusText(text);
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    setStatusText(text);
                }
            });
        }
    }

    private void setStatusText(String text) {
        statusLabel.setToolTipText(text);
        statusLabel.setText(shortStatus(text));
        statusTimer.restart();
    }

    private Font railFont() {
        return new Font(Font.SANS_SERIF, Font.PLAIN, railFontSize);
    }

    private static void applyFontRecursively(Component component, Font font) {
        component.setFont(font);
        if (component instanceof JPanel) {
            Component[] children = ((JPanel) component).getComponents();
            for (Component child : children) {
                applyFontRecursively(child, font);
            }
        }
    }

    private static boolean isStack(JsonObject info) {
        if (info == null) {
            return false;
        }
        if (info.has("isStack") && info.get("isStack").getAsBoolean()) {
            return true;
        }
        int slices = info.has("slices") ? info.get("slices").getAsInt() : 1;
        int frames = info.has("frames") ? info.get("frames").getAsInt() : 1;
        return slices > 1 || frames > 1;
    }

    private static int clampFontSize(int size) {
        return Math.max(10, Math.min(18, size));
    }

    private static String shortStatus(String text) {
        if (text == null) {
            return " ";
        }
        String compact = text.trim();
        if (compact.length() <= 28) {
            return compact.isEmpty() ? " " : compact;
        }
        return compact.substring(0, 25) + "...";
    }

    private static String readableMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = cause.getMessage();
        }
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : message;
    }

    private interface HotlineTask {
        void run() throws IOException;
    }

    private static final class PreconditionException extends IOException {
        PreconditionException(String message) {
            super(message);
        }
    }
}
