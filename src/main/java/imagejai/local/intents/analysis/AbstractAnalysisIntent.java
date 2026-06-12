package imagejai.local.intents.analysis;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;
import imagejai.local.Intent;

import java.util.Locale;
import java.util.Map;

abstract class AbstractAnalysisIntent implements Intent {

    protected static final String NO_IMAGE = "No image is open.";

    @Override
    public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
        ImagePlus imp = fiji.requireOpenImage();
        if (imp == null && requiresImage()) {
            return AssistantReply.text(NO_IMAGE);
        }
        try {
            return executeChecked(slots, fiji, imp);
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            return AssistantReply.text(message == null ? "The command failed." : message);
        }
    }

    protected boolean requiresImage() {
        return true;
    }

    protected abstract AssistantReply executeChecked(Map<String, String> slots,
                                                     FijiBridge fiji,
                                                     ImagePlus imp);

    protected static int intSlot(Map<String, String> slots, String name, int fallback) {
        String value = slots == null ? null : slots.get(name);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    protected static double doubleSlot(Map<String, String> slots, String name, double fallback) {
        String value = slots == null ? null : slots.get(name);
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    protected static String stringSlot(Map<String, String> slots, String name) {
        String value = slots == null ? null : slots.get(name);
        return value == null ? "" : value.trim();
    }

    protected static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }

    protected static String macroQuote(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    protected static boolean isBinary(ImagePlus imp) {
        if (imp == null || imp.getType() != ImagePlus.GRAY8) {
            return false;
        }
        ImageProcessor processor = imp.getProcessor();
        if (processor == null) {
            return false;
        }
        Object raw = processor.getPixels();
        if (!(raw instanceof byte[])) {
            return false;
        }
        byte[] pixels = (byte[]) raw;
        int step = Math.max(1, pixels.length / 512);
        for (int i = 0; i < pixels.length; i += step) {
            int v = pixels[i] & 0xff;
            if (v != 0 && v != 255) {
                return false;
            }
        }
        return true;
    }
}
