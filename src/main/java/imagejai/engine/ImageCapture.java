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
import java.io.OutputStream;

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
        try {
            return captureImage(imp, maxSize, Integer.MAX_VALUE);
        } catch (CaptureTooLargeException impossible) {
            return null;
        }
    }

    public static byte[] captureImage(ImagePlus imp, int maxSize, int maxPngBytes)
            throws CaptureTooLargeException {
        if (imp == null) {
            return null;
        }
        try {
            BufferedImage bi = imp.getBufferedImage();
            if (bi == null) {
                return null;
            }
            BufferedImage scaled = scaleToFit(bi, maxSize);
            return toPngBytes(scaled, maxPngBytes);
        } catch (CaptureTooLargeException tooLarge) {
            throw tooLarge;
        } catch (Exception e) {
            CaptureTooLargeException tooLarge = findTooLarge(e);
            if (tooLarge != null) throw tooLarge;
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
        try {
            return captureWithOverlays(imp, maxSize, Integer.MAX_VALUE);
        } catch (CaptureTooLargeException impossible) {
            return null;
        }
    }

    public static byte[] captureWithOverlays(ImagePlus imp, int maxSize,
                                             int maxPngBytes)
            throws CaptureTooLargeException {
        if (imp == null) {
            return null;
        }
        ImagePlus flattened = null;
        try {
            // flatten() creates a new ImagePlus with overlays/ROIs burned in
            flattened = imp.flatten();
            BufferedImage bi = flattened.getBufferedImage();
            if (bi == null) {
                return null;
            }
            BufferedImage scaled = scaleToFit(bi, maxSize);
            return toPngBytes(scaled, maxPngBytes);
        } catch (CaptureTooLargeException tooLarge) {
            throw tooLarge;
        } catch (Exception e) {
            CaptureTooLargeException tooLarge = findTooLarge(e);
            if (tooLarge != null) throw tooLarge;
            IJ.log("ImageCapture overlay error: " + e.getMessage());
            return null;
        } finally {
            if (flattened != null) flattened.close();
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
    static byte[] toPngBytes(BufferedImage img, int maxPngBytes) throws IOException {
        if (maxPngBytes < 1) throw new CaptureTooLargeException(maxPngBytes);
        BoundedOutputStream bounded = new BoundedOutputStream(maxPngBytes);
        try {
            if (!ImageIO.write(img, "png", bounded)) {
                throw new IOException("No PNG writer available");
            }
        } catch (IOException failure) {
            CaptureTooLargeException tooLarge = findTooLarge(failure);
            if (tooLarge != null) throw tooLarge;
            throw failure;
        }
        return bounded.toByteArray();
    }

    public static final class CaptureTooLargeException extends IOException {
        private final int limit;
        CaptureTooLargeException(int limit) {
            super("PNG exceeds " + limit + " byte limit");
            this.limit = limit;
        }
        public int limit() { return limit; }
    }

    private static CaptureTooLargeException findTooLarge(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CaptureTooLargeException) {
                return (CaptureTooLargeException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream bytes;

        BoundedOutputStream(int limit) {
            this.limit = limit;
            this.bytes = new ByteArrayOutputStream(Math.min(limit, 32 * 1024));
        }

        @Override public void write(int value) throws IOException {
            ensureCapacity(1);
            bytes.write(value);
        }

        @Override public void write(byte[] value, int offset, int length)
                throws IOException {
            if (value == null) throw new NullPointerException("value");
            if (offset < 0 || length < 0 || offset + length > value.length) {
                throw new IndexOutOfBoundsException();
            }
            ensureCapacity(length);
            bytes.write(value, offset, length);
        }

        private void ensureCapacity(int additional) throws CaptureTooLargeException {
            if (additional > limit - bytes.size()) {
                throw new CaptureTooLargeException(limit);
            }
        }

        byte[] toByteArray() { return bytes.toByteArray(); }
    }
}
