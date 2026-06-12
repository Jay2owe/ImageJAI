package imagejai.engine.picker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 24h on-disk cache of {@code /models} responses, one JSON file per provider.
 *
 * <p>Layout (per docs/multi_provider/02_curation_strategy.md §5):
 * <pre>
 * &lt;config-root&gt;/imagejai/cache/models/
 *   ollama.json  ollama-cloud.json  openai.json  anthropic.json  ...
 * </pre>
 *
 * <p>Each file is shaped as
 * {@code {"provider": "...", "fetched_at": "ISO-instant", "endpoint": "...",
 *  "models": ["id1", "id2", ...]}}. Extra fields are preserved on round-trip
 * but the cache only deserialises ids and timestamps — the merge layer does
 * not need pricing or modality data at this point.
 *
 * <p>The cache is deliberately written with hand-rolled JSON instead of Gson
 * so the production code path doesn't have to drag a {@code provided}
 * dependency into the test classpath. The format is small and stable.
 */
public final class ModelsCache {

    /** TTL after which {@link #isFresh(String, Instant)} returns {@code false}. */
    public static final Duration TTL = Duration.ofHours(24);

    /** One provider's cache slot — loaded snapshot. */
    public static final class Snapshot {
        private final String providerId;
        private final Instant fetchedAt;
        private final List<String> modelIds;

        public Snapshot(String providerId, Instant fetchedAt, List<String> modelIds) {
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            this.fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt");
            this.modelIds = modelIds == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(modelIds));
        }

        public String providerId() { return providerId; }
        public Instant fetchedAt() { return fetchedAt; }
        public List<String> modelIds() { return modelIds; }
    }

    private final Path rootDir;

    public ModelsCache(Path rootDir) {
        this.rootDir = Objects.requireNonNull(rootDir, "rootDir");
    }

    public Path rootDir() {
        return rootDir;
    }

    public Path pathFor(String providerId) {
        return rootDir.resolve(providerId + ".json");
    }

    public boolean has(String providerId) {
        return Files.exists(pathFor(providerId));
    }

    /** Return {@code true} iff a snapshot exists and is younger than {@link #TTL}. */
    public boolean isFresh(String providerId, Instant now) {
        Snapshot snap = read(providerId);
        if (snap == null) {
            return false;
        }
        return Duration.between(snap.fetchedAt(), now).compareTo(TTL) < 0;
    }

    /** Read the cached snapshot for one provider, or {@code null} when absent. */
    public Snapshot read(String providerId) {
        Path path = pathFor(providerId);
        if (!Files.exists(path)) {
            return null;
        }
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            return parse(providerId, sb.toString());
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Persist a fresh snapshot atomically (write to a tmp file then rename so
     * a torn write never replaces a known-good cache).
     */
    public void write(String providerId,
                      Instant fetchedAt,
                      String endpoint,
                      Set<String> modelIds) throws IOException {
        if (!Files.exists(rootDir)) {
            Files.createDirectories(rootDir);
        }
        Path target = pathFor(providerId);
        Path tmp = Files.createTempFile(rootDir, providerId + "-", ".tmp");
        Set<String> ids = modelIds == null
                ? Collections.<String>emptySet()
                : new LinkedHashSet<String>(modelIds);
        try (BufferedWriter w = new BufferedWriter(
                Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {
            w.write("{\n");
            w.write("  \"provider\": " + jsonString(providerId) + ",\n");
            w.write("  \"fetched_at\": " + jsonString(fetchedAt.toString()) + ",\n");
            w.write("  \"endpoint\": " + jsonString(endpoint == null ? "" : endpoint) + ",\n");
            w.write("  \"models\": [");
            boolean first = true;
            for (String id : ids) {
                if (!first) {
                    w.write(", ");
                }
                w.write(jsonString(id));
                first = false;
            }
            w.write("]\n}\n");
        }
        Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    static Snapshot parse(String providerId, String body) {
        // Tiny parser — sufficient for the cache shape we control. Reads
        // "fetched_at" as an ISO instant and "models" as either ["id"] or
        // [{"id": "..."}] for forward compat with richer payloads.
        Instant fetchedAt;
        try {
            fetchedAt = Instant.parse(extractStringField(body, "fetched_at"));
        } catch (DateTimeParseException ex) {
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
        List<String> ids = extractIds(body);
        return new Snapshot(providerId, fetchedAt, ids);
    }

    private static String extractStringField(String body, String key) {
        int idx = body.indexOf("\"" + key + "\"");
        if (idx < 0) throw new IllegalStateException("missing field " + key);
        int colon = body.indexOf(':', idx);
        int firstQuote = body.indexOf('"', colon + 1);
        int endQuote = body.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || endQuote < 0) {
            throw new IllegalStateException("malformed field " + key);
        }
        return body.substring(firstQuote + 1, endQuote);
    }

    private static List<String> extractIds(String body) {
        int idx = body.indexOf("\"models\"");
        if (idx < 0) return Collections.emptyList();
        int open = body.indexOf('[', idx);
        int close = body.indexOf(']', open);
        if (open < 0 || close < 0) return Collections.emptyList();
        String inner = body.substring(open + 1, close).trim();
        if (inner.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < inner.length()) {
            int q1 = inner.indexOf('"', i);
            if (q1 < 0) break;
            int q2 = inner.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            out.add(inner.substring(q1 + 1, q2));
            i = q2 + 1;
        }
        return out;
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
        return b.toString();
    }
}
