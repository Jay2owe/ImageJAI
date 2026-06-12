package imagejai.engine.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import imagejai.config.PrivacyPosture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Outbound counterpart to {@link AgentContextSanitizer}. In Pseudonymised and
 * On-premises modes it tokenises identifiers before JSON leaves the JVM.
 */
public class PseudonymisationFilter {
    private static final PseudonymisationFilter INSTANCE = new PseudonymisationFilter(
            PathTokenMap.getInstance(),
            new OmeXmlScrubber(),
            new CaptureHandler(new BurnInDetector(), VisualOverrideRegistry.getInstance()));

    private static final Pattern NUMERIC = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern IMAGE_EXTENSION = Pattern.compile(
            "(?i).+\\.(?:lif|tif|tiff|czi|nd2|lsm|oib|oif|vsi|svs|png|jpe?g|ome\\.tif|ome\\.tiff|csv|tsv|md|pdf|json|xml|txt)$");
    private static final Pattern PATH_SUBSTRING = Pattern.compile(
            "(?i)([A-Za-z]:[\\\\/][^\\r\\n\"'<>|]+?\\.(?:lif|tif|tiff|czi|nd2|lsm|oib|oif|vsi|svs|png|jpe?g|csv|tsv|md|pdf|json|xml|txt)"
                    + "|/[A-Za-z0-9._~+()\\- /]+?\\.(?:lif|tif|tiff|czi|nd2|lsm|oib|oif|vsi|svs|png|jpe?g|csv|tsv|md|pdf|json|xml|txt)"
                    + "|\\b(?!image-[0-9a-f]{4}\\b)[A-Za-z0-9._+()\\- ]+\\.(?:lif|tif|tiff|czi|nd2|lsm|oib|oif|vsi|svs|png|jpe?g|csv|tsv|md|pdf|json|xml|txt))");
    private static final Pattern PATH_TOKEN = Pattern.compile(
            "(?i)image-[0-9a-f]{4,12}(?:\\.[A-Za-z0-9.]+)?(?::\\d+)?");
    private static final String[] PATH_EXTENSIONS = {
            ".lif", ".tif", ".tiff", ".czi", ".nd2", ".lsm", ".oib", ".oif",
            ".vsi", ".svs", ".png", ".jpg", ".jpeg", ".csv", ".tsv", ".md",
            ".pdf", ".json", ".xml", ".txt"
    };

    private final PathTokenMap pathTokenMap;
    private final OmeXmlScrubber omeXmlScrubber;
    private final CaptureHandler captureHandler;

    public PseudonymisationFilter(PathTokenMap pathTokenMap,
                                  OmeXmlScrubber omeXmlScrubber,
                                  CaptureHandler captureHandler) {
        this.pathTokenMap = pathTokenMap == null ? PathTokenMap.getInstance() : pathTokenMap;
        this.omeXmlScrubber = omeXmlScrubber == null ? new OmeXmlScrubber() : omeXmlScrubber;
        this.captureHandler = captureHandler == null
                ? new CaptureHandler(new BurnInDetector(), VisualOverrideRegistry.getInstance())
                : captureHandler;
    }

    public static PseudonymisationFilter getInstance() {
        return INSTANCE;
    }

    public PathTokenMap pathTokenMap() {
        return pathTokenMap;
    }

    public RedactionReport apply(JsonObject response, String command,
                                 PrivacyPosture posture, String sessionId) {
        PrivacyPosture effective = posture == null ? PrivacyPosture.defaultPosture() : posture;
        if (effective == PrivacyPosture.STANDARD) {
            return RedactionReport.passthrough(command);
        }
        RedactionReport.Builder report = RedactionReport.builder()
                .command(command)
                .posture(effective);
        try {
            beforeFiltering(response, command, effective);
            if (response == null) {
                return report.build();
            }
            if ("capture_image".equals(command)) {
                captureHandler.apply(response, effective, sessionId, report);
            }
            scrubOmeXml(response, report);
            tokeniseResultsTables(response, command, report);
            tokenisePathTypedFields(response, report);
            freeTextScrub(response, report);
            RedactionReport built = report.build();
            response.add("_governance", built.governanceBlock());
            return built;
        } catch (Exception e) {
            return failClosed(response, command, effective, report);
        }
    }

    protected void beforeFiltering(JsonObject response, String command,
                                   PrivacyPosture posture) {
    }

    public void attachGovernanceIfNeeded(JsonObject response, String command,
                                         PrivacyPosture posture) {
        if (response == null || posture == null || posture == PrivacyPosture.STANDARD
                || response.has("_governance")) {
            return;
        }
        RedactionReport report = RedactionReport.builder()
                .command(command)
                .posture(posture)
                .build();
        response.add("_governance", report.governanceBlock());
    }

    /**
     * Inbound counterpart to {@link #apply}: turn a pseudonymised string the
     * agent is sending back to Fiji into the real string Fiji actually knows.
     *
     * <p>Outbound, {@link #apply} replaces the path portion of a window title
     * ({@code H31L21.lif - SCN}) with a token ({@code image-30e3.lif - SCN}).
     * Without the reverse step a macro the agent builds from that title —
     * {@code selectWindow("image-30e3.lif - SCN")} — can never match the real
     * window, so every subsequent interaction with the image fails. Here we
     * replace each resolvable path token ({@code image-XXXX[.ext][:N]}) with
     * its real path; unknown tokens are left untouched.
     *
     * <p>The reversed value never leaves the JVM: it flows straight into
     * Fiji's macro interpreter, and any echo of it in the reply
     * ({@code ranCode}, error text, provenance macros) is re-tokenised by
     * {@link #apply} before the response is written. The privacy guarantee is
     * therefore preserved end-to-end.
     */
    public String reverseResolveText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = PATH_TOKEN.matcher(text);
        StringBuffer out = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String token = matcher.group();
            PathTokenMap.ResolvedTarget resolved =
                    pathTokenMap.resolve(token).orElse(null);
            if (resolved != null && resolved.realPath() != null) {
                matcher.appendReplacement(out,
                        Matcher.quoteReplacement(resolved.realPath().toString()));
                changed = true;
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(token));
            }
        }
        matcher.appendTail(out);
        return changed ? out.toString() : text;
    }

    /**
     * Reverse pseudonym tokens in the inbound request's executable fields so
     * macros and rewind targets reach Fiji with the titles it actually knows.
     *
     * <p>Deliberately scoped to commands that run code against live windows.
     * Persistence commands (e.g. {@code intent_teach}, which writes its macro
     * to disk) are excluded so a real path can never be reversed back and then
     * written out of the JVM.
     */
    public void deTokeniseRequest(JsonObject request, String command,
                                  PrivacyPosture posture) {
        if (request == null || command == null || posture == null
                || posture == PrivacyPosture.STANDARD) {
            return;
        }
        switch (command) {
            case "execute_macro":
            case "execute_macro_async":
            case "run_script":
                reverseStringField(request, "code");
                break;
            case "run_pipeline":
                JsonElement steps = request.get("steps");
                if (steps != null && steps.isJsonArray()) {
                    for (JsonElement step : steps.getAsJsonArray()) {
                        if (step != null && step.isJsonObject()) {
                            reverseStringField(step.getAsJsonObject(), "code");
                        }
                    }
                }
                break;
            case "rewind":
                reverseStringField(request, "image_title");
                break;
            default:
                break;
        }
    }

    private void reverseStringField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            String reversed = reverseResolveText(value);
            if (!reversed.equals(value)) {
                object.addProperty(key, reversed);
            }
        }
    }

    private RedactionReport failClosed(JsonObject response, String command,
                                       PrivacyPosture posture,
                                       RedactionReport.Builder report) {
        if (response != null) {
            clear(response);
            response.addProperty("ok", false);
            response.addProperty("error", "redaction_failed");
        }
        RedactionReport failed = report.command(command)
                .posture(posture)
                .failed(true)
                .fieldPseudonymised("redaction_failed")
                .build();
        if (response != null) {
            response.add("_governance", failed.governanceBlock());
        }
        return failed;
    }

    private void scrubOmeXml(JsonElement element, RedactionReport.Builder report) {
        if (element == null || element instanceof JsonNull) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : keys(object)) {
                JsonElement child = object.get(key);
                if (child != null && child.isJsonPrimitive()
                        && child.getAsJsonPrimitive().isString()) {
                    String value = child.getAsString();
                    if (looksLikeOmeField(key, value)) {
                        String scrubbed = omeXmlScrubber.scrub(value);
                        if (!scrubbed.equals(value)) {
                            object.addProperty(key, scrubbed);
                            report.fieldPseudonymised("ome_xml");
                        }
                    }
                } else {
                    scrubOmeXml(child, report);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                scrubOmeXml(child, report);
            }
        }
    }

    private void tokeniseResultsTables(JsonObject response, String command,
                                       RedactionReport.Builder report) {
        if ("get_results_table".equals(command)) {
            JsonElement result = response.get("result");
            if (result != null && result.isJsonPrimitive()
                    && result.getAsJsonPrimitive().isString()) {
                String tokenised = tokeniseResultsCsv(result.getAsString(), report);
                if (!tokenised.equals(result.getAsString())) {
                    response.addProperty("result", tokenised);
                }
            }
        }
        tokeniseResultsTablesRecursive(response, report);
    }

    private void tokeniseResultsTablesRecursive(JsonElement element,
                                                RedactionReport.Builder report) {
        if (element == null || element instanceof JsonNull) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : keys(object)) {
                JsonElement child = object.get(key);
                if (child != null && child.isJsonPrimitive()
                        && child.getAsJsonPrimitive().isString()
                        && "resultsTable".equalsIgnoreCase(key)) {
                    String value = child.getAsString();
                    String tokenised = tokeniseResultsCsv(value, report);
                    if (!tokenised.equals(value)) {
                        object.addProperty(key, tokenised);
                    }
                } else if ("results".equalsIgnoreCase(key) && child != null
                        && child.isJsonArray()) {
                    tokeniseResultsArray(child.getAsJsonArray(), report);
                } else {
                    tokeniseResultsTablesRecursive(child, report);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                tokeniseResultsTablesRecursive(child, report);
            }
        }
    }

    private void tokeniseResultsArray(JsonArray array, RedactionReport.Builder report) {
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            for (String key : keys(row)) {
                JsonElement value = row.get(key);
                if (value == null || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()) {
                    continue;
                }
                if ("Label".equalsIgnoreCase(key)) {
                    row.addProperty(key, pathTokenMap.tokenForSensitiveText(
                            value.getAsString(), "label"));
                    report.fieldPseudonymised("results_table");
                } else if ("Slice".equalsIgnoreCase(key) && shouldTokeniseSlice(value.getAsString())) {
                    row.addProperty(key, tokenForSlice(value.getAsString()));
                    report.fieldPseudonymised("results_table");
                }
            }
        }
    }

    private String tokeniseResultsCsv(String csv, RedactionReport.Builder report) {
        if (csv == null || csv.trim().isEmpty()) {
            return csv;
        }
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) {
            return csv;
        }
        List<String> header = rows.get(0);
        int labelIndex = indexOfIgnoreCase(header, "Label");
        int sliceIndex = indexOfIgnoreCase(header, "Slice");
        if (labelIndex < 0 && sliceIndex < 0) {
            return csv;
        }
        boolean changed = false;
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (labelIndex >= 0 && labelIndex < row.size()) {
                String value = row.get(labelIndex);
                if (value != null && !value.isEmpty()) {
                    row.set(labelIndex, pathTokenMap.tokenForSensitiveText(value, "label"));
                    changed = true;
                }
            }
            if (sliceIndex >= 0 && sliceIndex < row.size()) {
                String value = row.get(sliceIndex);
                if (shouldTokeniseSlice(value)) {
                    row.set(sliceIndex, tokenForSlice(value));
                    changed = true;
                }
            }
        }
        if (changed) {
            report.fieldPseudonymised("results_table");
        }
        return changed ? writeCsv(rows) : csv;
    }

    private void tokenisePathTypedFields(JsonElement element,
                                         RedactionReport.Builder report) {
        tokenisePathTypedFields(element, "", report);
    }

    private void tokenisePathTypedFields(JsonElement element, String key,
                                         RedactionReport.Builder report) {
        if (element == null || element instanceof JsonNull) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String childKey : keys(object)) {
                JsonElement child = object.get(childKey);
                if (child != null && child.isJsonPrimitive()
                        && child.getAsJsonPrimitive().isString()
                        && !isBinaryField(childKey)) {
                    String value = child.getAsString();
                    String replaced = tokeniseStringForPathFields(childKey, value, report);
                    if (!value.equals(replaced)) {
                        object.addProperty(childKey, replaced);
                    }
                } else {
                    tokenisePathTypedFields(child, childKey, report);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement child = array.get(i);
                if (child != null && child.isJsonPrimitive()
                        && child.getAsJsonPrimitive().isString()
                        && !isBinaryField(key)) {
                    String value = child.getAsString();
                    String replaced = tokeniseStringForPathFields(key, value, report);
                    if (!value.equals(replaced)) {
                        array.set(i, new JsonPrimitive(replaced));
                    }
                } else {
                    tokenisePathTypedFields(child, key, report);
                }
            }
        }
    }

    private String tokeniseStringForPathFields(String key, String value,
                                               RedactionReport.Builder report) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (isPathTypedKey(key) && isPathLike(value) && isCleanPathValue(value)) {
            report.fieldPseudonymised("path");
            return pathTokenMap.tokenForPathString(value);
        }
        String registered = freeTextScrubString(value);
        if (!registered.equals(value)) {
            report.fieldPseudonymised("path");
            if (!hasPathLikeCueOutsideTokens(registered)) {
                return registered;
            }
            value = registered;
        }
        String replaced = replacePathLikeSubstrings(value, report);
        if (!replaced.equals(value)) {
            report.fieldPseudonymised("path");
        }
        return replaced;
    }

    private String replacePathLikeSubstrings(String value, RedactionReport.Builder report) {
        Matcher matcher = PATH_SUBSTRING.matcher(value);
        StringBuffer out = new StringBuffer();
        Map<String, String> replacements = new java.util.HashMap<String, String>();
        boolean changed = false;
        while (matcher.find()) {
            String match = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (looksLikePathToken(match)
                    || (couldBeInsidePathToken(value, start, end)
                    && isInsidePathToken(value, start, end))) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(match));
                continue;
            }
            String trimmed = match.trim();
            String token = replacements.get(trimmed);
            if (token == null) {
                token = pathTokenMap.tokenForPathString(trimmed);
                replacements.put(trimmed, token);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(token));
            changed = true;
        }
        matcher.appendTail(out);
        if (changed) {
            report.fieldPseudonymised("path");
        }
        return out.toString();
    }

    private void freeTextScrub(JsonElement element, RedactionReport.Builder report) {
        if (element == null || element instanceof JsonNull) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : keys(object)) {
                JsonElement child = object.get(key);
                if (child != null && child.isJsonPrimitive()
                        && child.getAsJsonPrimitive().isString()
                        && !isBinaryField(key)) {
                    String value = child.getAsString();
                    String scrubbed = freeTextScrubString(value);
                    if (!scrubbed.equals(value)) {
                        object.addProperty(key, scrubbed);
                        report.fieldPseudonymised("free_text");
                    }
                } else {
                    freeTextScrub(child, report);
                }
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement child = array.get(i);
                if (child != null && child.isJsonPrimitive()
                        && child.getAsJsonPrimitive().isString()) {
                    String value = child.getAsString();
                    String scrubbed = freeTextScrubString(value);
                    if (!scrubbed.equals(value)) {
                        array.set(i, new JsonPrimitive(scrubbed));
                        report.fieldPseudonymised("free_text");
                    }
                } else {
                    freeTextScrub(child, report);
                }
            }
        }
    }

    public String freeTextScrubString(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        List<Map.Entry<String, String>> entries =
                pathTokenMap.snapshotSensitiveStringsLongestFirst();
        if (entries.isEmpty()) {
            return value;
        }
        boolean possibleMatch = false;
        for (Map.Entry<String, String> entry : entries) {
            String original = entry.getKey();
            if (original != null && !original.isEmpty()
                    && value.indexOf(original) >= 0) {
                possibleMatch = true;
                break;
            }
        }
        if (!possibleMatch) {
            return value;
        }

        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        Matcher tokenMatcher = PATH_TOKEN.matcher(value);
        boolean hasToken = tokenMatcher.find();
        while (i < value.length()) {
            if (hasToken && tokenMatcher.start() == i) {
                out.append(value, tokenMatcher.start(), tokenMatcher.end());
                i = tokenMatcher.end();
                hasToken = tokenMatcher.find();
                continue;
            }
            Map.Entry<String, String> match = null;
            for (Map.Entry<String, String> entry : entries) {
                String original = entry.getKey();
                if (original == null || original.isEmpty()) {
                    continue;
                }
                if (startsWithAt(value, original, i)) {
                    match = entry;
                    break;
                }
            }
            if (match != null) {
                out.append(match.getValue());
                i += match.getKey().length();
            } else {
                out.append(value.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static boolean startsWithAt(String value, String needle, int index) {
        int n = needle.length();
        if (index < 0 || n == 0 || index + n > value.length()) {
            return false;
        }
        return value.regionMatches(index, needle, 0, n);
    }

    private static boolean looksLikeOmeField(String key, String value) {
        if (key != null) {
            String k = key.toLowerCase();
            if (k.contains("ome") || "info".equals(k) || k.contains("metadata")) {
                return true;
            }
        }
        return value != null && value.contains("<") && value.matches("(?is).*<(?:\\w+:)?(?:OME|Pixels|Experimenter|StageLabel|AcquisitionDate|.*Annotation)\\b.*");
    }

    private static boolean isPathTypedKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.equals("path") || k.endsWith("_path") || k.contains("filepath")
                || k.contains("filename") || k.equals("file") || k.equals("directory")
                || k.equals("folder") || k.equals("sourcefile") || k.equals("source_file")
                || k.equals("title") || k.equals("windowtitle");
    }

    public static boolean isPathLike(String value) {
        if (value == null || value.trim().isEmpty() || looksLikePathToken(value.trim())) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.contains("/") || trimmed.contains("\\")
                || IMAGE_EXTENSION.matcher(trimmed).matches();
    }

    /**
     * A path-typed field holds a "clean" path when the whole value is a single
     * file or directory — not a window title such as
     * {@code "C:\study\file.lif - NGF11_RH_SCN"}.
     *
     * <p>Folding a title's trailing suffix into
     * {@link PathTokenMap#tokenForPathString} would bury the series name inside
     * the token's extension and key the token per-series, so the same file
     * surfaces a different token in every title field — and a token that
     * diverges from the file's own path token. Title-like values instead fall
     * through to {@link #replacePathLikeSubstrings}, which tokenises only the
     * embedded file path and leaves the suffix untouched, giving one stable
     * token per file.
     */
    private static boolean isCleanPathValue(String value) {
        String trimmed = value.trim();
        if (IMAGE_EXTENSION.matcher(trimmed).matches()) {
            return true; // whole value ends at a recognised file extension
        }
        // No embedded extension-terminated file path → treat as a bare
        // directory and fold it wholesale; substring tokenisation would miss it
        // (it only matches values ending in an extension), risking a leak.
        return !PATH_SUBSTRING.matcher(trimmed).find();
    }

    private static boolean shouldTokeniseSlice(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String trimmed = value.trim();
        return !NUMERIC.matcher(trimmed).matches() || isPathLike(trimmed);
    }

    private String tokenForSlice(String value) {
        return isPathLike(value)
                ? pathTokenMap.tokenForPathString(value)
                : pathTokenMap.tokenForSensitiveText(value, "slice");
    }

    private static boolean isBinaryField(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return "base64".equals(k) || "image_base64".equals(k) || "data".equals(k)
                || "pixels".equals(k) || "thumbnail".equals(k) || k.endsWith("_base64");
    }

    private static boolean looksLikePathToken(String value) {
        return value != null && PATH_TOKEN.matcher(value).matches();
    }

    private static boolean isInsidePathToken(String value, int start, int end) {
        if (value == null || start < 0 || end < start) {
            return false;
        }
        Matcher matcher = PATH_TOKEN.matcher(value);
        while (matcher.find()) {
            if (matcher.start() <= start && matcher.end() >= end) {
                return true;
            }
            if (matcher.start() > start) {
                return false;
            }
        }
        return false;
    }

    private static boolean couldBeInsidePathToken(String value, int start, int end) {
        if (value == null) {
            return false;
        }
        int from = Math.max(0, start - 8);
        int to = Math.min(value.length(), end + 8);
        return value.substring(from, to).toLowerCase(java.util.Locale.ROOT)
                .contains("image-");
    }

    private static boolean hasPathLikeCueOutsideTokens(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        Matcher matcher = PATH_TOKEN.matcher(value);
        int start = 0;
        while (matcher.find()) {
            if (segmentHasPathCue(value, start, matcher.start())) {
                return true;
            }
            start = matcher.end();
        }
        return segmentHasPathCue(value, start, value.length());
    }

    private static boolean segmentHasPathCue(String value, int start, int end) {
        if (start >= end) {
            return false;
        }
        String segment = value.substring(start, end).toLowerCase(java.util.Locale.ROOT);
        for (String extension : PATH_EXTENSIONS) {
            if (segment.contains(extension)) {
                return true;
            }
        }
        return segment.indexOf('\\') >= 0 || segment.indexOf('/') >= 0;
    }

    private static int indexOfIgnoreCase(List<String> values, String needle) {
        for (int i = 0; i < values.size(); i++) {
            if (needle.equalsIgnoreCase(values.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else {
                if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n') {
                    row.add(cell.toString());
                    rows.add(row);
                    row = new ArrayList<String>();
                    cell.setLength(0);
                } else if (c != '\r') {
                    cell.append(c);
                }
            }
        }
        row.add(cell.toString());
        rows.add(row);
        return rows;
    }

    private static String writeCsv(List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) out.append(',');
                out.append(escapeCsv(row.get(c)));
            }
            if (r < rows.size() - 1) out.append('\n');
        }
        return out.toString();
    }

    private static String escapeCsv(String value) {
        String v = value == null ? "" : value;
        boolean quote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        if (!quote) {
            return v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static List<String> keys(JsonObject object) {
        return new ArrayList<String>(object.keySet());
    }

    private static void clear(JsonObject object) {
        for (String key : keys(object)) {
            object.remove(key);
        }
    }

}
