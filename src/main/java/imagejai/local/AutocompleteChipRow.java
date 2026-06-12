package imagejai.local;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Small row of Local Assistant phrase suggestions.
 */
public class AutocompleteChipRow extends JPanel {

    private static final Color CHIP_BG = new Color(50, 55, 64);
    private static final Color CHIP_FG = new Color(220, 225, 230);

    private final Consumer<String> onAccept;
    private List<RankedPhrase> candidates = Collections.emptyList();

    public AutocompleteChipRow(Consumer<String> onAccept) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        this.onAccept = onAccept;
        setOpaque(false);
        setBorder(new EmptyBorder(2, 0, 0, 0));
        setVisible(false);
    }

    public void setCandidates(List<RankedPhrase> nextCandidates) {
        List<RankedPhrase> next = nextCandidates == null
                ? Collections.<RankedPhrase>emptyList()
                : new ArrayList<RankedPhrase>(
                        nextCandidates.subList(0, Math.min(3, nextCandidates.size())));
        if (samePhrases(candidates, next)) {
            setVisible(!candidates.isEmpty());
            return;
        }

        candidates = Collections.unmodifiableList(next);
        removeAll();
        for (final RankedPhrase candidate : candidates) {
            JButton chip = new JButton(candidate.phrase());
            chip.setFocusPainted(false);
            chip.setBorderPainted(false);
            chip.setBackground(CHIP_BG);
            chip.setForeground(CHIP_FG);
            chip.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            chip.setToolTipText(candidate.intentId());
            chip.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    accept(candidate.phrase());
                }
            });
            add(chip);
        }
        setVisible(!candidates.isEmpty());
        revalidate();
        repaint();
    }

    public boolean acceptFirst() {
        if (candidates.isEmpty()) {
            return false;
        }
        accept(candidates.get(0).phrase());
        return true;
    }

    private void accept(String phrase) {
        if (onAccept != null) {
            onAccept.accept(phrase);
        }
    }

    private static boolean samePhrases(List<RankedPhrase> a, List<RankedPhrase> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).phrase().equals(b.get(i).phrase())) {
                return false;
            }
        }
        return true;
    }
}
