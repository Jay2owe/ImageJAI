package imagejai.local.intents;

import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.Calibration;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;
import imagejai.local.Intent;

import java.util.Locale;
import java.util.Map;

/**
 * Reports the active image calibration without mutating image state.
 */
public class PixelSizeIntent implements Intent {

    public static final String ID = "image.pixel_size";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Report the active image's pixel size";
    }

    @Override
    public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            return AssistantReply.text("No image is open.");
        }

        Calibration cal = imp.getCalibration();
        double pixelWidth = cal == null ? 1.0 : cal.pixelWidth;
        double pixelHeight = cal == null ? 1.0 : cal.pixelHeight;
        String unit = cal == null ? "" : cal.getUnit();
        if (unit == null || unit.trim().length() == 0) {
            unit = "pixels";
        }

        return AssistantReply.text(String.format(Locale.ROOT,
                "Pixel size: %.4f x %.4f %s",
                pixelWidth,
                pixelHeight,
                unit));
    }
}
