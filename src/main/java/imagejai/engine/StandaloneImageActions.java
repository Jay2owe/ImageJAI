package imagejai.engine;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Overlay;
import ij.gui.Roi;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Fiji image actions that do not require the embedded chat panel to be open. */
final class StandaloneImageActions {
    private static final int MAX_IMAGE_TITLE_CHARS = 1024;
    private static final Color HIGHLIGHT_COLOR = new Color(0, 200, 255);

    private StandaloneImageActions() {}

    static void highlightRoi(String imageTitle, int[] roiBounds) {
        if (imageTitle == null || roiBounds == null || roiBounds.length != 4) return;
        final String title = boundedTitle(imageTitle);
        final int[] bounds = roiBounds.clone();
        SwingUtilities.invokeLater(() -> {
            ImagePlus imp = WindowManager.getImage(title);
            if (imp == null) {
                IJ.log("[ImageJAI-GUI] highlightRoi: no image titled '" + title + "'");
                return;
            }
            installHighlight(imp, bounds);
        });
    }

    /** Install the same cyan overlay and six 200 ms toggles as ChatView. */
    static void installHighlight(ImagePlus imp, int[] bounds) {
        final Roi roi = new Roi(bounds[0], bounds[1], bounds[2], bounds[3]);
        roi.setStrokeColor(HIGHLIGHT_COLOR);
        roi.setStrokeWidth(2);
        Overlay overlay = imp.getOverlay() != null ? imp.getOverlay() : new Overlay();
        overlay.add(roi);
        imp.setOverlay(overlay);
        imp.draw();

        final int[] ticks = {0};
        final Timer timer = new Timer(200, null);
        timer.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent event) {
                ticks[0]++;
                roi.setStrokeColor(ticks[0] % 2 == 0
                        ? HIGHLIGHT_COLOR : new Color(0, 200, 255, 0));
                imp.draw();
                if (ticks[0] >= 6) {
                    timer.stop();
                    roi.setStrokeColor(HIGHLIGHT_COLOR);
                    imp.draw();
                }
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    static void focusImage(String imageTitle) {
        if (imageTitle == null) return;
        final String title = boundedTitle(imageTitle);
        SwingUtilities.invokeLater(() -> {
            ImagePlus imp = WindowManager.getImage(title);
            if (imp == null) {
                IJ.log("[ImageJAI-GUI] focusImage: no image titled '" + title + "'");
                return;
            }
            ImageWindow window = imp.getWindow();
            if (window != null) {
                window.toFront();
                window.requestFocus();
            }
        });
    }

    private static String boundedTitle(String title) {
        if (title.length() <= MAX_IMAGE_TITLE_CHARS) return title;
        int end = MAX_IMAGE_TITLE_CHARS;
        if (Character.isHighSurrogate(title.charAt(end - 1))) end--;
        return title.substring(0, end);
    }
}
