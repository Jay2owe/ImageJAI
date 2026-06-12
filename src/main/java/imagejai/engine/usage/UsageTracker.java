package imagejai.engine.usage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Per-(provider × model) usage stats persisted to
 * {@code ~/.imagej-ai/usage_tracking.json}. Drives Phase H's favourite-model
 * detection — a model becomes a favourite once it has been launched ≥ 3 times
 * across at least 2 distinct days (06 §7.2).
 *
 * <p>Atomic writes (tmp file + rename) per risk #25 in 07 §4.
 */
public final class UsageTracker {

    /** A model qualifies as a favourite at this many launches across ≥ 2 days. */
    public static final int FAVOURITE_USE_COUNT = 3;
    public static final int FAVOURITE_DISTINCT_DAYS = 2;

    public static final String DEFAULT_FILENAME = "usage_tracking.json";

    public static final class Record {
        public int useCount;
        /** ISO YYYY-MM-DD strings — TreeSet so the JSON is stable across runs. */
        public TreeSet<String> distinctDays = new TreeSet<>();
        public String firstUsed;
        public String lastUsed;
        /** Tier classification last seen at startup compare. */
        public String lastSeenTier;
        public Double lastSeenInputUsdPerMtok;
        public Double lastSeenOutputUsdPerMtok;
    }

    private static final Type RECORD_MAP_TYPE =
            new TypeToken<LinkedHashMap<String, Record>>() {}.getType();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Map<String, Record> records;

    private UsageTracker(Path path, Map<String, Record> records) {
        this.path = path;
        this.records = records == null ? new LinkedHashMap<>() : new LinkedHashMap<>(records);
    }

    public static Path defaultPath() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".imagej-ai", DEFAULT_FILENAME);
    }

    public static UsageTracker load() {
        return load(defaultPath());
    }

    public static UsageTracker load(Path path) {
        if (path == null || !Files.exists(path)) {
            return new UsageTracker(path, new LinkedHashMap<>());
        }
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Map<String, Record> map = GSON.fromJson(r, RECORD_MAP_TYPE);
            if (map == null) {
                map = new LinkedHashMap<>();
            }
            // Defensive: gson may produce null distinctDays for legacy files.
            for (Record record : map.values()) {
                if (record.distinctDays == null) {
                    record.distinctDays = new TreeSet<>();
                }
            }
            return new UsageTracker(path, map);
        } catch (IOException ex) {
            return new UsageTracker(path, new LinkedHashMap<>());
        }
    }

    /** Record one launch of {@code provider/model_id} on {@code today}. */
    public void recordLaunch(String providerId, String modelId, LocalDate today) {
        if (providerId == null || modelId == null || today == null) {
            return;
        }
        String key = key(providerId, modelId);
        Record record = records.computeIfAbsent(key, k -> new Record());
        record.useCount += 1;
        String iso = today.toString();
        if (record.firstUsed == null || iso.compareTo(record.firstUsed) < 0) {
            record.firstUsed = iso;
        }
        record.lastUsed = iso;
        record.distinctDays.add(iso);
    }

    /** Update the snapshot of pricing/tier last seen for one model. */
    public void recordSeenTier(String providerId,
                               String modelId,
                               String tier,
                               Double inputUsdPerMtok,
                               Double outputUsdPerMtok) {
        if (providerId == null || modelId == null) {
            return;
        }
        Record record = records.computeIfAbsent(key(providerId, modelId), k -> new Record());
        record.lastSeenTier = tier;
        record.lastSeenInputUsdPerMtok = inputUsdPerMtok;
        record.lastSeenOutputUsdPerMtok = outputUsdPerMtok;
    }

    public Record get(String providerId, String modelId) {
        if (providerId == null || modelId == null) {
            return null;
        }
        return records.get(key(providerId, modelId));
    }

    public boolean isFavourite(String providerId, String modelId) {
        Record record = get(providerId, modelId);
        if (record == null) {
            return false;
        }
        int distinctDays = record.distinctDays == null ? 0 : record.distinctDays.size();
        return record.useCount >= FAVOURITE_USE_COUNT
                && distinctDays >= FAVOURITE_DISTINCT_DAYS;
    }

    public List<String> favourites() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Record> e : records.entrySet()) {
            Record record = e.getValue();
            int distinctDays = record.distinctDays == null ? 0 : record.distinctDays.size();
            if (record.useCount >= FAVOURITE_USE_COUNT
                    && distinctDays >= FAVOURITE_DISTINCT_DAYS) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public Map<String, Record> snapshot() {
        return Collections.unmodifiableMap(records);
    }

    public void save() throws IOException {
        if (path == null) {
            return;
        }
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(records, RECORD_MAP_TYPE, w);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static String key(String providerId, String modelId) {
        return providerId + "/" + modelId;
    }

    public static String[] split(String key) {
        if (key == null) return new String[]{"", ""};
        int slash = key.indexOf('/');
        if (slash < 0) return new String[]{key, ""};
        return new String[]{key.substring(0, slash), key.substring(slash + 1)};
    }
}
