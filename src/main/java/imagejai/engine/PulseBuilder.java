package imagejai.engine;

import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;

/**
 * Step 05 (docs/tcp_upgrade/05_state_delta_and_pulse.md): produce a compact
 * one-line "pulse" string — a ~20-40 token readout of session state that
 * mutating handlers attach to replies when {@code caps.pulse} is negotiated
 * on. The pulse removes the need for an agent to burn turns on
 * {@code get_state} just to reorient ("what image is active now? how many
 * ROIs? how many results?").
 * <p>
 * Shape:
 * <pre>N img (TITLE BITDEPTH WxH[xSTACK]) &middot; ROI: N &middot; results: N rows</pre>
 * Missing parts collapse — no image open renders as "0 imgs".
 */
public final class PulseBuilder {

    private static final String SEP = " · "; // " · "

    private PulseBuilder() { }

    /**
     * Build the pulse string from current {@link WindowManager},
     * {@link ResultsTable}, and {@link RoiManager} state. Best-effort: any
     * failure inside an ImageJ lookup returns a stub so callers never see
     * an exception propagate out of the state-collection step.
     */
    public static String build() {
        try {
            StringBuilder sb = new StringBuilder();
            int[] ids = WindowManager.getIDList();
            int n = ids == null ? 0 : ids.length;
            sb.append(n).append(n == 1 ? " img" : " imgs");
            ImagePlus active = WindowManager.getCurrentImage();
            if (active != null) {
                sb.append(" (").append(active.getTitle()).append(" ")
                  .append(active.getBitDepth()).append("-bit ")
                  .append(active.getWidth()).append("x").append(active.getHeight());
                if (active.getStackSize() > 1) {
                    sb.append("x").append(active.getStackSize());
                }
                sb.append(")");
            }
            RoiManager rm = RoiManager.getInstance();
            int rois = rm != null ? rm.getCount() : 0;
            sb.append(SEP).append("ROI: ").append(rois);
            ResultsTable rt = ResultsTable.getResultsTable();
            int rows = rt != null ? rt.getCounter() : 0;
            sb.append(SEP).append("results: ").append(rows).append(" rows");
            return sb.toString();
        } catch (Throwable t) {
            return "pulse unavailable";
        }
    }
}
