package imagejai.local.intents.control;

import ij.ImagePlus;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;
import imagejai.local.Intent;

import java.util.Locale;
import java.util.Map;

abstract class AbstractControlIntent implements Intent {

    protected static final String NO_IMAGE = "No image is open.";
    protected static final String SAVE_FIRST =
            "Save the image first so I know where AI_Exports/ should live.";

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
            if (message != null && message.contains("file-backed directory")) {
                return AssistantReply.text(SAVE_FIRST);
            }
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
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    protected static double doubleSlot(Map<String, String> slots, String name, double fallback) {
        String value = slots == null ? null : slots.get(name);
        if (value == null) {
            return fallback;
        }
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

    protected static String plural(int count, String singular) {
        return count + " " + singular + (count == 1 ? "" : "s");
    }

    protected static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }

    protected static String macroQuote(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
