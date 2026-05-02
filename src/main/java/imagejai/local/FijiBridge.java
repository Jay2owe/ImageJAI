package imagejai.local;

import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.gui.Roi;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;
import imagejai.engine.CommandEngine;
import imagejai.engine.ExecutionResult;
import imagejai.engine.ExplorationEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Facade for direct Fiji/ImageJ access used by deterministic local intents.
 */
public class FijiBridge {

    private final CommandEngine commandEngine;

    public FijiBridge(CommandEngine commandEngine) {
        this.commandEngine = commandEngine;
    }

    public ImagePlus requireOpenImage() {
        return WindowManager.getCurrentImage();
    }

    public Path resolveAiExportsDir() {
        ImagePlus imp = requireOpenImage();
        if (imp == null) {
            throw new IllegalStateException("No image is open.");
        }
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi == null || fi.directory == null || fi.directory.trim().length() == 0) {
            throw new IllegalStateException("The active image has no file-backed directory.");
        }
        Path dir = Paths.get(fi.directory).resolve("AI_Exports");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create AI_Exports directory: " + e.getMessage(), e);
        }
        return dir;
    }

    public void runMacro(String code) {
        ExecutionResult result = commandEngine.executeMacro(code);
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getError());
        }
    }

    public ResultsTable currentResults() {
        return ResultsTable.getResultsTable();
    }

    public RoiManager currentRoiManager() {
        return RoiManager.getInstance();
    }

    public void runAnalyzeParticles(String sizeRange, String circRange, boolean showMasks) {
        String size = (sizeRange == null || sizeRange.trim().length() == 0)
                ? "0-Infinity" : sizeRange.trim();
        String circ = (circRange == null || circRange.trim().length() == 0)
                ? "0.00-1.00" : circRange.trim();
        String showOption = showMasks ? "show=Masks" : "show=Nothing";
        String macro = "run(\"Analyze Particles...\", \"size=" + macroQuote(size)
                + " circularity=" + macroQuote(circ)
                + " " + showOption + " display summarize exclude\");";
        runMacro(macro);
    }

    public ResultsTable measureCurrentRoiSet() {
        RoiManager rm = currentRoiManager();
        if (rm == null || rm.getCount() == 0) {
            return currentResults();
        }
        runMacro("roiManager(\"Measure\");");
        return currentResults();
    }

    public double computeCtcf(Roi roi, ImagePlus imp, double background) {
        if (roi == null || imp == null) {
            throw new IllegalStateException("CTCF requires an image and a cell ROI.");
        }
        Roi previous = imp.getRoi();
        try {
            imp.setRoi(roi);
            ImageStatistics stats = imp.getStatistics(Measurements.AREA | Measurements.MEAN);
            double area = stats == null ? 0.0 : stats.area;
            double mean = stats == null ? 0.0 : stats.mean;
            double intDen = area * mean;
            return intDen - (area * background);
        } finally {
            if (previous == null) {
                imp.deleteRoi();
            } else {
                imp.setRoi(previous);
            }
        }
    }

    public ThresholdComparison runExploreThresholds(String[] methods) {
        ExplorationEngine.ExplorationReport report =
                new ExplorationEngine(commandEngine).exploreThresholds(methods);
        return new ThresholdComparison(report);
    }

    private static String macroQuote(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static class ThresholdComparison {
        private final ExplorationEngine.ExplorationReport report;

        ThresholdComparison(ExplorationEngine.ExplorationReport report) {
            this.report = report;
        }

        public String recommended() {
            if (report == null || report.recommended == null) {
                return "";
            }
            return report.recommended.methodName;
        }

        public String reasoning() {
            return report == null || report.reasoning == null ? "" : report.reasoning;
        }

        public String summary() {
            return report == null ? "" : report.formatComparison();
        }
    }
}
