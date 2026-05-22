package imagejai.ui;

import imagejai.config.PrivacyPosture;
import imagejai.engine.PostureController;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Font;
import java.nio.file.Path;

/**
 * Compact Data Governance posture chip for launcher surfaces.
 */
public final class PostureBadge extends JLabel implements PostureController.Listener {
    private static final Color STANDARD_BG = new Color(0x6F7782);
    private static final Color STANDARD_FG = Color.WHITE;
    private static final Color PSEUDONYMISED_BG = new Color(0xF5A524);
    private static final Color PSEUDONYMISED_FG = new Color(0x1F2328);
    private static final Color ON_PREMISES_BG = new Color(0x2F9E44);
    private static final Color ON_PREMISES_FG = Color.WHITE;

    private final PostureController controller;

    public PostureBadge(PostureController controller) {
        this.controller = controller == null
                ? PostureController.getInstance()
                : controller;
        setOpaque(true);
        setBorder(new EmptyBorder(2, 8, 2, 8));
        setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        this.controller.addListener(this);
        apply(this.controller.current());
    }

    @Override
    public void postureChanged(PrivacyPosture from, PrivacyPosture to, Path folder) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                apply(to);
            }
        });
    }

    private void apply(PrivacyPosture posture) {
        PrivacyPosture p = posture == null
                ? PrivacyPosture.defaultPosture()
                : posture;
        setText(p.label());
        setToolTipText("<html><b>Privacy Posture:</b> " + p.label()
                + "<br>" + p.description()
                + "<br><br>Data Governance steward signal: outbound replies follow this folder's"
                + "<br>pseudonymisation scheme, and later stages attach the audit trail.</html>");
        switch (p) {
            case STANDARD:
                setBackground(STANDARD_BG);
                setForeground(STANDARD_FG);
                break;
            case PSEUDONYMISED:
                setBackground(PSEUDONYMISED_BG);
                setForeground(PSEUDONYMISED_FG);
                break;
            case ON_PREMISES:
                setBackground(ON_PREMISES_BG);
                setForeground(ON_PREMISES_FG);
                break;
            default:
                setBackground(STANDARD_BG);
                setForeground(STANDARD_FG);
                break;
        }
    }
}
