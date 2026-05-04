package imagejai.ui.picker;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Modal shown when {@link imagejai.engine.budget.BudgetCeilingTracker}
 * fires {@code onCeilingBreached} per
 * docs/multi_provider/06_tier_safety.md §6.3.
 *
 * <p>Three exits, mirroring {@link FirstUseDialog}'s scaffolding:
 * <ul>
 *   <li>{@link Result#RESUME} — caller re-arms the tracker with the new
 *       ceiling pulled from {@link #newCeilingUsd()}. Default = current × 2.</li>
 *   <li>{@link Result#SWITCH_FREE} — caller drops to a free model
 *       (06 §6.3 step 5 mirrors the Python {@code /switch} command).</li>
 *   <li>{@link Result#CLOSE} — close the terminal / end the session.</li>
 * </ul>
 *
 * <p>DOCUMENT_MODAL parented to the supplied {@link Frame} so Fiji image
 * windows stay interactive while the dialog is up — same pattern as
 * {@link FirstUseDialog}. The fallback estimate caveat ("may be ±20%") from
 * 06 §6.3 is rendered when the most recent call's cost was a fallback rather
 * than a header value, so the user knows the figure may drift on providers
 * that don't surface the LiteLLM cost header.
 */
public class BudgetCeilingDialog extends JDialog {

    public enum Result {
        RESUME,
        SWITCH_FREE,
        CLOSE
    }

    private final double sessionCostUsd;
    private final double currentCeilingUsd;
    private final boolean fallbackEstimate;
    private JSpinner ceilingSpinner;
    private Result result = Result.CLOSE;
    private double newCeilingUsd;

    public BudgetCeilingDialog(Frame owner, double sessionCostUsd, double currentCeilingUsd) {
        this(owner, sessionCostUsd, currentCeilingUsd, false);
    }

    public BudgetCeilingDialog(Frame owner,
                               double sessionCostUsd,
                               double currentCeilingUsd,
                               boolean fallbackEstimate) {
        super(owner, "Budget ceiling reached", true);
        this.sessionCostUsd = sessionCostUsd;
        this.currentCeilingUsd = currentCeilingUsd > 0 ? currentCeilingUsd : 1.0;
        this.fallbackEstimate = fallbackEstimate;
        this.newCeilingUsd = this.currentCeilingUsd * 2.0;
        setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Match FirstUseDialog: [×] never silently resumes the loop.
                result = Result.CLOSE;
                setVisible(false);
            }
        });
        buildUi();
        pack();
        Dimension preferred = getPreferredSize();
        setSize(Math.max(preferred.width, 560), Math.max(preferred.height, 280));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(16, 18, 12, 18));
        content.setBackground(Color.WHITE);

        JLabel headline = new JLabel(String.format(
                "Budget ceiling reached: $%.2f of $%.2f.",
                sessionCostUsd, currentCeilingUsd));
        headline.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        content.add(headline, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

        JLabel detail = new JLabel(bodyHtml());
        detail.setAlignmentX(LEFT_ALIGNMENT);
        body.add(detail);
        body.add(Box.createVerticalStrut(10));

        JPanel ceilingRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ceilingRow.setOpaque(false);
        ceilingRow.setAlignmentX(LEFT_ALIGNMENT);
        ceilingRow.add(new JLabel("New ceiling on resume:  $"));
        ceilingSpinner = new JSpinner(new SpinnerNumberModel(
                this.newCeilingUsd, 0.01, 100.00, 0.25));
        ceilingSpinner.setPreferredSize(new Dimension(80, 22));
        ceilingRow.add(ceilingSpinner);
        body.add(ceilingRow);

        content.add(body, BorderLayout.CENTER);
        content.add(buildButtons(), BorderLayout.SOUTH);
        setContentPane(content);
    }

    private String bodyHtml() {
        StringBuilder sb = new StringBuilder("<html><div style='width:460px'>");
        sb.append("<p>The agent has paused. Your last action completed; ")
          .append("nothing was charged that you didn't already authorise.</p>");
        if (fallbackEstimate) {
            sb.append("<p><i>Note: this estimate is based on local token counts ")
              .append("and the pricing in <code>models.yaml</code>; it may be ")
              .append("±20% off if upstream pricing has shifted.</i></p>");
        }
        sb.append("<p><b>Options</b><br>")
          .append("&middot; <b>Resume</b> — continue with a higher ceiling (")
          .append("default = current ceiling × 2)<br>")
          .append("&middot; <b>Switch to a free model</b> — drop to ")
          .append("Gemini Flash, Groq, or Ollama<br>")
          .append("&middot; <b>Close</b> — close this terminal to end the session</p>")
          .append("</div></html>");
        return sb.toString();
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        buttons.setOpaque(false);

        JButton switchFree = new JButton("Switch to a free model");
        switchFree.addActionListener(e -> {
            result = Result.SWITCH_FREE;
            setVisible(false);
        });
        buttons.add(switchFree);

        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            result = Result.CLOSE;
            setVisible(false);
        });
        buttons.add(close);

        JButton resume = new JButton("Resume");
        resume.addActionListener(e -> {
            result = Result.RESUME;
            Object value = ceilingSpinner.getValue();
            if (value instanceof Number) {
                double asked = ((Number) value).doubleValue();
                // 06 §6.3 step 5: ceiling is raised, never reset. Reject
                // values at-or-below the current ceiling so a confused user
                // doesn't lock the loop into immediate re-pause.
                if (asked > currentCeilingUsd) {
                    newCeilingUsd = asked;
                } else {
                    newCeilingUsd = currentCeilingUsd * 2.0;
                }
            }
            setVisible(false);
        });
        buttons.add(resume);

        getRootPane().setDefaultButton(resume);
        return buttons;
    }

    public Result showAndAwait() {
        setVisible(true);
        return result;
    }

    /** Ceiling the user picked on the Resume path. Undefined if {@link #showAndAwait()} returned anything else. */
    public double newCeilingUsd() {
        return newCeilingUsd;
    }

    public double sessionCostUsd() {
        return sessionCostUsd;
    }

    public double currentCeilingUsd() {
        return currentCeilingUsd;
    }

    public boolean fallbackEstimate() {
        return fallbackEstimate;
    }

    /** Test-only seam — set the result directly without showing the dialog. */
    void setResultForTest(Result result) {
        this.result = result == null ? Result.CLOSE : result;
    }

    /** Test-only seam — set the post-resume ceiling without showing the dialog. */
    void setNewCeilingForTest(double ceiling) {
        this.newCeilingUsd = ceiling;
    }
}
