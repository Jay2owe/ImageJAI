package imagejai.engine;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.RoiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tries processing variants on private image duplicates and compares their
 * results without leaving ImageJ's process-global UI and measurement state
 * changed.
 */
public class ExplorationEngine {

    public static class ExplorationResult {
        public String methodName;
        public String macroCode;
        public boolean success;
        public int objectCount;
        public double meanArea;
        public double meanCircularity;
        /** Binary foreground fraction. NaN when the result is not binary. */
        public double coverage = Double.NaN;
        public double metricValue = Double.NaN;
        public String metricLabel = "unavailable";
        public boolean binaryMask;
        public byte[] thumbnail;
        public String summary;
    }

    public static class ExplorationReport {
        public List<ExplorationResult> results;
        public ExplorationResult recommended;
        public String reasoning;

        public String formatComparison() {
            if (results == null || results.isEmpty()) return "No exploration results.";
            StringBuilder sb = new StringBuilder("=== Exploration Results ===\n\n");
            sb.append(String.format("%-18s %8s %12s %-24s  %s%n",
                    "Method", "Objects", "Mean Area", "Metric", "Status"));
            for (ExplorationResult result : results) {
                String marker = recommended != null && result.methodName.equals(recommended.methodName)
                        ? " <-- BEST" : "";
                if (!result.success) {
                    sb.append(String.format("%-18s %8s %12s %-24s  FAILED%s%n",
                            result.methodName, "-", "-", "-", marker));
                    continue;
                }
                String metric = Double.isNaN(result.metricValue)
                        ? result.metricLabel
                        : result.metricLabel + "=" + formatMetric(result);
                sb.append(String.format("%-18s %8d %12.1f %-24s  OK%s%n",
                        result.methodName, result.objectCount, result.meanArea, metric, marker));
            }
            if (recommended != null) sb.append("\nRecommended: ").append(recommended.methodName).append('\n');
            if (reasoning != null && !reasoning.isEmpty()) {
                sb.append("Reasoning: ").append(reasoning).append('\n');
            }
            return sb.toString();
        }

        private static String formatMetric(ExplorationResult result) {
            if ("binary coverage".equals(result.metricLabel)) {
                return String.format("%.1f%%", result.metricValue * 100.0);
            }
            return String.format("%.3f", result.metricValue);
        }
    }

    private enum Mode { THRESHOLD, PARAMETER, GENERIC }

    private static final class Variant {
        final String name;
        final String macro;
        final boolean requiresBinary;

        Variant(String name, String macro, boolean requiresBinary) {
            this.name = name;
            this.macro = macro;
            this.requiresBinary = requiresBinary;
        }
    }

    private static final class TemporaryImage {
        final String internalId;
        final ImagePlus image;

        TemporaryImage(String internalId, ImagePlus image) {
            this.internalId = internalId;
            this.image = image;
        }

        void closeExactObject() {
            if (image == null) return;
            try {
                image.changes = false;
                image.close();
            } catch (Throwable ignored) {
                // An unshown duplicate has no window to close; flushing it is enough.
            }
            try {
                image.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class GlobalState {
        final ImagePlus currentImage;
        final ResultsTable results;
        final boolean resultsPresent;
        final int measurements;
        final int precision;
        final boolean roiManagerPresent;
        final RoiManager roiManager;
        final List<byte[]> rois;

        private GlobalState(ImagePlus currentImage, ResultsTable results,
                            boolean resultsPresent, int measurements, int precision,
                            boolean roiManagerPresent, RoiManager roiManager,
                            List<byte[]> rois) {
            this.currentImage = currentImage;
            this.results = results;
            this.resultsPresent = resultsPresent;
            this.measurements = measurements;
            this.precision = precision;
            this.roiManagerPresent = roiManagerPresent;
            this.roiManager = roiManager;
            this.rois = rois;
        }

        static GlobalState capture() {
            ResultsTable liveResults = Analyzer.getResultsTable();
            RoiManager manager = RoiManager.getRawInstance();
            List<byte[]> encoded = new ArrayList<byte[]>();
            if (manager != null) {
                Roi[] liveRois = manager.getRoisAsArray();
                if (liveRois != null) {
                    for (Roi roi : liveRois) {
                        if (roi == null) continue;
                        try {
                            encoded.add(RoiEncoder.saveAsByteArray(roi));
                        } catch (Exception e) {
                            throw new IllegalStateException("Could not snapshot ROI Manager", e);
                        }
                    }
                }
            }
            return new GlobalState(WindowManager.getCurrentImage(), cloneTable(liveResults),
                    liveResults != null, Analyzer.getMeasurements(), Analyzer.getPrecision(),
                    manager != null, manager, encoded);
        }

        void restore() {
            Analyzer.setMeasurements(measurements);
            Analyzer.setPrecision(precision);
            Analyzer.setResultsTable(resultsPresent ? cloneTable(results) : null);

            RoiManager manager = roiManager != null ? roiManager : RoiManager.getRawInstance();
            if (roiManagerPresent) {
                if (manager == null) manager = new RoiManager(true);
                manager.reset();
                for (byte[] bytes : rois) {
                    Roi roi = RoiDecoder.openFromByteArray(bytes);
                    if (roi == null) throw new IllegalStateException("Could not restore ROI Manager");
                    manager.addRoi(roi);
                }
            } else if (manager != null) {
                manager.reset();
                manager.close();
            }

            if (currentImage != null && currentImage.getWindow() != null) {
                WindowManager.setCurrentWindow(currentImage.getWindow());
                WindowManager.setTempCurrentImage(null);
            } else {
                WindowManager.setTempCurrentImage(currentImage);
            }
        }
    }

    private static final int MEMORY_WARNING_THRESHOLD_MB = 200;
    private static final int MAX_EXPLORATION_VARIANTS = 12;

    private final CommandEngine commandEngine;
    private final StateInspector stateInspector;
    private final List<TemporaryImage> temporaryObjects = new ArrayList<TemporaryImage>();

    public ExplorationEngine(CommandEngine commandEngine) {
        if (commandEngine == null) throw new IllegalArgumentException("commandEngine is required");
        this.commandEngine = commandEngine;
        this.stateInspector = new StateInspector();
    }

    public ExplorationReport exploreThresholds(String[] methods) {
        boolean explicit = methods != null && methods.length > 0;
        String[] selected = explicit ? methods
                : new String[]{"Otsu", "Triangle", "Li", "Huang", "MaxEntropy", "Moments"};
        List<Variant> variants = new ArrayList<Variant>();
        for (String method : selected) {
            String macro = "if (bitDepth() != 8) run(\"8-bit\");\n"
                    + "setAutoThreshold(\"" + escapeQuotes(method) + "\");\n"
                    + "run(\"Convert to Mask\");\n";
            variants.add(new Variant(method, macro, true));
        }
        ExplorationReport report = runTransactional(variants, explicit, Mode.THRESHOLD);
        report.recommended = selectBestThreshold(report.results);
        report.reasoning = report.recommended == null
                ? "No successful threshold methods found."
                : buildThresholdReasoning(report.recommended, report.results);
        return report;
    }

    public ExplorationReport exploreParameter(String macroTemplate, String paramName,
                                               double[] values) {
        if (values == null || values.length == 0) return emptyReport("No parameter values to explore.");
        if (macroTemplate == null) return emptyReport("No macro template to explore.");
        List<Variant> variants = new ArrayList<Variant>();
        for (double value : values) {
            String valueText = formatNumber(value);
            variants.add(new Variant(paramName + "=" + valueText,
                    macroTemplate.replace("${value}", valueText), false));
        }
        ExplorationReport report = runTransactional(variants, true, Mode.PARAMETER);
        report.recommended = selectBestMetric(report.results);
        report.reasoning = report.recommended == null ? "No successful parameter values found."
                : "Selected " + report.recommended.methodName + " by its reported comparison metric.";
        return report;
    }

    public ExplorationReport explore(Map<String, String> namedMacros) {
        if (namedMacros == null || namedMacros.isEmpty()) return emptyReport("No macro variants to explore.");
        List<Variant> variants = new ArrayList<Variant>();
        for (Map.Entry<String, String> entry : namedMacros.entrySet()) {
            variants.add(new Variant(entry.getKey(), entry.getValue(), false));
        }
        ExplorationReport report = runTransactional(variants, true, Mode.GENERIC);
        report.recommended = selectBestGeneric(report.results);
        report.reasoning = report.recommended == null ? "No successful variants found."
                : "Selected " + report.recommended.methodName + " by its reported comparison metric.";
        return report;
    }

    private synchronized ExplorationReport runTransactional(List<Variant> requested,
                                                             boolean explicit,
                                                             Mode mode) {
        ImagePlus source = WindowManager.getCurrentImage();
        if (source == null) return emptyReport("No active image to explore.");

        int limit = Math.min(requested.size(), MAX_EXPLORATION_VARIANTS);
        if (!explicit && stateInspector.getMemoryInfo().getFreeMB() < MEMORY_WARNING_THRESHOLD_MB) {
            IJ.log("[ExplorationEngine] Low memory. Limiting exploration variants.");
            limit = Math.min(limit, 3);
        }

        GlobalState globals = GlobalState.capture();
        List<ExplorationResult> results = new ArrayList<ExplorationResult>();
        try {
            for (int i = 0; i < limit; i++) {
                results.add(runVariant(source, requested.get(i), mode));
            }
        } finally {
            globals.restore();
        }
        ExplorationReport report = new ExplorationReport();
        report.results = results;
        return report;
    }

    private ExplorationResult runVariant(ImagePlus source, Variant variant, Mode mode) {
        ExplorationResult result = new ExplorationResult();
        result.methodName = variant.name;
        result.macroCode = variant.macro;

        String id = UUID.randomUUID().toString();
        ImagePlus duplicate = source.duplicate();
        duplicate.setTitle("__ijai_explore_" + id);
        TemporaryImage temporary = new TemporaryImage(id, duplicate);
        temporaryObjects.add(temporary);
        try {
            ExecutionResult execution = commandEngine.executeMacroOnImage(variant.macro, duplicate);
            if (!execution.isSuccess()) {
                result.summary = "Failed: " + execution.getError();
                return result;
            }

            result.binaryMask = isBinaryImage(duplicate);
            if (variant.requiresBinary && !result.binaryMask) {
                result.summary = "Failed: result is not a complete 8-bit binary mask";
                return result;
            }

            if (result.binaryMask) {
                result.coverage = binaryCoverage(duplicate);
                result.metricValue = result.coverage;
                result.metricLabel = "binary coverage";
                Map<String, Double> particles = getParticleStats(duplicate);
                result.objectCount = particles.get("count").intValue();
                result.meanArea = particles.get("meanArea");
                result.meanCircularity = particles.get("meanCircularity");
            } else {
                result.metricValue = meanIntensity(duplicate);
                result.metricLabel = "mean intensity";
            }
            result.thumbnail = ImageCapture.captureImage(duplicate, 256);
            result.success = true;
            result.summary = summarize(result, mode);
            return result;
        } catch (Throwable t) {
            result.summary = "Error: " + safeMessage(t);
            IJ.log("[ExplorationEngine] " + variant.name + " failed: " + safeMessage(t));
            return result;
        } finally {
            temporary.closeExactObject();
        }
    }

    private Map<String, Double> getParticleStats(ImagePlus image) {
        Map<String, Double> stats = new LinkedHashMap<String, Double>();
        stats.put("count", 0.0);
        stats.put("meanArea", 0.0);
        stats.put("meanCircularity", 0.0);
        String macro = "run(\"Set Measurements...\", \"area circularity redirect=None decimal=3\");\n"
                + "run(\"Analyze Particles...\", \"size=0-Infinity circularity=0.00-1.00 "
                + "show=Nothing display clear summarize\");\n";
        ExecutionResult execution = commandEngine.executeMacroOnImage(macro, image);
        if (!execution.isSuccess()) return stats;

        ResultsTable table = Analyzer.getResultsTable();
        if (table == null || table.getCounter() == 0) return stats;
        int count = table.getCounter();
        stats.put("count", (double) count);
        if (table.columnExists(ResultsTable.AREA)) {
            double sum = 0.0;
            for (int row = 0; row < count; row++) sum += table.getValueAsDouble(ResultsTable.AREA, row);
            stats.put("meanArea", sum / count);
        }
        int circularity = table.getColumnIndex("Circ.");
        if (circularity == ResultsTable.COLUMN_NOT_FOUND) circularity = table.getColumnIndex("Circularity");
        if (circularity != ResultsTable.COLUMN_NOT_FOUND) {
            double sum = 0.0;
            for (int row = 0; row < count; row++) sum += table.getValueAsDouble(circularity, row);
            stats.put("meanCircularity", sum / count);
        }
        return stats;
    }

    static boolean isBinaryImage(ImagePlus image) {
        if (image == null || image.getType() != ImagePlus.GRAY8) return false;
        ImageStack stack = image.getStack();
        if (stack == null || stack.getSize() == 0) return false;
        for (int plane = 1; plane <= stack.getSize(); plane++) {
            Object raw = stack.getPixels(plane);
            if (!(raw instanceof byte[])) return false;
            byte[] pixels = (byte[]) raw;
            for (byte pixel : pixels) {
                int value = pixel & 0xff;
                if (value != 0 && value != 255) return false;
            }
        }
        return true;
    }

    private static double binaryCoverage(ImagePlus image) {
        long white = 0L;
        long total = 0L;
        ImageStack stack = image.getStack();
        for (int plane = 1; plane <= stack.getSize(); plane++) {
            byte[] pixels = (byte[]) stack.getPixels(plane);
            total += pixels.length;
            for (byte pixel : pixels) if ((pixel & 0xff) == 255) white++;
        }
        return total == 0L ? Double.NaN : (double) white / (double) total;
    }

    private static double meanIntensity(ImagePlus image) {
        ImageStack stack = image.getStack();
        double sum = 0.0;
        long count = 0L;
        for (int plane = 1; plane <= stack.getSize(); plane++) {
            int size = stack.getProcessor(plane).getPixelCount();
            for (int i = 0; i < size; i++) sum += stack.getProcessor(plane).getf(i);
            count += size;
        }
        return count == 0L ? Double.NaN : sum / count;
    }

    public synchronized void cleanup() {
        for (TemporaryImage temporary : temporaryObjects) temporary.closeExactObject();
        temporaryObjects.clear();
    }

    int trackedTemporaryCountForTest() {
        return temporaryObjects.size();
    }

    private ExplorationResult selectBestThreshold(List<ExplorationResult> results) {
        List<ExplorationResult> viable = new ArrayList<ExplorationResult>();
        for (ExplorationResult result : results) {
            if (result.success && result.binaryMask && result.objectCount > 5
                    && result.meanCircularity > 0.3 && result.coverage > 0.05
                    && result.coverage < 0.60) viable.add(result);
        }
        if (viable.isEmpty()) {
            for (ExplorationResult result : results) {
                if (result.success && result.binaryMask) viable.add(result);
            }
        }
        if (viable.isEmpty()) return null;
        Collections.sort(viable, Comparator.comparingInt(value -> value.objectCount));
        return viable.get(viable.size() / 2);
    }

    private ExplorationResult selectBestMetric(List<ExplorationResult> results) {
        List<ExplorationResult> valid = new ArrayList<ExplorationResult>();
        for (ExplorationResult result : results) {
            if (result.success && !Double.isNaN(result.metricValue)) valid.add(result);
        }
        if (valid.isEmpty()) return null;
        Collections.sort(valid, Comparator.comparingDouble(value -> value.metricValue));
        return valid.get(valid.size() / 2);
    }

    private ExplorationResult selectBestGeneric(List<ExplorationResult> results) {
        List<ExplorationResult> withObjects = new ArrayList<ExplorationResult>();
        for (ExplorationResult result : results) {
            if (result.success && result.binaryMask && result.objectCount > 0) withObjects.add(result);
        }
        return withObjects.isEmpty() ? selectBestMetric(results) : selectBestThreshold(withObjects);
    }

    private static String summarize(ExplorationResult result, Mode mode) {
        if (result.binaryMask) {
            return String.format("%s: %d objects, mean area=%.1f, circularity=%.3f, binary coverage=%.1f%%",
                    result.methodName, result.objectCount, result.meanArea,
                    result.meanCircularity, result.coverage * 100.0);
        }
        return String.format("%s: mean intensity=%.3f", result.methodName, result.metricValue);
    }

    private static String buildThresholdReasoning(ExplorationResult best,
                                                   List<ExplorationResult> all) {
        int failed = 0;
        for (ExplorationResult result : all) if (!result.success) failed++;
        String reasoning = String.format("%s produced %d objects with %.3f mean circularity and %.1f%% binary coverage.",
                best.methodName, best.objectCount, best.meanCircularity, best.coverage * 100.0);
        return failed == 0 ? reasoning : reasoning + " " + failed + " method(s) failed validation.";
    }

    private static ExplorationReport emptyReport(String reason) {
        ExplorationReport report = new ExplorationReport();
        report.results = Collections.emptyList();
        report.reasoning = reason;
        return report;
    }

    private static ResultsTable cloneTable(ResultsTable table) {
        return table == null ? null : (ResultsTable) table.clone();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private static String escapeQuotes(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String formatNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) return String.valueOf((int) value);
        return String.valueOf(value);
    }
}
