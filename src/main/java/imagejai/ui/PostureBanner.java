package imagejai.ui;

import imagejai.config.PrivacyPosture;
import imagejai.engine.PostureController;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-modal Data Governance prompts shown when image folders are opened.
 */
public final class PostureBanner implements PostureController.Presenter {

    public interface OwnerProvider {
        Frame owner();
    }

    private final OwnerProvider ownerProvider;
    private final Map<Path, JDialog> folderPrompts = new ConcurrentHashMap<Path, JDialog>();
    private final Map<Path, JDialog> downshiftPrompts = new ConcurrentHashMap<Path, JDialog>();

    public PostureBanner(OwnerProvider ownerProvider) {
        this.ownerProvider = ownerProvider;
    }

    @Override
    public void showFolderPosturePrompt(final Path folder,
                                        final PrivacyPosture defaultPosture,
                                        final PostureController controller) {
        if (GraphicsEnvironment.isHeadless() || folder == null || controller == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (folderPrompts.containsKey(folder)) {
                    bringToFront(folderPrompts.get(folder));
                    return;
                }
                final JDialog dialog = createDialog("Data Governance - Privacy Posture");
                folderPrompts.put(folder, dialog);
                dialog.addWindowListener(removeOnClose(folderPrompts, folder));

                JPanel root = rootPanel();
                root.add(title("Privacy Posture for this folder"), BorderLayout.NORTH);

                JPanel body = new JPanel(new BorderLayout(0, 8));
                body.setOpaque(false);
                body.add(new JLabel("<html><b>Folder:</b> " + escape(display(folder))
                        + "<br>This setting is remembered for this folder.</html>"),
                        BorderLayout.NORTH);

                final Map<PrivacyPosture, JRadioButton> buttons =
                        new LinkedHashMap<PrivacyPosture, JRadioButton>();
                JPanel choices = new JPanel(new GridLayout(0, 1, 0, 4));
                choices.setOpaque(false);
                ButtonGroup group = new ButtonGroup();
                for (PrivacyPosture posture : PrivacyPosture.values()) {
                    JRadioButton option = new JRadioButton("<html><b>"
                            + escape(posture.label()) + "</b> - "
                            + escape(posture.description()) + "</html>");
                    option.setOpaque(false);
                    option.setSelected(posture == defaultPosture);
                    group.add(option);
                    choices.add(option);
                    buttons.put(posture, option);
                }
                body.add(choices, BorderLayout.CENTER);

                JLabel guidance = new JLabel("<html>Recommended workflow in "
                        + "Pseudonymised mode: open images via Fiji File &gt; Open "
                        + "or the Browse Files dialog; do not paste filenames into "
                        + "the agent chat.</html>");
                guidance.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                body.add(guidance, BorderLayout.SOUTH);
                root.add(body, BorderLayout.CENTER);

                JPanel actions = actionsPanel();
                JButton apply = new JButton("Apply");
                apply.addActionListener(e -> {
                    PrivacyPosture selected = selectedPosture(buttons, defaultPosture);
                    controller.requestPosture(selected, folder,
                            "Initial Privacy Posture selected for folder");
                    dialog.dispose();
                });
                actions.add(apply);
                root.add(actions, BorderLayout.SOUTH);

                show(dialog, root);
            }
        });
    }

    @Override
    public void showDownshift(final Path folder, final PrivacyPosture from,
                              final PrivacyPosture to,
                              final PostureController controller) {
        if (GraphicsEnvironment.isHeadless() || folder == null || controller == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (downshiftPrompts.containsKey(folder)) {
                    bringToFront(downshiftPrompts.get(folder));
                    return;
                }
                final JDialog dialog = createDialog("Data Governance - Privacy Posture changed");
                downshiftPrompts.put(folder, dialog);
                dialog.addWindowListener(removeOnClose(downshiftPrompts, folder));

                JPanel root = rootPanel();
                root.add(title("Posture downshifted: "
                        + from.label() + " to " + to.label()), BorderLayout.NORTH);
                JLabel message = new JLabel("<html>Folder: " + escape(display(folder))
                        + "<br>ImageJAI is now using the stricter "
                        + escape(to.label()) + " Privacy Posture for this session.</html>");
                message.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
                root.add(message, BorderLayout.CENTER);

                JPanel actions = actionsPanel();
                JButton override = new JButton("Override (logged)");
                override.addActionListener(e -> {
                    String reason = JOptionPane.showInputDialog(dialog,
                            "Reason for overriding the folder Privacy Posture:",
                            "Logged Override",
                            JOptionPane.PLAIN_MESSAGE);
                    if (reason == null) {
                        return;
                    }
                    reason = reason.trim();
                    if (reason.isEmpty()) {
                        JOptionPane.showMessageDialog(dialog,
                                "A reason is required for a logged override.",
                                "Logged Override",
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    controller.requestPosture(from, folder, reason);
                    dialog.dispose();
                });
                JButton ok = new JButton("OK");
                ok.addActionListener(e -> dialog.dispose());
                actions.add(override);
                actions.add(ok);
                root.add(actions, BorderLayout.SOUTH);

                show(dialog, root);
            }
        });
    }

    @Override
    public void showWarning(final Path folder, final String message) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                final JDialog dialog = createDialog("Data Governance warning");
                JPanel root = rootPanel();
                root.add(title("Privacy Posture warning"), BorderLayout.NORTH);
                root.add(new JLabel("<html>" + escape(message) + "</html>"),
                        BorderLayout.CENTER);
                JPanel actions = actionsPanel();
                JButton ok = new JButton("OK");
                ok.addActionListener(e -> dialog.dispose());
                actions.add(ok);
                root.add(actions, BorderLayout.SOUTH);
                show(dialog, root);
            }
        });
    }

    private JDialog createDialog(String title) {
        Frame owner = ownerProvider == null ? null : ownerProvider.owner();
        JDialog dialog = new JDialog(owner, title, false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        return dialog;
    }

    private JPanel rootPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 130, 180)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        root.setBackground(new Color(246, 250, 255));
        return root;
    }

    private JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        return label;
    }

    private JPanel actionsPanel() {
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        return actions;
    }

    private void show(JDialog dialog, JPanel root) {
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setMinimumSize(dialog.getPreferredSize());
        Window owner = dialog.getOwner();
        if (owner != null && owner.isShowing()) {
            dialog.setLocationRelativeTo(owner);
        } else {
            dialog.setLocationByPlatform(true);
        }
        dialog.setVisible(true);
    }

    private WindowAdapter removeOnClose(final Map<Path, JDialog> dialogs,
                                        final Path folder) {
        return new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                dialogs.remove(folder);
            }
        };
    }

    private void bringToFront(JDialog dialog) {
        if (dialog != null) {
            dialog.toFront();
            dialog.requestFocus();
        }
    }

    private PrivacyPosture selectedPosture(Map<PrivacyPosture, JRadioButton> buttons,
                                           PrivacyPosture fallback) {
        for (Map.Entry<PrivacyPosture, JRadioButton> entry : buttons.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        return fallback == null ? PrivacyPosture.defaultPosture() : fallback;
    }

    private static String display(Path folder) {
        if (folder == null) {
            return "";
        }
        Path name = folder.getFileName();
        return name == null ? folder.toString() : name.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
