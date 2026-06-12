package imagejai.ui.installer;

import imagejai.config.Settings;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * Tier-safety section embedded at the top of {@link MultiProviderPanel}.
 * Implements the layout in docs/multi_provider/06_tier_safety.md §6.2.
 *
 * <p>Three controls:
 * <ul>
 *   <li>Pause-the-agent checkbox + ceiling dropdown.</li>
 *   <li>Confirm-paid checkbox.</li>
 *   <li>Warn-uncurated checkbox.</li>
 * </ul>
 *
 * <p>06 §6.5 — telemetry-free; the ceiling is enforced in-process by the
 * Python tool loop (agent/ollama_agent/budget_ceiling.py). Nothing here is
 * uploaded.
 */
public class TierSafetyPanel extends JPanel {

    /** Preset values per 06 §6.2 — last entry is "Custom…" handled separately. */
    static final Double[] PRESET_CEILINGS = new Double[] {
            0.25, 0.50, 1.00, 2.00, 5.00, 10.00
    };

    private final Settings settings;
    private final JCheckBox enableCeiling;
    private final JComboBox<String> ceilingDropdown;
    private final JCheckBox confirmPaid;
    private final JCheckBox warnUncurated;

    public TierSafetyPanel(Settings settings) {
        super();
        this.settings = settings;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Tier safety",
                TitledBorder.LEFT, TitledBorder.TOP,
                new Font(Font.SANS_SERIF, Font.BOLD, 12)));

        // Row 1 — budget ceiling.
        JPanel ceilingRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ceilingRow.setOpaque(false);
        enableCeiling = new JCheckBox("Pause the agent if a session exceeds");
        enableCeiling.setOpaque(false);
        enableCeiling.setSelected(settings != null && settings.budgetCeilingEnabled);
        ceilingRow.add(enableCeiling);
        ceilingDropdown = new JComboBox<String>(presetLabels());
        if (settings != null) {
            ceilingDropdown.setSelectedItem(formatCeiling(settings.budgetCeilingUsd));
        }
        ceilingRow.add(ceilingDropdown);
        add(ceilingRow);

        JLabel hint = new JLabel(
                "<html><div style='width:520px; color:#666666; font-size:10px;'>"
                + "Estimated from LiteLLM token accounting — accurate to within ~10% if "
                + "the model's pricing in models.yaml is current. Free models are not counted."
                + "</div></html>");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(0, 22, 6, 0));
        add(hint);

        // Row 2 — confirm paid.
        confirmPaid = new JCheckBox("Confirm before first use of a paid model in each session");
        confirmPaid.setOpaque(false);
        confirmPaid.setSelected(settings == null || settings.confirmPaidModels);
        confirmPaid.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(confirmPaid);

        // Row 3 — warn uncurated.
        warnUncurated = new JCheckBox("Warn before first use of an unverified model");
        warnUncurated.setOpaque(false);
        warnUncurated.setSelected(settings == null || settings.warnUncuratedModels);
        warnUncurated.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(warnUncurated);

        // Wire persistence — every change writes settings.json immediately so
        // closing the dialog without OK does not silently revert.
        enableCeiling.addActionListener(e -> persist());
        ceilingDropdown.addActionListener(e -> persist());
        confirmPaid.addActionListener(e -> persist());
        warnUncurated.addActionListener(e -> persist());
    }

    private void persist() {
        if (settings == null) {
            return;
        }
        settings.budgetCeilingEnabled = enableCeiling.isSelected();
        Object selected = ceilingDropdown.getSelectedItem();
        if (selected != null) {
            settings.budgetCeilingUsd = parseCeiling(selected.toString());
        }
        settings.confirmPaidModels = confirmPaid.isSelected();
        settings.warnUncuratedModels = warnUncurated.isSelected();
        settings.save();
    }

    static String[] presetLabels() {
        String[] labels = new String[PRESET_CEILINGS.length];
        for (int i = 0; i < PRESET_CEILINGS.length; i++) {
            labels[i] = formatCeiling(PRESET_CEILINGS[i]);
        }
        return labels;
    }

    static String formatCeiling(double value) {
        if (value <= 0) {
            return "$1.00";
        }
        return String.format("$%.2f", value);
    }

    static double parseCeiling(String label) {
        if (label == null) return 1.00;
        String stripped = label.trim();
        if (stripped.startsWith("$")) {
            stripped = stripped.substring(1);
        }
        try {
            return Double.parseDouble(stripped);
        } catch (NumberFormatException ex) {
            return 1.00;
        }
    }

    /** Test-only access to the embedded controls. */
    JCheckBox enableCeilingForTest() { return enableCeiling; }
    JComboBox<String> ceilingDropdownForTest() { return ceilingDropdown; }
    JCheckBox confirmPaidForTest() { return confirmPaid; }
    JCheckBox warnUncuratedForTest() { return warnUncurated; }
}
