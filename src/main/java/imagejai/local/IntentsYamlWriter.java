package imagejai.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Minimal append-only mutator for the flat tools/intents.yaml format.
 */
public final class IntentsYamlWriter {

    public static Path defaultIntentsPath() {
        return Paths.get("tools", "intents.yaml").toAbsolutePath().normalize();
    }

    public void addAlias(Path yaml, String intentId, String phrase) throws IOException {
        writeAtomically(yaml, addAlias(read(yaml), intentId, phrase));
    }

    public void addNewIntent(Path yaml, String id, String description,
                             List<String> seeds) throws IOException {
        writeAtomically(yaml, addNewIntent(read(yaml), id, description, seeds));
    }

    public String diff(Path yaml, List<Change> changes) throws IOException {
        String before = read(yaml);
        String after = apply(before, changes);
        return unifiedDiff(yaml, before, after);
    }

    public void apply(Path yaml, List<Change> changes) throws IOException {
        writeAtomically(yaml, apply(read(yaml), changes));
    }

    public String apply(String yaml, List<Change> changes) {
        String out = yaml == null ? "" : yaml;
        if (changes == null) {
            return out;
        }
        for (Change change : changes) {
            if (change == null) {
                continue;
            }
            if (change.type() == ChangeType.ALIAS) {
                out = addAlias(out, change.intentId(), change.phrase());
            } else if (change.type() == ChangeType.NEW_INTENT) {
                out = addNewIntent(out, change.intentId(), change.description(), change.seeds());
            }
        }
        return out;
    }

    public String addAlias(String yaml, String intentId, String phrase) {
        if (intentId == null || intentId.trim().isEmpty()) {
            throw new IllegalArgumentException("intent id is required");
        }
        if (phrase == null || phrase.trim().isEmpty()) {
            throw new IllegalArgumentException("phrase is required");
        }
        List<String> lines = splitLines(yaml);
        int idLine = findIntentLine(lines, intentId);
        if (idLine < 0) {
            throw new IllegalArgumentException("No intent with id: " + intentId);
        }
        int blockEnd = findBlockEnd(lines, idLine);
        int seedsLine = findSeedsLine(lines, idLine, blockEnd);
        if (seedsLine < 0) {
            String indent = leadingWhitespace(lines.get(idLine));
            lines.add(blockEnd, indent + "seeds:");
            lines.add(blockEnd + 1, indent + "  - " + quoteYaml(phrase));
            return joinLines(lines);
        }
        if (blockContainsSeed(lines, seedsLine, blockEnd, phrase)) {
            return yaml;
        }

        String line = lines.get(seedsLine);
        if (line.contains("[") && line.contains("]")) {
            lines.set(seedsLine, appendInlineSeed(line, phrase));
        } else {
            String indent = leadingWhitespace(line) + "  ";
            int insert = seedsLine + 1;
            while (insert < blockEnd && lines.get(insert).startsWith(indent)) {
                insert++;
            }
            lines.add(insert, indent + "- " + quoteYaml(phrase));
        }
        return joinLines(lines);
    }

    public String addNewIntent(String yaml, String id, String description,
                               List<String> seeds) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("intent id is required");
        }
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("description is required");
        }
        if (findIntentLine(splitLines(yaml), id) >= 0) {
            throw new IllegalArgumentException("Intent already exists: " + id);
        }
        List<String> safeSeeds = new ArrayList<String>();
        if (seeds != null) {
            for (String seed : seeds) {
                if (seed != null && seed.trim().length() > 0
                        && !containsNormalised(safeSeeds, seed)) {
                    safeSeeds.add(seed.trim());
                }
            }
        }
        if (safeSeeds.isEmpty()) {
            throw new IllegalArgumentException("at least one seed is required");
        }

        StringBuilder sb = new StringBuilder();
        String base = yaml == null ? "" : yaml;
        sb.append(trimTrailingWhitespace(base));
        if (sb.length() > 0) {
            sb.append('\n').append('\n');
        }
        sb.append("- id: ").append(id.trim()).append('\n');
        sb.append("  description: ").append(quoteYaml(description)).append('\n');
        sb.append("  seeds:").append('\n');
        for (String seed : safeSeeds) {
            sb.append("    - ").append(quoteYaml(seed)).append('\n');
        }
        return sb.toString();
    }

    public String unifiedDiff(Path yaml, String before, String after) {
        if ((before == null ? "" : before).equals(after == null ? "" : after)) {
            return "";
        }
        String[] oldLines = toArray(before);
        String[] newLines = toArray(after);
        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length
                && oldLines[prefix].equals(newLines[prefix])) {
            prefix++;
        }
        int oldSuffix = oldLines.length - 1;
        int newSuffix = newLines.length - 1;
        while (oldSuffix >= prefix && newSuffix >= prefix
                && oldLines[oldSuffix].equals(newLines[newSuffix])) {
            oldSuffix--;
            newSuffix--;
        }

        int contextBefore = Math.max(0, prefix - 3);
        int contextAfterOld = Math.min(oldLines.length - 1, oldSuffix + 3);
        int contextAfterNew = Math.min(newLines.length - 1, newSuffix + 3);
        String name = yaml == null ? "tools/intents.yaml" : yaml.toString().replace('\\', '/');

        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(name).append('\n');
        sb.append("+++ ").append(name).append('\n');
        sb.append("@@ -").append(contextBefore + 1).append(',')
                .append(Math.max(0, contextAfterOld - contextBefore + 1))
                .append(" +").append(contextBefore + 1).append(',')
                .append(Math.max(0, contextAfterNew - contextBefore + 1))
                .append(" @@").append('\n');
        for (int i = contextBefore; i < prefix; i++) {
            sb.append(' ').append(oldLines[i]).append('\n');
        }
        for (int i = prefix; i <= oldSuffix; i++) {
            sb.append('-').append(oldLines[i]).append('\n');
        }
        for (int i = prefix; i <= newSuffix; i++) {
            sb.append('+').append(newLines[i]).append('\n');
        }
        int oldContextStart = oldSuffix + 1;
        int newContextStart = newSuffix + 1;
        int contextCount = Math.min(contextAfterOld - oldContextStart + 1,
                contextAfterNew - newContextStart + 1);
        for (int i = 0; i < contextCount; i++) {
            sb.append(' ').append(oldLines[oldContextStart + i]).append('\n');
        }
        return sb.toString();
    }

    public static String deriveUserIntentId(String description) {
        String raw = description == null ? "" : description.toLowerCase(Locale.ROOT);
        String slug = raw.replaceAll("[^a-z0-9]+", ".");
        slug = slug.replaceAll("^\\.+|\\.+$", "");
        if (slug.length() == 0) {
            slug = "intent";
        }
        return "user." + slug;
    }

    public static String quoteYaml(String value) {
        String text = value == null ? "" : value;
        text = text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
        return "\"" + text + "\"";
    }

    private static String read(Path yaml) throws IOException {
        return Files.readString(yaml, StandardCharsets.UTF_8);
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("target path is required");
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Cannot write " + target + ": parent directory does not exist");
        }
        Path tmp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content == null ? "" : content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static int findIntentLine(List<String> lines, String intentId) {
        String wanted = "- id: " + intentId;
        String wantedQuoted = "- id: " + quoteYaml(intentId);
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (wanted.equals(trimmed) || wantedQuoted.equals(trimmed)) {
                return i;
            }
        }
        return -1;
    }

    private static int findBlockEnd(List<String> lines, int idLine) {
        for (int i = idLine + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("- id: ")) {
                return i;
            }
        }
        return lines.size();
    }

    private static int findSeedsLine(List<String> lines, int start, int end) {
        for (int i = start + 1; i < end; i++) {
            if (lines.get(i).trim().startsWith("seeds:")) {
                return i;
            }
        }
        return -1;
    }

    private static boolean blockContainsSeed(List<String> lines, int seedsLine,
                                             int blockEnd, String phrase) {
        String normalised = IntentMatcher.normalise(phrase);
        for (int i = seedsLine; i < blockEnd; i++) {
            if (IntentMatcher.normalise(lines.get(i)).equals(normalised)
                    || IntentMatcher.normalise(lines.get(i)).contains(normalised)) {
                return true;
            }
        }
        return false;
    }

    private static String appendInlineSeed(String line, String phrase) {
        int close = line.lastIndexOf(']');
        int open = line.indexOf('[');
        if (open < 0 || close < open) {
            return line;
        }
        String inside = line.substring(open + 1, close).trim();
        String addition = (inside.length() == 0 ? "" : ", ") + quoteYaml(phrase);
        return line.substring(0, close) + addition + line.substring(close);
    }

    private static List<String> splitLines(String yaml) {
        String input = yaml == null ? "" : yaml.replace("\r\n", "\n").replace('\r', '\n');
        if (input.length() == 0) {
            return new ArrayList<String>();
        }
        String[] parts = input.split("\n", -1);
        List<String> lines = new ArrayList<String>();
        Collections.addAll(lines, parts);
        if (!lines.isEmpty() && lines.get(lines.size() - 1).length() == 0) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String[] toArray(String text) {
        List<String> lines = splitLines(text);
        return lines.toArray(new String[0]);
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    private static String trimTrailingWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+$", "");
    }

    private static boolean containsNormalised(List<String> values, String candidate) {
        String key = IntentMatcher.normalise(candidate);
        for (String value : values) {
            if (IntentMatcher.normalise(value).equals(key)) {
                return true;
            }
        }
        return false;
    }

    public enum ChangeType {
        ALIAS,
        NEW_INTENT
    }

    public static final class Change {
        private final ChangeType type;
        private final String intentId;
        private final String phrase;
        private final String description;
        private final List<String> seeds;

        private Change(ChangeType type, String intentId, String phrase,
                       String description, List<String> seeds) {
            this.type = type;
            this.intentId = intentId == null ? "" : intentId;
            this.phrase = phrase == null ? "" : phrase;
            this.description = description == null ? "" : description;
            this.seeds = seeds == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(seeds));
        }

        public static Change alias(String intentId, String phrase) {
            return new Change(ChangeType.ALIAS, intentId, phrase, "", null);
        }

        public static Change newIntent(String id, String description, List<String> seeds) {
            return new Change(ChangeType.NEW_INTENT, id, "", description, seeds);
        }

        public ChangeType type() {
            return type;
        }

        public String intentId() {
            return intentId;
        }

        public String phrase() {
            return phrase;
        }

        public String description() {
            return description;
        }

        public List<String> seeds() {
            return seeds;
        }
    }
}
