package imagejai.engine;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    private final Path root;
    private final Path file;
    private final ExecutorService writer;
    private final Gson gson = new Gson();

    public FrictionLogJournal() {
        this(defaultRoot());
    }

    public FrictionLogJournal(Path root) {
        this.root = root;
        this.file = root.resolve(FILE_NAME);
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
                new ThreadPoolExecutor.DiscardOldestPolicy());
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
        }
    }

    public Stream<FrictionLog.FailureEntry> streamEntries() {
        final List<Path> paths = journalFilesOldestFirst();
        return paths.stream().flatMap(path -> {
            try {
                return Files.lines(path, StandardCharsets.UTF_8)
                        .map(this::fromJsonLine)
                        .filter(Objects::nonNull);
            } catch (IOException e) {
                return Stream.<FrictionLog.FailureEntry>empty();
            }
        });
    }

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
            if (Files.exists(file) && Files.size(file) >= ROTATE_BYTES) {
                rotate();
            }
            try (BufferedWriter w = Files.newBufferedWriter(file,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                w.write(gson.toJson(toMap(e)));
                w.write('\n');
            }
        } catch (IOException ignored) {
            // Disk full / permission denied: keep the in-memory ring buffer only.
        }
    }

    private void rotate() throws IOException {
        Path oldest = generation(MAX_GENERATIONS);
        Files.deleteIfExists(oldest);
        for (int i = MAX_GENERATIONS - 1; i >= 1; i--) {
            Path src = generation(i);
            Path dst = generation(i + 1);
            if (Files.exists(src)) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, generation(1), StandardCopyOption.REPLACE_EXISTING);
    }

    private List<Path> journalFilesOldestFirst() {
        List<Path> paths = new ArrayList<Path>();
        for (int i = MAX_GENERATIONS; i >= 1; i--) {
            Path p = generation(i);
            if (Files.exists(p)) paths.add(p);
        }
        if (Files.exists(file)) paths.add(file);
        return paths;
    }

    private Path generation(int generation) {
        return root.resolve(FILE_NAME + "." + generation);
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
            JsonObject o = new JsonParser().parse(line).getAsJsonObject();
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
}
