package imagejai.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Phase 5 — server-side intent mappings.
 *
 * <p>Resolves a user-typed phrase to a macro via a user-taught dictionary
 * stored at {@code ~/.imagej-ai/intent_mappings.json}. First-match-wins
 * regex lookup, capture groups usable as {@code $1}..{@code $9} in the
 * macro template. Hot-reloads on file mtime change (stat-on-call, no
 * filesystem watcher). Invalid regexes are quarantined rather than
 * fatal — the router survives a bad external edit.
 *
 * <p>The file is the authoritative source of truth. Every successful
 * resolve() updates {@code hits} / {@code last_used} and atomically
 * rewrites the file via {@code Files.move(tmp, target, ATOMIC_MOVE)}.
 */
public class IntentRouter {

    /** Persisted version tag so a future schema change can migrate cleanly. */
    public static final int VERSION = 1;

    public static class Mapping {
        public final String patternSrc;
        public final Pattern pattern;
        public String macro;
        public String description;
        public int hits;
        public String createdAt;
        public String lastUsed;

        Mapping(String patternSrc, Pattern pattern, String macro,
                String description, int hits, String createdAt, String lastUsed) {
            this.patternSrc = patternSrc;
            this.pattern = pattern;
            this.macro = macro;
            this.description = description;
            this.hits = hits;
            this.createdAt = createdAt;
            this.lastUsed = lastUsed;
        }
    }

    public static class Quarantined {
        public final String patternSrc;
        public final String macro;
        public final String description;
        public final String error;

        Quarantined(String patternSrc, String macro, String description, String error) {
            this.patternSrc = patternSrc;
            this.macro = macro;
            this.description = description;
            this.error = error;
        }
    }

    /** Return value of {@link #resolve(String)}: the substituted macro plus the matched mapping. */
    public static class Resolved {
        public final String macro;
        public final Mapping mapping;

        Resolved(String macro, Mapping mapping) {
            this.macro = macro;
            this.mapping = mapping;
        }
    }

    private final Path storePath;
    private final Gson gson;
    private List<Mapping> mappings = new ArrayList<Mapping>();
    private List<Quarantined> quarantined = new ArrayList<Quarantined>();
    private long lastLoadedMtime = -1L;

    public IntentRouter() {
        this(defaultPath());
    }

    public IntentRouter(Path storePath) {
        this.storePath = storePath;
        this.gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        reloadIfChanged();
    }

    /** {@code ~/.imagej-ai/intent_mappings.json}. */
    public static Path defaultPath() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) home = ".";
        return Paths.get(home, ".imagej-ai", "intent_mappings.json");
    }

    public Path getStorePath() {
        return storePath;
    }

    /**
     * Walk mappings in list order; first {@code matches()} wins. On hit,
     * increments {@code hits}, stamps {@code last_used}, persists, and
     * returns the substituted macro plus matched mapping. On miss, returns
     * {@link java.util.Optional#empty()}.
     *
     * <p>{@code null} or all-whitespace input always misses.
     */
    public synchronized java.util.Optional<Resolved> resolve(String phrase) {
        if (phrase == null) return java.util.Optional.empty();
        String trimmed = phrase.trim();
        if (trimmed.isEmpty()) return java.util.Optional.empty();
        reloadIfChanged();
        for (Mapping m : mappings) {
            Matcher mt = m.pattern.matcher(trimmed);
            if (mt.matches()) {
                m.hits++;
                m.lastUsed = nowIso();
                persist();
                return java.util.Optional.of(new Resolved(substitute(m.macro, mt), m));
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Template substitution: {@code $1}..{@code $9} replaced with the
     * matched group values. Literal (not regex) replace — a group value
     * containing {@code \} or {@code $} is inserted verbatim.
     */
    static String substitute(String template, Matcher m) {
        if (template == null) return "";
        if (m == null) return template;
        String out = template;
        int groupCount = m.groupCount();
        // Iterate high-to-low so that $10 never exists to collide; capped at
        // $9 per the spec, but this ordering stays safe if that ever grows.
        for (int i = groupCount; i >= 1; i--) {
            if (i > 9) continue; // spec caps at $1..$9
            String g = m.group(i);
            if (g == null) g = "";
            out = out.replace("$" + i, g);
        }
        return out;
    }

    /**
     * Add (or update, by patternSrc) a mapping and persist. Throws
     * {@link IllegalArgumentException} if the regex is invalid — callers
     * get a clean error to return to the client rather than silent
     * quarantining of a user-submitted teach.
     */
    public synchronized Mapping teach(String patternStr, String macro, String description) {
        if (patternStr == null || patternStr.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be empty");
        }
        if (macro == null) macro = "";
        reloadIfChanged();

        Pattern p;
        try {
            p = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            String desc = e.getDescription();
            if (desc == null) desc = e.getMessage();
            throw new IllegalArgumentException("Invalid regex: " + desc);
        }

        String now = nowIso();
        Mapping existing = findByPatternSrc(patternStr);
        if (existing != null) {
            existing.macro = macro;
            if (description != null) existing.description = description;
            // preserve hits + createdAt; teach is an update, not a reset
        } else {
            existing = new Mapping(patternSrc(patternStr), p, macro, description, 0, now, null);
            mappings.add(existing);
        }
        // Also drop any quarantined entry with the same patternSrc — user has
        // taught a valid replacement.
        removeFromQuarantine(patternStr);
        persist();
        return existing;
    }

    private static String patternSrc(String s) { return s; }

    private Mapping findByPatternSrc(String patternStr) {
        for (Mapping m : mappings) {
            if (m.patternSrc.equals(patternStr)) return m;
        }
        return null;
    }

    private void removeFromQuarantine(String patternStr) {
        Iterator<Quarantined> it = quarantined.iterator();
        while (it.hasNext()) {
            if (it.next().patternSrc.equals(patternStr)) it.remove();
        }
    }

    /**
     * Remove mappings or quarantined entries by patternSrc. Returns {@code true}
     * iff at least one entry was removed.
     */
    public synchronized boolean forget(String patternStr) {
        if (patternStr == null) return false;
        reloadIfChanged();
        boolean removed = false;
        Iterator<Mapping> it = mappings.iterator();
        while (it.hasNext()) {
            if (it.next().patternSrc.equals(patternStr)) {
                it.remove();
                removed = true;
            }
        }
        Iterator<Quarantined> qit = quarantined.iterator();
        while (qit.hasNext()) {
            if (qit.next().patternSrc.equals(patternStr)) {
                qit.remove();
                removed = true;
            }
        }
        if (removed) persist();
        return removed;
    }

    /** All mappings + quarantined list as a JSON object. */
    public synchronized JsonObject list() {
        reloadIfChanged();
        JsonObject out = new JsonObject();
        out.addProperty("version", VERSION);
        out.addProperty("path", storePath.toString());
        JsonArray arr = new JsonArray();
        for (Mapping m : mappings) arr.add(mappingToJson(m));
        out.add("mappings", arr);
        JsonArray qarr = new JsonArray();
        for (Quarantined q : quarantined) qarr.add(quarantinedToJson(q));
        out.add("quarantined", qarr);
        return out;
    }

    JsonObject mappingToJson(Mapping m) {
        JsonObject j = new JsonObject();
        j.addProperty("pattern", m.patternSrc);
        j.addProperty("macro", m.macro == null ? "" : m.macro);
        if (m.description != null) j.addProperty("description", m.description);
        j.addProperty("hits", m.hits);
        if (m.createdAt != null) j.addProperty("created_at", m.createdAt);
        if (m.lastUsed != null) j.addProperty("last_used", m.lastUsed);
        return j;
    }

    private JsonObject quarantinedToJson(Quarantined q) {
        JsonObject j = new JsonObject();
        j.addProperty("pattern", q.patternSrc);
        j.addProperty("macro", q.macro == null ? "" : q.macro);
        if (q.description != null) j.addProperty("description", q.description);
        j.addProperty("error", q.error == null ? "" : q.error);
        return j;
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private void reloadIfChanged() {
        try {
            if (!Files.exists(storePath)) {
                if (lastLoadedMtime != -1L || !mappings.isEmpty() || !quarantined.isEmpty()) {
                    // File was deleted externally — drop any cached state.
                    mappings = new ArrayList<Mapping>();
                    quarantined = new ArrayList<Quarantined>();
                    lastLoadedMtime = -1L;
                }
                return;
            }
            long mtime = Files.getLastModifiedTime(storePath).toMillis();
            if (mtime != lastLoadedMtime) {
                load();
                lastLoadedMtime = mtime;
            }
        } catch (IOException ignore) {
            // best-effort — keep whatever's in memory
        }
    }

    private void load() {
        List<Mapping> fresh = new ArrayList<Mapping>();
        List<Quarantined> freshQ = new ArrayList<Quarantined>();
        try {
            byte[] raw = Files.readAllBytes(storePath);
            if (raw.length == 0) {
                mappings = fresh;
                quarantined = freshQ;
                return;
            }
            JsonElement el = new JsonParser().parse(new String(raw, StandardCharsets.UTF_8));
            if (el == null || !el.isJsonObject()) {
                mappings = fresh;
                quarantined = freshQ;
                return;
            }
            JsonObject obj = el.getAsJsonObject();
            JsonElement arrEl = obj.get("mappings");
            if (arrEl != null && arrEl.isJsonArray()) {
                for (JsonElement e : arrEl.getAsJsonArray()) {
                    if (e == null || !e.isJsonObject()) continue;
                    JsonObject mo = e.getAsJsonObject();
                    String patternSrc = optStr(mo, "pattern", null);
                    if (patternSrc == null || patternSrc.isEmpty()) continue;
                    String macro = optStr(mo, "macro", "");
                    String description = optStr(mo, "description", null);
                    int hits = optInt(mo, "hits", 0);
                    String createdAt = optStr(mo, "created_at", null);
                    String lastUsed = optStr(mo, "last_used", null);
                    try {
                        Pattern p = Pattern.compile(patternSrc, Pattern.CASE_INSENSITIVE);
                        fresh.add(new Mapping(patternSrc, p, macro, description,
                                hits, createdAt, lastUsed));
                    } catch (PatternSyntaxException px) {
                        String d = px.getDescription();
                        if (d == null) d = px.getMessage();
                        freshQ.add(new Quarantined(patternSrc, macro, description, d));
                    }
                }
            }
            // Also preserve any prior quarantined list from the file so a manual
            // edit can re-surface old bad entries until the user forgets them.
            JsonElement qEl = obj.get("quarantined");
            if (qEl != null && qEl.isJsonArray()) {
                for (JsonElement e : qEl.getAsJsonArray()) {
                    if (e == null || !e.isJsonObject()) continue;
                    JsonObject qo = e.getAsJsonObject();
                    String patternSrc = optStr(qo, "pattern", null);
                    if (patternSrc == null || patternSrc.isEmpty()) continue;
                    // If this patternSrc is already represented in mappings, skip.
                    boolean dup = false;
                    for (Mapping m : fresh) {
                        if (m.patternSrc.equals(patternSrc)) { dup = true; break; }
                    }
                    if (dup) continue;
                    String macro = optStr(qo, "macro", "");
                    String description = optStr(qo, "description", null);
                    String error = optStr(qo, "error", "(unknown)");
                    freshQ.add(new Quarantined(patternSrc, macro, description, error));
                }
            }
        } catch (IOException ignore) {
            // keep previous in-memory state on IO error
            return;
        } catch (JsonParseException ignore) {
            // corrupt file — treat as empty. Don't overwrite; a later teach/forget will.
        }
        mappings = fresh;
        quarantined = freshQ;
    }

    private void persist() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray arr = new JsonArray();
        for (Mapping m : mappings) arr.add(mappingToJson(m));
        root.add("mappings", arr);
        if (!quarantined.isEmpty()) {
            JsonArray qarr = new JsonArray();
            for (Quarantined q : quarantined) qarr.add(quarantinedToJson(q));
            root.add("quarantined", qarr);
        }
        try {
            Path dir = storePath.getParent();
            if (dir != null && !Files.exists(dir)) Files.createDirectories(dir);
            Path tmp = storePath.resolveSibling(storePath.getFileName().toString() + ".tmp");
            Files.write(tmp, gson.toJson(root).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, storePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException amnse) {
                // Fallback for filesystems that don't support atomic move (rare).
                Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                lastLoadedMtime = Files.getLastModifiedTime(storePath).toMillis();
            } catch (IOException ignore) {
                // mtime read failure is non-fatal; next resolve will reload.
            }
        } catch (IOException ignore) {
            // Persistence failure is best-effort. Mapping change survives in
            // memory for this JVM; next restart loses it.
        }
    }

    // ---------------------------------------------------------------------
    // Small JSON helpers
    // ---------------------------------------------------------------------

    private static String optStr(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try { return e.getAsString(); } catch (Exception ex) { return def; }
    }

    private static int optInt(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try { return e.getAsInt(); } catch (Exception ex) { return def; }
    }

    private static String nowIso() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    // ---------------------------------------------------------------------
    // Package-private accessors for tests
    // ---------------------------------------------------------------------

    synchronized int mappingCount() { return mappings.size(); }
    synchronized int quarantinedCount() { return quarantined.size(); }
}
