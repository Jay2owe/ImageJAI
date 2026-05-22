package imagejai.engine.security;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Cheap burn-in heuristic for microscope frames. It scans border strips for
 * text-like high-contrast edge density and masks suspicious strips. No OCR or
 * external dependency is used.
 */
public final class BurnInDetector {
    public byte[] mask(byte[] png) {
        if (png == null || png.length == 0) {
            return png;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                return png;
            }
            BufferedImage masked = mask(image);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(masked, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("burn-in masking failed", e);
        }
    }

    public boolean hasDetectedBurnIn(byte[] png) {
        if (png == null || png.length == 0) {
            return false;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            return image != null && !detectMasks(image).isEmpty();
        } catch (Exception e) {
            throw new IllegalStateException("burn-in detection failed", e);
        }
    }

    public BufferedImage mask(BufferedImage image) {
        BufferedImage copy = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.setColor(Color.BLACK);
        for (Rectangle rect : detectMasks(image)) {
            g.fillRect(rect.x, rect.y, rect.width, rect.height);
        }
        g.dispose();
        return copy;
    }

    public List<Rectangle> detectMasks(BufferedImage image) {
        List<Rectangle> masks = new ArrayList<Rectangle>();
        if (image == null || image.getWidth() < 20 || image.getHeight() < 20) {
            return masks;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int stripH = clamp((int) Math.ceil(h * 0.12), 18, Math.max(18, h / 3));
        int stripW = clamp((int) Math.ceil(w * 0.12), 18, Math.max(18, w / 3));

        Rectangle top = new Rectangle(0, 0, w, stripH);
        Rectangle bottom = new Rectangle(0, h - stripH, w, stripH);
        Rectangle left = new Rectangle(0, 0, stripW, h);
        Rectangle right = new Rectangle(w - stripW, 0, stripW, h);

        if (isTextLike(image, top)) masks.add(top);
        if (isTextLike(image, bottom)) masks.add(bottom);
        if (isTextLike(image, left)) masks.add(left);
        if (isTextLike(image, right)) masks.add(right);
        return masks;
    }

    private boolean isTextLike(BufferedImage image, Rectangle rect) {
        int count = 0;
        double sum = 0.0;
        double sumSq = 0.0;
        int dark = 0;
        int light = 0;
        int edges = 0;
        int edgeComparisons = 0;

        for (int y = rect.y; y < rect.y + rect.height; y++) {
            for (int x = rect.x; x < rect.x + rect.width; x++) {
                int lum = luminance(image.getRGB(x, y));
                count++;
                sum += lum;
                sumSq += lum * lum;
                if (lum < 55) dark++;
                if (lum > 200) light++;
                if (x + 1 < rect.x + rect.width) {
                    int nx = luminance(image.getRGB(x + 1, y));
                    if (Math.abs(lum - nx) > 55) edges++;
                    edgeComparisons++;
                }
                if (y + 1 < rect.y + rect.height) {
                    int ny = luminance(image.getRGB(x, y + 1));
                    if (Math.abs(lum - ny) > 55) edges++;
                    edgeComparisons++;
                }
            }
        }
        if (count == 0 || edgeComparisons == 0) {
            return false;
        }
        double mean = sum / count;
        double variance = (sumSq / count) - (mean * mean);
        double edgeDensity = (double) edges / edgeComparisons;
        double darkFraction = (double) dark / count;
        double lightFraction = (double) light / count;

        return (variance > 850.0 && edgeDensity > 0.025
                && darkFraction > 0.005 && lightFraction > 0.005)
                || (variance > 1400.0 && edgeDensity > 0.018);
    }

    private static int luminance(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
