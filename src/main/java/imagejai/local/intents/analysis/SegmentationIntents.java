package imagejai.local.intents.analysis;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;

import java.util.Locale;
import java.util.Map;

class AutoThresholdIntent extends AbstractAnalysisIntent {
    public String id() { return "segmentation.auto_threshold"; }
    public String description() { return "Auto-threshold and convert the active image to a mask"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String method = normaliseMethod(stringSlot(slots, "method"));
        boolean dark = "true".equalsIgnoreCase(stringSlot(slots, "dark"));
        String threshold = method + (dark ? " dark" : "");
        String macro = "setOption(\"BlackBackground\", true);\n"
                + "setAutoThreshold(\"" + macroQuote(threshold) + "\");\n"
                + "run(\"Convert to Mask\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Thresholded with " + method
                + (dark ? " for a dark background." : ".") + " Converted to a mask.", macro);
    }

    static String normaliseMethod(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "Otsu";
        }
        String key = raw.trim().replace(" ", "").replace("_", "").toLowerCase(Locale.ROOT);
        if ("li".equals(key)) return "Li";
        if ("triangle".equals(key)) return "Triangle";
        if ("huang".equals(key)) return "Huang";
        if ("maxentropy".equals(key) || "maxent".equals(key)) return "MaxEntropy";
        if ("default".equals(key)) return "Default";
        return "Otsu";
    }
}

class CompareThresholdsIntent extends AbstractAnalysisIntent {
    public String id() { return "segmentation.compare_thresholds"; }
    public String description() { return "Compare threshold methods using the exploration engine"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String[] methods = methodsSlot(slots);
        FijiBridge.ThresholdComparison comparison = fiji.runExploreThresholds(methods);
        StringBuilder message = new StringBuilder("Compared threshold methods. This may take a minute on large stacks.");
        if (comparison.recommended() != null && comparison.recommended().length() > 0) {
            message.append("\nRecommended: ").append(comparison.recommended()).append(".");
        }
        if (comparison.summary() != null && comparison.summary().length() > 0) {
            message.append("\n").append(comparison.summary());
        }
        return AssistantReply.text(message.toString());
    }

    private String[] methodsSlot(Map<String, String> slots) {
        String methods = stringSlot(slots, "methods");
        if (methods.length() == 0) {
            return new String[]{"Otsu", "Li", "Triangle", "Huang", "MaxEntropy", "Default"};
        }
        String[] parts = methods.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = AutoThresholdIntent.normaliseMethod(parts[i]);
        }
        return parts;
    }
}

class ConvertToMaskIntent extends AbstractAnalysisIntent {
    public String id() { return "segmentation.convert_to_mask"; }
    public String description() { return "Convert the active threshold to a mask"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String macro = "setOption(\"BlackBackground\", true);\nrun(\"Convert to Mask\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Converted the active threshold to a mask with black background.", macro);
    }
}

abstract class CountObjectsIntent extends AbstractAnalysisIntent {
    protected abstract String label();
    protected String sizeRange() { return "0-Infinity"; }
    protected String circRange() { return "0.00-1.00"; }
    public String description() { return "Count objects with Analyze Particles"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        if (!isBinary(imp)) {
            return AssistantReply.text("Threshold the image first, for example: auto threshold otsu.");
        }
        String size = stringSlot(slots, "size");
        String circ = stringSlot(slots, "circularity");
        fiji.runAnalyzeParticles(size.length() == 0 ? sizeRange() : size,
                circ.length() == 0 ? circRange() : circ,
                true);
        ResultsTable rt = fiji.currentResults();
        int count = rt == null ? 0 : rt.getCounter();
        return AssistantReply.withMacro("Counted " + count + " " + label() + (count == 1 ? "" : "s") + ".",
                "run(\"Analyze Particles...\", \"size=" + (size.length() == 0 ? sizeRange() : size)
                        + " circularity=" + (circ.length() == 0 ? circRange() : circ)
                        + " show=Masks display summarize exclude\");");
    }
}

class CountCellsIntent extends CountObjectsIntent {
    public String id() { return "segmentation.count_cells"; }
    protected String label() { return "cell"; }
    protected String sizeRange() { return "50-Infinity"; }
    protected String circRange() { return "0.50-1.00"; }
}

class CountParticlesIntent extends CountObjectsIntent {
    public String id() { return "segmentation.count_particles"; }
    protected String label() { return "particle"; }
}

class CountNucleiIntent extends CountObjectsIntent {
    public String id() { return "segmentation.count_nuclei"; }
    protected String label() { return "nucleus"; }
    protected String sizeRange() { return "50-Infinity"; }
    protected String circRange() { return "0.50-1.00"; }
}

class FillHolesIntent extends AbstractAnalysisIntent {
    public String id() { return "segmentation.fill_holes"; }
    public String description() { return "Fill holes in a binary mask"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String macro = "run(\"Fill Holes\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Filled holes in the binary mask.", macro);
    }
}

class WatershedIntent extends AbstractAnalysisIntent {
    public String id() { return "segmentation.watershed"; }
    public String description() { return "Run watershed on a binary mask"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String macro = "run(\"Watershed\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Applied watershed to the binary mask.", macro);
    }
}

class SkeletonizeIntent extends AbstractAnalysisIntent {
    public String id() { return "segmentation.skeletonize"; }
    public String description() { return "Skeletonize a binary mask"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String macro = "run(\"Skeletonize\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Skeletonized the binary mask.", macro);
    }
}

class DistanceMapIntent extends AbstractAnalysisIntent {
    public String id() { return "segmentation.distance_map"; }
    public String description() { return "Create a distance map from a binary mask"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String macro = "run(\"Distance Map\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Created a distance map.", macro);
    }
}

class VoronoiIntent extends AbstractAnalysisIntent {
    public String id() { return "segmentation.voronoi"; }
    public String description() { return "Create a Voronoi segmentation from a binary mask"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String macro = "run(\"Voronoi\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Created a Voronoi segmentation.", macro);
    }
}

class FindMaximaIntent extends AbstractAnalysisIntent {
    public String id() { return "segmentation.find_maxima"; }
    public String description() { return "Find maxima with a prominence threshold"; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        double prominence = doubleSlot(slots, "prominence", 10.0);
        String macro = "run(\"Find Maxima...\", \"prominence=" + fmt(prominence)
                + " output=[Single Points]\");";
        fiji.runMacro(macro);
        return AssistantReply.withMacro("Found maxima with prominence " + fmt(prominence) + ".", macro);
    }
}
