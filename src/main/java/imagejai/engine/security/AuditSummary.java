package imagejai.engine.security;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable summary of an ImageJAI audit CSV for the stage 06 PDF generator.
 */
public final class AuditSummary {
    private final Path sourcePath;
    private final int totalRows;
    private final Instant firstTimestampUtc;
    private final Instant lastTimestampUtc;
    private final long totalBytesOut;
    private final long totalBytesIn;
    private final int redactedRows;
    private final int visualGrantRows;
    private final int visualConsumeRows;
    private final int postureEventRows;
    private final int downshiftRows;
    private final Map<String, Integer> commandCounts;
    private final Map<String, Integer> postureCounts;
    private final Set<String> fieldsRedacted;

    public AuditSummary(Path sourcePath,
                        int totalRows,
                        Instant firstTimestampUtc,
                        Instant lastTimestampUtc,
                        long totalBytesOut,
                        long totalBytesIn,
                        int redactedRows,
                        int visualGrantRows,
                        int visualConsumeRows,
                        int postureEventRows,
                        Map<String, Integer> commandCounts,
                        Map<String, Integer> postureCounts,
                        Set<String> fieldsRedacted) {
        this(sourcePath, totalRows, firstTimestampUtc, lastTimestampUtc,
                totalBytesOut, totalBytesIn, redactedRows, visualGrantRows,
                visualConsumeRows, postureEventRows, postureEventRows,
                commandCounts, postureCounts, fieldsRedacted);
    }

    public AuditSummary(Path sourcePath,
                        int totalRows,
                        Instant firstTimestampUtc,
                        Instant lastTimestampUtc,
                        long totalBytesOut,
                        long totalBytesIn,
                        int redactedRows,
                        int visualGrantRows,
                        int visualConsumeRows,
                        int postureEventRows,
                        int downshiftRows,
                        Map<String, Integer> commandCounts,
                        Map<String, Integer> postureCounts,
                        Set<String> fieldsRedacted) {
        this.sourcePath = sourcePath;
        this.totalRows = Math.max(0, totalRows);
        this.firstTimestampUtc = firstTimestampUtc;
        this.lastTimestampUtc = lastTimestampUtc;
        this.totalBytesOut = Math.max(0L, totalBytesOut);
        this.totalBytesIn = Math.max(0L, totalBytesIn);
        this.redactedRows = Math.max(0, redactedRows);
        this.visualGrantRows = Math.max(0, visualGrantRows);
        this.visualConsumeRows = Math.max(0, visualConsumeRows);
        this.postureEventRows = Math.max(0, postureEventRows);
        this.downshiftRows = Math.max(0, downshiftRows);
        this.commandCounts = immutableMap(commandCounts);
        this.postureCounts = immutableMap(postureCounts);
        this.fieldsRedacted = immutableSet(fieldsRedacted);
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public int totalRows() {
        return totalRows;
    }

    public int rowCount() {
        return totalRows;
    }

    public Instant firstTimestampUtc() {
        return firstTimestampUtc;
    }

    public Instant first() {
        return firstTimestampUtc;
    }

    public Instant lastTimestampUtc() {
        return lastTimestampUtc;
    }

    public Instant last() {
        return lastTimestampUtc;
    }

    public long totalBytesOut() {
        return totalBytesOut;
    }

    public long totalBytesIn() {
        return totalBytesIn;
    }

    public int redactedRows() {
        return redactedRows;
    }

    public int pseudonymised() {
        return redactedRows;
    }

    public int pseudonymisedCount() {
        return redactedRows;
    }

    public int visualGrantRows() {
        return visualGrantRows;
    }

    public int visualOverrideGrants() {
        return visualGrantRows;
    }

    public int visualConsumeRows() {
        return visualConsumeRows;
    }

    public int postureEventRows() {
        return postureEventRows;
    }

    public int downshiftRows() {
        return downshiftRows;
    }

    public int downshifts() {
        return downshiftRows;
    }

    public Map<String, Integer> commandCounts() {
        return commandCounts;
    }

    public Map<String, Integer> postureCounts() {
        return postureCounts;
    }

    public Set<String> fieldsRedacted() {
        return fieldsRedacted;
    }

    private static Map<String, Integer> immutableMap(Map<String, Integer> values) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (values != null) {
            out.putAll(values);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Set<String> immutableSet(Set<String> values) {
        Set<String> out = new LinkedHashSet<String>();
        if (values != null) {
            out.addAll(values);
        }
        return Collections.unmodifiableSet(out);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuditSummary)) {
            return false;
        }
        AuditSummary that = (AuditSummary) other;
        return totalRows == that.totalRows
                && totalBytesOut == that.totalBytesOut
                && totalBytesIn == that.totalBytesIn
                && redactedRows == that.redactedRows
                && visualGrantRows == that.visualGrantRows
                && visualConsumeRows == that.visualConsumeRows
                && postureEventRows == that.postureEventRows
                && downshiftRows == that.downshiftRows
                && Objects.equals(sourcePath, that.sourcePath)
                && Objects.equals(firstTimestampUtc, that.firstTimestampUtc)
                && Objects.equals(lastTimestampUtc, that.lastTimestampUtc)
                && commandCounts.equals(that.commandCounts)
                && postureCounts.equals(that.postureCounts)
                && fieldsRedacted.equals(that.fieldsRedacted);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourcePath, totalRows, firstTimestampUtc,
                lastTimestampUtc, totalBytesOut, totalBytesIn, redactedRows,
                visualGrantRows, visualConsumeRows, postureEventRows, downshiftRows,
                commandCounts, postureCounts, fieldsRedacted);
    }
}
