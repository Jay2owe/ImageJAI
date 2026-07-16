package imagejai.engine.security;

import imagejai.config.PrivacyPosture;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

/**
 * Immutable row value for {@code AI_Exports/imagejai_audit.csv}.
 *
 * <p>This project currently compiles with Java 11 bytecode, so this class keeps
 * record-style accessors without using the Java {@code record} syntax.
 */
public final class AuditRow {
    public static final int MAX_REDACTED_PAYLOAD_BYTES = 8 * 1024;

    private final Instant timestampUtc;
    private final String sessionId;
    private final String command;
    private final PrivacyPosture posture;
    private final String modelEndpoint;
    private final String captureSource;
    private final int bytesOut;
    private final int bytesIn;
    private final String imageHash;
    private final boolean redactionApplied;
    private final List<String> fieldsRedacted;
    private final String notes;
    private final String redactedPayloadJson;

    public AuditRow(Instant timestampUtc,
                    String sessionId,
                    String command,
                    PrivacyPosture posture,
                    String modelEndpoint,
                    String captureSource,
                    int bytesOut,
                    int bytesIn,
                    String imageHash,
                    boolean redactionApplied,
                    List<String> fieldsRedacted,
                    String notes) {
        this(timestampUtc, sessionId, command, posture, modelEndpoint,
                captureSource, bytesOut, bytesIn, imageHash, redactionApplied,
                fieldsRedacted, notes, "");
    }

    public AuditRow(Instant timestampUtc,
                    String sessionId,
                    String command,
                    PrivacyPosture posture,
                    String modelEndpoint,
                    String captureSource,
                    int bytesOut,
                    int bytesIn,
                    String imageHash,
                    boolean redactionApplied,
                    List<String> fieldsRedacted,
                    String notes,
                    String redactedPayloadJson) {
        this.timestampUtc = timestampUtc == null ? Instant.now() : timestampUtc;
        this.sessionId = safe(sessionId);
        this.command = safe(command);
        this.posture = posture == null ? PrivacyPosture.defaultPosture() : posture;
        this.modelEndpoint = safe(modelEndpoint);
        this.captureSource = safe(captureSource);
        this.bytesOut = Math.max(0, bytesOut);
        this.bytesIn = Math.max(0, bytesIn);
        this.imageHash = safe(imageHash);
        this.redactionApplied = redactionApplied;
        this.fieldsRedacted = immutableCleanList(fieldsRedacted);
        this.notes = safe(notes);
        this.redactedPayloadJson = capRedactedPayload(redactedPayloadJson);
    }

    public Instant timestampUtc() {
        return timestampUtc;
    }

    public String sessionId() {
        return sessionId;
    }

    public String command() {
        return command;
    }

    public PrivacyPosture posture() {
        return posture;
    }

    public String modelEndpoint() {
        return modelEndpoint;
    }

    public String captureSource() {
        return captureSource;
    }

    public int bytesOut() {
        return bytesOut;
    }

    public int bytesIn() {
        return bytesIn;
    }

    public String imageHash() {
        return imageHash;
    }

    public boolean redactionApplied() {
        return redactionApplied;
    }

    public List<String> fieldsRedacted() {
        return fieldsRedacted;
    }

    public String notes() {
        return notes;
    }

    public String redactedPayloadJson() {
        return redactedPayloadJson;
    }

    public String toCsvLine() {
        List<String> cells = new ArrayList<String>(12);
        cells.add(timestampUtc.toString());
        cells.add(sessionId);
        cells.add(command);
        cells.add(posture.label());
        cells.add(modelEndpoint);
        cells.add(captureSource);
        cells.add(String.valueOf(bytesOut));
        cells.add(String.valueOf(bytesIn));
        cells.add(imageHash);
        cells.add(String.valueOf(redactionApplied));
        cells.add(joinFields(fieldsRedacted));
        cells.add(notes);

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(csv(cells.get(i)));
        }
        return out.toString();
    }

    public static AuditRow fromCsvLine(String line) {
        List<String> cells = parseCsvLine(line);
        if (cells.size() != 12) {
            throw new IllegalArgumentException("Expected 12 audit columns, got " + cells.size());
        }
        return new AuditRow(
                parseInstant(unprotectFormula(cells.get(0))),
                unprotectFormula(cells.get(1)),
                unprotectFormula(cells.get(2)),
                parsePosture(unprotectFormula(cells.get(3))),
                unprotectFormula(cells.get(4)),
                unprotectFormula(cells.get(5)),
                parseInt(unprotectFormula(cells.get(6))),
                parseInt(unprotectFormula(cells.get(7))),
                unprotectFormula(cells.get(8)),
                parseBoolean(unprotectFormula(cells.get(9))),
                splitFields(unprotectFormula(cells.get(10))),
                unprotectFormula(cells.get(11)));
    }

    public static String capRedactedPayload(String value) {
        String v = safe(value);
        if (v.getBytes(StandardCharsets.UTF_8).length <= MAX_REDACTED_PAYLOAD_BYTES) {
            return v;
        }
        String suffix = "\n...(truncated)...";
        int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        int limit = Math.max(0, MAX_REDACTED_PAYLOAD_BYTES - suffixBytes);
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < v.length();) {
            int cp = v.codePointAt(i);
            String chunk = new String(Character.toChars(cp));
            int bytes = chunk.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > limit) {
                break;
            }
            out.append(chunk);
            used += bytes;
            i += Character.charCount(cp);
        }
        out.append(suffix);
        return out.toString();
    }

    static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean quoteClosed = false;
        boolean atCellStart = true;
        String input = line == null ? "" : line;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                        quoteClosed = true;
                    }
                } else {
                    cell.append(c);
                }
            } else if (quoteClosed) {
                if (c == ',') {
                    cells.add(cell.toString());
                    cell.setLength(0);
                    quoteClosed = false;
                    atCellStart = true;
                } else if (c != '\r' && c != '\n') {
                    throw new IllegalArgumentException("unexpected character after closing quote");
                }
            } else if (c == '"' && atCellStart) {
                quoted = true;
                atCellStart = false;
            } else if (c == '"') {
                throw new IllegalArgumentException("quote inside unquoted field");
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
                atCellStart = true;
            } else if (c != '\r' && c != '\n') {
                cell.append(c);
                atCellStart = false;
            }
        }
        if (quoted) throw new IllegalArgumentException("unterminated quoted field");
        cells.add(cell.toString());
        return cells;
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid timestamp", e);
        }
    }

    private static PrivacyPosture parsePosture(String value) {
        if (value != null) {
            for (PrivacyPosture posture : PrivacyPosture.values()) {
                if (posture.label().equalsIgnoreCase(value)
                        || posture.name().equalsIgnoreCase(value.replace('-', '_'))) {
                    return posture;
                }
            }
        }
        throw new IllegalArgumentException("invalid privacy posture: " + value);
    }

    private static int parseInt(String value) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            if (parsed < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid non-negative integer: " + value, e);
        }
    }

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("invalid boolean: " + value);
    }

    private static List<String> splitFields(String value) {
        List<String> out = new ArrayList<String>();
        if (value == null || value.trim().isEmpty()) {
            return out;
        }
        String[] parts = value.split(";");
        for (String part : parts) {
            String cleaned = part == null ? "" : part.trim();
            if (!cleaned.isEmpty()) {
                out.add(cleaned);
            }
        }
        return out;
    }

    private static String joinFields(List<String> fields) {
        StringBuilder out = new StringBuilder();
        for (String field : fields) {
            if (field == null || field.trim().isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(field.trim());
        }
        return out.toString();
    }

    private static String csv(String value) {
        String v = safe(value);
        if (!v.isEmpty() && (v.charAt(0) == '=' || v.charAt(0) == '+'
                || v.charAt(0) == '-' || v.charAt(0) == '@')) {
            v = "'" + v;
        }
        boolean quote = v.contains(",") || v.contains("\"")
                || v.contains("\n") || v.contains("\r");
        if (!quote) {
            return v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String unprotectFormula(String value) {
        if (value != null && value.length() > 1 && value.charAt(0) == '\'') {
            char next = value.charAt(1);
            if (next == '=' || next == '+' || next == '-' || next == '@') {
                return value.substring(1);
            }
        }
        return value;
    }

    private static List<String> immutableCleanList(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                String cleaned = safe(value).trim();
                if (!cleaned.isEmpty()) {
                    out.add(cleaned);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuditRow)) {
            return false;
        }
        AuditRow row = (AuditRow) other;
        return bytesOut == row.bytesOut
                && bytesIn == row.bytesIn
                && redactionApplied == row.redactionApplied
                && timestampUtc.equals(row.timestampUtc)
                && sessionId.equals(row.sessionId)
                && command.equals(row.command)
                && posture == row.posture
                && modelEndpoint.equals(row.modelEndpoint)
                && captureSource.equals(row.captureSource)
                && imageHash.equals(row.imageHash)
                && fieldsRedacted.equals(row.fieldsRedacted)
                && notes.equals(row.notes)
                && redactedPayloadJson.equals(row.redactedPayloadJson);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestampUtc, sessionId, command, posture,
                modelEndpoint, captureSource, bytesOut, bytesIn, imageHash,
                redactionApplied, fieldsRedacted, notes, redactedPayloadJson);
    }
}
