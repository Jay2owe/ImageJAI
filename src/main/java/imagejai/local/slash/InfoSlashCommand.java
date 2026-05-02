package imagejai.local.slash;

import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.process.ImageStatistics;
import imagejai.local.AssistantReply;
import imagejai.local.SlashCommand;
import imagejai.local.SlashCommandContext;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class InfoSlashCommand implements SlashCommand {
    private final OpenImageProvider provider;

    public InfoSlashCommand() {
        this(new WindowManagerOpenImageProvider());
    }

    public InfoSlashCommand(OpenImageProvider provider) {
        this.provider = provider;
    }

    public String name() { return "info"; }
    public String intentId() { return "slash.info"; }
    public String description() { return "Show a table of all open images"; }

    public AssistantReply execute(SlashCommandContext context) {
        return AssistantReply.text(format(provider.openImages()));
    }

    public static String format(List<OpenImageInfo> images) {
        if (images == null || images.isEmpty()) {
            return "No images are open.";
        }
        StringBuilder sb = new StringBuilder("Open images:\nTitle | Dimensions | Bit depth | Pixel size | File path | Saturated?");
        for (OpenImageInfo image : images) {
            sb.append("\n").append(image.title).append(" | ")
                    .append(image.width).append(" x ").append(image.height)
                    .append(" x ").append(image.channels)
                    .append(" x ").append(image.slices)
                    .append(" x ").append(image.frames)
                    .append(" | ").append(image.bitDepth).append("-bit")
                    .append(" | ").append(image.pixelSize)
                    .append(" | ").append(image.filePath == null || image.filePath.length() == 0 ? "-" : image.filePath)
                    .append(" | ").append(image.saturated);
        }
        return sb.toString();
    }

    public interface OpenImageProvider {
        List<OpenImageInfo> openImages();
    }

    public static class OpenImageInfo {
        public final String title;
        public final int width;
        public final int height;
        public final int channels;
        public final int slices;
        public final int frames;
        public final int bitDepth;
        public final String pixelSize;
        public final String filePath;
        public final String saturated;

        public OpenImageInfo(String title, int width, int height, int channels,
                             int slices, int frames, int bitDepth, String pixelSize,
                             String filePath, String saturated) {
            this.title = title == null ? "" : title;
            this.width = width;
            this.height = height;
            this.channels = channels;
            this.slices = slices;
            this.frames = frames;
            this.bitDepth = bitDepth;
            this.pixelSize = pixelSize == null ? "" : pixelSize;
            this.filePath = filePath == null ? "" : filePath;
            this.saturated = saturated == null ? "unknown" : saturated;
        }
    }

    static class WindowManagerOpenImageProvider implements OpenImageProvider {
        public List<OpenImageInfo> openImages() {
            List<OpenImageInfo> rows = new ArrayList<OpenImageInfo>();
            int[] ids = WindowManager.getIDList();
            if (ids == null) {
                return rows;
            }
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) {
                    rows.add(fromImage(imp));
                }
            }
            return rows;
        }
    }

    private static OpenImageInfo fromImage(ImagePlus imp) {
        Calibration cal = imp.getCalibration();
        String unit = cal == null || cal.getUnit() == null || cal.getUnit().trim().length() == 0
                ? "pixels" : cal.getUnit();
        String pixelSize = cal == null
                ? "1 x 1 pixels"
                : String.format(Locale.ROOT, "%.4f x %.4f %s", cal.pixelWidth, cal.pixelHeight, unit);
        FileInfo fi = imp.getOriginalFileInfo();
        String path = "";
        if (fi != null && fi.directory != null && fi.fileName != null
                && fi.directory.trim().length() > 0 && fi.fileName.trim().length() > 0) {
            path = Paths.get(fi.directory, fi.fileName).toString();
        }
        return new OpenImageInfo(
                imp.getTitle(),
                imp.getWidth(),
                imp.getHeight(),
                imp.getNChannels(),
                imp.getNSlices(),
                imp.getNFrames(),
                imp.getBitDepth(),
                pixelSize,
                path,
                saturated(imp));
    }

    private static String saturated(ImagePlus imp) {
        ImageStatistics stats = imp.getStatistics(Measurements.MIN_MAX);
        int bitDepth = imp.getBitDepth();
        if (bitDepth == 8 || bitDepth == 24) {
            return stats.min <= 0.0 || stats.max >= 255.0 ? "yes" : "no";
        }
        if (bitDepth == 16) {
            return stats.min <= 0.0 || stats.max >= 65535.0 ? "yes" : "no";
        }
        return "n/a";
    }
}
