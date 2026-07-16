package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 14 (docs/tcp_upgrade/14_federated_ledger.md): persistent on-disk ledger
 * of known-good fixes keyed by {@link #fingerprint(String, String, String)}.
 *
 * <p>An agent that hits an error can call {@code ledger_lookup} to ask the
 * server whether anyone has previously confirmed a working fix for the same
 * fingerprint; when a macro fails inside {@code handleExecuteMacro} the server
 * also auto-attaches the top matches to the error's {@code suggested[]} list
 * so the next round-trip already contains the fix.
 *
 * <p>On-disk form lives at {@code ~/.imagejai/ledger.json} by default; the
 * constructor accepts an explicit path so tests can point the store at a temp
 * directory. Writes are atomic (temp file + rename) and every load/save pairs
 * with a synchronisation lock, so two sockets that confirm concurrently won't
 * corrupt the JSON. Growth is capped at {@link #MAX_ENTRIES} with LRU eviction
 * by {@code lastSeen} once the cap is reached.
 *
 * <p>Pure IO + hashing. No ImageJ dependencies — safe to unit-test headless.
 */
final class LedgerStore {

    /** Current on-disk format version. Written as {@code version} on every save. */
    static final int FORMAT_VERSION = 1;

    /** Hard cap on entries retained; LRU-evict by {@code lastSeen} above this. */
    static final int MAX_ENTRIES = 10_000;

    static final long MAX_STORE_BYTES = 64L * 1024L * 1024L;
    static final int MAX_FIELD_BYTES = 1024 * 1024;
    static final int MAX_AGENTS_PER_ENTRY = 1_000;

    /** SHA-256 truncated to 128 bits → 32 hex chars. */
    private static final int FINGERPRINT_HEX_LEN = 32;

    /**
     * Pre-compiled run() matcher used by {@link #normaliseMacro(String)}. Picks
     * up single- or double-quoted plugin names in the first two {@code run(...)}
     * calls so entries collide on the same command sequence regardless of arg
     * differences.
     */
    private static final Pattern RUN_CALL =
            Pattern.compile("run\\s*\\(\\s*[\"']([^\"']+)[\"']");

    /** Immutable ledger entry. Updates replace the map value under {@link #lock}. */
    static final class Entry {
        final String fingerprint;
        final String macroPrefix;
        final String errorCode;
        final String errorFragment;
        final String confirmedFix;
        final String exampleMacro;
        final Set<String> confirmedBy;
        final int timesSeen;
        final int confirmationsTrue;
        final int confirmationsFalse;
        final long firstSeen;
        final long lastSeen;

        Entry(String fingerprint) {
            this(fingerprint, null, null, null, null, null,
                    Collections.<String>emptySet(), 0, 0, 0, 0L, 0L);
        }

        Entry(String fingerprint, String macroPrefix, String errorCode,
              String errorFragment, String confirmedFix, String exampleMacro,
              Set<String> confirmedBy, int timesSeen, int confirmationsTrue,
              int confirmationsFalse, long firstSeen, long lastSeen) {
            this.fingerprint = fingerprint;
            this.macroPrefix = macroPrefix;
            this.errorCode = errorCode;
            this.errorFragment = errorFragment;
            this.confirmedFix = confirmedFix;
            this.exampleMacro = exampleMacro;
            this.confirmedBy = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(confirmedBy));
            this.timesSeen = timesSeen;
            this.confirmationsTrue = confirmationsTrue;
            this.confirmationsFalse = confirmationsFalse;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
        }

        /** Serialise to a {@link JsonObject} in the on-disk / API shape. */
        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("fingerprint", fingerprint);
            if (macroPrefix != null)   o.addProperty("macroPrefix", macroPrefix);
            if (errorCode != null)     o.addProperty("errorCode", errorCode);
            if (errorFragment != null) o.addProperty("errorFragment", errorFragment);
            if (confirmedFix != null)  o.addProperty("confirmedFix", confirmedFix);
            if (exampleMacro != null)  o.addProperty("exampleMacro", exampleMacro);
            JsonArray by = new JsonArray();
            for (String a : confirmedBy) by.add(a);
            o.add("confirmedBy", by);
            o.addProperty("timesSeen", timesSeen);
            o.addProperty("confirmationsTrue", confirmationsTrue);
            o.addProperty("confirmationsFalse", confirmationsFalse);
            o.addProperty("firstSeen", firstSeen);
            o.addProperty("lastSeen", lastSeen);
            return o;
        }

        static Entry fromJson(JsonObject o) {
            String fp = optString(o, "fingerprint", null);
            if (fp == null || fp.isEmpty()) throw new IllegalArgumentException("missing fingerprint");
            LinkedHashSet<String> agents = new LinkedHashSet<String>();
            JsonElement by = o.get("confirmedBy");
            if (by != null && !by.isJsonArray()) {
                throw new IllegalArgumentException("confirmedBy is not an array");
            }
            if (by != null) {
                for (JsonElement el : by.getAsJsonArray()) {
                    if (el == null || !el.isJsonPrimitive()) {
                        throw new IllegalArgumentException("confirmedBy contains non-string value");
                    }
                    agents.add(el.getAsString());
                }
            }
            return new Entry(fp,
                    optString(o, "macroPrefix", null), optString(o, "errorCode", null),
                    optString(o, "errorFragment", null), optString(o, "confirmedFix", null),
                    optString(o, "exampleMacro", null), agents,
                    requiredNonNegativeInt(o, "timesSeen"),
                    requiredNonNegativeInt(o, "confirmationsTrue"),
                    requiredNonNegativeInt(o, "confirmationsFalse"),
                    requiredNonNegativeLong(o, "firstSeen"),
                    requiredNonNegativeLong(o, "lastSeen"));
        }
    }

    static final class PersistenceException extends IllegalStateException {
        final String code;
        PersistenceException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // Lock guards both in-memory map mutation and file IO so two confirm calls
    // from different sockets cannot interleave a load/save and lose each
    // other's updates.
    private final Object lock = new Object();
    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private final Path path;
    /** In-memory fallback flag — flips true if disk IO fails at load or save. */
    private boolean memoryOnly = false;
    private boolean writeBlocked = false;
    private String loadError;
    private Path quarantinedPath;

    LedgerStore(Path path) {
        this.path = path;
        load();
    }

    /** Default store at {@code ~/.imagejai/ledger.json}. */
    static LedgerStore openDefault() {
        String home = System.getProperty("user.home");
        Path dir = Paths.get(home == null ? "." : home, ".imagejai");
        return new LedgerStore(dir.resolve("ledger.json"));
    }

    /** Visible for tests. */
    Path path() { return path; }

    /** Visible for tests — best-effort memory-only warning after IO failure. */
    boolean isMemoryOnly() { return memoryOnly; }

    boolean isWriteBlocked() { return writeBlocked; }

    String loadError() { return loadError; }

    Path quarantinedPath() { return quarantinedPath; }

    /** Visible for tests. */
    int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Look up entries whose fingerprint — computed from the caller's
     * {@code errorFragment} and {@code macroPrefix} — matches one we have on
     * file. Returns at most {@code max} results sorted by descending
     * {@link #confidenceRank(Entry)} then descending {@code timesSeen}.
     */
    List<Entry> lookup(String errorCode, String errorFragment,
                       String macroPrefix, int max) {
        if (max <= 0) return Collections.emptyList();
        String fp = fingerprint(errorCode, errorFragment, macroPrefix);
        List<Entry> out = new ArrayList<Entry>();
        synchronized (lock) {
            if (writeBlocked) {
                throw new PersistenceException("CORRUPT_STORE_BLOCKED",
                        "ledger is unavailable after load failure: " + loadError);
            }
            Entry direct = entries.get(fp);
            if (direct != null) out.add(direct);
            // Fuzzy fallback: same errorCode + errorFragment normalised, any
            // macro prefix. Cheap fallback for when the caller's macro
            // preamble differs slightly from the recorded one.
            String normFrag = normaliseError(errorFragment);
            String normCode = errorCode == null ? "" : errorCode;
            if (!normFrag.isEmpty() || !normCode.isEmpty()) {
                for (Entry e : entries.values()) {
                    if (e == direct) continue;
                    String eCode = e.errorCode == null ? "" : e.errorCode;
                    if (!eCode.equals(normCode)) continue;
                    if (!normaliseError(e.errorFragment).equals(normFrag)) continue;
                    out.add(e);
                }
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int ca = confidenceRank(a);
                int cb = confidenceRank(b);
                if (ca != cb) return Integer.compare(cb, ca);
                int seen = Integer.compare(b.timesSeen, a.timesSeen);
                return seen != 0 ? seen : a.fingerprint.compareTo(b.fingerprint);
            }
        });
        if (out.size() > max) out = new ArrayList<Entry>(out.subList(0, max));
        return Collections.unmodifiableList(out);
    }

    /**
     * Record a confirmation. If an entry with {@code fingerprint} already
     * exists its counters and {@code confirmedBy} set are updated; otherwise
     * a fresh entry is created from the supplied context. {@code worked} flips
     * {@code confirmationsTrue} vs {@code confirmationsFalse} — only a
     * {@code worked == true} call populates {@code confirmedFix} when the
     * entry is new, so a stream of "didn't work" confirms never silently
     * overwrites a useful fix field.
     */
    Entry confirm(String fingerprint,
                  String errorCode,
                  String errorFragment,
                  String macroPrefix,
                  String fix,
                  String exampleMacro,
                  String agentId,
                  boolean worked) {
        requireField("fingerprint", fingerprint, 256);
        requireField("errorCode", errorCode, MAX_FIELD_BYTES);
        requireField("errorFragment", errorFragment, MAX_FIELD_BYTES);
        requireField("macroPrefix", macroPrefix, MAX_FIELD_BYTES);
        requireField("fix", fix, MAX_FIELD_BYTES);
        requireField("exampleMacro", exampleMacro, MAX_FIELD_BYTES);
        requireField("agentId", agentId, 16 * 1024);
        if (fingerprint == null || fingerprint.isEmpty()) {
            fingerprint = fingerprint(errorCode, errorFragment, macroPrefix);
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (writeBlocked) {
                throw new PersistenceException("CORRUPT_STORE_BLOCKED",
                        "ledger writes are blocked after load failure: " + loadError);
            }
            Entry old = entries.get(fingerprint);
            LinkedHashSet<String> agents = new LinkedHashSet<String>();
            if (old != null) agents.addAll(old.confirmedBy);
            if (agentId != null && !agentId.isEmpty()) agents.add(agentId);
            if (agents.size() > MAX_AGENTS_PER_ENTRY) {
                throw new PersistenceException("ENTRY_LIMIT_EXCEEDED",
                        "confirmedBy exceeds " + MAX_AGENTS_PER_ENTRY + " agents");
            }
            String confirmedFix = old == null ? null : old.confirmedFix;
            if (worked && fix != null && !fix.isEmpty()
                    && (confirmedFix == null || confirmedFix.isEmpty())) {
                confirmedFix = fix;
            }
            String example = old == null ? null : old.exampleMacro;
            if ((example == null || example.isEmpty())
                    && exampleMacro != null && !exampleMacro.isEmpty()) {
                example = exampleMacro;
            }
            Entry e = new Entry(fingerprint,
                    old == null ? valueOrEmpty(macroPrefix) : old.macroPrefix,
                    old == null ? valueOrEmpty(errorCode) : old.errorCode,
                    old == null ? valueOrEmpty(errorFragment) : old.errorFragment,
                    confirmedFix, example, agents,
                    incrementChecked(old == null ? 0 : old.timesSeen, "timesSeen"),
                    incrementChecked(old == null ? 0 : old.confirmationsTrue,
                            worked ? "confirmationsTrue" : null),
                    incrementChecked(old == null ? 0 : old.confirmationsFalse,
                            worked ? null : "confirmationsFalse"),
                    old == null ? now : old.firstSeen, now);
            entries.put(fingerprint, e);
            evictIfOversized();
            save();
            return e;
        }
    }

    /** Current confidence tier for {@code e}. Mirrors plan §Confidence tiers. */
    static String confidenceOf(Entry e) {
        if (e == null) return "low";
        int t = e.confirmationsTrue;
        int f = e.confirmationsFalse;
        // Contradicted entries (ratio below 2:1) collapse to low regardless
        // of the seen count — mitigates the "ledger poisoning" failure mode.
        if (f > 0 && t < 2 * f) return "low";
        int distinctAgents = e.confirmedBy.size();
        if (distinctAgents >= 2 && e.timesSeen >= 5) return "high";
        if (distinctAgents >= 1 && e.timesSeen >= 2) return "medium";
        return "low";
    }

    /** Rank for ordering: high=2, medium=1, low=0. */
    private static int confidenceRank(Entry e) {
        String c = confidenceOf(e);
        if ("high".equals(c)) return 2;
        if ("medium".equals(c)) return 1;
        return 0;
    }

    /** Build the {@code suggested[]} entry the TCP layer attaches to errors. */
    static JsonObject toSuggestedJson(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("fromLedger", true);
        o.addProperty("fingerprint", e.fingerprint);
        if (e.confirmedFix != null) o.addProperty("confirmedFix", e.confirmedFix);
        if (e.exampleMacro != null) o.addProperty("exampleMacro", e.exampleMacro);
        o.addProperty("confidence", confidenceOf(e));
        o.addProperty("timesSeen", e.timesSeen);
        JsonArray by = new JsonArray();
        for (String a : e.confirmedBy) by.add(a);
        o.add("confirmedBy", by);
        return o;
    }

    // -----------------------------------------------------------------------
    // Fingerprint + normalisation
    // -----------------------------------------------------------------------

    /** Stable 128-bit fingerprint of the error context. See plan §Fingerprint design. */
    static String fingerprint(String errorCode, String errorFragment, String macroPrefix) {
        String normCode  = errorCode == null ? "" : errorCode;
        String normFrag  = normaliseError(errorFragment);
        String normMacro = normaliseMacro(macroPrefix);
        String payload = normCode + "\0" + normFrag + "\0" + normMacro;
        return sha256Hex128(payload);
    }

    /**
     * Strip volatile parts of an error message — line numbers, digits, file
     * paths, whitespace variance — so two instances of the same failure class
     * fingerprint identically. Null/empty inputs pass through as empty.
     */
    static String normaliseError(String f) {
        if (f == null) return "";
        String s = f
                .replaceAll("(?i)line\\s+\\d+", "line ?")
                .replaceAll("(?i)at line\\s+\\d+", "at line ?")
                .replaceAll("\\d+", "?")
                .toLowerCase(Locale.ROOT);
        return s.trim().replaceAll("\\s+", " ");
    }

    /**
     * Collapse a macro snippet down to the first two {@code run("<name>"...)}
     * plugin invocations. Throws away arguments and ordering noise so entries
     * key on the operation sequence rather than the specific parameters.
     */
    static String normaliseMacro(String m) {
        if (m == null || m.isEmpty()) return "";
        Matcher matcher = RUN_CALL.matcher(m);
        List<String> names = new ArrayList<String>();
        while (matcher.find() && names.size() < 2) {
            names.add(matcher.group(1).trim().toLowerCase(Locale.ROOT));
        }
        if (names.isEmpty()) {
            // Fall back to the first non-empty line so macros without run()
            // calls still produce a stable (if coarser) signature.
            String[] lines = m.trim().split("\\r?\\n");
            for (String line : lines) {
                String t = line.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) return t;
            }
            return "";
        }
        return String.join("|", names);
    }

    private static String sha256Hex128(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(FINGERPRINT_HEX_LEN);
            // Truncate to 128 bits — first 16 bytes.
            for (int i = 0; i < 16; i++) {
                int v = digest[i] & 0xff;
                if (v < 0x10) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is guaranteed — fall back to a stable string hash so we
            // never NPE out on the hot path.
            return String.format(Locale.ROOT, "%032x", (long) payload.hashCode());
        }
    }

    // -----------------------------------------------------------------------
    // IO
    // -----------------------------------------------------------------------

    private void load() {
        synchronized (lock) {
            entries.clear();
            if (path == null || !Files.exists(path)) return;
            try {
                String raw = SafeFileIO.readUtf8Bounded(path, MAX_STORE_BYTES);
                JsonElement root = JsonParser.parseString(raw);
                if (root == null || !root.isJsonObject()) {
                    throw new IllegalArgumentException("root is not an object");
                }
                JsonObject object = root.getAsJsonObject();
                if (!object.has("version") || object.get("version").getAsInt() != FORMAT_VERSION) {
                    throw new IllegalArgumentException("unsupported or missing version");
                }
                JsonElement entriesEl = object.get("entries");
                if (entriesEl == null || !entriesEl.isJsonArray()) {
                    throw new IllegalArgumentException("entries is not an array");
                }
                if (entriesEl.getAsJsonArray().size() > MAX_ENTRIES) {
                    throw new IllegalArgumentException("entries exceeds " + MAX_ENTRIES);
                }
                Map<String, Entry> loaded = new HashMap<String, Entry>();
                for (JsonElement el : entriesEl.getAsJsonArray()) {
                    if (el == null || !el.isJsonObject()) {
                        throw new IllegalArgumentException("entry is not an object");
                    }
                    Entry e = Entry.fromJson(el.getAsJsonObject());
                    validateLoadedEntry(e);
                    if (loaded.put(e.fingerprint, e) != null) {
                        throw new IllegalArgumentException("duplicate fingerprint: " + e.fingerprint);
                    }
                }
                entries.putAll(loaded);
                memoryOnly = false;
                writeBlocked = false;
                loadError = null;
            } catch (IOException | RuntimeException ex) {
                markCorrupt(ex);
            }
        }
    }

    /** Persist the in-memory state. Callers already hold {@link #lock}. */
    private void save() {
        if (writeBlocked) {
            throw new PersistenceException("CORRUPT_STORE_BLOCKED",
                    "refusing to overwrite a store that failed validation");
        }
        if (path == null) {
            memoryOnly = true;
            throw new PersistenceException("NO_STORE_PATH", "ledger path is unavailable");
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            JsonArray arr = new JsonArray();
            // Deterministic output order by lastSeen ascending so diffs are
            // readable. TreeMap tolerates duplicate lastSeen values because we
            // key by (lastSeen, fingerprint).
            TreeMap<String, Entry> sorted = new TreeMap<String, Entry>();
            for (Entry e : entries.values()) {
                sorted.put(String.format(Locale.ROOT, "%020d-%s", e.lastSeen, e.fingerprint), e);
            }
            for (Entry e : sorted.values()) arr.add(e.toJson());
            root.add("entries", arr);
            String json = root.toString();
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_STORE_BYTES) {
                throw new IOException("serialized store exceeds " + MAX_STORE_BYTES + " bytes");
            }
            SafeFileIO.writeUtf8Atomically(path, json);
            memoryOnly = false;
        } catch (IOException ex) {
            memoryOnly = true;
            throw new PersistenceException("STORE_WRITE_FAILED",
                    "ledger save failed: " + ex.getMessage());
        } catch (SecurityException ex) {
            memoryOnly = true;
            throw new PersistenceException("STORE_WRITE_DENIED",
                    "ledger save denied: " + ex.getMessage());
        }
    }

    private void markCorrupt(Exception ex) {
        memoryOnly = true;
        writeBlocked = true;
        loadError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        try {
            quarantinedPath = SafeFileIO.quarantine(path, loadError);
        } catch (IOException quarantineFailure) {
            loadError += "; quarantine failed: " + quarantineFailure.getMessage();
        }
        System.err.println("[ImageJAI-Ledger] invalid store quarantined; writes blocked: "
                + loadError);
    }

    private void evictIfOversized() {
        if (entries.size() <= MAX_ENTRIES) return;
        // LRU by lastSeen ascending — oldest goes first. One eviction per
        // over-cap confirm is enough because the cap is enforced after every
        // insert.
        List<Entry> all = new ArrayList<Entry>(entries.values());
        Collections.sort(all, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int time = Long.compare(a.lastSeen, b.lastSeen);
                return time != 0 ? time : a.fingerprint.compareTo(b.fingerprint);
            }
        });
        int toRemove = entries.size() - MAX_ENTRIES;
        for (int i = 0; i < toRemove && i < all.size(); i++) {
            entries.remove(all.get(i).fingerprint);
        }
    }

    // -----------------------------------------------------------------------
    // Small JSON helpers (duplicated tiny ones here to keep the class free of
    // a reverse dependency on TCPCommandServer's private optString / optInt).
    // -----------------------------------------------------------------------

    private static String optString(JsonObject o, String key, String def) {
        if (o == null) return def;
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return def;
        try { return el.getAsString(); } catch (Exception e) { return def; }
    }

    private static int optInt(JsonObject o, String key, int def) {
        if (o == null) return def;
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return def;
        try { return el.getAsInt(); } catch (Exception e) { return def; }
    }

    private static long optLong(JsonObject o, String key, long def) {
        if (o == null) return def;
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive()) return def;
        try { return el.getAsLong(); } catch (Exception e) { return def; }
    }

    private static int requiredNonNegativeInt(JsonObject o, String key) {
        if (o == null || !o.has(key)) throw new IllegalArgumentException("missing " + key);
        int value;
        try { value = o.get(key).getAsInt(); }
        catch (Exception e) { throw new IllegalArgumentException("invalid " + key, e); }
        if (value < 0) throw new IllegalArgumentException("negative " + key);
        return value;
    }

    private static long requiredNonNegativeLong(JsonObject o, String key) {
        if (o == null || !o.has(key)) throw new IllegalArgumentException("missing " + key);
        long value;
        try { value = o.get(key).getAsLong(); }
        catch (Exception e) { throw new IllegalArgumentException("invalid " + key, e); }
        if (value < 0L) throw new IllegalArgumentException("negative " + key);
        return value;
    }

    private static void validateLoadedEntry(Entry e) {
        requireField("fingerprint", e.fingerprint, 256);
        requireField("macroPrefix", e.macroPrefix, MAX_FIELD_BYTES);
        requireField("errorCode", e.errorCode, MAX_FIELD_BYTES);
        requireField("errorFragment", e.errorFragment, MAX_FIELD_BYTES);
        requireField("confirmedFix", e.confirmedFix, MAX_FIELD_BYTES);
        requireField("exampleMacro", e.exampleMacro, MAX_FIELD_BYTES);
        if (e.confirmedBy.size() > MAX_AGENTS_PER_ENTRY) {
            throw new IllegalArgumentException("too many confirmedBy values");
        }
        for (String agent : e.confirmedBy) requireField("confirmedBy", agent, 16 * 1024);
    }

    private static void requireField(String name, String value, int maxBytes) {
        if (value == null) return;
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw new PersistenceException("ENTRY_LIMIT_EXCEEDED",
                    name + " exceeds " + maxBytes + " UTF-8 bytes");
        }
    }

    private static int incrementChecked(int current, String fieldToIncrement) {
        if (fieldToIncrement == null) return current;
        if (current == Integer.MAX_VALUE) {
            throw new PersistenceException("COUNTER_OVERFLOW", fieldToIncrement + " overflow");
        }
        return current + 1;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
