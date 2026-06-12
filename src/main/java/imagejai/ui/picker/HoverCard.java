package imagejai.ui.picker;

import imagejai.engine.picker.ModelEntry;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Single reused borderless JWindow that renders the hover detail card per
 * docs/multi_provider/05_ui_design.md §5. Phase D ships a minimal layout —
 * Phase H finalises the pricing rows and tier-summary line.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #scheduleShow(ModelEntry, Point)} — start the 400 ms timer; if
 *       the cursor stays for the full delay, content is rebuilt and the
 *       window appears.</li>
 *   <li>{@link #scheduleHide()} — start the 120 ms grace timer; cancellable
 *       by another scheduleShow.</li>
 *   <li>{@link #disposeNow()} — hide and forget any pending timers.</li>
 * </ul>
 */
public class HoverCard {

    private static final int FIXED_WIDTH = 440;
    private static final int SHOW_DELAY_MS = 400;
    private static final int HIDE_GRACE_MS = 120;

    private final JWindow window;
    private final JPanel content;
    private final JLabel titleLabel;
    private final JLabel tierLabel;
    private final JLabel capabilityLabel;
    private final JLabel detailLabel;
    private final JLabel idLabel;

    private Timer showTimer;
    private Timer hideTimer;

    public HoverCard() {
        window = new JWindow();
        window.setFocusableWindowState(false);
        content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(new Color(250, 250, 252));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(180, 180, 190)),
                new EmptyBorder(8, 10, 8, 10)));

        titleLabel = label(Font.BOLD, 12, new Color(20, 20, 25));
        tierLabel = label(Font.PLAIN, 11, new Color(60, 60, 70));
        capabilityLabel = label(Font.PLAIN, 11, new Color(80, 80, 90));
        detailLabel = label(Font.PLAIN, 11, new Color(60, 60, 70));
        idLabel = label(Font.ITALIC, 10, new Color(140, 140, 150));

        content.add(titleLabel);
        content.add(tierLabel);
        content.add(capabilityLabel);
        content.add(detailLabel);
        content.add(idLabel);

        window.setContentPane(content);
        window.setSize(new Dimension(FIXED_WIDTH, 130));
    }

    private JLabel label(int fontStyle, int fontSize, Color colour) {
        JLabel l = new JLabel(" ");
        l.setFont(new Font(Font.SANS_SERIF, fontStyle, fontSize));
        l.setForeground(colour);
        return l;
    }

    public void scheduleShow(final ModelEntry entry, final Point screenLocation) {
        cancelShow();
        cancelHide();
        showTimer = new Timer(SHOW_DELAY_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showNow(entry, screenLocation);
            }
        });
        showTimer.setRepeats(false);
        showTimer.start();
    }

    public void scheduleHide() {
        cancelShow();
        cancelHide();
        hideTimer = new Timer(HIDE_GRACE_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                window.setVisible(false);
            }
        });
        hideTimer.setRepeats(false);
        hideTimer.start();
    }

    public void disposeNow() {
        cancelShow();
        cancelHide();
        window.setVisible(false);
        window.dispose();
    }

    private void showNow(ModelEntry entry, Point screenLocation) {
        applyContent(entry);
        if (screenLocation != null) {
            window.setLocation(screenLocation);
        }
        window.setVisible(true);
    }

    private void applyContent(ModelEntry entry) {
        titleLabel.setText(entry.displayName() + "  ·  " + providerHint(entry.providerId()));
        // 05 §11.1: TierBadgeIcon reused in the hover-card header.
        tierLabel.setIcon(TierBadgeIcon.forTier(entry.tier()));
        tierLabel.setText(tierLabel(entry.tier()));
        capabilityLabel.setText("Tools: " + entry.toolCallReliability().name().toLowerCase()
                + "  │  Vision: " + (entry.visionCapable() ? "yes" : "no")
                + "  │  Context: " + formatContextWindow(entry.contextWindow()));
        String desc = entry.description() == null ? "" : entry.description().trim();
        detailLabel.setText(desc.isEmpty() ? " " : desc);
        idLabel.setText("id: " + entry.providerId() + "/" + entry.modelId());
    }

    private static String tierLabel(ModelEntry.Tier tier) {
        switch (tier) {
            case FREE: return "● Free  ·  No card needed.";
            case FREE_WITH_LIMITS: return "● Free with limits  ·  No card needed.";
            case PAID: return "● Pay-as-you-go  ·  Charges only with credit on file.";
            case REQUIRES_SUBSCRIPTION: return "● Subscription  ·  Active subscription required.";
            case UNCURATED:
            default: return "● Uncurated  ·  Pricing not yet verified.";
        }
    }

    private static String formatContextWindow(int tokens) {
        if (tokens <= 0) return "unknown";
        if (tokens >= 1_000_000) return (tokens / 1_000_000) + "M tokens";
        if (tokens >= 1_000) return (tokens / 1_000) + "K tokens";
        return tokens + " tokens";
    }

    private static String providerHint(String providerId) {
        return providerId == null ? "" : providerId;
    }

    private void cancelShow() {
        if (showTimer != null) {
            showTimer.stop();
            showTimer = null;
        }
    }

    private void cancelHide() {
        if (hideTimer != null) {
            hideTimer.stop();
            hideTimer = null;
        }
    }
}
