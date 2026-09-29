package imagejai.ui.installer;

import imagejai.ui.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Cursor;

/**
 * Single provider card in {@link MultiProviderPanel}. Renders the provider's
 * name + 1-line description, a status icon (✓/⚠/✗), a tier hint, and one of
 * three action buttons depending on the install shape.
 */
public class ProviderCard extends JPanel {

    /** Mirrors {@code ProviderEntry.Status} but kept here so the panel doesn't
     *  pull engine-side types into Swing-only code. */
    public enum Status {
        READY, NEEDS_SETUP, UNAVAILABLE
    }

    /** Coarse classification of the provider's billing model — drives the tier hint label. */
    public enum CostTier {
        FREE, FREE_WITH_LIMITS, PAID, REQUIRES_SUBSCRIPTION
    }

    private final String providerKey;
    private final String displayName;
    private final String description;
    private final JButton statusButton;
    private final JLabel tierLabel;
    private final JButton actionButton;
    private final JLabel detailLabel;
    private Status status;
    private StatusClickListener statusClickListener;

    public interface StatusClickListener {
        void onStatusClicked(ProviderCard card, Status status);
    }

    public ProviderCard(String providerKey,
                        String displayName,
                        String description,
                        Status status,
                        CostTier tier) {
        super(new BorderLayout(6, 4));
        this.providerKey = providerKey;
        this.displayName = displayName == null ? providerKey : displayName;
        this.description = description == null ? "" : description;
        this.status = status == null ? Status.NEEDS_SETUP : status;
        setOpaque(true);
        setBackground(ThemeColors.panelBackground());
        setForeground(ThemeColors.textOn(getBackground()));
        getAccessibleContext().setAccessibleName(this.displayName + " provider settings");
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.mix(
                        getBackground(), getForeground(), 0.30)),
                new EmptyBorder(8, 10, 8, 10)));

        JPanel header = new JPanel(new GridBagLayout());
        header.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 8);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        statusButton = new JButton(symbolFor(this.status));
        statusButton.setFont(statusButton.getFont().deriveFont(Font.BOLD, 14f));
        statusButton.setForeground(statusColorFor(this.status));
        statusButton.setContentAreaFilled(false);
        statusButton.setFocusPainted(true);
        statusButton.setFocusable(true);
        statusButton.setMargin(new Insets(1, 5, 1, 5));
        statusButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        statusButton.addActionListener(e -> {
            if (statusClickListener != null) {
                statusClickListener.onStatusClicked(ProviderCard.this, ProviderCard.this.status);
            }
        });
        updateStatusAccessibility();
        header.add(statusButton, c);

        c.gridx = 1;
        JLabel name = new JLabel(this.displayName);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
        name.setForeground(getForeground());
        header.add(name, c);

        c.gridx = 2; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        tierLabel = new JLabel(tierHint(tier));
        tierLabel.setForeground(ThemeColors.ensureContrast(getBackground(),
                new Color(105, 105, 115), 4.5));
        tierLabel.setHorizontalAlignment(JLabel.RIGHT);
        header.add(tierLabel, c);

        add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        detailLabel = new JLabel(htmlDescription(this.description, this.status));
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.PLAIN, 12f));
        detailLabel.setForeground(getForeground());
        body.add(detailLabel, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        actionButton = new JButton(actionLabelFor(this.status));
        actionButton.setFocusPainted(true);
        actionButton.setFocusable(true);
        updateActionAccessibility();
        actions.add(actionButton);
        add(actions, BorderLayout.SOUTH);

        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height + 16));
    }

    public String providerKey() {
        return providerKey;
    }

    /** Replace the click handler — caller wires this to wizard launch. */
    public void setActionListener(java.awt.event.ActionListener listener) {
        for (java.awt.event.ActionListener existing : actionButton.getActionListeners()) {
            actionButton.removeActionListener(existing);
        }
        if (listener != null) {
            actionButton.addActionListener(listener);
        }
    }

    public void setStatusClickListener(StatusClickListener listener) {
        this.statusClickListener = listener;
    }

    /** Update status after a save or refresh. */
    public void updateStatus(Status status) {
        this.status = status == null ? Status.NEEDS_SETUP : status;
        statusButton.setText(symbolFor(this.status));
        statusButton.setForeground(statusColorFor(this.status));
        actionButton.setText(actionLabelFor(this.status));
        detailLabel.setText(htmlDescription(description, this.status));
        updateStatusAccessibility();
        updateActionAccessibility();
    }

    public JButton actionButton() {
        return actionButton;
    }

    public JButton statusButton() {
        return statusButton;
    }

    private static String htmlDescription(String description, Status status) {
        String detail = description == null ? "" : description.trim();
        // Strip prior <html> wrappers so updateStatus calls don't double-wrap.
        if (detail.startsWith("<html>")) {
            detail = detail.substring(6);
            int end = detail.lastIndexOf("</html>");
            if (end >= 0) {
                detail = detail.substring(0, end);
            }
        }
        return "<html>" + detail + "</html>";
    }

    private static String symbolFor(Status status) {
        switch (status) {
            case READY: return "✓";
            case UNAVAILABLE: return "✗";
            case NEEDS_SETUP:
            default: return "⚠";
        }
    }

    private Color statusColorFor(Status status) {
        Color preferred;
        switch (status) {
            case READY: preferred = new Color(35, 125, 65); break;
            case UNAVAILABLE: preferred = new Color(180, 50, 50); break;
            case NEEDS_SETUP:
            default: preferred = new Color(150, 105, 15); break;
        }
        return ThemeColors.semanticText(getBackground(), preferred);
    }

    private void updateStatusAccessibility() {
        statusButton.getAccessibleContext().setAccessibleName(
                displayName + " status: " + statusText(status));
        statusButton.getAccessibleContext().setAccessibleDescription(
                "Open status details for " + displayName + ".");
        statusButton.setToolTipText("Status: " + statusText(status));
    }

    private void updateActionAccessibility() {
        actionButton.getAccessibleContext().setAccessibleName(
                actionLabelFor(status).replace("…", "") + " for " + displayName);
        actionButton.getAccessibleContext().setAccessibleDescription(
                "Configure the " + displayName + " provider.");
    }

    private static String statusText(Status status) {
        if (status == Status.READY) return "ready";
        if (status == Status.UNAVAILABLE) return "unavailable";
        return "needs setup";
    }

    private static String actionLabelFor(Status status) {
        switch (status) {
            case READY: return "Edit credentials…";
            case UNAVAILABLE: return "Retry / Reconfigure…";
            case NEEDS_SETUP:
            default: return "Set up…";
        }
    }

    private static String tierHint(CostTier tier) {
        if (tier == null) {
            return "";
        }
        switch (tier) {
            case FREE: return "Free";
            case FREE_WITH_LIMITS: return "Free with limits";
            case PAID: return "Pay-as-you-go";
            case REQUIRES_SUBSCRIPTION: return "Subscription";
            default: return "";
        }
    }
}
