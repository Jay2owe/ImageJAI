package imagejai.ui;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.Prefs;
import imagejai.config.Settings;
import imagejai.engine.EmbeddedAgentSession;
import imagejai.engine.terminal.AgentRegistry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JOptionPane;
import javax.swing.JSeparator;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Left-side terminal rail for deterministic Fiji actions.
 */
public class LeftRail extends JPanel {
    public interface SessionRelauncher {
        void relaunchEmbeddedSession(EmbeddedAgentSession oldSession);
    }

    public static final String PREF_COLLAPSED = "ai.assistant.rail.collapsed";
    public static final String PREF_FONT = "ai.assistant.rail.font";

    private static final String CLOSE_ALL_MACRO = "run('Close All');";
    private static final String RESET_ROI_MACRO = "roiManager('Reset');";
    private static final String Z_PROJECT_MACRO =
            "run('Z Project...', 'projection=[Max Intensity]');";
    private static final String WIP_TEMPLATE_RESOURCE = "/wip_template.md";
    private static final String AUDIT_SCRIPT =
            "import json, os, re, sys\n"
                    + "sys.path.insert(0, os.path.join(os.getcwd(), 'agent'))\n"
                    + "from auditor import audit_results\n"
                    + "data = json.load(sys.stdin)\n"
                    + "info = data.get('image_info') or {}\n"
                    + "type_str = str(info.get('type') or '')\n"
                    + "bit_depth = 8 if '8' in type_str else 16 if '16' in type_str else 32 if '32' in type_str else None\n"
                    + "cal = str(info.get('calibration') or '')\n"
                    + "pixel_size = None\n"
                    + "unit = None\n"
                    + "m = re.match(r'([0-9.]+)\\s+(.+)/px', cal)\n"
                    + "if m:\n"
                    + "    pixel_size = float(m.group(1))\n"
                    + "    unit = m.group(2)\n"
                    + "result = audit_results(data.get('results_table') or '', pixel_size=pixel_size, unit=unit, bit_depth=bit_depth)\n"
                    + "print(result.get('summary') or '')\n";

    private static final Color BG = new Color(34, 34, 40);
    private static final Color BG_DARK = new Color(28, 28, 33);
    private static final Color BORDER = new Color(55, 55, 64);
    private static final Color ACCENT = new Color(0, 200, 255);
    private static final Color TEXT = new Color(230, 230, 235);
    private static final Color TEXT_MUTED = new Color(130, 130, 140);

    private final TcpHotline tcpHotline;
    private final Settings settings;
    private final SessionRelauncher sessionRelauncher;
    private final Runnable focusReturn;
    private final JPanel body;
    private final JButton collapseButton;
    private final JLabel titleLabel;
    private final JLabel statusLabel;
    private final Timer statusTimer;
    private final JButton commandsButton;
    private final JButton newAgentChatButton;
    private final JButton newWipButton;
    private final SessionHistoryPanel sessionHistoryPanel;
    private final JButton macroSetsButton;
    private final JButton runRecipeButton;
    private final JButton auditButton;

    private boolean collapsed;
    private int railFontSize;
    private File workspace;
    private EmbeddedAgentSession session;

    public LeftRail(Settings settings, File workspace,
                    SessionRelauncher sessionRelauncher, Runnable focusReturn) {
        this.settings = settings;
        this.tcpHotline = new TcpHotline(settings);
        this.workspace = workspace;
        this.sessionRelauncher = sessionRelauncher;
        this.focusReturn = focusReturn;
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

        newWipButton = railButton("New WIP", "Create a work-in-progress note");
        newWipButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                newWip();
            }
        });
        sessionHistoryPanel = new SessionHistoryPanel(tcpHotline, workspace, focusReturn);

        commandsButton = railButton("Commands", "no command list");
        commandsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showCommandsPopup(commandsButton);
            }
        });
        newAgentChatButton = railButton("New agent chat", "Clear agent context");
        newAgentChatButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                newAgentChat();
            }
        });

        macroSetsButton = railButton("Macro sets...", "Run a saved .ijm macro set");
        macroSetsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showMacroSetsPopup(macroSetsButton);
            }
        });

        runRecipeButton = railButton("Run recipe...", "Run an agent recipe");
        runRecipeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showRecipesPopup(runRecipeButton);
            }
        });

        auditButton = railButton("Audit my results", "Audit the current Results table");
        auditButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                auditResults();
            }
        });

        add(createHeader());
        add(Box.createVerticalStrut(8));
        add(body);
        add(Box.createVerticalGlue());
        add(statusLabel);

        buildSessionSection();
        buildAgentSection();
        buildHotlineSection();
        buildGuidanceSection();
        updateAgentButtons();
        applyCollapsedState(false);
    }

    public void setWorkspace(File workspace) {
        this.workspace = workspace;
        sessionHistoryPanel.setWorkspace(workspace);
    }

    public void attachSession(EmbeddedAgentSession newSession) {
        session = newSession;
        updateAgentButtons();
    }

    public void clearSession(EmbeddedAgentSession expected) {
        if (expected != null && session != expected) {
            return;
        }
        session = null;
        updateAgentButtons();
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

    private void buildSessionSection() {
        body.add(sectionTitle("Session"));
        body.add(Box.createVerticalStrut(6));
        body.add(newWipButton);
        body.add(Box.createVerticalStrut(12));
    }

    private void buildAgentSection() {
        body.add(sectionTitle("Agent"));
        body.add(Box.createVerticalStrut(6));
        body.add(commandsButton);
        body.add(Box.createVerticalStrut(6));
        body.add(newAgentChatButton);
        body.add(Box.createVerticalStrut(12));
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
        body.add(Box.createVerticalStrut(6));
        body.add(sessionHistoryPanel);
        body.add(Box.createVerticalStrut(6));
        body.add(macroSetsButton);
        body.add(Box.createVerticalStrut(12));
    }

    private void buildGuidanceSection() {
        body.add(sectionTitle("Guidance"));
        body.add(Box.createVerticalStrut(6));
        body.add(runRecipeButton);
        body.add(Box.createVerticalStrut(6));
        body.add(auditButton);
    }

    private void updateAgentButtons() {
        boolean hasSession = session != null && session.isAlive();
        boolean hasCommands = hasSession
                && !AgentRegistry.builtInCommands(session.info()).isEmpty();
        commandsButton.setEnabled(hasCommands);
        commandsButton.setToolTipText(hasCommands
                ? "Show agent slash commands"
                : "no command list");
        newAgentChatButton.setEnabled(hasSession);
        newAgentChatButton.setToolTipText(hasSession
                ? "Clear the agent chat; restart only if clear is not confirmed"
                : "No embedded agent running");
    }

    private void showCommandsPopup(Component owner) {
        if (session == null || !session.isAlive()) {
            return;
        }
        List<AgentRegistry.CommandEntry> builtins =
                AgentRegistry.builtInCommands(session.info());
        if (builtins.isEmpty()) {
            showStatus("No command list");
            return;
        }

        JPopupMenu popup = new JPopupMenu();
        addPopupSection(popup, "Built-in", builtins);
        List<AgentRegistry.CommandEntry> user =
                AgentRegistry.userCommands(session.info(), workspace);
        if (!user.isEmpty()) {
            popup.add(new JSeparator());
            addPopupSection(popup, "User", user);
        }
        popup.show(owner, 0, owner.getHeight());
    }

    private void addPopupSection(JPopupMenu popup, String label,
                                 List<AgentRegistry.CommandEntry> commands) {
        JMenuItem header = new JMenuItem(label);
        header.setEnabled(false);
        popup.add(header);
        for (final AgentRegistry.CommandEntry entry : commands) {
            JMenuItem item = new JMenuItem(entry.command);
            if (!entry.description.isEmpty()) {
                item.setToolTipText(entry.description);
            }
            item.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (session != null && session.isAlive()) {
                        session.writeInput(entry.command);
                        IJ.log("[ImageJAI-Term] Injected agent command: " + entry.command);
                        focusTerminal();
                    }
                }
            });
            popup.add(item);
        }
    }

    private void newAgentChat() {
        final EmbeddedAgentSession current = session;
        if (current == null || !current.isAlive()) {
            showStatus("No agent running");
            updateAgentButtons();
            return;
        }

        final Pattern clearPattern = AgentRegistry.clearPattern(current.info());
        current.writeInput("/clear");
        showStatus("Sent /clear");
        focusTerminal();

        Timer clearTimer = new Timer(1000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (session != current) {
                    return;
                }
                String tail = current.readScrollback(40);
                if (clearPattern != null && clearPattern.matcher(tail).find()) {
                    IJ.log("[ImageJAI-Term] Agent chat cleared without PTY restart: "
                            + current.info().name);
                    showStatus("Agent chat cleared");
                    focusTerminal();
                    return;
                }
                IJ.log("[ImageJAI-Term] Agent /clear not confirmed; restarting PTY: "
                        + current.info().name);
                showStatus("Restarting agent");
                if (sessionRelauncher != null) {
                    sessionRelauncher.relaunchEmbeddedSession(current);
                }
            }
        });
        clearTimer.setRepeats(false);
        clearTimer.start();
    }

    private void newWip() {
        String raw = JOptionPane.showInputDialog(
                SwingUtilities.getWindowAncestor(this),
                "Slug for the new WIP note:",
                "New WIP",
                JOptionPane.PLAIN_MESSAGE);
        if (raw == null) {
            focusTerminal();
            return;
        }
        String slug = sanitizeSlug(raw);
        if (slug.isEmpty()) {
            showStatus("Enter a slug");
            focusTerminal();
            return;
        }

        try {
            File base = workspace != null ? workspace : new File(".");
            Path wipDir = base.toPath().resolve("agent").resolve("work_in_progress");
            Files.createDirectories(wipDir);
            Path wip = wipDir.resolve(slug + ".md");
            if (!Files.exists(wip)) {
                String template = loadWipTemplate().replace("<slug>", slug);
                Files.write(wip, template.getBytes(StandardCharsets.UTF_8));
                IJ.log("[ImageJAI-Term] Created WIP note: " + wip.toAbsolutePath());
            } else {
                IJ.log("[ImageJAI-Term] Reusing existing WIP note: " + wip.toAbsolutePath());
            }

            String path = wip.toAbsolutePath().normalize().toString();
            if (session != null && session.isAlive()) {
                session.writeInput("Start new WIP: read `" + path + "` and help me scope it.");
                showStatus("WIP prompt sent");
            } else {
                showStatus("WIP note created");
            }
        } catch (IOException e) {
            String msg = readableMessage(e);
            showStatus(msg);
            IJ.log("[ImageJAI-Term] New WIP failed: " + msg);
        } finally {
            focusTerminal();
        }
    }

    private void showMacroSetsPopup(Component owner) {
        final List<Path> macros = listFiles("agent/macro_sets", ".ijm");
        JPopupMenu popup = new JPopupMenu();
        if (macros.isEmpty()) {
            JMenuItem empty = new JMenuItem("No .ijm files");
            empty.setEnabled(false);
            popup.add(empty);
        } else {
            for (final Path macro : macros) {
                JMenuItem item = new JMenuItem(macro.getFileName().toString());
                item.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        runHotline("Macro set " + macro.getFileName(), macroSetsButton,
                                new HotlineTask() {
                                    @Override
                                    public void run() throws IOException {
                                        String code = new String(Files.readAllBytes(macro), StandardCharsets.UTF_8);
                                        tcpHotline.executeMacro(code, "rail:macro-set");
                                    }
                                });
                    }
                });
                popup.add(item);
            }
        }
        popup.show(owner, 0, owner.getHeight());
    }

    private void showRecipesPopup(Component owner) {
        final List<Path> recipes = listFiles("agent/recipes", ".yaml");
        JPopupMenu popup = new JPopupMenu();
        if (recipes.isEmpty()) {
            JMenuItem empty = new JMenuItem("No recipes found");
            empty.setEnabled(false);
            popup.add(empty);
        } else {
            for (final Path recipe : recipes) {
                final String name = stripExtension(recipe.getFileName().toString());
                JMenuItem item = new JMenuItem(name);
                item.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        runRecipe(name);
                    }
                });
                popup.add(item);
            }
        }
        popup.show(owner, 0, owner.getHeight());
    }

    private void runRecipe(final String name) {
        final EmbeddedAgentSession current = session;
        runRecipeButton.setEnabled(false);
        showStatus("Running recipe...");
        focusTerminal();
        new SwingWorker<Integer, String>() {
            @Override
            protected Integer doInBackground() throws Exception {
                File base = workspace != null ? workspace : new File(".");
                ProcessBuilder pb = new ProcessBuilder(
                        "python", "-u", "agent/run_recipe.py", name);
                pb.directory(base);
                pb.redirectErrorStream(true);
                pb.environment().put("IMAGEJAI_TCP_PORT", String.valueOf(tcpHotlinePort()));
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        publish(line);
                    }
                }
                return process.waitFor();
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    showStatus(line);
                    if (current != null && current.isAlive()) {
                        current.writeRaw(line + "\r");
                    }
                }
            }

            @Override
            protected void done() {
                runRecipeButton.setEnabled(true);
                try {
                    int exit = get();
                    showStatus(exit == 0 ? "Recipe done" : "Recipe failed");
                    IJ.log("[ImageJAI-Term] Recipe " + name + " exited " + exit);
                } catch (Exception e) {
                    String msg = readableMessage(e);
                    showStatus(msg);
                    IJ.log("[ImageJAI-Term] Recipe failed: " + name + " - " + msg);
                }
                focusTerminal();
            }
        }.execute();
    }

    private void auditResults() {
        final EmbeddedAgentSession current = session;
        auditButton.setEnabled(false);
        showStatus("Auditing results...");
        focusTerminal();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                String csv = tcpHotline.getResultsTable();
                JsonObject info = tcpHotline.getImageInfo();
                JsonObject payload = new JsonObject();
                payload.addProperty("results_table", csv);
                payload.add("image_info", info != null ? info : new JsonObject());

                File base = workspace != null ? workspace : new File(".");
                ProcessBuilder pb = new ProcessBuilder("python", "-c", AUDIT_SCRIPT);
                pb.directory(base);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                try (Writer writer = new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8)) {
                    writer.write(payload.toString());
                }
                String output = readAll(process.getInputStream()).trim();
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new IOException(output.isEmpty()
                            ? "Auditor exited " + exit
                            : output);
                }
                return output.isEmpty() ? "Audit returned no summary." : output;
            }

            @Override
            protected void done() {
                auditButton.setEnabled(true);
                try {
                    String summary = get();
                    if (current != null && current.isAlive()) {
                        current.writeInput("Audit my results:\n" + summary);
                    }
                    showStatus("Audit sent");
                    IJ.log("[ImageJAI-Term] Audit summary sent to PTY");
                } catch (Exception e) {
                    String msg = readableMessage(e);
                    showStatus(msg);
                    IJ.log("[ImageJAI-Term] Audit failed: " + msg);
                }
                focusTerminal();
            }
        }.execute();
    }

    private JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT_MUTED);
        label.setFont(railFont().deriveFont(Font.BOLD));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JButton hotlineButton(final String label, String tooltip, final HotlineTask task) {
        final JButton button = railButton(label, tooltip);
        button.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runHotline(label, button, task);
            }
        });
        return button;
    }

    private JButton railButton(final String label, String tooltip) {
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
        return button;
    }

    private void runHotline(final String label, final JButton button, final HotlineTask task) {
        button.setEnabled(false);
        showStatus("Running " + label + "...");
        focusTerminal();
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
                focusTerminal();
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

    private void focusTerminal() {
        if (focusReturn != null) {
            focusReturn.run();
        }
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

    private int tcpHotlinePort() {
        return settings != null ? settings.tcpPort : 7746;
    }

    private List<Path> listFiles(String relativeDir, String suffix) {
        List<Path> files = new ArrayList<Path>();
        File base = workspace != null ? workspace : new File(".");
        Path dir = base.toPath().resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase().endsWith(suffix)) {
                    files.add(path);
                }
            }
        } catch (IOException e) {
            showStatus(readableMessage(e));
            IJ.log("[ImageJAI-Term] Could not list " + relativeDir + ": " + e.getMessage());
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return a.getFileName().toString()
                        .compareToIgnoreCase(b.getFileName().toString());
            }
        });
        return files;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sanitizeSlug(String raw) {
        if (raw == null) {
            return "";
        }
        String slug = raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return slug;
    }

    private static String loadWipTemplate() throws IOException {
        InputStream in = LeftRail.class.getResourceAsStream(WIP_TEMPLATE_RESOURCE);
        if (in == null) {
            return "# <slug>\n\n## Goal\n\n## Steps\n\n## Decisions\n\n## Open questions\n";
        }
        try (InputStream stream = in) {
            return readAll(stream);
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                out.append(buf, 0, n);
            }
        }
        return out.toString();
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
