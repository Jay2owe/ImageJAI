package imagejai.engine;

/**
 * Metadata about an open ImagePlus image.
 */
public class ImageInfo {

    private final String title;
    private final int width;
    private final int height;
    private final int slices;
    private final int channels;
    private final int frames;
    private final String type;
    private final String calibration;
    private final boolean isStack;
    private final boolean isHyperstack;

    public ImageInfo(String title, int width, int height, int slices, int channels,
                     int frames, String type, String calibration, boolean isStack,
                     boolean isHyperstack) {
        this.title = title;
        this.width = width;
        this.height = height;
        this.slices = slices;
        this.channels = channels;
        this.frames = frames;
        this.type = type;
        this.calibration = calibration;
        this.isStack = isStack;
        this.isHyperstack = isHyperstack;
    }

    public String getTitle() {
        return title;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getSlices() {
        return slices;
    }

    public int getChannels() {
        return channels;
    }

    public int getFrames() {
        return frames;
    }

    public String getType() {
        return type;
    }

    public String getCalibration() {
        return calibration;
    }

    public boolean isStack() {
        return isStack;
    }

    public boolean isHyperstack() {
        return isHyperstack;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append(": ").append(width).append("x").append(height);
        sb.append(" ").append(type);
        if (isHyperstack) {
            sb.append(" [hyperstack: c=").append(channels)
              .append(" z=").append(slices)
              .append(" t=").append(frames).append("]");
        } else if (isStack) {
            sb.append(" [stack: ").append(slices).append(" slices]");
        }
        if (calibration != null && !calibration.isEmpty()) {
            sb.append(" (").append(calibration).append(")");
        }
        return sb.toString();
    }
}
