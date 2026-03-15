package uk.ac.ucl.imagej.ai.engine;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.ResultsTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tries multiple image processing approaches and compares results quantitatively.
 * <p>
 * Each exploration duplicates the active image, applies a variant, measures metrics
 * (object count, area, circularity, coverage), captures a thumbnail, then closes the
 * duplicate. The best result is recommended based on heuristics.
 * <p>
 * All temporary images are tracked for cleanup. Memory is checked before starting.
 */
public class ExplorationEngine {

    /**
     * Result of a single exploration variant.
     */
    public static class ExplorationResult {
        public String methodName;
        public String macroCode;
        public boolean success;
        public int objectCount;
        public double meanArea;
        public double meanCircularity;
        public double coverage;
        public byte[] thumbnail;
        public String summary;
    }

    /**
     * Report comparing multiple exploration results.
     */
    public static class ExplorationReport {
        public List<ExplorationResult> results;
        public ExplorationResult recommended;
        public String reasoning;

        /**
         * Format the exploration results as a human-readable comparison table.
         */
        public String formatComparison() {
            if (results == null || results.isEmpty()) {
                return "No exploration results.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Exploration Results ===\n\n");

            // Header
            sb.append(String.format("%-15s %8s %10s %12s %9s  %s\n",
                    "Method", "Objects", "Mean Area", "Circularity", "Coverage", "Status"));
            sb.append(String.format("%-15s %8s %10s %12s %9s  %s\n",
                    "---------------", "--------", "----------", "------------", "---------", "------"));

            for (ExplorationResult r : results) {
                String marker = "";
                if (recommended != null && r.methodName.equals(recommended.methodName)) {
                    marker = " <-- BEST";
                }
                if (r.success) {
                    sb.append(String.format("%-15s %8d %10.1f %12.3f %8.1f%%  OK%s\n",
                            r.methodName, r.objectCount, r.meanArea,
                            r.meanCircularity, r.coverage * 100.0, marker));
                } else {
                    sb.append(String.format("%-15s %8s %10s %12s %9s  FAILED%s\n",
                            r.methodName, "-", "-", "-", "-", marker));
                }
            }

            if (recommended != null) {
                sb.append("\nRecommended: ").append(recommended.methodName).append("\n");
            }
            if (reasoning != null && !reasoning.isEmpty()) {
                sb.append("Reasoning: ").append(reasoning).append("\n");
            }

            return sb.toString();
        }
    }

    private static final int MEMORY_WARNING_THRESHOLD_MB = 200;
    private static final int MAX_EXPLORATION_VARIANTS = 12;

    private final CommandEngine commandEngine;
    private final StateInspector stateInspector;
    private final List<String> temporaryImages;

    public ExplorationEngine(CommandEngine commandEngine) {
        this.commandEngine = commandEngine;
        this.stateInspector = new StateInspector();
        this.temporaryImages = new ArrayList<String>();
    }

    /**
     * Try multiple thresholding methods on the active image and compare results.
     *
     * @param methods array of threshold method names (e.g., "Otsu", "Triangle", "Li")
     * @return report comparing all methods with a recommendation
     */
    public ExplorationReport exploreThresholds(String[] methods) {
        if (methods == null || methods.length == 0) {
            methods = new String[]{"Otsu", "Triangle", "Li", "Huang", "MaxEntropy", "Moments"};
        }

        ImagePlus activeImage = WindowManager.getCurrentImage();
        if (activeImage == null) {
            ExplorationReport report = new ExplorationReport();
            report.results = Collections.emptyList();
            report.reasoning = "No active image to explore.";
            return report;
        }

        String originalTitle = activeImage.getTitle();

        // Check memory before starting
        MemoryInfo mem = stateInspector.getMemoryInfo();
        int maxVariants = methods.length;
        if (mem.getFreeMB() < MEMORY_WARNING_THRESHOLD_MB) {
            IJ.log("[ExplorationEngine] Low memory (" + mem.getFreeMB()
                    + " MB free). Limiting exploration variants.");
            maxVariants = Math.min(maxVariants, 3);
        }
        if (maxVariants > MAX_EXPLORATION_VARIANTS) {
            maxVariants = MAX_EXPLORATION_VARIANTS;
        }

        List<ExplorationResult> results = new ArrayList<ExplorationResult>();

        for (int i = 0; i < maxVariants; i++) {
            String method = methods[i];
            String tempTitle = "explore_" + method;

            ExplorationResult result = new ExplorationResult();
            result.methodName = method;

            // Build the macro for this threshold method
            StringBuilder macro = new StringBuilder();
            macro.append("selectWindow(\"").append(escapeQuotes(originalTitle)).append("\");\n");
            macro.append("run(\"Duplicate...\", \"title=").append(escapeQuotes(tempTitle)).append("\");\n");
            macro.append("selectWindow(\"").append(escapeQuotes(tempTitle)).append("\");\n");
            // Ensure 8-bit for thresholding
            macro.append("if (bitDepth() != 8) run(\"8-bit\");\n");
            macro.append("setAutoThreshold(\"").append(escapeQuotes(method)).append("\");\n");
            macro.append("run(\"Convert to Mask\");\n");
            result.macroCode = macro.toString();

            // Track temporary image for cleanup
            temporaryImages.add(tempTitle);

            try {
                // Execute the threshold macro
                ExecutionResult execResult = commandEngine.executeMacro(result.macroCode);
                if (!execResult.isSuccess()) {
                    result.success = false;
                    result.summary = "Failed: " + execResult.getError();
                    closeImage(tempTitle);
                    results.add(result);
                    continue;
                }

                // Measure coverage (mean intensity of binary image / 255)
                result.coverage = measureCoverage(tempTitle);

                // Get particle statistics (runs Analyze Particles with summarize)
                Map<String, Double> stats = getParticleStats(tempTitle);
                result.objectCount = stats.containsKey("count") ? stats.get("count").intValue() : 0;
                result.meanArea = stats.containsKey("meanArea") ? stats.get("meanArea") : 0.0;
                result.meanCircularity = stats.containsKey("meanCircularity")
                        ? stats.get("meanCircularity") : 0.0;

                // Capture thumbnail
                ImagePlus tempImp = WindowManager.getImage(tempTitle);
                if (tempImp != null) {
                    result.thumbnail = ImageCapture.captureImage(tempImp, 256);
                }

                result.success = true;
                result.summary = String.format("%s: %d objects, mean area=%.1f, circ=%.3f, coverage=%.1f%%",
                        method, result.objectCount, result.meanArea,
                        result.meanCircularity, result.coverage * 100.0);

            } catch (Exception e) {
                result.success = false;
                result.summary = "Error: " + e.getMessage();
                IJ.log("[ExplorationEngine] Error exploring " + method + ": " + e.getMessage());
            }

            // Close temporary image after capturing metrics
            closeImage(tempTitle);
            results.add(result);
        }

        // Re-select the original image
        selectImage(originalTitle);

        // Build report
        ExplorationReport report = new ExplorationReport();
        report.results = results;
        report.recommended = selectBestThreshold(results);
        if (report.recommended != null) {
            report.reasoning = buildThresholdReasoning(report.recommended, results);
        } else {
            report.reasoning = "No successful threshold methods found.";
        }

        return report;
    }

    /**
     * Try a macro template with different parameter values and compare results.
     *
     * @param macroTemplate template with ${value} placeholder
     * @param paramName     human-readable parameter name (e.g., "sigma")
     * @param values        array of values to try
     * @return report comparing all parameter values
     */
    public ExplorationReport exploreParameter(String macroTemplate, String paramName,
                                               double[] values) {
        if (values == null || values.length == 0) {
            ExplorationReport report = new ExplorationReport();
            report.results = Collections.emptyList();
            report.reasoning = "No parameter values to explore.";
            return report;
        }

        ImagePlus activeImage = WindowManager.getCurrentImage();
        if (activeImage == null) {
            ExplorationReport report = new ExplorationReport();
            report.results = Collections.emptyList();
            report.reasoning = "No active image to explore.";
            return report;
        }

        String originalTitle = activeImage.getTitle();

        // Memory check
        MemoryInfo mem = stateInspector.getMemoryInfo();
        int maxVariants = values.length;
        if (mem.getFreeMB() < MEMORY_WARNING_THRESHOLD_MB) {
            IJ.log("[ExplorationEngine] Low memory. Limiting exploration variants.");
            maxVariants = Math.min(maxVariants, 3);
        }
        if (maxVariants > MAX_EXPLORATION_VARIANTS) {
            maxVariants = MAX_EXPLORATION_VARIANTS;
        }

        List<ExplorationResult> results = new ArrayList<ExplorationResult>();

        for (int i = 0; i < maxVariants; i++) {
            double value = values[i];
            String valueStr = formatNumber(value);
            String tempTitle = "explore_" + paramName + "_" + valueStr;

            ExplorationResult result = new ExplorationResult();
            result.methodName = paramName + "=" + valueStr;

            // Build macro: duplicate, select duplicate, run template with substituted value
            String substituted = macroTemplate.replace("${value}", valueStr);
            StringBuilder macro = new StringBuilder();
            macro.append("selectWindow(\"").append(escapeQuotes(originalTitle)).append("\");\n");
            macro.append("run(\"Duplicate...\", \"title=").append(escapeQuotes(tempTitle)).append("\");\n");
            macro.append("selectWindow(\"").append(escapeQuotes(tempTitle)).append("\");\n");
            macro.append(substituted).append("\n");
            result.macroCode = macro.toString();

            temporaryImages.add(tempTitle);

            try {
                ExecutionResult execResult = commandEngine.executeMacro(result.macroCode);
                if (!execResult.isSuccess()) {
                    result.success = false;
                    result.summary = "Failed: " + execResult.getError();
                    closeImage(tempTitle);
                    results.add(result);
                    continue;
                }

                // Measure basic statistics on the resulting image
                result.coverage = measureCoverage(tempTitle);

                // For parameter exploration, also try particle analysis if the image is binary
                ImagePlus tempImp = WindowManager.getImage(tempTitle);
                if (tempImp != null && isBinaryImage(tempImp)) {
                    Map<String, Double> stats = getParticleStats(tempTitle);
                    result.objectCount = stats.containsKey("count") ? stats.get("count").intValue() : 0;
                    result.meanArea = stats.containsKey("meanArea") ? stats.get("meanArea") : 0.0;
                    result.meanCircularity = stats.containsKey("meanCircularity")
                            ? stats.get("meanCircularity") : 0.0;
                }

                // Capture thumbnail
                if (tempImp != null) {
                    result.thumbnail = ImageCapture.captureImage(tempImp, 256);
                }

                result.success = true;
                result.summary = String.format("%s=%s: coverage=%.1f%%",
                        paramName, valueStr, result.coverage * 100.0);

            } catch (Exception e) {
                result.success = false;
                result.summary = "Error: " + e.getMessage();
            }

            closeImage(tempTitle);
            results.add(result);
        }

        selectImage(originalTitle);

        ExplorationReport report = new ExplorationReport();
        report.results = results;
        report.recommended = selectBestParameter(results);
        if (report.recommended != null) {
            report.reasoning = "Selected " + report.recommended.methodName
                    + " based on metric comparison.";
        } else {
            report.reasoning = "No successful parameter values found.";
        }

        return report;
    }

    /**
     * Generic exploration: try multiple named macro variants and compare.
     *
     * @param namedMacros map of variant name to macro code
     * @return report comparing all variants
     */
    public ExplorationReport explore(Map<String, String> namedMacros) {
        if (namedMacros == null || namedMacros.isEmpty()) {
            ExplorationReport report = new ExplorationReport();
            report.results = Collections.emptyList();
            report.reasoning = "No macro variants to explore.";
            return report;
        }

        ImagePlus activeImage = WindowManager.getCurrentImage();
        if (activeImage == null) {
            ExplorationReport report = new ExplorationReport();
            report.results = Collections.emptyList();
            report.reasoning = "No active image to explore.";
            return report;
        }

        String originalTitle = activeImage.getTitle();

        // Memory check
        MemoryInfo mem = stateInspector.getMemoryInfo();
        int maxVariants = namedMacros.size();
        if (mem.getFreeMB() < MEMORY_WARNING_THRESHOLD_MB) {
            IJ.log("[ExplorationEngine] Low memory. Limiting exploration variants.");
            maxVariants = Math.min(maxVariants, 3);
        }
        if (maxVariants > MAX_EXPLORATION_VARIANTS) {
            maxVariants = MAX_EXPLORATION_VARIANTS;
        }

        List<ExplorationResult> results = new ArrayList<ExplorationResult>();
        int count = 0;

        for (Map.Entry<String, String> entry : namedMacros.entrySet()) {
            if (count >= maxVariants) {
                break;
            }
            count++;

            String name = entry.getKey();
            String macroCode = entry.getValue();
            String tempTitle = "explore_" + name.replaceAll("[^a-zA-Z0-9_]", "_");

            ExplorationResult result = new ExplorationResult();
            result.methodName = name;

            // Build macro: duplicate, select, then run provided code
            StringBuilder macro = new StringBuilder();
            macro.append("selectWindow(\"").append(escapeQuotes(originalTitle)).append("\");\n");
            macro.append("run(\"Duplicate...\", \"title=").append(escapeQuotes(tempTitle)).append("\");\n");
            macro.append("selectWindow(\"").append(escapeQuotes(tempTitle)).append("\");\n");
            macro.append(macroCode).append("\n");
            result.macroCode = macro.toString();

            temporaryImages.add(tempTitle);

            try {
                ExecutionResult execResult = commandEngine.executeMacro(result.macroCode);
                if (!execResult.isSuccess()) {
                    result.success = false;
                    result.summary = "Failed: " + execResult.getError();
                    closeImage(tempTitle);
                    results.add(result);
                    continue;
                }

                // Measure what we can
                result.coverage = measureCoverage(tempTitle);

                ImagePlus tempImp = WindowManager.getImage(tempTitle);
                if (tempImp != null && isBinaryImage(tempImp)) {
                    Map<String, Double> stats = getParticleStats(tempTitle);
                    result.objectCount = stats.containsKey("count") ? stats.get("count").intValue() : 0;
                    result.meanArea = stats.containsKey("meanArea") ? stats.get("meanArea") : 0.0;
                    result.meanCircularity = stats.containsKey("meanCircularity")
                            ? stats.get("meanCircularity") : 0.0;
                }

                if (tempImp != null) {
                    result.thumbnail = ImageCapture.captureImage(tempImp, 256);
                }

                result.success = true;
                result.summary = String.format("%s: %d objects, coverage=%.1f%%",
                        name, result.objectCount, result.coverage * 100.0);

            } catch (Exception e) {
                result.success = false;
                result.summary = "Error: " + e.getMessage();
            }

            closeImage(tempTitle);
            results.add(result);
        }

        selectImage(originalTitle);

        ExplorationReport report = new ExplorationReport();
        report.results = results;
        // For generic exploration, pick the one with best balance of metrics
        report.recommended = selectBestGeneric(results);
        if (report.recommended != null) {
            report.reasoning = "Selected " + report.recommended.methodName
                    + " based on overall metric quality.";
        } else {
            report.reasoning = "No successful variants found.";
        }

        return report;
    }

    /**
     * Close all temporary images created during exploration.
     */
    public void cleanup() {
        for (String title : temporaryImages) {
            closeImage(title);
        }
        temporaryImages.clear();
    }

    // -----------------------------------------------------------------------
    // Private metric extraction helpers
    // -----------------------------------------------------------------------

    /**
     * Measure the coverage of a binary image (fraction of white pixels).
     * Runs "Measure" on the image and reads Mean value; Mean/255 = coverage.
     */
    private double measureCoverage(String imageTitle) {
        String macro = "selectWindow(\"" + escapeQuotes(imageTitle) + "\");\n"
                + "run(\"Measure\");\n"
                + "mean = getResult(\"Mean\", nResults - 1);\n"
                + "print(mean);";

        ExecutionResult result = commandEngine.executeMacro(macro);
        if (!result.isSuccess()) {
            return 0.0;
        }

        String output = result.getOutput();
        if (output == null || output.trim().isEmpty()) {
            return 0.0;
        }

        try {
            double mean = Double.parseDouble(output.trim());
            return mean / 255.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Get particle analysis statistics from a binary image.
     * Runs Analyze Particles with summarize, then reads the Summary table.
     *
     * @return map with keys: "count", "meanArea", "meanCircularity"
     */
    private Map<String, Double> getParticleStats(String imageTitle) {
        Map<String, Double> stats = new LinkedHashMap<String, Double>();
        stats.put("count", 0.0);
        stats.put("meanArea", 0.0);
        stats.put("meanCircularity", 0.0);

        // Run Analyze Particles with summarize flag to populate the Summary table
        // Use display to also get individual results
        String macro = "selectWindow(\"" + escapeQuotes(imageTitle) + "\");\n"
                + "run(\"Set Measurements...\", \"area circularity redirect=None decimal=3\");\n"
                + "run(\"Analyze Particles...\", \"size=0-Infinity circularity=0.00-1.00 "
                + "show=Nothing display clear summarize\");\n";

        ExecutionResult result = commandEngine.executeMacro(macro);
        if (!result.isSuccess()) {
            return stats;
        }

        // Read from the Results table for individual particle metrics
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt != null && rt.getCounter() > 0) {
            int count = rt.getCounter();
            stats.put("count", (double) count);

            // Calculate mean area
            double totalArea = 0.0;
            boolean hasArea = rt.columnExists(ResultsTable.AREA);
            if (hasArea) {
                for (int row = 0; row < count; row++) {
                    totalArea += rt.getValueAsDouble(ResultsTable.AREA, row);
                }
                stats.put("meanArea", totalArea / count);
            }

            // Calculate mean circularity
            double totalCirc = 0.0;
            int circCol = rt.getColumnIndex("Circ.");
            if (circCol == ResultsTable.COLUMN_NOT_FOUND) {
                circCol = rt.getColumnIndex("Circularity");
            }
            if (circCol != ResultsTable.COLUMN_NOT_FOUND) {
                for (int row = 0; row < count; row++) {
                    totalCirc += rt.getValueAsDouble(circCol, row);
                }
                stats.put("meanCircularity", totalCirc / count);
            }
        }

        return stats;
    }

    /**
     * Check if an image appears to be binary (only 0 and 255 values).
     */
    private boolean isBinaryImage(ImagePlus imp) {
        if (imp == null) {
            return false;
        }
        // A quick heuristic: check the image type and a sample of pixels
        int type = imp.getType();
        if (type != ImagePlus.GRAY8) {
            return false;
        }
        byte[] pixels = (byte[]) imp.getProcessor().getPixels();
        // Sample up to 100 pixels
        int step = Math.max(1, pixels.length / 100);
        for (int i = 0; i < pixels.length; i += step) {
            int val = pixels[i] & 0xFF;
            if (val != 0 && val != 255) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Selection heuristics
    // -----------------------------------------------------------------------

    /**
     * Select the best threshold method based on heuristics:
     * - Not too few objects (>5) and not too many (suggests noise)
     * - Reasonable circularity (>0.3 for cells)
     * - Coverage between 5% and 60%
     * - If unclear, prefer the method with median object count
     */
    private ExplorationResult selectBestThreshold(List<ExplorationResult> results) {
        List<ExplorationResult> viable = new ArrayList<ExplorationResult>();
        for (ExplorationResult r : results) {
            if (r.success
                    && r.objectCount > 5
                    && r.meanCircularity > 0.3
                    && r.coverage > 0.05
                    && r.coverage < 0.60) {
                viable.add(r);
            }
        }

        if (viable.isEmpty()) {
            // Relax constraints: just pick any successful result
            for (ExplorationResult r : results) {
                if (r.success) {
                    viable.add(r);
                }
            }
        }

        if (viable.isEmpty()) {
            return null;
        }

        if (viable.size() == 1) {
            return viable.get(0);
        }

        // Sort by object count and pick the median
        Collections.sort(viable, new Comparator<ExplorationResult>() {
            @Override
            public int compare(ExplorationResult a, ExplorationResult b) {
                return Integer.compare(a.objectCount, b.objectCount);
            }
        });

        return viable.get(viable.size() / 2);
    }

    /**
     * Select the best parameter value. For parameter sweeps, prefer the middle
     * ground — not the most extreme coverage change.
     */
    private ExplorationResult selectBestParameter(List<ExplorationResult> results) {
        List<ExplorationResult> successful = new ArrayList<ExplorationResult>();
        for (ExplorationResult r : results) {
            if (r.success) {
                successful.add(r);
            }
        }
        if (successful.isEmpty()) {
            return null;
        }
        // Pick the one with median coverage
        Collections.sort(successful, new Comparator<ExplorationResult>() {
            @Override
            public int compare(ExplorationResult a, ExplorationResult b) {
                return Double.compare(a.coverage, b.coverage);
            }
        });
        return successful.get(successful.size() / 2);
    }

    /**
     * Select best result from generic exploration using a composite score.
     */
    private ExplorationResult selectBestGeneric(List<ExplorationResult> results) {
        // Reuse threshold heuristics for binary results; otherwise pick by coverage
        List<ExplorationResult> withObjects = new ArrayList<ExplorationResult>();
        List<ExplorationResult> successful = new ArrayList<ExplorationResult>();

        for (ExplorationResult r : results) {
            if (r.success) {
                successful.add(r);
                if (r.objectCount > 0) {
                    withObjects.add(r);
                }
            }
        }

        if (!withObjects.isEmpty()) {
            return selectBestThreshold(withObjects);
        }

        if (!successful.isEmpty()) {
            return selectBestParameter(successful);
        }

        return null;
    }

    /**
     * Build reasoning text explaining why a threshold method was chosen.
     */
    private String buildThresholdReasoning(ExplorationResult best, List<ExplorationResult> all) {
        StringBuilder sb = new StringBuilder();
        sb.append(best.methodName).append(" was selected because it yields ");
        sb.append(best.objectCount).append(" objects");
        sb.append(String.format(" with mean circularity %.3f", best.meanCircularity));
        sb.append(String.format(" and %.1f%% coverage", best.coverage * 100.0));
        sb.append(". ");

        // Note any excluded methods
        int failedCount = 0;
        int excludedCount = 0;
        for (ExplorationResult r : all) {
            if (!r.success) {
                failedCount++;
            } else if (r != best) {
                if (r.objectCount <= 5) {
                    excludedCount++;
                } else if (r.coverage < 0.05 || r.coverage > 0.60) {
                    excludedCount++;
                }
            }
        }
        if (failedCount > 0) {
            sb.append(failedCount).append(" method(s) failed. ");
        }
        if (excludedCount > 0) {
            sb.append(excludedCount).append(" method(s) excluded due to extreme object count or coverage.");
        }

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Utility methods
    // -----------------------------------------------------------------------

    /**
     * Close a temporary image by title.
     */
    private void closeImage(String title) {
        try {
            String macro = "if (isOpen(\"" + escapeQuotes(title) + "\")) {\n"
                    + "  selectWindow(\"" + escapeQuotes(title) + "\");\n"
                    + "  close();\n"
                    + "}\n";
            commandEngine.executeMacro(macro);
        } catch (Exception e) {
            IJ.log("[ExplorationEngine] Failed to close " + title + ": " + e.getMessage());
        }
    }

    /**
     * Re-select an image by title.
     */
    private void selectImage(String title) {
        try {
            String macro = "if (isOpen(\"" + escapeQuotes(title) + "\")) {\n"
                    + "  selectWindow(\"" + escapeQuotes(title) + "\");\n"
                    + "}\n";
            commandEngine.executeMacro(macro);
        } catch (Exception e) {
            IJ.log("[ExplorationEngine] Failed to select " + title + ": " + e.getMessage());
        }
    }

    /**
     * Escape quotes for use inside ImageJ macro strings.
     */
    private static String escapeQuotes(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Format a double as a clean string (no trailing zeros for integers).
     */
    private static String formatNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((int) value);
        }
        return String.valueOf(value);
    }
}
