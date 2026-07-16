package imagejai.engine;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Async append-only JSONL journal for {@link FrictionLog} failures.
 *
 * <p>Files are read oldest-to-newest: {@code friction.jsonl.5} through
 * {@code friction.jsonl.1}, then the live {@code friction.jsonl}.
 */
public class FrictionLogJournal implements AutoCloseable {

    public static final long ROTATE_BYTES = 10L * 1024L * 1024L;
    public static final int MAX_GENERATIONS = 5;
    public static final String FILE_NAME = "friction.jsonl";
    public static final int MAX_JSONL_LINE_BYTES = 64 * 1024;
    public static final int MAX_STREAM_ENTRIES = 50_000;
    public static final long MAX_READ_FILE_BYTES = ROTATE_BYTES + MAX_JSONL_LINE_BYTES + 1L;

    interface InputOpener {
        InputStream open(Path path) throws IOException;
    }

    interface PathProbe {
        BasicFileAttributes readAttributes(Path path) throws IOException;
    }

    private final Path root;
    private final Path file;
    private final ExecutorService writer;
    private final InputOpener inputOpener;
    private final PathProbe pathProbe;
    private final Gson gson = new Gson();
    private final AtomicLong droppedWrites = new AtomicLong();
    private volatile String lastWriteError = "";
    private volatile ReadDiagnostics lastReadDiagnostics = ReadDiagnostics.empty();

    public FrictionLogJournal() {
        this(defaultRoot());
    }

    public FrictionLogJournal(Path root) {
        this(root, Files::newInputStream, FrictionLogJournal::readAttributes);
    }

    FrictionLogJournal(Path root, InputOpener inputOpener) {
        this(root, inputOpener, FrictionLogJournal::readAttributes);
    }

    FrictionLogJournal(Path root, InputOpener inputOpener, PathProbe pathProbe) {
        this.root = root;
        this.file = root.resolve(FILE_NAME);
        this.inputOpener = inputOpener;
        this.pathProbe = pathProbe;
        ThreadFactory tf = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "imagej-ai-friction-journal");
                t.setDaemon(true);
                return t;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(1024),
                tf,
                new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        this.writer = executor;
    }

    public static Path defaultRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) home = ".";
        return Paths.get(home, ".imagej-ai");
    }

    public Path root() {
        return root;
    }

    public Path file() {
        return file;
    }

    public void append(final FrictionLog.FailureEntry entry) {
        if (entry == null) return;
        try {
            writer.execute(new Runnable() {
                @Override
                public void run() {
                    writeLine(entry);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutdown or a saturated executor must never block the caller.
            droppedWrites.incrementAndGet();
            lastWriteError = "writer_queue_full_or_closed";
        }
    }

    public Stream<FrictionLog.FailureEntry> streamEntries() {
        List<FrictionLog.FailureEntry> entries = new ArrayList<FrictionLog.FailureEntry>();
        MutableReadDiagnostics diagnostics = new MutableReadDiagnostics();
        final List<Path> paths = journalFilesOldestFirst(diagnostics);
        for (Path path : paths) {
            try {
                readPath(path, entries, diagnostics);
            } catch (NoSuchFileException e) {
                // A generation can disappear during rotation; absence is not corruption.
            } catch (IOException e) {
                throw readFailure("unreadable", path,
                        "Could not read friction journal: " + message(e), e, diagnostics);
            }
        }
        lastReadDiagnostics = diagnostics.snapshot("", false);
        return entries.stream();
    }

    public ReadDiagnostics lastReadDiagnostics() { return lastReadDiagnostics; }
    public long droppedWriteCount() { return droppedWrites.get(); }
    public String lastWriteError() { return lastWriteError; }

    @Override
    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean awaitIdle(long timeout, TimeUnit unit) throws Exception {
        Future<?> barrier = writer.submit(new Runnable() {
            @Override
            public void run() {
                // Barrier task only.
            }
        });
        barrier.get(timeout, unit);
        return true;
    }

    protected void writeLine(FrictionLog.FailureEntry e) {
        try {
            Files.createDirectories(root);
            try {
                if (Files.size(file) >= ROTATE_BYTES) rotate();
            } catch (NoSuchFileException absent) {
                // First append creates the live journal below.
            }
            String json = gson.toJson(toMap(e));
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSONL_LINE_BYTES) {
                droppedWrites.incrementAndGet();
                lastWriteError = "line_too_large";
                return;
            }
            try (BufferedWriter w = Files.newBufferedWriter(file,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                w.write(json);
                w.write('\n');
            }
            lastWriteError = "";
        } catch (IOException ex) {
            // Disk full / permission denied: keep the in-memory ring buffer only.
            droppedWrites.incrementAndGet();
            lastWriteError = "write_failed: " + message(ex);
        }
    }

    private void rotate() throws IOException {
        Path oldest = generation(MAX_GENERATIONS);
        Files.deleteIfExists(oldest);
        for (int i = MAX_GENERATIONS - 1; i >= 1; i--) {
            Path src = generation(i);
            Path dst = generation(i + 1);
            try {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (NoSuchFileException absent) {
                // Sparse generations are expected.
            }
        }
        Files.move(file, generation(1), StandardCopyOption.REPLACE_EXISTING);
    }

    private List<Path> journalFilesOldestFirst(MutableReadDiagnostics diagnostics) {
        List<Path> paths = new ArrayList<Path>();
        for (int i = MAX_GENERATIONS; i >= 1; i--) {
            Path p = generation(i);
            addReadableJournalPath(paths, p, diagnostics);
        }
        addReadableJournalPath(paths, file, diagnostics);
        return paths;
    }

    private void addReadableJournalPath(List<Path> paths, Path path,
                                        MutableReadDiagnostics diagnostics) {
        try {
            if (!pathProbe.readAttributes(path).isRegularFile()) {
                throw readFailure("not_regular", path,
                        "Friction journal is not a regular file.", null, diagnostics);
            }
            paths.add(path);
        } catch (NoSuchFileException absent) {
            // Missing generations and a never-created live journal are empty.
        } catch (IOException failure) {
            throw readFailure("unreadable", path,
                    "Could not inspect friction journal: " + message(failure),
                    failure, diagnostics);
        }
    }

    private Path generation(int generation) {
        return root.resolve(FILE_NAME + "." + generation);
    }

    private void readPath(Path path, List<FrictionLog.FailureEntry> entries,
                          MutableReadDiagnostics diagnostics) throws IOException {
        byte[] line = new byte[MAX_JSONL_LINE_BYTES];
        int lineLength = 0;
        long fileBytes = 0L;
        diagnostics.filesRead++;
        try (InputStream raw = inputOpener.open(path);
             InputStream in = new BufferedInputStream(raw, 8192)) {
            int value;
            while ((value = in.read()) >= 0) {
                fileBytes++;
                if (fileBytes > MAX_READ_FILE_BYTES) {
                    throw readFailure("file_too_large", path,
                            "Journal file exceeds safety cap of " + MAX_READ_FILE_BYTES
                                    + " bytes.", null, diagnostics);
                }
                if (value == '\n') {
                    consumeLine(path, line, lineLength, entries, diagnostics);
                    lineLength = 0;
                } else {
                    if (lineLength >= MAX_JSONL_LINE_BYTES) {
                        throw readFailure("line_too_large", path,
                                "Journal line exceeds safety cap of "
                                        + MAX_JSONL_LINE_BYTES + " bytes.", null, diagnostics);
                    }
                    line[lineLength++] = (byte) value;
                }
            }
            if (lineLength > 0) {
                consumeLine(path, line, lineLength, entries, diagnostics);
            }
        }
    }

    private void consumeLine(Path path, byte[] bytes, int length,
                             List<FrictionLog.FailureEntry> entries,
                             MutableReadDiagnostics diagnostics) {
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        diagnostics.linesRead++;
        FrictionLog.FailureEntry entry = fromJsonLine(
                new String(bytes, 0, length, StandardCharsets.UTF_8));
        if (entry == null) {
            if (length > 0) {
                diagnostics.malformedLines++;
                diagnostics.droppedEntries++;
            }
            return;
        }
        if (entries.size() >= MAX_STREAM_ENTRIES) {
            throw readFailure("entry_cap", path,
                    "Journal contains more than " + MAX_STREAM_ENTRIES + " entries.",
                    null, diagnostics);
        }
        entries.add(entry);
    }

    private JournalReadException readFailure(String code, Path path, String message,
                                             Throwable cause,
                                             MutableReadDiagnostics diagnostics) {
        ReadDiagnostics snapshot = diagnostics.snapshot(code, true);
        lastReadDiagnostics = snapshot;
        return new JournalReadException(code, path, message, cause, snapshot);
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

    private Map<String, Object> toMap(FrictionLog.FailureEntry e) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ts", e.ts);
        m.put("agent_id", e.agentId == null ? "" : e.agentId);
        m.put("command", e.command == null ? "" : e.command);
        m.put("args_summary", e.argsSummary == null ? "" : e.argsSummary);
        m.put("error", e.error == null ? "" : e.error);
        m.put("normalised_error", e.normalisedError == null ? "" : e.normalisedError);
        // safe_mode_v2 stage 08: structured columns. Null on the entry means
        // the writing call site did not classify; we omit the key entirely
        // rather than emit empty strings so the JSONL stays compact and the
        // reader can distinguish "missing" from "explicitly empty".
        if (e.outcome != null) m.put("outcome", e.outcome);
        if (e.severity != null) m.put("severity", e.severity);
        if (e.ruleId != null) m.put("rule_id", e.ruleId);
        if (e.target != null) m.put("target", e.target);
        return m;
    }

    private FrictionLog.FailureEntry fromJsonLine(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            long ts = longValue(o.get("ts"));
            String agentId = stringValue(o.get("agent_id"));
            String command = stringValue(o.get("command"));
            String argsSummary = stringValue(o.get("args_summary"));
            String error = stringValue(o.get("error"));
            // safe_mode_v2 stage 08: tolerate JSONL written before the
            // schema extension — missing keys deserialise to null on the
            // entry, preserving the "wasn't classified" distinction.
            String outcome = optionalString(o, "outcome");
            String severity = optionalString(o, "severity");
            String ruleId = optionalString(o, "rule_id");
            String target = optionalString(o, "target");
            return new FrictionLog.FailureEntry(ts, agentId, command, argsSummary, error,
                    outcome, severity, ruleId, target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String optionalString(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long longValue(JsonElement e) {
        if (e == null || e.isJsonNull()) return 0L;
        try {
            return e.getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String stringValue(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        try {
            return e.getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static final class MutableReadDiagnostics {
        int filesRead;
        long linesRead;
        long malformedLines;
        long droppedEntries;

        ReadDiagnostics snapshot(String errorCode, boolean truncated) {
            return new ReadDiagnostics(filesRead, linesRead, malformedLines,
                    droppedEntries, truncated, errorCode);
        }
    }

    public static final class ReadDiagnostics {
        private final int filesRead;
        private final long linesRead;
        private final long malformedLines;
        private final long droppedEntries;
        private final boolean truncated;
        private final String errorCode;

        ReadDiagnostics(int filesRead, long linesRead, long malformedLines,
                        long droppedEntries, boolean truncated, String errorCode) {
            this.filesRead = filesRead;
            this.linesRead = linesRead;
            this.malformedLines = malformedLines;
            this.droppedEntries = droppedEntries;
            this.truncated = truncated;
            this.errorCode = errorCode == null ? "" : errorCode;
        }

        static ReadDiagnostics empty() {
            return new ReadDiagnostics(0, 0L, 0L, 0L, false, "");
        }

        public int filesRead() { return filesRead; }
        public long linesRead() { return linesRead; }
        public long malformedLines() { return malformedLines; }
        public long droppedEntries() { return droppedEntries; }
        public boolean truncated() { return truncated; }
        public String errorCode() { return errorCode; }
    }

    public static final class JournalReadException extends IllegalStateException {
        private final String code;
        private final Path path;
        private final ReadDiagnostics diagnostics;

        JournalReadException(String code, Path path, String message, Throwable cause,
                             ReadDiagnostics diagnostics) {
            super(message, cause);
            this.code = code;
            this.path = path;
            this.diagnostics = diagnostics;
        }

        public String code() { return code; }
        public Path path() { return path; }
        public ReadDiagnostics diagnostics() { return diagnostics; }
    }
}
