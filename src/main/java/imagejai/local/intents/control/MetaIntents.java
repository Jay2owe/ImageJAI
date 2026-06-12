package imagejai.local.intents.control;

import ij.ImagePlus;
import imagejai.config.Constants;
import imagejai.config.Settings;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;

import java.util.Map;

class CapabilitiesIntent extends AbstractControlIntent {
    public String id() { return "builtin.capabilities"; }
    public String description() { return "Describe Local Assistant capabilities"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text(HelpText.full());
    }
}

class CommandsIntent extends AbstractControlIntent {
    public String id() { return "builtin.commands"; }
    public String description() { return "List supported Local Assistant commands"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text(HelpText.full());
    }
}

class CurrentAgentIntent extends AbstractControlIntent {
    public String id() { return "builtin.current_agent"; }
    public String description() { return "Report the selected agent"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Current agent: " + Settings.load().getSelectedAgentName() + ".");
    }
}

class VersionIntent extends AbstractControlIntent {
    public String id() { return "builtin.version"; }
    public String description() { return "Report ImageJAI version"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text(Constants.PLUGIN_NAME + " " + Constants.VERSION
                + ", Local Assistant phrasebook v1.");
    }
}

final class HelpText {
    private HelpText() {
    }

    static String full() {
        return "Local Assistant understands plain English for:\n"
                + "- Inspecting images: pixel size, dimensions, bit depth, channel/slice/frame counts, current position, title, file path, intensity stats, open images, saturation.\n"
                + "- Controlling images: close, duplicate, revert, save as TIFF/PNG/JPEG, next/previous slice, switch channel, jump slice/frame, merge/split channels, z projections, substacks, crop, scale, invert, convert type, set scale.\n"
                + "- Analysis: subtract background, blur/filter, threshold, compare thresholds, convert masks, count cells/particles/nuclei, binary cleanup, maxima, intensity, CTCF, ROI measurements, summaries, profiles, histograms.\n"
                + "- ROIs and results: list/count/clear/save ROIs, show results, save results as CSV.\n"
                + "- Display and diagnostics: auto contrast, reset display, fit window, zoom, plugin command count, recorder, ROI Manager, Channels Tool, Log, console, open dialogs, memory, garbage collect.\n"
                + "Use ordinary phrases such as \"what's the bit depth\", \"threshold otsu\", \"count cells\", or \"set scale 100 px = 10 um\".";
    }
}
