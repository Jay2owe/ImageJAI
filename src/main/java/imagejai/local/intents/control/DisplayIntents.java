package imagejai.local.intents.control;

import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.measure.Measurements;
import ij.process.ImageStatistics;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;

import java.util.Map;

class AutoContrastIntent extends AbstractControlIntent {
    public String id() { return "display.auto_contrast"; }
    public String description() { return "Auto contrast for display only"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        ImageStatistics stats = imp.getStatistics(Measurements.MIN_MAX);
        imp.setDisplayRange(stats.min, stats.max);
        imp.updateAndDraw();
        return AssistantReply.withMacro("Auto contrast set the display range only; pixel values were not changed.",
                "setMinAndMax(" + fmt(stats.min) + ", " + fmt(stats.max) + ");");
    }
}

class ResetDisplayIntent extends AbstractControlIntent {
    public String id() { return "display.reset"; }
    public String description() { return "Reset display range"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        imp.resetDisplayRange();
        imp.updateAndDraw();
        return AssistantReply.withMacro("Reset the display range.", "resetMinAndMax();");
    }
}

class FitWindowIntent extends AbstractControlIntent {
    public String id() { return "display.fit_window"; }
    public String description() { return "Fit image window to image"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        ImageWindow win = imp.getWindow();
        ImageCanvas canvas = imp.getCanvas();
        if (canvas != null) {
            canvas.fitToWindow();
        }
        if (win != null) {
            win.pack();
        }
        return AssistantReply.withMacro("Fit the window to the image.", "run(\"To Selection\"); // window fit");
    }
}

class SetZoomIntent extends AbstractControlIntent {
    public String id() { return "display.zoom"; }
    public String description() { return "Set image zoom percentage"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double percent = doubleSlot(slots, "percent", -1.0);
        if (percent <= 0.0) {
            return AssistantReply.text("Tell me a positive zoom percentage, for example: zoom to 200 percent.");
        }
        ij.plugin.Zoom.set(imp, percent / 100.0);
        return AssistantReply.withMacro("Set zoom to " + fmt(percent) + "%.",
                "call(\"ij.plugin.Zoom.set\", " + fmt(percent / 100.0) + ");");
    }
}
