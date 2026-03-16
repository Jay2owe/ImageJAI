package imagejai.engine;

import ij.ImagePlus;
import ij.gui.Arrow;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.TextRoi;
import ij.process.ImageProcessor;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Font;

/**
 * Utility for adding non-destructive overlay annotations to images.
 * All AI-added annotations are prefixed with "AI:" for selective removal.
 */
public class AnnotationHelper {

    private static final String AI_PREFIX = "AI:";
    private static final Font DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    private static final Font WARNING_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);

    /**
     * Add a text annotation at the specified position.
     */
    public static void addTextAnnotation(ImagePlus imp, String text, int x, int y,
                                          Color color, int fontSize) {
        if (imp == null || text == null) {
            return;
        }
        final ImagePlus target = imp;
        final String t = text;
        final int px = x;
        final int py = y;
        final Color c = color;
        final int fs = fontSize;

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fs);
                TextRoi textRoi = new TextRoi(px, py, t, font);
                textRoi.setStrokeColor(c);
                textRoi.setName(AI_PREFIX + "text:" + t.substring(0, Math.min(t.length(), 20)));
                textRoi.setNonScalable(true);

                Overlay overlay = target.getOverlay();
                if (overlay == null) {
                    overlay = new Overlay();
                    target.setOverlay(overlay);
                }
                overlay.add(textRoi);
                target.updateAndDraw();
            }
        });
    }

    /**
     * Add a warning overlay (yellow text at top of image).
     */
    public static void addWarningOverlay(ImagePlus imp, String warning) {
        if (imp == null || warning == null) {
            return;
        }
        final ImagePlus target = imp;
        final String w = warning;

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                // Position at top-left with some margin
                TextRoi textRoi = new TextRoi(10, 10, w, WARNING_FONT);
                textRoi.setStrokeColor(Color.YELLOW);
                textRoi.setName(AI_PREFIX + "warning:" + w.substring(0, Math.min(w.length(), 20)));
                textRoi.setNonScalable(true);

                // Add semi-transparent background by creating a filled rectangle ROI behind the text
                // Estimate text bounds
                int textWidth = w.length() * 8;
                int textHeight = 20;
                Roi bgRoi = new Roi(5, 5, textWidth + 10, textHeight + 10);
                bgRoi.setFillColor(new Color(0, 0, 0, 128));
                bgRoi.setName(AI_PREFIX + "warning-bg");

                Overlay overlay = target.getOverlay();
                if (overlay == null) {
                    overlay = new Overlay();
                    target.setOverlay(overlay);
                }
                overlay.add(bgRoi);
                overlay.add(textRoi);
                target.updateAndDraw();
            }
        });
    }

    /**
     * Add an arrow annotation pointing from (fromX, fromY) to (toX, toY).
     */
    public static void addArrowAnnotation(ImagePlus imp, int fromX, int fromY,
                                           int toX, int toY, Color color) {
        if (imp == null) {
            return;
        }
        final ImagePlus target = imp;
        final int fx = fromX;
        final int fy = fromY;
        final int tx = toX;
        final int ty = toY;
        final Color c = color;

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Arrow arrow = new Arrow(fx, fy, tx, ty);
                arrow.setStrokeColor(c);
                arrow.setStrokeWidth(2);
                arrow.setHeadSize(10);
                arrow.setName(AI_PREFIX + "arrow");

                Overlay overlay = target.getOverlay();
                if (overlay == null) {
                    overlay = new Overlay();
                    target.setOverlay(overlay);
                }
                overlay.add(arrow);
                target.updateAndDraw();
            }
        });
    }

    /**
     * Add a scale bar overlay to the image.
     *
     * @param imp             the image to annotate
     * @param barWidthMicrons desired scale bar width in calibrated units
     */
    public static void addScaleBar(ImagePlus imp, double barWidthMicrons) {
        if (imp == null || barWidthMicrons <= 0) {
            return;
        }
        final ImagePlus target = imp;
        final double barWidth = barWidthMicrons;

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                double pixelWidth = target.getCalibration().pixelWidth;
                if (pixelWidth <= 0) {
                    pixelWidth = 1.0;
                }
                int barPixels = (int) Math.round(barWidth / pixelWidth);
                if (barPixels < 1) {
                    barPixels = 1;
                }

                // Position at bottom-right with margin
                int imgWidth = target.getWidth();
                int imgHeight = target.getHeight();
                int margin = 20;
                int barHeight = 6;
                int x = imgWidth - barPixels - margin;
                int y = imgHeight - margin - barHeight;

                // Scale bar rectangle
                Roi barRoi = new Roi(x, y, barPixels, barHeight);
                barRoi.setFillColor(Color.WHITE);
                barRoi.setStrokeColor(Color.WHITE);
                barRoi.setName(AI_PREFIX + "scalebar");

                // Label
                String unit = target.getCalibration().getUnit();
                if (unit == null || unit.isEmpty()) {
                    unit = "px";
                }
                String label = String.valueOf((int) barWidth) + " " + unit;
                TextRoi labelRoi = new TextRoi(x, y - 18, label,
                        new Font(Font.SANS_SERIF, Font.BOLD, 12));
                labelRoi.setStrokeColor(Color.WHITE);
                labelRoi.setName(AI_PREFIX + "scalebar-label");
                labelRoi.setNonScalable(true);

                Overlay overlay = target.getOverlay();
                if (overlay == null) {
                    overlay = new Overlay();
                    target.setOverlay(overlay);
                }
                overlay.add(barRoi);
                overlay.add(labelRoi);
                target.updateAndDraw();
            }
        });
    }

    /**
     * Remove all AI-added annotations (those with names starting with "AI:").
     */
    public static void removeAIAnnotations(ImagePlus imp) {
        if (imp == null) {
            return;
        }
        final ImagePlus target = imp;

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Overlay overlay = target.getOverlay();
                if (overlay == null || overlay.size() == 0) {
                    return;
                }

                // Build new overlay without AI annotations
                Overlay newOverlay = new Overlay();
                for (int i = 0; i < overlay.size(); i++) {
                    Roi roi = overlay.get(i);
                    String name = roi.getName();
                    if (name == null || !name.startsWith(AI_PREFIX)) {
                        newOverlay.add(roi);
                    }
                }

                if (newOverlay.size() == 0) {
                    target.setOverlay(null);
                } else {
                    target.setOverlay(newOverlay);
                }
                target.updateAndDraw();
            }
        });
    }

    /**
     * Highlight saturated pixels with a colored overlay.
     * Creates a binary mask of saturated pixels, converts to ROI, adds as overlay.
     */
    public static void highlightSaturatedPixels(ImagePlus imp, Color highlightColor) {
        if (imp == null) {
            return;
        }
        final ImagePlus target = imp;
        final Color color = highlightColor;

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                int type = target.getType();
                double maxVal;
                if (type == ImagePlus.GRAY8 || type == ImagePlus.COLOR_256) {
                    maxVal = 255;
                } else if (type == ImagePlus.GRAY16) {
                    maxVal = 65535;
                } else {
                    // Cannot highlight for RGB or 32-bit float
                    return;
                }

                ImageProcessor ip = target.getProcessor();
                int width = ip.getWidth();
                int height = ip.getHeight();

                // Set threshold to find saturated pixels, create selection
                ImageProcessor mask = ip.duplicate();
                mask.setThreshold(maxVal, maxVal, ImageProcessor.NO_LUT_UPDATE);
                // Create ROI from threshold
                ImagePlus tempImp = new ImagePlus("temp", mask);
                ij.IJ.run(tempImp, "Create Selection", "");
                Roi saturatedRoi = tempImp.getRoi();
                tempImp.close();

                if (saturatedRoi == null) {
                    return;
                }

                // Add as transparent overlay
                Color transparentColor = new Color(color.getRed(), color.getGreen(),
                        color.getBlue(), 100);
                saturatedRoi.setFillColor(transparentColor);
                saturatedRoi.setStrokeColor(color);
                saturatedRoi.setName(AI_PREFIX + "saturated-highlight");

                Overlay overlay = target.getOverlay();
                if (overlay == null) {
                    overlay = new Overlay();
                    target.setOverlay(overlay);
                }
                overlay.add(saturatedRoi);
                target.updateAndDraw();
            }
        });
    }
}
