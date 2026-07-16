package imagejai.engine.security;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-JVM pseudonym map for image paths, series targets, and short sensitive
 * text values. The map is deliberately process-local: it is never serialised
 * and never logs original values.
 */
public final class PathTokenMap {
    public static final int MAX_PATH_TOKENS = 2048;
    public static final int MAX_SENSITIVE_TOKENS = 4096;
    public static final int MAX_PATH_CHARS = 4096;
    public static final int MAX_SENSITIVE_CHARS = 4096;
    public static final long MAX_SENSITIVE_TOTAL_CHARS = 262_144L;
    public static final int PATH_TOKEN_HEX_CHARS = 32;
    public static final int TEXT_TOKEN_HEX_CHARS = 24;
    private static final int MAX_PREFIX_CHARS = 32;

    private static final SecureRandom RNG = new SecureRandom();
    private static final PathTokenMap INSTANCE = new PathTokenMap();

    private final byte[] salt;
    private final ConcurrentHashMap<String, String> pathKeyToToken =
            new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, Path> tokenToPath =
            new ConcurrentHashMap<String, Path>();
    private final ConcurrentHashMap<String, String> sensitiveToToken =
            new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, String> tokenToSensitive =
            new ConcurrentHashMap<String, String>();
    private final AtomicLong sensitiveVersion = new AtomicLong(0L);
    private final AtomicLong rejectedEntries = new AtomicLong(0L);
    private long sensitiveCharacters;

    public PathTokenMap() {
        this(randomSalt());
    }

    PathTokenMap(byte[] salt) {
        this.salt = salt.clone();
    }

    public static PathTokenMap getInstance() {
        return INSTANCE;
    }

    public synchronized String tokenForPath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        return tokenForPath(path, path.toString());
    }

    public synchronized String tokenForPathString(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        return tokenForPath(Paths.get(rawPath), rawPath);
    }

    public String tokenForSeries(Path path, int seriesIndex) {
        return tokenForPath(path) + ":" + seriesIndex;
    }

    public synchronized String tokenForSensitiveText(String original, String prefix) {
        if (original == null || original.isEmpty()) {
            return original;
        }
        String existing = sensitiveToToken.get(original);
        if (existing != null) {
            return existing;
        }
        ensureSensitiveValueLength(original);
        ensureSensitiveCapacity(Collections.singleton(original));
        String cleanedPrefix = (prefix == null || prefix.trim().isEmpty())
                ? "value"
                : prefix.replaceAll("[^A-Za-z0-9_-]", "").toLowerCase(Locale.ROOT);
        if (cleanedPrefix.isEmpty()) {
            cleanedPrefix = "value";
        }
        if (cleanedPrefix.length() > MAX_PREFIX_CHARS) {
            cleanedPrefix = cleanedPrefix.substring(0, MAX_PREFIX_CHARS);
        }
        String token = uniqueTextToken(cleanedPrefix, original);
        registerSensitiveUnchecked(original, token);
        return token;
    }

    private String tokenForPath(Path path, String rawAlias) {
        Path normalised = normalise(path);
        String key = normalised.toString();
        ensurePathLength(key);
        ensurePathLength(rawAlias);

        Set<String> aliases = new LinkedHashSet<String>();
        aliases.add(key);
        if (rawAlias != null && !rawAlias.isEmpty()) aliases.add(rawAlias);
        ensureSensitiveCapacity(aliases);

        String token = pathKeyToToken.get(key);
        if (token == null) {
            if (pathKeyToToken.size() >= MAX_PATH_TOKENS) {
                reject("Path token capacity reached (max " + MAX_PATH_TOKENS + ")");
            }
            token = uniquePathToken(key, extensionOf(normalised));
            pathKeyToToken.put(key, token);
            tokenToPath.put(token, normalised);
        }
        for (String alias : aliases) {
            registerSensitiveUnchecked(alias, token);
        }
        return token;
    }

    public Optional<ResolvedTarget> resolve(String token) {
        if (token == null || token.trim().isEmpty()) {
            return Optional.empty();
        }
        String trimmed = token.trim();
        int series = -1;
        String base = trimmed;
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0 && colon < trimmed.length() - 1) {
            try {
                series = Integer.parseInt(trimmed.substring(colon + 1));
                base = trimmed.substring(0, colon);
            } catch (NumberFormatException ignored) {
                series = -1;
                base = trimmed;
            }
        }
        Path path = tokenToPath.get(base);
        if (path == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedTarget(path, series));
    }

    public synchronized Map<String, String> snapshotSensitiveStrings() {
        return new HashMap<String, String>(sensitiveToToken);
    }

    public synchronized List<Map.Entry<String, String>> snapshotSensitiveStringsLongestFirst() {
        return sensitiveSnapshot().entries;
    }

    synchronized SensitiveSnapshot sensitiveSnapshot() {
        List<Map.Entry<String, String>> entries =
                new ArrayList<Map.Entry<String, String>>(sensitiveToToken.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                int len = Integer.compare(b.getKey().length(), a.getKey().length());
                return len != 0 ? len : a.getKey().compareTo(b.getKey());
            }
        });
        return new SensitiveSnapshot(sensitiveVersion.get(),
                Collections.unmodifiableList(entries));
    }

    public long sensitiveVersion() {
        return sensitiveVersion.get();
    }

    public int pathEntryCount() {
        return pathKeyToToken.size();
    }

    public int sensitiveEntryCount() {
        return sensitiveToToken.size();
    }

    public synchronized long sensitiveCharacterCount() {
        return sensitiveCharacters;
    }

    public long rejectedEntryCount() {
        return rejectedEntries.get();
    }

    private String uniquePathToken(String key, String extension) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String token = "image-" + hexDigest(key + "#" + attempt,
                    PATH_TOKEN_HEX_CHARS) + extension;
            Path existing = tokenToPath.get(token);
            if (existing == null || existing.toString().equals(key)) {
                return token;
            }
        }
        throw new IllegalStateException("Could not allocate a unique path token");
    }

    private String uniqueTextToken(String prefix, String original) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String token = prefix + "-" + hexDigest(original + "#" + attempt,
                    TEXT_TOKEN_HEX_CHARS);
            String existing = tokenToSensitive.get(token);
            if (existing == null || existing.equals(original)) {
                return token;
            }
        }
        throw new IllegalStateException("Could not allocate a unique sensitive token");
    }

    private void ensurePathLength(String value) {
        if (value != null && value.length() > MAX_PATH_CHARS) {
            rejectedEntries.incrementAndGet();
            throw new IllegalArgumentException("Path exceeds "
                    + MAX_PATH_CHARS + " characters");
        }
    }

    private void ensureSensitiveValueLength(String value) {
        if (value != null && value.length() > MAX_SENSITIVE_CHARS) {
            rejectedEntries.incrementAndGet();
            throw new IllegalArgumentException("Sensitive value exceeds "
                    + MAX_SENSITIVE_CHARS + " characters");
        }
    }

    private void ensureSensitiveCapacity(Iterable<String> candidates) {
        int additionalEntries = 0;
        long additionalCharacters = 0L;
        Set<String> unique = new LinkedHashSet<String>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty() || !unique.add(candidate)
                    || sensitiveToToken.containsKey(candidate)) {
                continue;
            }
            ensureSensitiveValueLength(candidate);
            additionalEntries++;
            additionalCharacters += candidate.length();
        }
        if ((long) sensitiveToToken.size() + additionalEntries > MAX_SENSITIVE_TOKENS
                || sensitiveCharacters + additionalCharacters
                > MAX_SENSITIVE_TOTAL_CHARS) {
            reject("Sensitive token capacity reached (max "
                    + MAX_SENSITIVE_TOKENS + " entries / "
                    + MAX_SENSITIVE_TOTAL_CHARS + " characters)");
        }
    }

    private void registerSensitiveUnchecked(String original, String token) {
        if (original == null || original.isEmpty()
                || sensitiveToToken.containsKey(original)) {
            return;
        }
        sensitiveToToken.put(original, token);
        tokenToSensitive.putIfAbsent(token, original);
        sensitiveCharacters += original.length();
        sensitiveVersion.incrementAndGet();
    }

    private void reject(String message) {
        rejectedEntries.incrementAndGet();
        throw new IllegalStateException(message);
    }

    static final class SensitiveSnapshot {
        final long version;
        final List<Map.Entry<String, String>> entries;

        SensitiveSnapshot(long version, List<Map.Entry<String, String>> entries) {
            this.version = version;
            this.entries = entries;
        }
    }

    private String hexDigest(String value, int chars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] digest = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(chars);
            for (byte b : digest) {
                if (sb.length() >= chars) break;
                String h = Integer.toHexString(b & 0xff);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.substring(0, Math.min(chars, sb.length()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not build token", e);
        }
    }

    private static byte[] randomSalt() {
        byte[] salt = new byte[32];
        RNG.nextBytes(salt);
        return salt;
    }

    private static Path normalise(Path path) {
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return path.normalize();
    }

    private static String extensionOf(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return "";
        }
        String n = name.toString();
        int dot = n.lastIndexOf('.');
        if (dot <= 0 || dot == n.length() - 1) {
            return "";
        }
        return n.substring(dot);
    }

    public static final class ResolvedTarget {
        private final Path realPath;
        private final int series;

        public ResolvedTarget(Path realPath, int series) {
            this.realPath = realPath;
            this.series = series;
        }

        public Path realPath() {
            return realPath;
        }

        public int series() {
            return series;
        }
    }
}
