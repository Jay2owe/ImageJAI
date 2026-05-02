package imagejai.ui.picker;

import imagejai.engine.picker.ModelEntry;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Placeholder first-use-of-paid-model dialog. Phase H supplies the final copy
 * (per docs/multi_provider/06_tier_safety.md §3) and the per-session
 * "don't ask again" set; Phase D ships the modal scaffolding so the cascading
 * dropdown can already route paid clicks through it.
 */
public class FirstUseDialog extends JDialog {

    public enum Result {
        CONTINUE,
        PICK_FREE,
        CANCEL
    }

    private Result result = Result.CANCEL;

    public FirstUseDialog(Frame owner, ModelEntry entry) {
        super(owner, "First use of a paid model", true);
        setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        setLayout(new BorderLayout(8, 8));

        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.setBorder(new EmptyBorder(16, 18, 12, 18));
        body.setBackground(Color.WHITE);

        JLabel headline = new JLabel("First use of " + entry.displayName()
                + " this session.");
        headline.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        body.add(headline, BorderLayout.NORTH);

        JLabel detail = new JLabel("<html>This is a paid model on "
                + entry.providerId()
                + ". Charges happen only if you have credit on file with the provider."
                + "<br>Phase H of the multi-provider rollout will surface concrete pricing here.</html>");
        detail.setVerticalAlignment(SwingConstants.TOP);
        body.add(detail, BorderLayout.CENTER);

        add(body, BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        JButton pickFree = new JButton("Use a free model instead");
        pickFree.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                result = Result.PICK_FREE;
                setVisible(false);
            }
        });
        JButton cont = new JButton("Continue");
        cont.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                result = Result.CONTINUE;
                setVisible(false);
            }
        });
        buttons.add(pickFree);
        buttons.add(cont);
        return buttons;
    }

    public Result showAndAwait() {
        setVisible(true);
        return result;
    }
}
