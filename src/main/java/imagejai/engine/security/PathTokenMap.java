package imagejai.engine.security;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-JVM pseudonym map for image paths, series targets, and short sensitive
 * text values. The map is deliberately process-local: it is never serialised
 * and never logs original values.
 */
public final class PathTokenMap {
    private static final SecureRandom RNG = new SecureRandom();
    private static final PathTokenMap INSTANCE = new PathTokenMap();

    private final byte[] salt;
    private final ConcurrentHashMap<String, String> pathKeyToToken =
            new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, Path> tokenToPath =
            new ConcurrentHashMap<String, Path>();
    private final ConcurrentHashMap<String, String> sensitiveToToken =
            new ConcurrentHashMap<String, String>();

    public PathTokenMap() {
        this(randomSalt());
    }

    PathTokenMap(byte[] salt) {
        this.salt = salt.clone();
    }

    public static PathTokenMap getInstance() {
        return INSTANCE;
    }

    public String tokenForPath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        Path normalised = normalise(path);
        String key = normalised.toString();
        String existing = pathKeyToToken.get(key);
        if (existing != null) {
            return existing;
        }
        String token = uniquePathToken(key, extensionOf(normalised));
        String raced = pathKeyToToken.putIfAbsent(key, token);
        String chosen = raced == null ? token : raced;
        tokenToPath.putIfAbsent(chosen, normalised);
        sensitiveToToken.putIfAbsent(key, chosen);
        if (!key.equals(path.toString())) {
            sensitiveToToken.putIfAbsent(path.toString(), chosen);
        }
        return chosen;
    }

    public String tokenForPathString(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        String token = tokenForPath(Paths.get(rawPath));
        sensitiveToToken.putIfAbsent(rawPath, token);
        return token;
    }

    public String tokenForSeries(Path path, int seriesIndex) {
        return tokenForPath(path) + ":" + seriesIndex;
    }

    public String tokenForSensitiveText(String original, String prefix) {
        if (original == null || original.isEmpty()) {
            return original;
        }
        String existing = sensitiveToToken.get(original);
        if (existing != null) {
            return existing;
        }
        String cleanedPrefix = (prefix == null || prefix.trim().isEmpty())
                ? "value"
                : prefix.replaceAll("[^A-Za-z0-9_-]", "").toLowerCase();
        if (cleanedPrefix.isEmpty()) {
            cleanedPrefix = "value";
        }
        String token = uniqueTextToken(cleanedPrefix, original);
        String raced = sensitiveToToken.putIfAbsent(original, token);
        return raced == null ? token : raced;
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

    public Map<String, String> snapshotSensitiveStrings() {
        return new HashMap<String, String>(sensitiveToToken);
    }

    public List<Map.Entry<String, String>> snapshotSensitiveStringsLongestFirst() {
        List<Map.Entry<String, String>> entries =
                new ArrayList<Map.Entry<String, String>>(sensitiveToToken.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                int len = Integer.compare(b.getKey().length(), a.getKey().length());
                return len != 0 ? len : a.getKey().compareTo(b.getKey());
            }
        });
        return entries;
    }

    private String uniquePathToken(String key, String extension) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String token = "image-" + hexDigest(key + "#" + attempt, 4) + extension;
            Path existing = tokenToPath.get(token);
            if (existing == null || existing.toString().equals(key)) {
                return token;
            }
        }
        return "image-" + hexDigest(key + "#fallback", 12) + extension;
    }

    private String uniqueTextToken(String prefix, String original) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String token = prefix + "-" + hexDigest(original + "#" + attempt, 6);
            String existing = sensitiveToToken.get(original);
            if (existing == null || existing.equals(token)) {
                return token;
            }
        }
        return prefix + "-" + hexDigest(original + "#fallback", 12);
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
