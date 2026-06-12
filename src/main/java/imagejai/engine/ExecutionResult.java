package imagejai.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of an ImageJ macro execution.
 */
public class ExecutionResult {

    private final boolean success;
    private final String output;
    private final String error;
    private final String resultsTable;
    private final List<String> newImages;
    private final long executionTimeMs;

    private ExecutionResult(boolean success, String output, String error,
                            String resultsTable, List<String> newImages,
                            long executionTimeMs) {
        this.success = success;
        this.output = output;
        this.error = error;
        this.resultsTable = resultsTable;
        this.newImages = newImages != null
                ? Collections.unmodifiableList(new ArrayList<String>(newImages))
                : Collections.<String>emptyList();
        this.executionTimeMs = executionTimeMs;
    }

    /**
     * Create a successful result.
     */
    public static ExecutionResult success(String output, String resultsTable,
                                          List<String> newImages, long executionTimeMs) {
        return new ExecutionResult(true, output, null, resultsTable, newImages, executionTimeMs);
    }

    /**
     * Create a failure result.
     */
    public static ExecutionResult failure(String error, long executionTimeMs) {
        return new ExecutionResult(false, null, error, null, null, executionTimeMs);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }

    public String getResultsTable() {
        return resultsTable;
    }

    public List<String> getNewImages() {
        return newImages;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ExecutionResult{success=").append(success);
        sb.append(", time=").append(executionTimeMs).append("ms");
        if (success) {
            if (output != null && !output.isEmpty()) {
                sb.append(", output='").append(truncate(output, 100)).append("'");
            }
            if (!newImages.isEmpty()) {
                sb.append(", newImages=").append(newImages);
            }
            if (resultsTable != null && !resultsTable.isEmpty()) {
                sb.append(", hasResultsTable=true");
            }
        } else {
            sb.append(", error='").append(truncate(error, 200)).append("'");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
