package imagejai.engine;

import ij.IJ;
import ij.ImagePlus;
import ij.process.ImageProcessor;
import imagejai.config.Constants;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Captures ImagePlus images as PNG byte arrays for vision LLM input.
 */
public class ImageCapture {

    /**
     * Capture the current active image as a PNG thumbnail.
     *
     * @return PNG bytes, or null if no image is open
     */
    public static byte[] captureActiveImage() {
        ImagePlus imp = IJ.getImage();
        if (imp == null) {
            return null;
        }
        return captureImage(imp, Constants.MAX_THUMBNAIL_SIZE);
    }

    /**
     * Capture a specific ImagePlus as a PNG, scaled to fit within maxSize.
     *
     * @param imp     the image to capture
     * @param maxSize maximum dimension (width or height) in pixels
     * @return PNG bytes, or null on error
     */
    public static byte[] captureImage(ImagePlus imp, int maxSize) {
        if (imp == null) {
            return null;
        }
        try {
            BufferedImage bi = imp.getBufferedImage();
            if (bi == null) {
                return null;
            }
            BufferedImage scaled = scaleToFit(bi, maxSize);
            return toPngBytes(scaled);
        } catch (Exception e) {
            IJ.log("ImageCapture error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Capture an image with its overlays and ROIs rendered into the output.
     * Uses ImagePlus.flatten() to burn in overlays before capture.
     *
     * @param imp     the image to capture
     * @param maxSize maximum dimension in pixels
     * @return PNG bytes, or null on error
     */
    public static byte[] captureWithOverlays(ImagePlus imp, int maxSize) {
        if (imp == null) {
            return null;
        }
        try {
            // flatten() creates a new ImagePlus with overlays/ROIs burned in
            ImagePlus flattened = imp.flatten();
            BufferedImage bi = flattened.getBufferedImage();
            flattened.close();
            if (bi == null) {
                return null;
            }
            BufferedImage scaled = scaleToFit(bi, maxSize);
            return toPngBytes(scaled);
        } catch (Exception e) {
            IJ.log("ImageCapture overlay error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Scale a BufferedImage to fit within maxSize, maintaining aspect ratio.
     * Returns the original image if it already fits.
     */
    private static BufferedImage scaleToFit(BufferedImage img, int maxSize) {
        int w = img.getWidth();
        int h = img.getHeight();

        if (w <= maxSize && h <= maxSize) {
            return img;
        }

        double scale = Math.min((double) maxSize / w, (double) maxSize / h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);

        // Ensure at least 1 pixel
        if (newW < 1) newW = 1;
        if (newH < 1) newH = 1;

        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(img, 0, 0, newW, newH, null);
        g2d.dispose();
        return scaled;
    }

    /**
     * Convert a BufferedImage to PNG byte array.
     */
    private static byte[] toPngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
