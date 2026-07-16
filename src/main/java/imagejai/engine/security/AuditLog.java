package imagejai.engine.security;

import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;

import java.awt.Desktop;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Append-only audit CSV writer for ImageJAI Data Governance.
 */
public final class AuditLog {
    public interface Listener {
        void auditRowsUpdated(List<AuditRow> recentRows);
    }

    interface PathResolver {
        Path csvPath();
    }

    public static final String FILE_NAME = "imagejai_audit.csv";
    public static final String HEADER =
            "timestamp_utc,session_id,command,posture,model_endpoint,"
                    + "capture_source,bytes_out,bytes_in,image_hash,redaction_applied,"
                    + "fields_redacted,notes";

    private static final int DEFAULT_RECENT_LIMIT = 500;
    private static final int LOCK_ATTEMPTS = 3;
    private static final long LOCK_RETRY_MS = 333L;
    private static final AuditLog INSTANCE = new AuditLog(new CurrentImagePathResolver());

    private final PathResolver pathResolver;
    private final ExecutorService writer;
    private final ExecutorService notifier;
    private final ArrayDeque<AuditRow> recentRows = new ArrayDeque<AuditRow>();
    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<Listener>();

    public AuditLog(Path csvPath) {
        this(new FixedPathResolver(csvPath));
    }

    AuditLog(PathResolver pathResolver) {
        this.pathResolver = pathResolver == null
                ? new CurrentImagePathResolver()
                : pathResolver;
        this.writer = daemonExecutor("ImageJAI-audit-log");
        this.notifier = daemonExecutor("ImageJAI-audit-log-listener");
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdownAndAwait(1500L);
            }
        }, "ImageJAI-audit-log-shutdown"));
    }

    public static AuditLog getInstance() {
        return INSTANCE;
    }

    public void append(final AuditRow row) {
        if (row == null) {
            return;
        }
        remember(row);
        notifyListenersAsync();
        try {
            writer.submit(new Runnable() {
                @Override
                public void run() {
                    Path csvPath = resolveCsvPath();
                    if (csvPath == null) {
                        return;
                    }
                    try {
                        writeSync(csvPath, row);
                    } catch (IOException e) {
                        System.err.println("[ImageJAI-Audit] append failed: " + e.getMessage());
                    }
                }
            });
        } catch (RuntimeException e) {
            System.err.println("[ImageJAI-Audit] append scheduling failed: " + e.getMessage());
        }
    }

    public void open() throws IOException {
        Path csvPath = resolveCsvPath();
        if (csvPath == null) {
            throw new IOException("Could not resolve AI_Exports audit log path");
        }
        writeSync(csvPath, null);
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop.open is not supported on this platform");
        }
        Desktop.getDesktop().open(csvPath.toFile());
    }

    public List<AuditRow> recent(int n) {
        int limit = Math.max(0, n);
        synchronized (recentRows) {
            List<AuditRow> all = new ArrayList<AuditRow>(recentRows);
            if (limit == 0) {
                return Collections.unmodifiableList(new ArrayList<AuditRow>());
            }
            if (all.size() <= limit) {
                return Collections.unmodifiableList(all);
            }
            return Collections.unmodifiableList(
                    new ArrayList<AuditRow>(all.subList(all.size() - limit, all.size())));
        }
    }

    public AutoCloseable subscribeRecent(final Listener listener) {
        return subscribeRecent(DEFAULT_RECENT_LIMIT, listener);
    }

    public AutoCloseable subscribeRecent(final int limit, final Listener listener) {
        if (listener == null) {
            return new AutoCloseable() {
                @Override
                public void close() {
                }
            };
        }
        final Listener limitingListener = new Listener() {
            @Override
            public void auditRowsUpdated(List<AuditRow> recentRows) {
                listener.auditRowsUpdated(limitRows(recentRows, limit));
            }
        };
        listeners.addIfAbsent(limitingListener);
        try {
            listener.auditRowsUpdated(recent(limit));
        } catch (Throwable ignore) {
        }
        return new AutoCloseable() {
            @Override
            public void close() {
                listeners.remove(limitingListener);
            }
        };
    }

    public void flushForTest() throws Exception {
        Future<?> writeFuture = writer.submit(new Runnable() {
            @Override
            public void run() {
            }
        });
        writeFuture.get(5, TimeUnit.SECONDS);
        Future<?> notifyFuture = notifier.submit(new Runnable() {
            @Override
            public void run() {
            }
        });
        notifyFuture.get(5, TimeUnit.SECONDS);
    }

    public void shutdownAndAwait(long millis) {
        writer.shutdown();
        notifier.shutdown();
        try {
            writer.awaitTermination(Math.max(0L, millis), TimeUnit.MILLISECONDS);
            notifier.awaitTermination(Math.max(0L, millis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Path csvPath() {
        return resolveCsvPath();
    }

    public static AuditSummary summaryFor(Path path) throws IOException {
        Path csvPath = resolveSummaryPath(path);
        if (csvPath == null || !Files.exists(csvPath)) {
            return emptySummary(csvPath);
        }

        List<CsvRecord> records = readCsvRecords(csvPath);
        Map<String, Integer> commandCounts = new TreeMap<String, Integer>();
        Map<String, Integer> postureCounts = new TreeMap<String, Integer>();
        Set<String> fields = new TreeSet<String>();
        List<String> malformedDiagnostics = new ArrayList<String>();
        int malformedRows = 0;

        int rows = 0;
        long bytesOut = 0L;
        long bytesIn = 0L;
        int redactedRows = 0;
        int visualGrants = 0;
        int visualConsumes = 0;
        int postureEvents = 0;
        int downshifts = 0;
        Instant first = null;
        Instant last = null;

        for (CsvRecord record : records) {
            String line = record.text;
            if (line == null || line.trim().isEmpty() || HEADER.equals(line.trim())) {
                continue;
            }
            AuditRow row;
            try {
                row = AuditRow.fromCsvLine(line);
            } catch (IllegalArgumentException badLine) {
                malformedRows++;
                if (malformedDiagnostics.size() < 100) {
                    malformedDiagnostics.add("line " + record.startLine + ": "
                            + badLine.getMessage());
                }
                continue;
            }
            rows++;
            if (first == null || row.timestampUtc().isBefore(first)) {
                first = row.timestampUtc();
            }
            if (last == null || row.timestampUtc().isAfter(last)) {
                last = row.timestampUtc();
            }
            bytesOut += row.bytesOut();
            bytesIn += row.bytesIn();
            if (row.redactionApplied()) {
                redactedRows++;
            }
            increment(commandCounts, row.command());
            increment(postureCounts, row.posture().label());
            fields.addAll(row.fieldsRedacted());
            if ("visual.granted".equals(row.command())) {
                visualGrants++;
            }
            if ("visual.consumed".equals(row.command())) {
                visualConsumes++;
            }
            if (row.command().startsWith("posture.")) {
                postureEvents++;
            }
            if ("posture.downshift".equals(row.command())) {
                downshifts++;
            }
        }

        return new AuditSummary(csvPath, rows, first, last, bytesOut, bytesIn,
                redactedRows, visualGrants, visualConsumes, postureEvents,
                downshifts,
                commandCounts, postureCounts, fields, malformedRows,
                malformedDiagnostics);
    }

    private void remember(AuditRow row) {
        synchronized (recentRows) {
            recentRows.addLast(row);
            while (recentRows.size() > DEFAULT_RECENT_LIMIT) {
                recentRows.removeFirst();
            }
        }
    }

    private void notifyListenersAsync() {
        if (listeners.isEmpty()) {
            return;
        }
        final List<AuditRow> snapshot = recent(DEFAULT_RECENT_LIMIT);
        try {
            notifier.submit(new Runnable() {
                @Override
                public void run() {
                    notifyListeners(snapshot);
                }
            });
        } catch (RuntimeException ignore) {
        }
    }

    private void notifyListeners(List<AuditRow> snapshot) {
        for (Listener listener : listeners) {
            try {
                listener.auditRowsUpdated(snapshot);
            } catch (Throwable ignore) {
            }
        }
    }

    private static List<AuditRow> limitRows(List<AuditRow> rows, int limit) {
        int n = Math.max(0, limit);
        if (rows == null || rows.isEmpty() || n == 0) {
            return Collections.unmodifiableList(new ArrayList<AuditRow>());
        }
        if (rows.size() <= n) {
            return Collections.unmodifiableList(new ArrayList<AuditRow>(rows));
        }
        return Collections.unmodifiableList(
                new ArrayList<AuditRow>(rows.subList(rows.size() - n, rows.size())));
    }

    private static ExecutorService daemonExecutor(final String name) {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private Path resolveCsvPath() {
        try {
            return pathResolver.csvPath();
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeSync(Path csvPath, AuditRow row) throws IOException {
        try {
            writeWithLock(csvPath, row);
        } catch (IOException e) {
            Path sidecar = sidecarPath(csvPath);
            if (sidecar == null || sidecar.equals(csvPath)) {
                throw e;
            }
            writeWithLock(sidecar, row);
        }
    }

    private void writeWithLock(Path path, AuditRow row) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            FileLock lock = lockWithRetries(channel);
            if (lock == null) {
                throw new IOException("Could not acquire audit CSV lock");
            }
            try {
                if (channel.size() == 0L) {
                    write(channel, HEADER + System.lineSeparator());
                }
                if (row != null) {
                    channel.position(channel.size());
                    write(channel, row.toCsvLine() + System.lineSeparator());
                }
                channel.force(true);
            } finally {
                lock.release();
            }
        }
    }

    private FileLock lockWithRetries(FileChannel channel) throws IOException {
        IOException last = null;
        for (int i = 0; i < LOCK_ATTEMPTS; i++) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException e) {
                last = new IOException(e);
            } catch (IOException e) {
                last = e;
            }
            if (i + 1 < LOCK_ATTEMPTS) {
                try {
                    Thread.sleep(LOCK_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for audit CSV lock", e);
                }
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private static void write(FileChannel channel, String text) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static List<CsvRecord> readCsvRecords(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        List<CsvRecord> records = new ArrayList<CsvRecord>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int startLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    current.append(c).append(c);
                    i++;
                    continue;
                }
                quoted = !quoted;
                current.append(c);
            } else if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                records.add(new CsvRecord(startLine, current.toString()));
                current.setLength(0);
                line++;
                startLine = line;
            } else {
                current.append(c);
                if (c == '\n') line++;
            }
        }
        if (current.length() > 0 || quoted) records.add(new CsvRecord(startLine, current.toString()));
        return records;
    }

    private static final class CsvRecord {
        final int startLine;
        final String text;
        CsvRecord(int startLine, String text) {
            this.startLine = startLine;
            this.text = text;
        }
    }

    private static Path sidecarPath(Path csvPath) {
        if (csvPath == null) {
            return null;
        }
        String host = "sidecar";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignore) {
        }
        host = host == null ? "sidecar" : host.replaceAll("[^A-Za-z0-9_.-]+", "_");
        Path parent = csvPath.getParent();
        String name = "imagejai_audit." + host + ".csv";
        return parent == null ? Paths.get(name) : parent.resolve(name);
    }

    private static void increment(Map<String, Integer> map, String key) {
        String k = key == null ? "" : key;
        Integer current = map.get(k);
        map.put(k, current == null ? 1 : current + 1);
    }

    private static AuditSummary emptySummary(Path csvPath) {
        return new AuditSummary(csvPath, 0, null, null, 0L, 0L,
                0, 0, 0, 0, 0,
                new LinkedHashMap<String, Integer>(),
                new LinkedHashMap<String, Integer>(),
                new LinkedHashSet<String>());
    }

    private static Path resolveSummaryPath(Path path) {
        if (path == null) {
            return null;
        }
        Path normalised = path.toAbsolutePath().normalize();
        String fileName = normalised.getFileName() == null
                ? ""
                : normalised.getFileName().toString();
        if (fileName.toLowerCase().endsWith(".csv")) {
            return normalised;
        }
        if ("AI_Exports".equalsIgnoreCase(fileName)) {
            return normalised.resolve(FILE_NAME);
        }
        return normalised.resolve("AI_Exports").resolve(FILE_NAME);
    }

    private static final class FixedPathResolver implements PathResolver {
        private final Path csvPath;

        FixedPathResolver(Path csvPath) {
            this.csvPath = csvPath == null ? null : csvPath.toAbsolutePath().normalize();
        }

        @Override
        public Path csvPath() {
            return csvPath;
        }
    }

    private static final class CurrentImagePathResolver implements PathResolver {
        @Override
        public Path csvPath() {
            Path root = imageDirectory(WindowManager.getCurrentImage());
            if (root == null) {
                int[] ids = WindowManager.getIDList();
                if (ids != null) {
                    int[] sorted = ids.clone();
                    java.util.Arrays.sort(sorted);
                    for (int id : sorted) {
                        root = imageDirectory(WindowManager.getImage(id));
                        if (root != null) {
                            break;
                        }
                    }
                }
            }
            if (root == null) {
                root = Paths.get(System.getProperty("user.dir", "."));
            }
            return root.resolve("AI_Exports").resolve(FILE_NAME);
        }

        private static Path imageDirectory(ImagePlus image) {
            if (image == null) {
                return null;
            }
            try {
                FileInfo fileInfo = image.getOriginalFileInfo();
                if (fileInfo == null || fileInfo.directory == null
                        || fileInfo.directory.trim().isEmpty()) {
                    return null;
                }
                return Paths.get(fileInfo.directory).toAbsolutePath().normalize();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
