package imagejai.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * A Swing component that renders a BufferedImage thumbnail inline in the chat.
 * Scales to fit width (max 380px) while maintaining aspect ratio.
 * Optionally displays a caption below the image.
 */
public class ImagePreview extends JPanel {

    private static final int MAX_WIDTH = 380;
    private static final Color BORDER_COLOR = new Color(60, 60, 70);
    private static final Color CAPTION_COLOR = new Color(160, 160, 170);
    private static final Color BG_COLOR = new Color(35, 35, 42);

    private final BufferedImage original;
    private final BufferedImage scaled;
    private final String caption;
    private final int displayWidth;
    private final int displayHeight;

    /**
     * Create an image preview with no caption.
     *
     * @param image the image to display
     */
    public ImagePreview(BufferedImage image) {
        this(image, null);
    }

    /**
     * Create an image preview with an optional caption.
     *
     * @param image   the image to display
     * @param caption optional caption text (may be null)
     */
    public ImagePreview(BufferedImage image, String caption) {
        if (image == null) {
            throw new IllegalArgumentException("Image must not be null");
        }

        this.original = image;
        this.caption = caption;

        // Calculate scaled dimensions
        int origWidth = image.getWidth();
        int origHeight = image.getHeight();

        if (origWidth <= MAX_WIDTH) {
            displayWidth = origWidth;
            displayHeight = origHeight;
        } else {
            double scale = (double) MAX_WIDTH / origWidth;
            displayWidth = MAX_WIDTH;
            displayHeight = (int) Math.round(origHeight * scale);
        }

        // Create scaled image for rendering
        if (displayWidth != origWidth || displayHeight != origHeight) {
            scaled = new BufferedImage(displayWidth, displayHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(image, 0, 0, displayWidth, displayHeight, null);
            g2.dispose();
        } else {
            scaled = image;
        }

        // Layout
        setLayout(new BorderLayout(0, 2));
        setOpaque(true);
        setBackground(BG_COLOR);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                new EmptyBorder(4, 4, 4, 4)
        ));

        // Caption label below image
        if (caption != null && !caption.trim().isEmpty()) {
            JLabel captionLabel = new JLabel(caption);
            captionLabel.setForeground(CAPTION_COLOR);
            captionLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
            captionLabel.setHorizontalAlignment(SwingConstants.CENTER);
            captionLabel.setBorder(new EmptyBorder(2, 0, 0, 0));
            add(captionLabel, BorderLayout.SOUTH);
        }

        // Set preferred size for layout
        int totalHeight = displayHeight + 8; // padding
        if (caption != null && !caption.trim().isEmpty()) {
            totalHeight += 18; // caption height
        }
        setPreferredSize(new Dimension(displayWidth + 10, totalHeight));
        setMaximumSize(new Dimension(MAX_WIDTH + 10, totalHeight));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (scaled != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Center the image horizontally within the component
            Insets insets = getInsets();
            int availableWidth = getWidth() - insets.left - insets.right;
            int x = insets.left + (availableWidth - displayWidth) / 2;
            int y = insets.top;

            g2.drawImage(scaled, x, y, displayWidth, displayHeight, null);
            g2.dispose();
        }
    }

    /**
     * Get the original (unscaled) image.
     */
    public BufferedImage getOriginalImage() {
        return original;
    }

    /**
     * Get the display width of the scaled image.
     */
    public int getDisplayWidth() {
        return displayWidth;
    }

    /**
     * Get the display height of the scaled image.
     */
    public int getDisplayHeight() {
        return displayHeight;
    }

    /**
     * Get the maximum width for image previews.
     */
    public static int getMaxPreviewWidth() {
        return MAX_WIDTH;
    }
}
