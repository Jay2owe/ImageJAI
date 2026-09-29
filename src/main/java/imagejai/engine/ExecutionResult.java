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
    private final boolean resultsTableTruncated;
    private final long resultsTableOriginalBytes;
    private final int resultsTableReturnedBytes;
    private final int resultsTableTotalRows;
    private final int resultsTableReturnedRows;
    private final List<String> newImages;
    private final long executionTimeMs;

    private ExecutionResult(boolean success, String output, String error,
                            String resultsTable, List<String> newImages,
                            long executionTimeMs, boolean resultsTableTruncated,
                            long resultsTableOriginalBytes,
                            int resultsTableReturnedBytes,
                            int resultsTableTotalRows,
                            int resultsTableReturnedRows) {
        this.success = success;
        this.output = output;
        this.error = error;
        this.resultsTable = resultsTable;
        this.resultsTableTruncated = resultsTableTruncated;
        this.resultsTableOriginalBytes = resultsTableOriginalBytes;
        this.resultsTableReturnedBytes = resultsTableReturnedBytes;
        this.resultsTableTotalRows = resultsTableTotalRows;
        this.resultsTableReturnedRows = resultsTableReturnedRows;
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
        long bytes = utf8Length(resultsTable);
        return new ExecutionResult(true, output, null, resultsTable, newImages,
                executionTimeMs, false, bytes,
                (int) Math.min(bytes, Integer.MAX_VALUE), 0, 0);
    }

    public static ExecutionResult successWithBoundedResults(
            String output, StateInspector.BoundedCsv results,
            List<String> newImages, long executionTimeMs) {
        if (results == null) {
            return success(output, (String) null, newImages, executionTimeMs);
        }
        return new ExecutionResult(true, output, null, results.text(), newImages,
                executionTimeMs, results.truncated(), results.originalBytes(),
                results.returnedBytes(), results.totalRows(), results.returnedRows());
    }

    /**
     * Create a failure result.
     */
    public static ExecutionResult failure(String error, long executionTimeMs) {
        return new ExecutionResult(false, null, error, null, null, executionTimeMs,
                false, 0L, 0, 0, 0);
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

    public boolean isResultsTableTruncated() { return resultsTableTruncated; }
    public long getResultsTableOriginalBytes() { return resultsTableOriginalBytes; }
    public int getResultsTableReturnedBytes() { return resultsTableReturnedBytes; }
    public int getResultsTableTotalRows() { return resultsTableTotalRows; }
    public int getResultsTableReturnedRows() { return resultsTableReturnedRows; }

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

    private static long utf8Length(String value) {
        if (value == null || value.isEmpty()) return 0;
        long bytes = 0L;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            bytes += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            i += Character.charCount(cp);
        }
        return bytes;
    }
}
