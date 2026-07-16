package imagejai.engine.picker;

import imagejai.engine.LaunchPolicy;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
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
    public static final int MAX_CACHE_BYTES = 1024 * 1024;
    public static final int MAX_MODELS = 2048;

    interface InputOpener {
        InputStream open(Path path) throws IOException;
    }

    interface PathProbe {
        BasicFileAttributes readAttributes(Path path) throws IOException;
    }

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
    private final InputOpener inputOpener;
    private final PathProbe pathProbe;

    public ModelsCache(Path rootDir) {
        this(rootDir, Files::newInputStream, ModelsCache::readAttributes);
    }

    ModelsCache(Path rootDir, InputOpener inputOpener) {
        this(rootDir, inputOpener, ModelsCache::readAttributes);
    }

    ModelsCache(Path rootDir, InputOpener inputOpener, PathProbe pathProbe) {
        this.rootDir = Objects.requireNonNull(rootDir, "rootDir");
        this.inputOpener = Objects.requireNonNull(inputOpener, "inputOpener");
        this.pathProbe = Objects.requireNonNull(pathProbe, "pathProbe");
    }

    public Path rootDir() {
        return rootDir;
    }

    public Path pathFor(String providerId) {
        return rootDir.resolve(LaunchPolicy.requireProviderId(providerId) + ".json");
    }

    public boolean has(String providerId) {
        Path path = pathFor(providerId);
        try {
            BasicFileAttributes attributes = pathProbe.readAttributes(path);
            if (!attributes.isRegularFile()) {
                throw new CacheReadException("not_regular", path,
                        "Model cache is not a regular file.");
            }
            return true;
        } catch (NoSuchFileException ex) {
            return false;
        } catch (IOException ex) {
            throw new CacheReadException("unreadable", path,
                    "Could not inspect model cache: " + message(ex), ex);
        }
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
        try {
            BasicFileAttributes attributes = pathProbe.readAttributes(path);
            if (!attributes.isRegularFile()) {
                throw new CacheReadException("not_regular", path,
                        "Model cache is not a regular file.");
            }
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException ex) {
            throw new CacheReadException("unreadable", path,
                    "Could not inspect model cache: " + message(ex), ex);
        }
        try (InputStream in = inputOpener.open(path)) {
            byte[] bytes = readBounded(in, path);
            Snapshot parsed = parse(providerId, new String(bytes, StandardCharsets.UTF_8));
            if (parsed == null) {
                throw new IllegalArgumentException("Cache JSON is malformed.");
            }
            return parsed;
        } catch (NoSuchFileException ex) {
            return null;
        } catch (IOException ex) {
            throw new CacheReadException("unreadable", path,
                    "Could not read model cache: " + message(ex), ex);
        } catch (ModelCountLimitException ex) {
            throw new CacheReadException("model_cap", path,
                    "Model cache exceeds safety cap of " + MAX_MODELS + " models.", ex);
        } catch (RuntimeException ex) {
            if (ex instanceof CacheReadException) {
                throw ex;
            }
            throw new CacheReadException("malformed", path,
                    "Could not parse model cache: " + message(ex), ex);
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
        providerId = LaunchPolicy.requireProviderId(providerId);
        Files.createDirectories(rootDir);
        Path target = pathFor(providerId);
        Path tmp = Files.createTempFile(rootDir, providerId + "-", ".tmp");
        Set<String> ids = new LinkedHashSet<String>();
        if (modelIds != null) {
            for (String id : modelIds) {
                if (ids.size() >= MAX_MODELS) {
                    throw new IllegalArgumentException("Model cache exceeds safety cap of "
                            + MAX_MODELS + " models.");
                }
                ids.add(LaunchPolicy.requireModelId(id));
            }
        }
        try (BufferedWriter w = new BufferedWriter(
                Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {
            w.write("{\n");
            w.write("  \"provider\": " + jsonString(providerId) + ",\n");
            w.write("  \"fetched_at\": " + jsonString(fetchedAt.toString()) + ",\n");
            w.write("  \"endpoint\": " + jsonString(credentialFreeEndpoint(endpoint)) + ",\n");
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
        fetchedAt = Instant.parse(extractStringField(body, "fetched_at"));
        List<String> ids = new ArrayList<String>();
        for (String id : extractIds(body)) {
            try {
                ids.add(LaunchPolicy.requireModelId(id));
            } catch (IllegalArgumentException ignored) {
                // Treat attacker-controlled or corrupt cache entries as absent.
            }
        }
        return new Snapshot(providerId, fetchedAt, ids);
    }

    static String credentialFreeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return "";
        }
        String candidate = endpoint.trim();
        try {
            URI uri = new URI(candidate);
            if (uri.isAbsolute() && uri.getHost() != null) {
                return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                        uri.getPath(), null, null).toString();
            }
        } catch (URISyntaxException ignored) {
            return "";
        }
        String lower = candidate.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*(?:key|api_?key|token|secret|password)=.*")) {
            return "";
        }
        return candidate;
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
        if (idx < 0) throw new IllegalStateException("missing field models");
        int open = body.indexOf('[', idx);
        int close = body.indexOf(']', open);
        if (open < 0 || close < 0) throw new IllegalStateException("malformed field models");
        String inner = body.substring(open + 1, close).trim();
        if (inner.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < inner.length()) {
            int q1 = inner.indexOf('"', i);
            if (q1 < 0) break;
            int q2 = inner.indexOf('"', q1 + 1);
            if (q2 < 0) throw new IllegalStateException("malformed model identifier");
            if (out.size() >= MAX_MODELS) {
                throw new ModelCountLimitException();
            }
            out.add(inner.substring(q1 + 1, q2));
            i = q2 + 1;
        }
        return out;
    }

    private static byte[] readBounded(InputStream in, Path path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(8192, MAX_CACHE_BYTES));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (read > MAX_CACHE_BYTES - total) {
                throw new CacheReadException("too_large", path,
                        "Model cache exceeds safety cap of " + MAX_CACHE_BYTES + " bytes.");
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    private static String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : value;
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class);
    }

    public static final class CacheReadException extends IllegalStateException {
        private final String code;
        private final Path path;

        CacheReadException(String code, Path path, String message) {
            super(message);
            this.code = code;
            this.path = path;
        }

        CacheReadException(String code, Path path, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.path = path;
        }

        public String code() { return code; }
        public Path path() { return path; }
    }

    private static final class ModelCountLimitException extends IllegalStateException {
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
