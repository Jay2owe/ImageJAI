package imagejai.local.intents;

import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;
import imagejai.local.Intent;

import java.util.Map;

/**
 * Describes the built-in Local Assistant command surface.
 */
public class HelpIntent implements Intent {

    public static final String ID = "builtin.help";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Show Local Assistant help";
    }

    @Override
    public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
        return AssistantReply.text("Local Assistant understands plain English for:\n"
                + "- Inspecting images: pixel size, dimensions, bit depth, channel/slice/frame counts, current position, title, file path, intensity stats, open images, saturation.\n"
                + "- Controlling images: close, duplicate, revert, save as TIFF/PNG/JPEG, next/previous slice, switch channel, jump slice/frame, merge/split channels, z projections, substacks, crop, scale, invert, convert type, set scale.\n"
                + "- ROIs and results: list/count/clear/save ROIs, show results, save results as CSV.\n"
                + "- Display and diagnostics: auto contrast, reset display, fit window, zoom, plugin command count, recorder, ROI Manager, Channels Tool, Log, console, open dialogs, memory, garbage collect.\n"
                + "Use ordinary phrases such as \"what's the bit depth\", \"save as TIFF\", \"z project max\", or \"set scale 100 px = 10 um\".");
    }
}
