package imagejai.local.intents.control;

import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.measure.Measurements;
import ij.process.ImageStatistics;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;

import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

class ImageDimensionsIntent extends AbstractControlIntent {
    public String id() { return "image.dimensions"; }
    public String description() { return "Report image dimensions"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int[] d = imp.getDimensions();
        return AssistantReply.text(String.format(Locale.ROOT,
                "%s: %d x %d pixels, %s, %s, %s.",
                imp.getTitle(), d[0], d[1],
                plural(d[2], "channel"), plural(d[3], "slice"), plural(d[4], "frame")));
    }
}

class BitDepthIntent extends AbstractControlIntent {
    public String id() { return "image.bit_depth"; }
    public String description() { return "Report image bit depth"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Bit depth: " + imp.getBitDepth() + "-bit.");
    }
}

class ChannelCountIntent extends AbstractControlIntent {
    public String id() { return "image.channel_count"; }
    public String description() { return "Report channel count"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Channels: " + imp.getNChannels() + ".");
    }
}

class SliceCountIntent extends AbstractControlIntent {
    public String id() { return "image.slice_count"; }
    public String description() { return "Report slice count"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Slices: " + imp.getNSlices() + ".");
    }
}

class FrameCountIntent extends AbstractControlIntent {
    public String id() { return "image.frame_count"; }
    public String description() { return "Report frame count"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Frames: " + imp.getNFrames() + ".");
    }
}

class ActiveChannelIntent extends AbstractControlIntent {
    public String id() { return "image.active_channel"; }
    public String description() { return "Report active channel"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Active channel: " + imp.getC() + " of " + imp.getNChannels() + ".");
    }
}

class ActiveSliceIntent extends AbstractControlIntent {
    public String id() { return "image.active_slice"; }
    public String description() { return "Report active slice"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Active slice: " + imp.getZ() + " of " + imp.getNSlices() + ".");
    }
}

class ActiveFrameIntent extends AbstractControlIntent {
    public String id() { return "image.active_frame"; }
    public String description() { return "Report active frame"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Active frame: " + imp.getT() + " of " + imp.getNFrames() + ".");
    }
}

class ImageTitleIntent extends AbstractControlIntent {
    public String id() { return "image.title"; }
    public String description() { return "Report image title"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Image title: " + imp.getTitle());
    }
}

class FilePathIntent extends AbstractControlIntent {
    public String id() { return "image.file_path"; }
    public String description() { return "Report image file path"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi == null || fi.directory == null || fi.directory.trim().length() == 0
                || fi.fileName == null || fi.fileName.trim().length() == 0) {
            return AssistantReply.text("This image does not have a saved file path.");
        }
        return AssistantReply.text("File path: " + Paths.get(fi.directory, fi.fileName).toString());
    }
}

class IntensityStatsIntent extends AbstractControlIntent {
    public String id() { return "image.intensity_stats"; }
    public String description() { return "Report min, max, and mean intensity"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        ImageStatistics stats = imp.getStatistics(Measurements.MIN_MAX | Measurements.MEAN);
        return AssistantReply.text(String.format(Locale.ROOT,
                "Active channel intensity: min %s, max %s, mean %s.",
                fmt(stats.min), fmt(stats.max), fmt(stats.mean)));
    }
}

class ListOpenImagesIntent extends AbstractControlIntent {
    public String id() { return "image.list_open"; }
    public String description() { return "List open images"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        int[] ids = WindowManager.getIDList();
        if (ids == null || ids.length == 0) {
            return AssistantReply.text("No images are open.");
        }
        StringBuilder sb = new StringBuilder("Open images:");
        for (int i = 0; i < ids.length; i++) {
            ImagePlus image = WindowManager.getImage(ids[i]);
            if (image != null) {
                sb.append("\n").append(i + 1).append(". ").append(image.getTitle())
                        .append(" (").append(image.getWidth()).append(" x ")
                        .append(image.getHeight()).append(", ")
                        .append(image.getBitDepth()).append("-bit)");
            }
        }
        return AssistantReply.text(sb.toString());
    }
}

class SaturationCheckIntent extends AbstractControlIntent {
    public String id() { return "image.saturation_check"; }
    public String description() { return "Check whether the active image is saturated"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        ImageStatistics stats = imp.getStatistics(Measurements.MIN_MAX);
        int bitDepth = imp.getBitDepth();
        if (bitDepth == 8 || bitDepth == 24) {
            return saturatedReply(stats.max >= 255.0, stats.min <= 0.0, "0", "255");
        }
        if (bitDepth == 16) {
            return saturatedReply(stats.max >= 65535.0, stats.min <= 0.0, "0", "65535");
        }
        return AssistantReply.text("Saturation check: 32-bit images do not have a fixed saturation value. "
                + "Current min " + fmt(stats.min) + ", max " + fmt(stats.max) + ".");
    }

    private AssistantReply saturatedReply(boolean high, boolean low, String lowLabel, String highLabel) {
        if (high || low) {
            String where = high && low ? "low and high ends" : (high ? "high end" : "low end");
            return AssistantReply.text("Saturation check: saturated pixels are present at the "
                    + where + " (" + lowLabel + "-" + highLabel + ").");
        }
        return AssistantReply.text("Saturation check: no saturated pixels detected at "
                + lowLabel + " or " + highLabel + " in the active plane.");
    }
}
