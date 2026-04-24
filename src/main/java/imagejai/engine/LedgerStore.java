package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** One immutable-ish ledger entry. Fields are mutated under {@link #lock}. */
    static final class Entry {
        final String fingerprint;
        String macroPrefix;
        String errorCode;
        String errorFragment;
        String confirmedFix;
        String exampleMacro;
        final Set<String> confirmedBy = new LinkedHashSet<String>();
        int timesSeen;
        int confirmationsTrue;
        int confirmationsFalse;
        long firstSeen;
        long lastSeen;

        Entry(String fingerprint) {
            this.fingerprint = fingerprint;
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
            if (fp == null || fp.isEmpty()) return null;
            Entry e = new Entry(fp);
            e.macroPrefix      = optString(o, "macroPrefix", null);
            e.errorCode        = optString(o, "errorCode", null);
            e.errorFragment    = optString(o, "errorFragment", null);
            e.confirmedFix     = optString(o, "confirmedFix", null);
            e.exampleMacro     = optString(o, "exampleMacro", null);
            e.timesSeen        = optInt(o, "timesSeen", 0);
            e.confirmationsTrue  = optInt(o, "confirmationsTrue", 0);
            e.confirmationsFalse = optInt(o, "confirmationsFalse", 0);
            e.firstSeen        = optLong(o, "firstSeen", 0L);
            e.lastSeen         = optLong(o, "lastSeen", 0L);
            JsonElement by = o.get("confirmedBy");
            if (by != null && by.isJsonArray()) {
                for (JsonElement el : by.getAsJsonArray()) {
                    if (el != null && el.isJsonPrimitive()) {
                        try { e.confirmedBy.add(el.getAsString()); }
                        catch (Exception ignore) {}
                    }
                }
            }
            return e;
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
        String fp = fingerprint(errorCode, errorFragment, macroPrefix);
        List<Entry> out = new ArrayList<Entry>();
        synchronized (lock) {
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
                return Integer.compare(b.timesSeen, a.timesSeen);
            }
        });
        if (out.size() > max) out = new ArrayList<Entry>(out.subList(0, max));
        return out;
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
        if (fingerprint == null || fingerprint.isEmpty()) {
            fingerprint = fingerprint(errorCode, errorFragment, macroPrefix);
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            Entry e = entries.get(fingerprint);
            if (e == null) {
                e = new Entry(fingerprint);
                e.errorCode     = errorCode == null ? "" : errorCode;
                e.errorFragment = errorFragment == null ? "" : errorFragment;
                e.macroPrefix   = macroPrefix == null ? "" : macroPrefix;
                e.firstSeen     = now;
                if (worked && fix != null && !fix.isEmpty()) {
                    e.confirmedFix = fix;
                }
                if (exampleMacro != null && !exampleMacro.isEmpty()) {
                    e.exampleMacro = exampleMacro;
                }
                entries.put(fingerprint, e);
            } else {
                // Existing entry: refresh the fix if this call carried a
                // stronger one AND reports success.
                if (worked && fix != null && !fix.isEmpty()
                        && (e.confirmedFix == null || e.confirmedFix.isEmpty())) {
                    e.confirmedFix = fix;
                }
                if ((e.exampleMacro == null || e.exampleMacro.isEmpty())
                        && exampleMacro != null && !exampleMacro.isEmpty()) {
                    e.exampleMacro = exampleMacro;
                }
            }
            e.timesSeen++;
            if (worked) e.confirmationsTrue++;
            else e.confirmationsFalse++;
            if (agentId != null && !agentId.isEmpty()) {
                e.confirmedBy.add(agentId);
            }
            e.lastSeen = now;
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
                .toLowerCase();
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
            names.add(matcher.group(1).trim().toLowerCase());
        }
        if (names.isEmpty()) {
            // Fall back to the first non-empty line so macros without run()
            // calls still produce a stable (if coarser) signature.
            String[] lines = m.trim().split("\\r?\\n");
            for (String line : lines) {
                String t = line.trim().toLowerCase();
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
            return String.format("%032x", (long) payload.hashCode());
        }
    }

    // -----------------------------------------------------------------------
    // IO
    // -----------------------------------------------------------------------

    private void load() {
        synchronized (lock) {
            entries.clear();
            if (path == null || !Files.exists(path)) return;
            try (Reader r = new InputStreamReader(Files.newInputStream(path),
                    StandardCharsets.UTF_8);
                 BufferedReader br = new BufferedReader(r)) {
                JsonElement root = new JsonParser().parse(br);
                if (root == null || !root.isJsonObject()) return;
                JsonElement entriesEl = root.getAsJsonObject().get("entries");
                if (entriesEl == null || !entriesEl.isJsonArray()) return;
                for (JsonElement el : entriesEl.getAsJsonArray()) {
                    if (el == null || !el.isJsonObject()) continue;
                    Entry e = Entry.fromJson(el.getAsJsonObject());
                    if (e != null) entries.put(e.fingerprint, e);
                }
                memoryOnly = false;
            } catch (IOException ex) {
                memoryOnly = true;
                System.err.println("[ImageJAI-Ledger] load failed: " + ex.getMessage());
            } catch (RuntimeException ex) {
                memoryOnly = true;
                System.err.println("[ImageJAI-Ledger] parse failed: " + ex.getMessage());
            }
        }
    }

    /** Persist the in-memory state. Callers already hold {@link #lock}. */
    private void save() {
        if (path == null) { memoryOnly = true; return; }
        try {
            Path dir = path.getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = (dir == null)
                    ? Paths.get(path.toString() + ".tmp")
                    : dir.resolve(path.getFileName().toString() + ".tmp");

            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            JsonArray arr = new JsonArray();
            // Deterministic output order by lastSeen ascending so diffs are
            // readable. TreeMap tolerates duplicate lastSeen values because we
            // key by (lastSeen, fingerprint).
            TreeMap<String, Entry> sorted = new TreeMap<String, Entry>();
            for (Entry e : entries.values()) {
                sorted.put(String.format("%020d-%s", e.lastSeen, e.fingerprint), e);
            }
            for (Entry e : sorted.values()) arr.add(e.toJson());
            root.add("entries", arr);

            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tmp),
                    StandardCharsets.UTF_8);
                 BufferedWriter bw = new BufferedWriter(w)) {
                bw.write(root.toString());
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                // Windows across drives can't atomic-move. REPLACE_EXISTING
                // alone is not torn-write-safe but is the best we can do.
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            memoryOnly = false;
        } catch (IOException ex) {
            memoryOnly = true;
            System.err.println("[ImageJAI-Ledger] save failed: " + ex.getMessage());
        } catch (SecurityException ex) {
            memoryOnly = true;
            System.err.println("[ImageJAI-Ledger] save denied: " + ex.getMessage());
        }
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
                return Long.compare(a.lastSeen, b.lastSeen);
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
}
