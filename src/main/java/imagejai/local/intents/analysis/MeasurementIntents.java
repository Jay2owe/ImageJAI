package imagejai.local.intents.analysis;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;

import java.awt.Rectangle;
import java.util.Locale;
import java.util.Map;

class MeasureIntensityIntent extends AbstractAnalysisIntent {
    public String id() { return "measurement.intensity"; }
    public String description() { return "Measure intensity for the active image or ROI"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String macro = "run(\"Set Measurements...\", \"area mean min max integrated redirect=None decimal=3\");\n"
                + "run(\"Measure\");";
        fiji.runMacro(macro);
        ResultsTable rt = fiji.currentResults();
        int rows = rt == null ? 0 : rt.getCounter();
        return AssistantReply.withMacro("Measured intensity. Results table rows: " + rows + ".", macro);
    }
}

class MeasureCtcfIntent extends AbstractAnalysisIntent {
    public String id() { return "measurement.ctcf"; }
    public String description() { return "Measure CTCF using the first ROI as background and remaining ROIs as cells"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        RoiManager rm = fiji.currentRoiManager();
        if (rm == null || rm.getCount() < 2) {
            return AssistantReply.text("Add a background ROI first, then one or more cell ROIs to the ROI Manager.");
        }
        Roi[] rois = rm.getRoisAsArray();
        Roi backgroundRoi = rois[0];
        imp.setRoi(backgroundRoi);
        ImageStatistics bgStats = imp.getStatistics(Measurements.MEAN);
        double background = bgStats == null ? Double.NaN : bgStats.mean;
        if (Double.isNaN(background)) {
            return AssistantReply.text("Could not measure the background ROI.");
        }

        ResultsTable rt = fiji.currentResults();
        for (int i = 1; i < rois.length; i++) {
            Roi roi = rois[i];
            imp.setRoi(roi);
            ImageStatistics stats = imp.getStatistics(Measurements.AREA | Measurements.MEAN);
            double area = stats == null ? 0.0 : stats.area;
            double mean = stats == null ? 0.0 : stats.mean;
            double intDen = area * mean;
            double ctcf = fiji.computeCtcf(roi, imp, background);
            rt.incrementCounter();
            rt.addValue("ROI", i);
            rt.addValue("Area", area);
            rt.addValue("Mean", mean);
            rt.addValue("IntDen", intDen);
            rt.addValue("BackgroundMean", background);
            rt.addValue("CTCF", ctcf);
        }
        rt.show("Results");
        return AssistantReply.withMacro("Calculated CTCF for " + (rois.length - 1)
                + " ROI" + (rois.length == 2 ? "" : "s") + " using background mean "
                + fmt(background) + ".",
                "// CTCF = IntDen - (Area * Background_Mean); first ROI Manager entry used as background.");
    }
}

class MeasureRoisIntent extends AbstractAnalysisIntent {
    public String id() { return "measurement.measure_rois"; }
    public String description() { return "Measure all ROIs in the ROI Manager"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        RoiManager rm = fiji.currentRoiManager();
        if (rm == null || rm.getCount() == 0) {
            return AssistantReply.text("The ROI Manager is empty.");
        }
        ResultsTable rt = fiji.measureCurrentRoiSet();
        return AssistantReply.withMacro("Measured " + rm.getCount() + " ROI"
                + (rm.getCount() == 1 ? "" : "s") + ". Results table rows: "
                + (rt == null ? 0 : rt.getCounter()) + ".",
                "roiManager(\"Measure\");");
    }
}

class SummariseIntent extends AbstractAnalysisIntent {
    public String id() { return "measurement.summarise"; }
    public String description() { return "Summarise the Results table"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        ResultsTable rt = fiji.currentResults();
        if (rt == null || rt.getCounter() == 0) {
            return AssistantReply.text("The Results table is empty.");
        }
        String macro = "run(\"Summarize\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Summarised the Results table.", macro);
    }
}

class ClearResultsIntent extends AbstractAnalysisIntent {
    public String id() { return "measurement.clear_results"; }
    public String description() { return "Clear the Results table"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        ResultsTable rt = fiji.currentResults();
        int rows = rt == null ? 0 : rt.getCounter();
        String macro = "run(\"Clear Results\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Cleared " + rows + " result row" + (rows == 1 ? "" : "s") + ".", macro);
    }
}

class SetMeasurementsIntent extends AbstractAnalysisIntent {
    public String id() { return "measurement.set_measurements"; }
    public String description() { return "Set the measurements recorded by ImageJ"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String keys = stringSlot(slots, "keys");
        if (keys.length() == 0) {
            keys = "area,mean,integrated";
        }
        String options = measurementOptions(keys);
        String macro = "run(\"Set Measurements...\", \"" + macroQuote(options) + " redirect=None decimal=3\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Set measurements: " + keys + ".", macro);
    }

    private String measurementOptions(String keys) {
        StringBuilder sb = new StringBuilder();
        String[] parts = keys.split(",");
        for (String part : parts) {
            String key = part.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
            String mapped = mapKey(key);
            if (mapped.length() == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(mapped);
        }
        if (sb.length() == 0) {
            return "area mean integrated";
        }
        return sb.toString();
    }

    private String mapKey(String key) {
        if ("intden".equals(key) || "integrated_density".equals(key)) return "integrated";
        if ("std".equals(key) || "standard_deviation".equals(key)) return "standard";
        if ("center_of_mass".equals(key)) return "center";
        if ("area_fraction".equals(key)) return "area_fraction";
        if ("feret".equals(key) || "ferets".equals(key)) return "feret";
        if (key.matches("[a-z_]+")) return key;
        return "";
    }
}

class LineProfileIntent extends AbstractAnalysisIntent {
    public String id() { return "measurement.line_profile"; }
    public String description() { return "Plot the intensity profile along the active line ROI"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        Roi roi = imp.getRoi();
        if (roi == null || !isLineRoi(roi)) {
            return AssistantReply.text("Draw a line ROI first, then ask for a line profile.");
        }
        String macro = "run(\"Plot Profile\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Opened the line profile plot.", macro);
    }

    private boolean isLineRoi(Roi roi) {
        int type = roi.getType();
        return type == Roi.LINE || type == Roi.POLYLINE || type == Roi.FREELINE;
    }
}

class HistogramIntent extends AbstractAnalysisIntent {
    public String id() { return "measurement.histogram"; }
    public String description() { return "Open a histogram for the active image or ROI"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String macro = "run(\"Histogram\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Opened the histogram.", macro);
    }
}

class NearestNeighbourDistanceIntent extends AbstractAnalysisIntent {
    public String id() { return "measurement.nearest_neighbour"; }
    public String description() { return "Compute nearest-neighbour distances between ROI centers"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        RoiManager rm = fiji.currentRoiManager();
        if (rm == null || rm.getCount() < 2) {
            return AssistantReply.text("Add at least two ROIs to the ROI Manager first.");
        }
        Roi[] rois = rm.getRoisAsArray();
        ResultsTable rt = fiji.currentResults();
        for (int i = 0; i < rois.length; i++) {
            double nearest = nearestDistance(rois, i);
            rt.incrementCounter();
            rt.addValue("ROI", i + 1);
            rt.addValue("NearestNeighborDistance", nearest);
        }
        rt.show("Results");
        return AssistantReply.withMacro("Computed nearest-neighbour distances for "
                + rois.length + " ROIs.",
                "// Distances computed between ROI bounding-box centres and written to Results.");
    }

    private double nearestDistance(Roi[] rois, int index) {
        Point a = centre(rois[index]);
        double best = Double.POSITIVE_INFINITY;
        for (int j = 0; j < rois.length; j++) {
            if (j == index) continue;
            Point b = centre(rois[j]);
            double dx = a.x - b.x;
            double dy = a.y - b.y;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy));
        }
        return best;
    }

    private Point centre(Roi roi) {
        Rectangle b = roi.getBounds();
        return new Point(b.getCenterX(), b.getCenterY());
    }

    private static class Point {
        final double x;
        final double y;
        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
