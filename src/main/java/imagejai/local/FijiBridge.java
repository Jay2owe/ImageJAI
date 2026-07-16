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
import imagejai.engine.MutationCoordinator;
import imagejai.config.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Facade for direct Fiji/ImageJ access used by deterministic local intents.
 */
public class FijiBridge {

    private final CommandEngine commandEngine;
    private Settings settings;
    private AssistantMutationExecutor mutationExecutor;

    public FijiBridge(CommandEngine commandEngine) {
        this.commandEngine = commandEngine;
        configureGovernance(new Settings());
    }

    void configureGovernance(Settings settings) {
        configureGovernance(settings, null);
    }

    void configureGovernance(Settings settings, MutationCoordinator coordinator) {
        this.settings = settings == null ? new Settings() : settings;
        if (mutationExecutor != null) mutationExecutor.close();
        mutationExecutor = coordinator == null
                ? new AssistantMutationExecutor(
                        this.settings, "local-assistant", "local-assistant")
                : new AssistantMutationExecutor(
                        this.settings, "local-assistant", "local-assistant", coordinator);
    }

    void cancelActiveMutation() {
        if (mutationExecutor != null) mutationExecutor.cancelActive();
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
        if (commandEngine == null) {
            throw new IllegalStateException("ImageJ command engine is unavailable.");
        }
        ExecutionResult result = mutationExecutor.executeMacro(code,
                new MutationCoordinator.Operation<ExecutionResult>() {
                    @Override
                    public ExecutionResult run() {
                        return commandEngine.executeMacro(code);
                    }
                });
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getError());
        }
    }

    public AssistantReply executeIntent(final String intentId,
                                        final MutationCoordinator.Operation<AssistantReply> operation) {
        if (!mutationIntent(intentId)) {
            try {
                return operation.run();
            } catch (Exception e) {
                return AssistantReply.text("Action failed: " + e.getMessage());
            }
        }
        String auditCode = intentAuditCode(intentId);
        MutationCoordinator.Completion<AssistantReply> completion = mutationExecutor.execute(
                auditCode, operation, 120_000L,
                approvalRequired(intentId));
        if (completion.state() != MutationCoordinator.State.SUCCEEDED) {
            String message = completion.error() == null
                    ? completion.state().name() : completion.error().getMessage();
            return AssistantReply.text("Action blocked or failed: " + message);
        }
        return completion.result();
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
        if (commandEngine == null) {
            throw new IllegalStateException("ImageJ command engine is unavailable.");
        }
        if (mutationExecutor.isInsideMutation()) {
            return new ThresholdComparison(new ExplorationEngine(commandEngine)
                    .exploreThresholds(methods));
        }
        StringBuilder provenance = new StringBuilder("// Explore thresholds\n");
        if (methods != null) {
            for (String method : methods) {
                provenance.append("// method: ").append(method).append('\n');
            }
        }
        MutationCoordinator.Completion<ExplorationEngine.ExplorationReport> completion =
                mutationExecutor.execute(provenance.toString(),
                        new MutationCoordinator.Operation<ExplorationEngine.ExplorationReport>() {
                            @Override
                            public ExplorationEngine.ExplorationReport run() {
                                return new ExplorationEngine(commandEngine)
                                        .exploreThresholds(methods);
                            }
                        }, 120_000L);
        if (completion.state() != MutationCoordinator.State.SUCCEEDED) {
            String message = completion.error() == null
                    ? completion.state().name() : completion.error().getMessage();
            throw new IllegalStateException(message);
        }
        return new ThresholdComparison(completion.result());
    }

    private static String macroQuote(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean approvalRequired(String intentId) {
        String id = intentId == null ? "" : intentId.toLowerCase(java.util.Locale.ROOT);
        return id.startsWith("image.close")
                || "image.revert".equals(id)
                || "slash.close".equals(id);
    }

    private static boolean mutationIntent(String intentId) {
        String id = intentId == null ? "" : intentId.toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith("preprocess.") || id.startsWith("segmentation.")
                || id.startsWith("measurement.") || id.startsWith("display.")
                || id.startsWith("dialog.") || id.startsWith("roi.")
                || id.startsWith("results.")) {
            return true;
        }
        if (id.startsWith("diagnostics.")) {
            return !"diagnostics.memory".equals(id)
                    && !"diagnostics.plugins".equals(id)
                    && !"diagnostics.open_dialogs".equals(id);
        }
        if (id.startsWith("slash.")) return "slash.close".equals(id);
        if (!id.startsWith("image.")) return false;
        return !("image.title".equals(id)
                || "image.dimensions".equals(id)
                || "image.file_path".equals(id)
                || "image.bit_depth".equals(id)
                || "image.channel_count".equals(id)
                || "image.slice_count".equals(id)
                || "image.frame_count".equals(id)
                || "image.active_channel".equals(id)
                || "image.active_slice".equals(id)
                || "image.active_frame".equals(id)
                || "image.intensity_stats".equals(id)
                || "image.saturation_check".equals(id)
                || "image.list_open".equals(id)
                || "image.pixel_size".equals(id));
    }

    private static String intentAuditCode(String intentId) {
        String id = intentId == null ? "unknown" : intentId;
        if ("roi.clear".equals(id)) return "roiManager(\"Reset\");";
        if (id.startsWith("image.close") || "slash.close".equals(id)) return "close();";
        if ("image.revert".equals(id)) return "run(\"Revert\");";
        if ("image.crop".equals(id)) return "run(\"Crop\");";
        if ("image.invert".equals(id)) return "run(\"Invert\");";
        return "// Local Assistant intent: " + id;
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
