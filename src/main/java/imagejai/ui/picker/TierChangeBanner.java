package imagejai.ui.picker;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier-change notification strip that mounts at the top of {@link
 * imagejai.ui.AiRootPanel} per docs/multi_provider/06_tier_safety.md
 * §7.4. Stacks one row per surviving notification.
 *
 * <p>Empty until {@link MainNotificationCheck} produces notifications at Fiji
 * startup. Each row carries a "Dismiss" button that removes the notification
 * from the visible stack and reports back via {@link DismissListener} so the
 * caller can persist the decision (per-machine, per-change).
 */
public class TierChangeBanner extends JPanel {

    public interface DismissListener {
        void onDismissed(MainNotificationCheck.Notification notification);
    }

    private final List<MainNotificationCheck.Notification> notifications = new ArrayList<>();
    private DismissListener dismissListener;

    public TierChangeBanner() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
    }

    public void setDismissListener(DismissListener listener) {
        this.dismissListener = listener;
    }

    public void setNotifications(List<MainNotificationCheck.Notification> ns) {
        this.notifications.clear();
        if (ns != null) {
            this.notifications.addAll(ns);
        }
        rebuild();
    }

    public List<MainNotificationCheck.Notification> notifications() {
        return new ArrayList<>(notifications);
    }

    public boolean isEmpty() {
        return notifications.isEmpty();
    }

    private void rebuild() {
        removeAll();
        // Per 06 §7.5 cap consolidated banners at 3 favourites at once. If more
        // are queued we collapse the rest into one summary row.
        List<MainNotificationCheck.Notification> visible = new ArrayList<>();
        if (notifications.size() <= 3) {
            visible.addAll(notifications);
        } else {
            visible.addAll(notifications.subList(0, 2));
            int remaining = notifications.size() - 2;
            visible.add(MainNotificationCheck.Notification.consolidated(remaining));
        }
        for (MainNotificationCheck.Notification n : visible) {
            add(buildRow(n));
            add(Box.createVerticalStrut(2));
        }
        revalidate();
        repaint();
    }

    private Component buildRow(final MainNotificationCheck.Notification n) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(true);
        row.setBackground(backgroundFor(n.severity));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, accentFor(n.severity)),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        JLabel text = new JLabel("<html>" + escape(n.title) + "<br>"
                + "<font color='#444444'>" + escape(n.body) + "</font></html>");
        text.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        row.add(text, BorderLayout.CENTER);

        JButton dismiss = new JButton("Dismiss");
        dismiss.setFocusable(false);
        dismiss.setMargin(new java.awt.Insets(2, 8, 2, 8));
        dismiss.addActionListener(e -> {
            notifications.remove(n);
            if (dismissListener != null) {
                dismissListener.onDismissed(n);
            }
            rebuild();
        });
        row.add(dismiss, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        return row;
    }

    private Color backgroundFor(MainNotificationCheck.Severity severity) {
        if (severity == MainNotificationCheck.Severity.HIGH) {
            return new Color(255, 240, 232);
        }
        if (severity == MainNotificationCheck.Severity.MEDIUM) {
            return new Color(255, 250, 220);
        }
        return new Color(232, 240, 255);
    }

    private Color accentFor(MainNotificationCheck.Severity severity) {
        if (severity == MainNotificationCheck.Severity.HIGH) {
            return new Color(200, 70, 60);
        }
        if (severity == MainNotificationCheck.Severity.MEDIUM) {
            return new Color(200, 150, 30);
        }
        return new Color(70, 130, 220);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
