package imagejai.engine.security;

import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    public static final int MAX_LISTENERS = 64;
    public static final int WRITER_QUEUE_CAPACITY = 2048;
    public static final int NOTIFIER_QUEUE_CAPACITY = 1;
    public static final long MAX_SUMMARY_BYTES = 16L * 1024L * 1024L;
    public static final int MAX_ROW_TEXT_CHARS = 16_384;
    public static final int MAX_ROW_FIELDS = 128;
    public static final int MAX_ROW_FIELD_CHARS = 256;
    private static final int LOCK_ATTEMPTS = 3;
    private static final long LOCK_RETRY_MS = 333L;
    private static final AuditLog INSTANCE = new AuditLog(new CurrentImagePathResolver());
    private static volatile Runnable beforeSummaryReadHookForTest;

    private final PathResolver pathResolver;
    private final ThreadPoolExecutor writer;
    private final ThreadPoolExecutor notifier;
    private final Object writerLifecycleLock = new Object();
    private final Object notifierLifecycleLock = new Object();
    private final ArrayDeque<PendingWrite> pendingWrites =
            new ArrayDeque<PendingWrite>();
    private final ArrayDeque<AuditRow> recentRows = new ArrayDeque<AuditRow>();
    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<Listener>();
    private final AtomicLong droppedRecentRows = new AtomicLong(0L);
    private final AtomicLong droppedWriterTasks = new AtomicLong(0L);
    private final AtomicLong droppedNotifications = new AtomicLong(0L);
    private final AtomicLong rejectedListeners = new AtomicLong(0L);
    private final AtomicLong listenerFailures = new AtomicLong(0L);
    private final AtomicLong notificationVersion = new AtomicLong(0L);
    private final AtomicLong completedWriterBatches = new AtomicLong(0L);
    private boolean writerDrainScheduled;
    private boolean notificationScheduled;
    private volatile Runnable beforeWriterDrainHookForTest;
    private volatile Runnable beforeWriterIdleHookForTest;

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
        final AuditRow boundedRow = boundedRow(row);
        // Bind provenance at admission. Resolving the active image later on
        // the writer thread can route an already-admitted row to whichever
        // image happens to be active when the queue drains.
        final Path admittedPath = resolveCsvPath();
        remember(boundedRow);
        notificationVersion.incrementAndGet();
        notifyListenersAsync();
        enqueueWrite(new PendingWrite(admittedPath, boundedRow));
    }

    private void enqueueWrite(PendingWrite write) {
        synchronized (writerLifecycleLock) {
            if (writer.isShutdown() || pendingWrites.size() >= WRITER_QUEUE_CAPACITY) {
                droppedWriterTasks.incrementAndGet();
                System.err.println("[ImageJAI-Audit] append queue full or closed; "
                        + "row retained in memory only");
                return;
            }
            pendingWrites.addLast(write);
            if (!writerDrainScheduled) {
                scheduleWriterDrainLocked();
            }
        }
    }

    /** Must be called while holding {@link #writerLifecycleLock}. */
    private void scheduleWriterDrainLocked() {
        writerDrainScheduled = true;
        try {
            writer.submit(new Runnable() {
                @Override
                public void run() {
                    drainWriterQueue();
                }
            });
        } catch (RejectedExecutionException e) {
            discardUnscheduledWritesLocked("append queue closed");
        } catch (RuntimeException e) {
            discardUnscheduledWritesLocked("append scheduling failed: " + e.getMessage());
        }
    }

    /** Must be called while holding {@link #writerLifecycleLock}. */
    private void discardUnscheduledWritesLocked(String reason) {
        int discarded = pendingWrites.size();
        pendingWrites.clear();
        writerDrainScheduled = false;
        droppedWriterTasks.addAndGet(discarded);
        System.err.println("[ImageJAI-Audit] " + reason + "; " + discarded
                + " row(s) retained in memory only");
    }

    private void drainWriterQueue() {
        boolean handedOffIdle = false;
        try {
            Runnable hook = beforeWriterDrainHookForTest;
            beforeWriterDrainHookForTest = null;
            if (hook != null) {
                hook.run();
            }
            while (true) {
                List<PendingWrite> batch = new ArrayList<PendingWrite>();
                synchronized (writerLifecycleLock) {
                    if (pendingWrites.isEmpty()) {
                        Runnable idleHook = beforeWriterIdleHookForTest;
                        beforeWriterIdleHookForTest = null;
                        if (idleHook != null) {
                            idleHook.run();
                        }
                        // Admission and the active-drain transition are atomic.
                        // An append after this point sees false and submits its own
                        // drain before it can return successfully.
                        writerDrainScheduled = false;
                        handedOffIdle = true;
                        return;
                    }
                    while (!pendingWrites.isEmpty()) {
                        batch.add(pendingWrites.removeFirst());
                    }
                }
                writeBatch(batch);
                completedWriterBatches.incrementAndGet();
            }
        } finally {
            if (!handedOffIdle) {
                synchronized (writerLifecycleLock) {
                    writerDrainScheduled = false;
                    if (!pendingWrites.isEmpty()) {
                        scheduleWriterDrainLocked();
                    }
                }
            }
        }
    }

    private void writeBatch(List<PendingWrite> batch) {
        Map<Path, List<AuditRow>> rowsByPath =
                new LinkedHashMap<Path, List<AuditRow>>();
        for (PendingWrite write : batch) {
            Path csvPath = write.csvPath;
            if (csvPath == null) {
                continue;
            }
            List<AuditRow> rows = rowsByPath.get(csvPath);
            if (rows == null) {
                rows = new ArrayList<AuditRow>();
                rowsByPath.put(csvPath, rows);
            }
            rows.add(write.row);
        }
        for (Map.Entry<Path, List<AuditRow>> entry : rowsByPath.entrySet()) {
            try {
                writeBatchSync(entry.getKey(), entry.getValue());
            } catch (IOException e) {
                System.err.println("[ImageJAI-Audit] append failed: " + e.getMessage());
            }
        }
    }

    /** Immutable destination-and-row pair captured by {@link #append(AuditRow)}. */
    private static final class PendingWrite {
        final Path csvPath;
        final AuditRow row;

        PendingWrite(Path csvPath, AuditRow row) {
            this.csvPath = csvPath;
            this.row = row;
        }
    }

    private static AuditRow boundedRow(AuditRow row) {
        List<String> boundedFields = new ArrayList<String>();
        List<String> fields = row.fieldsRedacted();
        boolean fieldsTruncated = fields.size() > MAX_ROW_FIELDS;
        int retained = Math.min(fields.size(), fieldsTruncated
                ? MAX_ROW_FIELDS - 1 : MAX_ROW_FIELDS);
        for (int i = 0; i < retained; i++) {
            boundedFields.add(boundChars(fields.get(i), MAX_ROW_FIELD_CHARS));
        }
        if (fieldsTruncated) boundedFields.add("__truncated_fields__");
        String notes = boundChars(row.notes(), MAX_ROW_TEXT_CHARS);
        if (row.notes().length() > notes.length()) {
            String suffix = " [truncated from " + row.notes().length() + " chars]";
            notes = notes.substring(0, Math.max(0,
                    MAX_ROW_TEXT_CHARS - suffix.length())) + suffix;
        }
        return new AuditRow(row.timestampUtc(),
                boundChars(row.sessionId(), MAX_ROW_FIELD_CHARS),
                boundChars(row.command(), MAX_ROW_FIELD_CHARS),
                row.posture(),
                boundChars(row.modelEndpoint(), MAX_ROW_FIELD_CHARS),
                boundChars(row.captureSource(), MAX_ROW_FIELD_CHARS),
                row.bytesOut(), row.bytesIn(),
                boundChars(row.imageHash(), MAX_ROW_FIELD_CHARS),
                row.redactionApplied(), boundedFields, notes,
                row.redactedPayloadJson());
    }

    private static String boundChars(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
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

    public synchronized AutoCloseable subscribeRecent(final int limit,
                                                      final Listener listener) {
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
        if (listeners.size() >= MAX_LISTENERS) {
            rejectedListeners.incrementAndGet();
            return noOpSubscription();
        }
        listeners.addIfAbsent(limitingListener);
        try {
            listener.auditRowsUpdated(recent(limit));
        } catch (Throwable ignore) {
            listenerFailures.incrementAndGet();
        }
        return new AutoCloseable() {
            @Override
            public void close() {
                listeners.remove(limitingListener);
            }
        };
    }

    private static AutoCloseable noOpSubscription() {
        return new AutoCloseable() {
            @Override public void close() { }
        };
    }

    public long droppedRecentRowCount() { return droppedRecentRows.get(); }
    public long droppedWriterTaskCount() { return droppedWriterTasks.get(); }
    public long droppedNotificationCount() { return droppedNotifications.get(); }
    public long rejectedListenerCount() { return rejectedListeners.get(); }
    public long listenerFailureCount() { return listenerFailures.get(); }
    public int listenerCount() { return listeners.size(); }
    public int writerQueueSize() {
        synchronized (writerLifecycleLock) {
            return pendingWrites.size();
        }
    }
    public int notifierQueueSize() { return notifier.getQueue().size(); }

    public void flushForTest() throws Exception {
        Future<?> writeFuture;
        synchronized (writerLifecycleLock) {
            if (!pendingWrites.isEmpty() && !writerDrainScheduled) {
                scheduleWriterDrainLocked();
            }
            // Submission shares the lifecycle lock with append scheduling. The
            // barrier therefore cannot overtake an accepted-but-not-yet-submitted drain.
            writeFuture = writer.submit(new Runnable() {
                @Override
                public void run() {
                }
            });
        }
        writeFuture.get(5, TimeUnit.SECONDS);
        Future<?> notifyFuture = null;
        synchronized (notifierLifecycleLock) {
            if (notificationScheduled) {
                // The notification drain remains one executor task until it has
                // observed a stable version. A fence behind it cannot overtake a
                // coalesced update.
                notifyFuture = notifier.submit(new Runnable() {
                    @Override
                    public void run() {
                    }
                });
            }
        }
        if (notifyFuture != null) {
            notifyFuture.get(5, TimeUnit.SECONDS);
        }
    }

    public void shutdownAndAwait(long millis) {
        // Serialize shutdown with append admission. If append holds the lock first,
        // its drain is submitted before shutdown; if shutdown wins, append observes
        // the closed executor and is rejected before entering pendingWrites.
        synchronized (writerLifecycleLock) {
            writer.shutdown();
        }
        synchronized (notifierLifecycleLock) {
            notifier.shutdown();
        }
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
        if (!Files.isRegularFile(csvPath)) {
            throw new IOException("Audit log is not a regular readable file: " + csvPath);
        }
        long fileBytes = Files.size(csvPath);
        if (fileBytes > MAX_SUMMARY_BYTES) {
            throw new IOException("Audit log exceeds summary limit of "
                    + MAX_SUMMARY_BYTES + " bytes");
        }
        Runnable beforeRead = beforeSummaryReadHookForTest;
        if (beforeRead != null) beforeRead.run();

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
                droppedRecentRows.incrementAndGet();
            }
        }
    }

    private void notifyListenersAsync() {
        if (listeners.isEmpty()) {
            return;
        }
        synchronized (notifierLifecycleLock) {
            if (notificationScheduled) {
                droppedNotifications.incrementAndGet();
                return;
            }
            notificationScheduled = true;
            try {
                notifier.submit(new Runnable() {
                    @Override
                    public void run() {
                        drainNotifications();
                    }
                });
            } catch (RejectedExecutionException full) {
                notificationScheduled = false;
                droppedNotifications.incrementAndGet();
            } catch (RuntimeException ignore) {
                notificationScheduled = false;
                droppedNotifications.incrementAndGet();
            }
        }
    }

    private void drainNotifications() {
        while (true) {
            long observed = notificationVersion.get();
            // Snapshot only after bounded executor admission.
            notifyListeners(recent(DEFAULT_RECENT_LIMIT));
            synchronized (notifierLifecycleLock) {
                if (notificationVersion.get() == observed) {
                    notificationScheduled = false;
                    return;
                }
            }
        }
    }

    private void notifyListeners(List<AuditRow> snapshot) {
        for (Listener listener : listeners) {
            try {
                listener.auditRowsUpdated(snapshot);
            } catch (Throwable ignore) {
                listenerFailures.incrementAndGet();
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

    private static ThreadPoolExecutor daemonExecutor(final String name) {
        final int capacity = name.endsWith("listener")
                ? NOTIFIER_QUEUE_CAPACITY : WRITER_QUEUE_CAPACITY;
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(capacity), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    private Path resolveCsvPath() {
        try {
            return pathResolver.csvPath();
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeSync(Path csvPath, AuditRow row) throws IOException {
        writeBatchSync(csvPath, row == null
                ? Collections.<AuditRow>emptyList()
                : Collections.singletonList(row));
    }

    private void writeBatchSync(Path csvPath, List<AuditRow> rows) throws IOException {
        try {
            writeWithLock(csvPath, rows);
        } catch (IOException e) {
            Path sidecar = sidecarPath(csvPath);
            if (sidecar == null || sidecar.equals(csvPath)) {
                throw e;
            }
            writeWithLock(sidecar, rows);
        }
    }

    private void writeWithLock(Path path, List<AuditRow> rows) throws IOException {
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
                if (rows != null && !rows.isEmpty()) {
                    channel.position(channel.size());
                    for (AuditRow row : rows) {
                        write(channel, row.toCsvLine() + System.lineSeparator());
                    }
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
        String text = new String(readBounded(path, MAX_SUMMARY_BYTES),
                StandardCharsets.UTF_8);
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

    private static byte[] readBounded(Path path, long maxBytes) throws IOException {
        if (maxBytes < 0L || maxBytes > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("invalid byte limit");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.min(maxBytes, 64L * 1024L));
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long total = 0L;
            while (channel.read(buffer) != -1) {
                buffer.flip();
                int count = buffer.remaining();
                if (total + count > maxBytes) {
                    throw new IOException("Audit log exceeds summary limit of "
                            + maxBytes + " bytes");
                }
                out.write(buffer.array(), buffer.position(), count);
                total += count;
                buffer.clear();
            }
        }
        return out.toByteArray();
    }

    static void setBeforeSummaryReadHookForTest(Runnable hook) {
        beforeSummaryReadHookForTest = hook;
    }

    void setBeforeWriterDrainHookForTest(Runnable hook) {
        beforeWriterDrainHookForTest = hook;
    }

    void setBeforeWriterIdleHookForTest(Runnable hook) {
        beforeWriterIdleHookForTest = hook;
    }

    long completedWriterBatchCountForTest() {
        return completedWriterBatches.get();
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
