package imagejai.local.intents.control;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

class ListRoisIntent extends AbstractControlIntent {
    public String id() { return "roi.list"; }
    public String description() { return "List ROI Manager entries"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        RoiManager rm = fiji.currentRoiManager();
        if (rm == null || rm.getCount() == 0) {
            return AssistantReply.text("The ROI Manager is empty.");
        }
        StringBuilder sb = new StringBuilder("ROI Manager entries:");
        Roi[] rois = rm.getRoisAsArray();
        for (int i = 0; i < rois.length; i++) {
            Roi roi = rois[i];
            String name = rm.getName(i);
            sb.append("\n").append(i + 1).append(". ")
                    .append(name == null ? "(unnamed)" : name);
            if (roi != null && roi.getBounds() != null) {
                java.awt.Rectangle b = roi.getBounds();
                sb.append(" [x=").append(b.x).append(", y=").append(b.y)
                        .append(", w=").append(b.width).append(", h=").append(b.height).append("]");
            }
        }
        return AssistantReply.text(sb.toString());
    }
}

class CountRoisIntent extends AbstractControlIntent {
    public String id() { return "roi.count"; }
    public String description() { return "Count ROI Manager entries"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        RoiManager rm = fiji.currentRoiManager();
        int count = rm == null ? 0 : rm.getCount();
        return AssistantReply.text("ROI count: " + count + ".");
    }
}

class ClearRoiManagerIntent extends AbstractControlIntent {
    public String id() { return "roi.clear"; }
    public String description() { return "Clear the ROI Manager"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        RoiManager rm = fiji.currentRoiManager();
        if (rm == null || rm.getCount() == 0) {
            return AssistantReply.text("The ROI Manager is already empty.");
        }
        int count = rm.getCount();
        rm.reset();
        return AssistantReply.withMacro("Cleared " + plural(count, "ROI") + ".", "roiManager(\"Reset\");");
    }
}

class SaveRoisIntent extends AbstractControlIntent {
    public String id() { return "roi.save"; }
    public String description() { return "Save ROIs to AI_Exports"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        RoiManager rm = fiji.currentRoiManager();
        if (rm == null || rm.getCount() == 0) {
            return AssistantReply.text("The ROI Manager is empty.");
        }
        Path out = fiji.resolveAiExportsDir().resolve("rois.zip");
        boolean ok = rm.save(out.toString());
        if (!ok) {
            return AssistantReply.text("Could not save ROIs to " + out + ".");
        }
        return AssistantReply.withMacro("Saved " + plural(rm.getCount(), "ROI") + " to " + out + ".",
                "roiManager(\"Save\", \"" + macroQuote(out.toString().replace('\\', '/')) + "\");");
    }
}

class ShowResultsTableIntent extends AbstractControlIntent {
    public String id() { return "results.show"; }
    public String description() { return "Show the Results table"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        ResultsTable rt = fiji.currentResults();
        rt.show("Results");
        return AssistantReply.withMacro("Showing the Results table with " + rt.getCounter() + " rows.",
                "run(\"Results...\");");
    }
}

class SaveResultsCsvIntent extends AbstractControlIntent {
    public String id() { return "results.save_csv"; }
    public String description() { return "Save Results table to CSV in AI_Exports"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        ResultsTable rt = fiji.currentResults();
        if (rt == null || rt.getCounter() == 0) {
            return AssistantReply.text("The Results table is empty.");
        }
        Path out = fiji.resolveAiExportsDir().resolve("results.csv");
        try {
            rt.saveAs(out.toString());
        } catch (IOException e) {
            return AssistantReply.text("Could not save results to " + out + ": " + e.getMessage());
        }
        return AssistantReply.withMacro("Saved Results table to " + out + ".",
                "saveAs(\"Results\", \"" + macroQuote(out.toString().replace('\\', '/')) + "\");");
    }
}
