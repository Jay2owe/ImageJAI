package imagejai.ui.installer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

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
    private final JLabel statusLabel;
    private final JLabel tierLabel;
    private final JButton actionButton;
    private final JLabel detailLabel;

    public ProviderCard(String providerKey,
                        String displayName,
                        String description,
                        Status status,
                        CostTier tier) {
        super(new BorderLayout(6, 4));
        this.providerKey = providerKey;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 210)),
                new EmptyBorder(8, 10, 8, 10)));

        JPanel header = new JPanel(new GridBagLayout());
        header.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 8);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        statusLabel = new JLabel(symbolFor(status));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
        statusLabel.setForeground(colorFor(status));
        header.add(statusLabel, c);

        c.gridx = 1;
        JLabel name = new JLabel(displayName);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
        header.add(name, c);

        c.gridx = 2; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        tierLabel = new JLabel(tierHint(tier));
        tierLabel.setForeground(new Color(120, 120, 130));
        tierLabel.setHorizontalAlignment(JLabel.RIGHT);
        header.add(tierLabel, c);

        add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        detailLabel = new JLabel(htmlDescription(description, status));
        detailLabel.setFont(detailLabel.getFont().deriveFont(Font.PLAIN, 12f));
        body.add(detailLabel, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        actionButton = new JButton(actionLabelFor(status));
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

    /** Update status after a save or refresh. */
    public void updateStatus(Status status) {
        statusLabel.setText(symbolFor(status));
        statusLabel.setForeground(colorFor(status));
        actionButton.setText(actionLabelFor(status));
        // Detail line keeps the user oriented after a status change.
        Component[] components = getComponents();
        if (components.length >= 2) {
            detailLabel.setText(htmlDescription(detailLabel.getText(), status));
        }
    }

    public JButton actionButton() {
        return actionButton;
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

    private static Color colorFor(Status status) {
        switch (status) {
            case READY: return new Color(50, 140, 80);
            case UNAVAILABLE: return new Color(180, 50, 50);
            case NEEDS_SETUP:
            default: return new Color(180, 130, 30);
        }
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
