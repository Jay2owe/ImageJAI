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
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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
    static final int MAX_CODE_BYTES = 2 * 1024 * 1024;
    static final int MAX_INDEX_BYTES = 8 * 1024 * 1024;
    private static final DateTimeFormatter HMS =
            DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT);

    /** Dataset captured when work is admitted, before any asynchronous switch. */
    public static final class DatasetBinding {
        public final String identity;
        public final String hash;
        public final String title;
        public final String sourcePath;
        public final int width;
        public final int height;
        public final int slices;
        public final int channels;
        public final int frames;
        public final int bitDepth;
        final Path codeDir;

        DatasetBinding(String identity, String hash, String title,
                       String sourcePath, Path codeDir) {
            this(identity, hash, title, sourcePath, codeDir,
                    -1, -1, -1, -1, -1, -1);
        }

        DatasetBinding(String identity, String hash, String title,
                       String sourcePath, Path codeDir, int width, int height,
                       int slices, int channels, int frames, int bitDepth) {
            this.identity = identity;
            this.hash = hash;
            this.title = title == null ? "" : title;
            this.sourcePath = sourcePath;
            this.codeDir = codeDir;
            this.width = width;
            this.height = height;
            this.slices = slices;
            this.channels = channels;
            this.frames = frames;
            this.bitDepth = bitDepth;
        }
    }

    /** Immutable snapshot view of a journal entry. */
    public static final class Entry {
        public final long id;
        public final String persistentId;
        public final String name;
        public final String language;
        public final String code;
        public final String canonical;
        public final String fileName;
        public final String source;
        public final long macroId;
        public final long firstRunAtMs;
        public final long lastRunAtMs;
        public final long durationMs;
        public final boolean success;
        public final String failureMessage;
        public final boolean plumbingOnly;
        public final int runCount;
        public final String datasetIdentity;
        public final String datasetHash;
        public final String datasetTitle;
        public final String datasetSourcePath;
        final Path codeDir;

        Entry(long id, String persistentId, String name, String language, String code, String canonical,
              String fileName, String source, long macroId, long startedAtMs,
              long durationMs, boolean success, String failureMessage,
              boolean plumbingOnly, long lastRunAtMs, int runCount,
              DatasetBinding dataset) {
            this.id = id;
            this.persistentId = persistentId;
            this.name = name;
            this.language = language;
            this.code = code;
            this.canonical = canonical;
            this.fileName = fileName;
            this.source = source;
            this.macroId = macroId;
            this.firstRunAtMs = startedAtMs;
            this.lastRunAtMs = lastRunAtMs;
            this.durationMs = durationMs;
            this.success = success;
            this.failureMessage = failureMessage;
            this.plumbingOnly = plumbingOnly;
            this.runCount = runCount;
            this.datasetIdentity = dataset == null ? null : dataset.identity;
            this.datasetHash = dataset == null ? null : dataset.hash;
            this.datasetTitle = dataset == null ? "" : dataset.title;
            this.datasetSourcePath = dataset == null ? null : dataset.sourcePath;
            this.codeDir = dataset == null ? null : dataset.codeDir;
        }

        public boolean isPlumbingOnly() {
            return plumbingOnly;
        }

        Entry withRerun(long atMs) {
            DatasetBinding dataset = new DatasetBinding(datasetIdentity, datasetHash,
                    datasetTitle, datasetSourcePath, codeDir);
            return new Entry(id, persistentId, name, language, code, canonical,
                    fileName, source, macroId, firstRunAtMs, durationMs, success,
                    failureMessage, plumbingOnly, atMs, runCount + 1, dataset);
        }
    }

    /** Called synchronously on every record / rerun / promotion. */
    public interface Listener { void onChange(); }

    public static final class PersistenceException extends IllegalStateException {
        public final String code;
        PersistenceException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ---- state ----
    private final Deque<Entry> ring = new ArrayDeque<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);
    private final ExecutorService ioExecutor;
    private final Path fixedCodeDir;
    private final boolean forcePersistence;
    private final long sessionStartedAtMs = System.currentTimeMillis();
    private boolean indexLoadAttempted;
    private boolean writeBlocked;
    private String persistenceError;
    private Path quarantinedIndex;

    private SessionCodeJournal() { this(null, false); }

    SessionCodeJournal(Path fixedCodeDir, boolean forcePersistence) {
        this.fixedCodeDir = fixedCodeDir;
        this.forcePersistence = forcePersistence;
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "imagej-ai-journal-io");
            t.setDaemon(true);
            return t;
        });
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /**
     * Snapshot the current ring (newest first). Safe to iterate without
     * holding the journal lock.
     */
    public synchronized List<Entry> snapshot() {
        loadFromIndexIfPersistEnabled();
        return Collections.unmodifiableList(new ArrayList<Entry>(ring));
    }

    /**
     * Snapshot entries that have actually run in this JVM session. Persisted
     * history may be loaded into the ring for the history panel, but the rail's
     * "Session Macros" button should not show old entries unless they were
     * re-run during the current process.
     */
    public synchronized List<Entry> snapshotCurrentSession() {
        loadFromIndexIfPersistEnabled();
        List<Entry> current = new ArrayList<>();
        for (Entry e : ring) {
            if (e.lastRunAtMs >= sessionStartedAtMs) {
                current.add(e);
            }
        }
        return Collections.unmodifiableList(current);
    }

    /**
     * Record a macro / script execution. The {@code source} string mirrors
     * the existing {@code macro.started} event's source field so later
     * rail-history re-runs can be suppressed by tagging them differently.
     */
    public void record(String language, String code, String source,
                       long macroId, long startedAtMs, long durationMs,
                       boolean success, String failureMessage) {
        record(captureInitiatingDataset(), language, code, source, macroId,
                startedAtMs, durationMs, success, failureMessage);
    }

    public void record(DatasetBinding dataset, String language, String code, String source,
                       long macroId, long startedAtMs, long durationMs,
                       boolean success, String failureMessage) {
        if (code == null) return;
        String trimmed = code.trim();
        if (trimmed.length() <= MIN_CODE_LEN) return;
        requireBounded("code", code, MAX_CODE_BYTES);
        requireBounded("source", source, 64 * 1024);
        requireBounded("failureMessage", failureMessage, 256 * 1024);
        if (dataset == null) dataset = captureInitiatingDataset();

        loadFromIndexIfPersistEnabled();
        String canonical = canonicalise(code);
        Entry toWrite = null;
        List<Entry> indexSnapshot = null;
        Path targetDir = dataset == null ? resolveCodeDirNow() : dataset.codeDir;
        synchronized (this) {
            if (writeBlocked) {
                throw new PersistenceException("CORRUPT_HISTORY_BLOCKED",
                        "journal writes are blocked after invalid INDEX: " + persistenceError);
            }
            Entry head = ring.peekFirst();
            if (head != null && head.canonical.equals(canonical)
                    && sameDataset(head, dataset)) {
                Entry rerun = head.withRerun(startedAtMs);
                ring.removeFirst();
                ring.addFirst(rerun);
                targetDir = rerun.codeDir;
                indexSnapshot = new ArrayList<>(ring);
            } else for (Iterator<Entry> it = ring.iterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (e.canonical.equals(canonical) && sameDataset(e, dataset)) {
                    it.remove();
                    Entry rerun = e.withRerun(startedAtMs);
                    ring.addFirst(rerun);
                    targetDir = rerun.codeDir;
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
                String persistentId = UUID.randomUUID().toString();
                String fileName = timeSuffix + "_" + persistentId + "_" + slug
                        + "." + extensionFor(safeLanguage);
                Entry e = new Entry(idSeq.incrementAndGet(), persistentId, slug, safeLanguage, code,
                        canonical, fileName, source == null ? "tcp" : source, macroId,
                        startedAtMs, durationMs, success, failureMessage, name.plumbingOnly,
                        startedAtMs, 1, dataset);
                ring.addFirst(e);
                while (ring.size() > RING_CAP) ring.pollLast();
                toWrite = e;
                indexSnapshot = new ArrayList<>(ring);
            }
        }
        writeAsync(toWrite, entriesForDirectory(indexSnapshot, targetDir), targetDir);
        fire();
    }

    public static DatasetBinding captureInitiatingDataset() {
        ImageGraph.ImageRef ref = ImageGraph.captureActiveImage();
        if (ref == null) {
            Path dir = resolveFallbackCodeDir();
            return new DatasetBinding(null, null, "", null, dir);
        }
        String hash = null;
        try {
            hash = StateInspector.datasetHash(ref.image);
        } catch (Throwable t) {
            IJ.log("[ImageJAI-Journal] dataset hash failed: " + t);
        }
        Path root = imageDirectory(ref.image);
        Path dir = root == null ? resolveFallbackCodeDir()
                : root.resolve("AI_Exports").resolve(".session").resolve("code");
        return new DatasetBinding(ref.identity, hash, ref.title, ref.sourcePath, dir,
                ref.image.getWidth(), ref.image.getHeight(), ref.image.getNSlices(),
                ref.image.getNChannels(), ref.image.getNFrames(), ref.image.getBitDepth());
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
                List<Path> touched = new ArrayList<Path>();
                for (Entry e : filesToDelete) {
                    Path dir = e.codeDir;
                    if (dir != null && e.fileName != null && !e.fileName.trim().isEmpty()) {
                        Files.deleteIfExists(dir.resolve(e.fileName));
                        if (!touched.contains(dir)) touched.add(dir);
                    }
                }
                for (Path dir : touched) Files.deleteIfExists(dir.resolve("INDEX.json"));
            } catch (Throwable t) {
                IJ.log("[ImageJAI-Journal] clear files failed: " + t);
            }
        });
    }

    public synchronized void loadFromIndexIfPresent() {
        if (writeBlocked) {
            throw new PersistenceException("CORRUPT_HISTORY_BLOCKED",
                    "journal history is unavailable: " + persistenceError);
        }
        if (indexLoadAttempted) return;
        indexLoadAttempted = true;
        Path dir = fixedCodeDir != null ? fixedCodeDir : resolveCodeDirNow();
        if (dir == null) return;
        Path index = dir.resolve("INDEX.json");
        if (!Files.isRegularFile(index)) return;
        try {
            JsonElement root = JsonParser.parseString(
                    SafeFileIO.readUtf8Bounded(index, MAX_INDEX_BYTES));
            if (root == null || !root.isJsonArray()) {
                throw new IllegalArgumentException("INDEX root is not an array");
            }
            JsonArray entries = root.getAsJsonArray();
            if (entries.size() > RING_CAP) {
                throw new IllegalArgumentException("INDEX exceeds " + RING_CAP + " entries");
            }
            Deque<Entry> loaded = new ArrayDeque<Entry>();
            long maxId = idSeq.get();
            for (JsonElement element : entries) {
                if (element == null || !element.isJsonObject()) {
                    throw new IllegalArgumentException("INDEX entry is not an object");
                }
                Entry e = entryFromIndexObject(dir, element.getAsJsonObject());
                loaded.addLast(e);
                if (e.id > maxId) maxId = e.id;
            }
            ring.clear();
            ring.addAll(loaded);
            idSeq.set(Math.max(idSeq.get(), maxId));
            IJ.log("[ImageJAI-Journal] loaded " + ring.size() + " entries from " + index);
        } catch (Throwable t) {
            writeBlocked = true;
            persistenceError = t.getClass().getSimpleName() + ": " + t.getMessage();
            try {
                quarantinedIndex = SafeFileIO.quarantine(index, persistenceError);
            } catch (IOException quarantineFailure) {
                persistenceError += "; quarantine failed: " + quarantineFailure.getMessage();
            }
            IJ.log("[ImageJAI-Journal] invalid INDEX quarantined; writes blocked: "
                    + persistenceError);
            throw new PersistenceException("CORRUPT_HISTORY_BLOCKED",
                    "journal history is unavailable: " + persistenceError);
        }
    }

    public Path filePathFor(Entry e) {
        if (e == null || e.fileName == null || e.fileName.trim().isEmpty()) return null;
        Path dir = e.codeDir != null ? e.codeDir : resolveCodeDirNow();
        return dir == null ? null : dir.resolve(e.fileName);
    }

    // ---- internals ----

    private void loadFromIndexIfPersistEnabled() {
        if (forcePersistence || Prefs.get(PREF_PERSIST, false)) {
            loadFromIndexIfPresent();
        }
    }

    private static Entry entryFromIndexObject(Path dir, JsonObject obj) throws IOException {
        String fileName = requiredString(obj, "file");
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("unsafe journal file name");
        }
        Path file = dir.resolve(fileName);
        if (!Files.isRegularFile(file)) throw new IOException("missing code file: " + fileName);
        String code = SafeFileIO.readUtf8Bounded(file, MAX_CODE_BYTES);
        String language = stringValue(obj, "language", "ijm");
        long timestamp = longValue(obj, "timestamp", System.currentTimeMillis());
        boolean plumbingOnly = obj.has("plumbingOnly")
                ? booleanValue(obj, "plumbingOnly", false)
                : CodeAutoNamer.describeFor(language, code, timeSuffix(timestamp)).plumbingOnly;
        DatasetBinding dataset = new DatasetBinding(
                stringValue(obj, "datasetIdentity", null),
                stringValue(obj, "datasetHash", null),
                stringValue(obj, "datasetTitle", ""),
                stringValue(obj, "datasetSourcePath", null), dir);
        return new Entry(
                longValue(obj, "id", 0L),
                stringValue(obj, "persistentId", UUID.randomUUID().toString()),
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
                plumbingOnly,
                longValue(obj, "lastRunAt", timestamp),
                checkedRunCount(obj), dataset);
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

    private static String requiredString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            throw new IllegalArgumentException("missing " + key);
        }
        try {
            String value = obj.get(key).getAsString();
            if (value.isEmpty()) throw new IllegalArgumentException("empty " + key);
            return value;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid " + key, e);
        }
    }

    private static int checkedRunCount(JsonObject obj) {
        long value = longValue(obj, "runCount", 1L);
        if (value < 1L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid runCount");
        }
        return (int) value;
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

    private static boolean sameDataset(Entry entry, DatasetBinding dataset) {
        if (entry == null || dataset == null) return entry != null && entry.codeDir == null;
        if (entry.datasetIdentity != null || dataset.identity != null) {
            return java.util.Objects.equals(entry.datasetIdentity, dataset.identity);
        }
        if (entry.datasetHash != null || dataset.hash != null) {
            return java.util.Objects.equals(entry.datasetHash, dataset.hash);
        }
        return java.util.Objects.equals(entry.codeDir, dataset.codeDir);
    }

    private static List<Entry> entriesForDirectory(List<Entry> entries, Path dir) {
        List<Entry> selected = new ArrayList<Entry>();
        if (entries == null) return selected;
        for (Entry entry : entries) {
            if (java.util.Objects.equals(entry.codeDir, dir)) selected.add(entry);
        }
        return Collections.unmodifiableList(selected);
    }

    private static void requireBounded(String field, String value, int maxBytes) {
        if (value == null) return;
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw new IllegalArgumentException(field + " exceeds " + maxBytes + " UTF-8 bytes");
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

    private void writeAsync(Entry e, List<Entry> indexSnapshot, Path capturedDir) {
        if (!forcePersistence && !Prefs.get(PREF_PERSIST, false)) return;
        ioExecutor.submit(() -> {
            try {
                synchronized (SessionCodeJournal.this) {
                    if (writeBlocked) {
                        throw new IllegalStateException("journal writes blocked: " + persistenceError);
                    }
                }
                Path dir = capturedDir;
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
    private Path resolveCodeDirNow() {
        if (fixedCodeDir != null) return fixedCodeDir;
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

    private static Path resolveFallbackCodeDir() {
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            int[] sorted = ids.clone();
            java.util.Arrays.sort(sorted);
            for (int id : sorted) {
                Path root = imageDirectory(WindowManager.getImage(id));
                if (root != null) {
                    return root.resolve("AI_Exports").resolve(".session").resolve("code");
                }
            }
        }
        Path cwdExports = Paths.get(System.getProperty("user.dir", ".")).resolve("AI_Exports");
        return Files.isDirectory(cwdExports)
                ? cwdExports.resolve(".session").resolve("code") : null;
    }

    private static Path imageDirectory(ImagePlus imp) {
        if (imp == null) return null;
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi == null || fi.directory == null || fi.directory.trim().isEmpty()) return null;
        return Paths.get(fi.directory);
    }

    private static String extensionFor(String language) {
        if (language == null) return "ijm";
        String l = language.toLowerCase(Locale.ROOT);
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
                pw.print("\"persistentId\":\"" + jsonEscape(e.persistentId) + "\",");
                pw.print("\"name\":\"" + jsonEscape(e.name) + "\",");
                pw.print("\"file\":\"" + jsonEscape(e.fileName) + "\",");
                pw.print("\"language\":\"" + jsonEscape(e.language) + "\",");
                pw.print("\"timestamp\":" + e.firstRunAtMs + ",");
                pw.print("\"lastRunAt\":" + e.lastRunAtMs + ",");
                pw.print("\"runCount\":" + e.runCount + ",");
                pw.print("\"source\":\"" + jsonEscape(e.source) + "\",");
                pw.print("\"success\":" + e.success + ",");
                pw.print("\"failureMessage\":\"" + jsonEscape(e.failureMessage) + "\",");
                pw.print("\"plumbingOnly\":" + e.plumbingOnly + ",");
                pw.print("\"datasetIdentity\":\"" + jsonEscape(e.datasetIdentity) + "\",");
                pw.print("\"datasetHash\":\"" + jsonEscape(e.datasetHash) + "\",");
                pw.print("\"datasetTitle\":\"" + jsonEscape(e.datasetTitle) + "\",");
                pw.print("\"datasetSourcePath\":\"" + jsonEscape(e.datasetSourcePath) + "\"");
                pw.print("}");
                if (i < entries.size() - 1) pw.print(",");
                pw.print("\n");
            }
            pw.print("]\n");
        }
        String json = sw.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_INDEX_BYTES) {
            throw new IOException("INDEX exceeds " + MAX_INDEX_BYTES + " bytes");
        }
        SafeFileIO.writeUtf8Atomically(index, json);
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    synchronized boolean isWriteBlockedForTest() { return writeBlocked; }

    synchronized Path quarantinedIndexForTest() { return quarantinedIndex; }

    void awaitWritesForTest() throws Exception {
        Future<?> marker = ioExecutor.submit(() -> { });
        marker.get();
    }

    void shutdownForTest() {
        ioExecutor.shutdownNow();
    }
}
