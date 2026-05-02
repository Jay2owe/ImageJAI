package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.io.FileInfo;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Silent per-session log of every macro / script the agent has run.
 *
 * <p>Hooked into {@link TCPCommandServer#handleExecuteMacro} and
 * {@code handleRunScript} via {@link #INSTANCE}. The agent has zero
 * visibility into this: no new TCP command exposes it, no event stream
 * touches it, no response field carries it. Its only consumers live
 * inside the Java plugin (UI from stage 11 of the embedded-agent-widget
 * plan).
 *
 * <p>Storage: in-memory ring of last 200 entries + append-only files at
 * {@code AI_Exports/.session/code/&lt;HHMMSS&gt;_&lt;name&gt;.ext}.
 *
 * <p>Dedup rules: skip code ≤20 chars; if new code's canonical form
 * matches an existing entry, bump its run-count and promote it to head
 * instead of adding a duplicate row.
 */
public final class SessionCodeJournal {

    public static final SessionCodeJournal INSTANCE = new SessionCodeJournal();
    public static final String PREF_PERSIST = "ai.assistant.history.persist";
    private static final int RING_CAP = 200;
    private static final int MIN_CODE_LEN = 20;
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HHmmss");

    /** Immutable snapshot view of a journal entry. */
    public static final class Entry {
        public final long id;
        public final String name;
        public final String language;
        public final String code;
        public final String canonical;
        public final String fileName;
        public final String source;
        public final long macroId;
        public final long firstRunAtMs;
        public long lastRunAtMs;
        public final long durationMs;
        public final boolean success;
        public final String failureMessage;
        public final boolean plumbingOnly;
        public int runCount;

        Entry(long id, String name, String language, String code, String canonical,
              String fileName, String source, long macroId, long startedAtMs,
              long durationMs, boolean success, String failureMessage,
              boolean plumbingOnly) {
            this.id = id;
            this.name = name;
            this.language = language;
            this.code = code;
            this.canonical = canonical;
            this.fileName = fileName;
            this.source = source;
            this.macroId = macroId;
            this.firstRunAtMs = startedAtMs;
            this.lastRunAtMs = startedAtMs;
            this.durationMs = durationMs;
            this.success = success;
            this.failureMessage = failureMessage;
            this.plumbingOnly = plumbingOnly;
            this.runCount = 1;
        }

        public boolean isPlumbingOnly() {
            return plumbingOnly;
        }
    }

    /** Called synchronously on every record / rerun / promotion. */
    public interface Listener { void onChange(); }

    // ---- state ----
    private final Deque<Entry> ring = new ArrayDeque<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);
    private final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "imagej-ai-journal-io");
                t.setDaemon(true);
                return t;
            });
    private boolean indexLoadAttempted;
    private SessionCodeJournal() {}

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /**
     * Snapshot the current ring (newest first). Safe to iterate without
     * holding the journal lock.
     */
    public synchronized List<Entry> snapshot() {
        loadFromIndexIfPersistEnabled();
        return new ArrayList<>(ring);
    }

    /**
     * Record a macro / script execution. The {@code source} string mirrors
     * the existing {@code macro.started} event's source field so later
     * rail-history re-runs can be suppressed by tagging them differently.
     */
    public void record(String language, String code, String source,
                       long macroId, long startedAtMs, long durationMs,
                       boolean success, String failureMessage) {
        if (code == null) return;
        String trimmed = code.trim();
        if (trimmed.length() <= MIN_CODE_LEN) return;

        loadFromIndexIfPersistEnabled();
        String canonical = canonicalise(code);
        Entry toWrite = null;
        List<Entry> indexSnapshot = null;
        synchronized (this) {
            Entry head = ring.peekFirst();
            if (head != null && head.canonical.equals(canonical)) {
                head.runCount++;
                head.lastRunAtMs = startedAtMs;
                indexSnapshot = new ArrayList<>(ring);
            } else for (Iterator<Entry> it = ring.iterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (e.canonical.equals(canonical)) {
                    it.remove();
                    e.runCount++;
                    e.lastRunAtMs = startedAtMs;
                    ring.addFirst(e);
                    indexSnapshot = new ArrayList<>(ring);
                    break;
                }
            }
            if (indexSnapshot == null) {
                String timeSuffix = timeSuffix(startedAtMs);
                CodeAutoNamer.NamingResult name = CodeAutoNamer.describeFor(language, code, timeSuffix);
                String slug = name.slug;
                slug = dedupSlug(slug);
                String safeLanguage = language == null ? "ijm" : language;
                String fileName = timeSuffix + "_" + slug + "." + extensionFor(safeLanguage);
                Entry e = new Entry(idSeq.incrementAndGet(), slug, safeLanguage, code,
                        canonical, fileName, source == null ? "tcp" : source, macroId,
                        startedAtMs, durationMs, success, failureMessage, name.plumbingOnly);
                ring.addFirst(e);
                while (ring.size() > RING_CAP) ring.pollLast();
                toWrite = e;
                indexSnapshot = new ArrayList<>(ring);
            }
        }
        writeAsync(toWrite, indexSnapshot);
        fire();
    }

    public synchronized Entry get(long id) {
        loadFromIndexIfPersistEnabled();
        for (Entry e : ring) {
            if (e.id == id) return e;
        }
        return null;
    }

    public synchronized boolean removeFromRing(long id) {
        for (Iterator<Entry> it = ring.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.id == id) {
                it.remove();
                fire();
                return true;
            }
        }
        return false;
    }

    public synchronized void clearRing() {
        if (ring.isEmpty()) return;
        ring.clear();
        fire();
    }

    public synchronized void clearRingAndDeleteFiles() {
        if (ring.isEmpty()) return;
        final List<Entry> filesToDelete = new ArrayList<>(ring);
        ring.clear();
        fire();
        ioExecutor.submit(() -> {
            try {
                Path dir = resolveCodeDir();
                if (dir == null) return;
                for (Entry e : filesToDelete) {
                    if (e.fileName != null && !e.fileName.trim().isEmpty()) {
                        Files.deleteIfExists(dir.resolve(e.fileName));
                    }
                }
                Files.deleteIfExists(dir.resolve("INDEX.json"));
            } catch (Throwable t) {
                IJ.log("[ImageJAI-Journal] clear files failed: " + t);
            }
        });
    }

    public synchronized void loadFromIndexIfPresent() {
        if (indexLoadAttempted) return;
        indexLoadAttempted = true;
        Path dir = resolveCodeDir();
        if (dir == null) return;
        Path index = dir.resolve("INDEX.json");
        if (!Files.isRegularFile(index)) return;
        try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonArray()) return;
            JsonArray entries = root.getAsJsonArray();
            ring.clear();
            long maxId = idSeq.get();
            for (JsonElement element : entries) {
                if (element == null || !element.isJsonObject()) continue;
                Entry e = entryFromIndexObject(dir, element.getAsJsonObject());
                if (e == null) continue;
                ring.addLast(e);
                if (e.id > maxId) maxId = e.id;
                while (ring.size() > RING_CAP) ring.pollLast();
            }
            idSeq.set(Math.max(idSeq.get(), maxId));
            IJ.log("[ImageJAI-Journal] loaded " + ring.size() + " entries from " + index);
        } catch (Throwable t) {
            IJ.log("[ImageJAI-Journal] INDEX load failed: " + t);
        }
    }

    public Path filePathFor(Entry e) {
        if (e == null || e.fileName == null || e.fileName.trim().isEmpty()) return null;
        Path dir = resolveCodeDir();
        return dir == null ? null : dir.resolve(e.fileName);
    }

    // ---- internals ----

    private void loadFromIndexIfPersistEnabled() {
        if (Prefs.get(PREF_PERSIST, false)) {
            loadFromIndexIfPresent();
        }
    }

    private static Entry entryFromIndexObject(Path dir, JsonObject obj) throws IOException {
        String fileName = stringValue(obj, "file", "");
        if (fileName.isEmpty()) return null;
        Path file = dir.resolve(fileName);
        if (!Files.isRegularFile(file)) return null;
        String code = Files.readString(file, StandardCharsets.UTF_8);
        String language = stringValue(obj, "language", "ijm");
        long timestamp = longValue(obj, "timestamp", System.currentTimeMillis());
        boolean plumbingOnly = obj.has("plumbingOnly")
                ? booleanValue(obj, "plumbingOnly", false)
                : CodeAutoNamer.describeFor(language, code, timeSuffix(timestamp)).plumbingOnly;
        Entry e = new Entry(
                longValue(obj, "id", 0L),
                stringValue(obj, "name", CodeAutoNamer.nameFor(language, code, timeSuffix(timestamp))),
                language,
                code,
                canonicalise(code),
                fileName,
                stringValue(obj, "source", "tcp"),
                0L,
                timestamp,
                0L,
                booleanValue(obj, "success", true),
                stringValue(obj, "failureMessage", null),
                plumbingOnly);
        e.lastRunAtMs = longValue(obj, "lastRunAt", timestamp);
        e.runCount = (int) longValue(obj, "runCount", 1L);
        return e;
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        try {
            return value.getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long longValue(JsonObject obj, String key, long fallback) {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        try {
            return value.getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonObject obj, String key, boolean fallback) {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        try {
            return value.getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private String dedupSlug(String slug) {
        // Same slug + different canonical code → numeric suffix.
        int suffix = 2;
        String candidate = slug;
        while (true) {
            boolean collision = false;
            for (Entry e : ring) {
                if (e.name.equals(candidate)) { collision = true; break; }
            }
            if (!collision) return candidate;
            candidate = slug + "_" + suffix++;
            if (suffix > 99) return candidate; // give up, unique enough
        }
    }

    private static String canonicalise(String code) {
        return code.replace("\r\n", "\n").replaceAll("\\s+", " ").trim();
    }

    private static String timeSuffix(long startedAtMs) {
        if (startedAtMs <= 0L) return LocalTime.now().format(HMS);
        return Instant.ofEpochMilli(startedAtMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(HMS);
    }

    private void fire() {
        for (Listener l : listeners) {
            try { l.onChange(); } catch (Throwable t) {
                IJ.log("[ImageJAI-Journal] listener threw: " + t);
            }
        }
    }

    private void writeAsync(Entry e, List<Entry> indexSnapshot) {
        ioExecutor.submit(() -> {
            try {
                Path dir = resolveCodeDir();
                if (dir == null) return;
                Files.createDirectories(dir);
                if (e != null) {
                    Files.writeString(dir.resolve(e.fileName), e.code, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW);
                }
                writeIndexAtomically(dir.resolve("INDEX.json"), indexSnapshot);
            } catch (Throwable t) {
                IJ.log("[ImageJAI-Journal] write failed: " + t);
            }
        });
    }

    /**
     * Lazily resolve {@code AI_Exports/.session/code/} under the plugin
     * workspace. Returns null if we can't figure it out — the in-memory
     * ring still works.
     */
    private Path resolveCodeDir() {
        Path root = imageDirectory(WindowManager.getCurrentImage());
        if (root == null) {
            int[] ids = WindowManager.getIDList();
            if (ids != null) {
                for (int id : ids) {
                    root = imageDirectory(WindowManager.getImage(id));
                    if (root != null) break;
                }
            }
        }
        if (root == null) {
            Path cwdExports = Paths.get(System.getProperty("user.dir", ".")).resolve("AI_Exports");
            if (Files.isDirectory(cwdExports)) root = cwdExports.getParent();
        }
        return root == null ? null : root.resolve("AI_Exports").resolve(".session").resolve("code");
    }

    private static Path imageDirectory(ImagePlus imp) {
        if (imp == null) return null;
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi == null || fi.directory == null || fi.directory.trim().isEmpty()) return null;
        return Paths.get(fi.directory);
    }

    private static String extensionFor(String language) {
        if (language == null) return "ijm";
        String l = language.toLowerCase();
        if (l.startsWith("groov")) return "groovy";
        if (l.startsWith("jython") || l.startsWith("python")) return "py";
        if (l.startsWith("java") || l.startsWith("js") || l.startsWith("ecma")) return "js";
        return "ijm";
    }

    /**
     * Append a single line to {@code INDEX.json} in NDJSON style — one
     * JSON object per line, trivially parseable without a manifest-wide
     * rewrite on each call. Not a strict JSON array; the UI (stage 11)
     * can tolerate that since it parses line-by-line.
     */
    private static void writeIndexAtomically(Path index, List<Entry> entries) throws IOException {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            pw.print("[\n");
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                pw.print("  {");
                pw.print("\"id\":" + e.id + ",");
                pw.print("\"name\":\"" + jsonEscape(e.name) + "\",");
                pw.print("\"file\":\"" + jsonEscape(e.fileName) + "\",");
                pw.print("\"language\":\"" + jsonEscape(e.language) + "\",");
                pw.print("\"timestamp\":" + e.firstRunAtMs + ",");
                pw.print("\"lastRunAt\":" + e.lastRunAtMs + ",");
                pw.print("\"runCount\":" + e.runCount + ",");
                pw.print("\"source\":\"" + jsonEscape(e.source) + "\",");
                pw.print("\"success\":" + e.success + ",");
                pw.print("\"failureMessage\":\"" + jsonEscape(e.failureMessage) + "\",");
                pw.print("\"plumbingOnly\":" + e.plumbingOnly);
                pw.print("}");
                if (i < entries.size() - 1) pw.print(",");
                pw.print("\n");
            }
            pw.print("]\n");
        }
        Path tmp = Files.createTempFile(index.getParent(), "INDEX", ".tmp");
        Files.writeString(tmp, sw.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
            Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
