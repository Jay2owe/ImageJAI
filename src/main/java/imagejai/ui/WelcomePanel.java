package imagejai.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Welcome / empty-state card (Design B): shown whenever no agent session is
 * live. A single primary CTA over a recommended-agent line, plus a "choose a
 * different assistant" link and a posture footer. Pure view — it holds no agent
 * detection or launch logic; the parent wires the two callbacks and pushes the
 * recommendation / posture text in.
 */
public class WelcomePanel extends JPanel {

    private static final Color BG_MAIN = new Color(30, 30, 35);
    private static final Color ACCENT = new Color(0, 200, 255);
    private static final Color TEXT = new Color(205, 205, 215);
    private static final Color TEXT_MUTED = new Color(120, 120, 130);

    private final JButton startButton;
    private final JLabel recommendedLabel;
    private final JButton chooseDifferentButton;
    private final JLabel postureLabel;
    private final Runnable onStart;
    private final Runnable onChooseDifferent;

    public WelcomePanel(final Runnable onStart, final Runnable onChooseDifferent) {
        this.onStart = onStart;
        this.onChooseDifferent = onChooseDifferent;

        setBackground(BG_MAIN);
        setBorder(new EmptyBorder(16, 24, 16, 24));
        setLayout(new GridBagLayout());

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("✦  ImageJAI");
        title.setForeground(ACCENT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        content.add(title);

        content.add(Box.createVerticalStrut(12));

        JLabel tagline = new JLabel("<html><div style='text-align:center;width:300px'>"
                + "Do image analysis by describing it in plain English. "
                + "I pick the tools, write the macro, and show you the result."
                + "</div></html>");
        tagline.setForeground(TEXT);
        tagline.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        tagline.setAlignmentX(Component.CENTER_ALIGNMENT);
        tagline.setHorizontalAlignment(SwingConstants.CENTER);
        content.add(tagline);

        content.add(Box.createVerticalStrut(24));

        startButton = new JButton("▶   Start analysing");
        stylePrimary(startButton);
        startButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (WelcomePanel.this.onStart != null) {
                    WelcomePanel.this.onStart.run();
                }
            }
        });
        content.add(startButton);

        content.add(Box.createVerticalStrut(10));

        recommendedLabel = new JLabel(" ");
        recommendedLabel.setForeground(TEXT_MUTED);
        recommendedLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        recommendedLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        recommendedLabel.setHorizontalAlignment(SwingConstants.CENTER);
        content.add(recommendedLabel);

        content.add(Box.createVerticalStrut(4));

        chooseDifferentButton = new JButton("Choose a different assistant ▾");
        styleLink(chooseDifferentButton);
        chooseDifferentButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (WelcomePanel.this.onChooseDifferent != null) {
                    WelcomePanel.this.onChooseDifferent.run();
                }
            }
        });
        content.add(chooseDifferentButton);

        content.add(Box.createVerticalStrut(28));

        postureLabel = new JLabel(" ");
        postureLabel.setForeground(TEXT_MUTED);
        postureLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        postureLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        postureLabel.setHorizontalAlignment(SwingConstants.CENTER);
        content.add(postureLabel);

        add(content, new GridBagConstraints());
    }

    /** Set the recommended agent name shown under the CTA and the CTA's hover reason. */
    public void setRecommendation(String displayName, String reason) {
        if (displayName == null || displayName.trim().isEmpty()) {
            recommendedLabel.setText(" ");
            startButton.setToolTipText(null);
        } else {
            recommendedLabel.setText("Recommended: " + displayName);
            String tip = "Recommended: " + displayName;
            if (reason != null && !reason.trim().isEmpty()) {
                tip = tip + " — " + reason;
            }
            startButton.setToolTipText(tip);
        }
        revalidate();
        repaint();
    }

    /** Override the primary CTA caption (e.g. "Start with the built-in assistant"). */
    public void setStartCaption(String caption) {
        if (caption != null && !caption.trim().isEmpty()) {
            startButton.setText(caption);
            revalidate();
            repaint();
        }
    }

    /** Set the posture footer text. */
    public void setPostureText(String label) {
        postureLabel.setText(label == null || label.trim().isEmpty() ? " " : label);
    }

    private void stylePrimary(JButton b) {
        b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        b.setForeground(new Color(18, 18, 22));
        b.setBackground(ACCENT);
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(11, 30, 11, 30));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.setMaximumSize(new Dimension(300, 46));
    }

    private void styleLink(JButton b) {
        b.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        b.setForeground(ACCENT);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.setMaximumSize(new Dimension(300, 24));
    }
}
