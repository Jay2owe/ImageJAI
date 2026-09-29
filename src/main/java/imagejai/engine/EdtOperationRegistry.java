package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, session-owned registry for asynchronous work queued on Swing's
 * event-dispatch thread (EDT).
 *
 * <p>The action token is the source of truth. A record becomes terminal only
 * after {@link GuiActionDispatcher.ActionToken#isFinished()} reports that the
 * EDT wrapper has exited. Cancellation and shutdown may invalidate queued
 * work, but never manufacture a terminal state while that wrapper is still
 * pending or running.</p>
 */
public final class EdtOperationRegistry implements AutoCloseable {

    public static final int DEFAULT_MAX_ACTIVE = 64;
    public static final int DEFAULT_MAX_RETAINED = 256;
    public static final int DEFAULT_MAX_RESULT_BYTES = 1_048_576;
    public static final int DEFAULT_MAX_ERROR_CHARS = 16_384;
    public static final int MAX_OWNER_CHARS = 256;
    public static final int MAX_OPERATION_ID_CHARS = 68;
    public static final long DEFAULT_RETENTION_MS = 60L * 60_000L;
    public static final long DEFAULT_SCAN_INTERVAL_MS = 25L;
    public static final long DEFAULT_COMPLETION_READINESS_MS = 120_000L;

    public static final String STATE_QUEUED = "queued";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_COMPLETED = "completed";
    public static final String STATE_FAILED = "failed";

    @FunctionalInterface
    public interface ActionStarter {
        GuiActionDispatcher.ActionToken start() throws Exception;
    }

    @FunctionalInterface
    public interface CompletionSupplier {
        JsonObject complete() throws Exception;

        /**
         * Cheap, nonblocking readiness probe run only after the EDT wrapper
         * exits. Returning false keeps the operation nonterminal so a later
         * scanner/poll pass can observe asynchronously published state.
         */
        default boolean isReady() throws Exception { return true; }
    }

    /** A lightweight handle. Status remains owner-scoped through the registry. */
    public final class Operation {
        private final String id;
        private final String ownerSession;
        private final long startedAt;
        private volatile GuiActionDispatcher.ActionToken token;
        private volatile String terminalState;
        private volatile JsonObject result;
        private volatile boolean resultTruncated;
        private volatile long resultOriginalBytes;
        private volatile String error;
        private volatile long endedAt;
        private CompletionSupplier completionSupplier;
        private long completionWaitStartedNanos;
        private boolean completionWaitStarted;
        private final CountDownLatch terminalSignal = new CountDownLatch(1);
        private boolean cancelledBeforeStart;
        private boolean active = true;

        private Operation(String id, String ownerSession, long startedAt,
                          CompletionSupplier completionSupplier) {
            this.id = id;
            this.ownerSession = ownerSession;
            this.startedAt = startedAt;
            this.completionSupplier = completionSupplier;
        }

        public String id() { return id; }
        public String ownerSession() { return ownerSession; }
        public long startedAt() { return startedAt; }
        public long endedAt() { return endedAt; }
        public boolean hasStarted() {
            GuiActionDispatcher.ActionToken current = token;
            return current != null && current.hasStarted();
        }
        public boolean isTerminal() {
            observe(this);
            return terminalState != null;
        }

        /** Bounded handler wait; false means the operation is still ambiguous. */
        public boolean awaitTerminal(long timeoutMs) throws InterruptedException {
            if (timeoutMs < 0L) throw new IllegalArgumentException("timeoutMs must be non-negative");
            observe(this);
            if (terminalState != null) return true;
            return terminalSignal.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        public String state() {
            observe(this);
            String terminal = terminalState;
            if (terminal != null) return terminal;
            GuiActionDispatcher.ActionToken current = token;
            return current != null && current.hasStarted()
                    ? STATE_RUNNING : STATE_QUEUED;
        }

        /** A detached protocol projection of the current operation state. */
        public JsonObject toJson() {
            return statusJson(this);
        }
    }

    private final ConcurrentHashMap<String, Operation> operations =
            new ConcurrentHashMap<String, Operation>();
    private final Object lifecycleLock = new Object();
    private final int maxActive;
    private final int maxRetained;
    private final int maxResultBytes;
    private final int maxErrorChars;
    private final long retentionMs;
    private final long completionReadinessNanos;
    private final SecureRandom random;
    private final ScheduledExecutorService scanner;
    private final AtomicInteger activeCount = new AtomicInteger();
    private volatile boolean accepting = true;
    private volatile boolean shutdown;

    public EdtOperationRegistry() {
        this(DEFAULT_MAX_ACTIVE, DEFAULT_MAX_RETAINED, DEFAULT_RETENTION_MS,
                DEFAULT_SCAN_INTERVAL_MS, DEFAULT_MAX_RESULT_BYTES,
                DEFAULT_MAX_ERROR_CHARS, DEFAULT_COMPLETION_READINESS_MS);
    }

    public EdtOperationRegistry(int maxActive, int maxRetained,
                                long retentionMs, long scanIntervalMs,
                                int maxResultBytes, int maxErrorChars) {
        this(maxActive, maxRetained, retentionMs, scanIntervalMs,
                maxResultBytes, maxErrorChars,
                DEFAULT_COMPLETION_READINESS_MS);
    }

    EdtOperationRegistry(int maxActive, int maxRetained,
                         long retentionMs, long scanIntervalMs,
                         int maxResultBytes, int maxErrorChars,
                         long completionReadinessMs) {
        this(maxActive, maxRetained, retentionMs, scanIntervalMs,
                maxResultBytes, maxErrorChars, completionReadinessMs,
                new SecureRandom());
    }

    EdtOperationRegistry(int maxActive, int maxRetained,
                         long retentionMs, long scanIntervalMs,
                         int maxResultBytes, int maxErrorChars,
                         long completionReadinessMs, SecureRandom random) {
        if (maxActive <= 0) throw new IllegalArgumentException("maxActive must be positive");
        if (maxRetained < maxActive) {
            throw new IllegalArgumentException("maxRetained must be at least maxActive");
        }
        if (retentionMs < 0L) throw new IllegalArgumentException("retentionMs must be non-negative");
        if (scanIntervalMs <= 0L) {
            throw new IllegalArgumentException("scanIntervalMs must be positive");
        }
        if (maxResultBytes < 128) {
            throw new IllegalArgumentException("maxResultBytes must be at least 128");
        }
        if (maxErrorChars <= 0) {
            throw new IllegalArgumentException("maxErrorChars must be positive");
        }
        if (completionReadinessMs <= 0L) {
            throw new IllegalArgumentException(
                    "completionReadinessMs must be positive");
        }
        this.maxActive = maxActive;
        this.maxRetained = maxRetained;
        this.retentionMs = retentionMs;
        this.maxResultBytes = maxResultBytes;
        this.maxErrorChars = maxErrorChars;
        this.completionReadinessNanos =
                TimeUnit.MILLISECONDS.toNanos(completionReadinessMs);
        this.random = random == null ? new SecureRandom() : random;
        this.scanner = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ImageJAI-EDT-Operation-Scanner");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.scanner.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { scanSafely(); }
        }, scanIntervalMs, scanIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Reserve registry capacity before starting/queueing an EDT action. This
     * is the preferred API because a rejected admission cannot leave
     * untracked GUI work behind.
     */
    public Operation admit(String ownerSession, ActionStarter starter,
                           CompletionSupplier completionSupplier) {
        if (starter == null) throw new IllegalArgumentException("starter is required");
        scanSafely();
        Operation operation;
        synchronized (lifecycleLock) {
            operation = reserveLocked(ownerSession, completionSupplier);
            try {
                GuiActionDispatcher.ActionToken token = starter.start();
                if (token == null) throw new IllegalStateException("starter returned no action token");
                operation.token = token;
            } catch (Throwable failure) {
                finishFailure(operation, failure, true);
                operations.remove(operation.id, operation);
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                if (failure instanceof Error) throw (Error) failure;
                throw new RejectedExecutionException("could not start EDT operation", failure);
            }
        }
        observe(operation);
        return operation;
    }

    /**
     * Register work that has already been queued. Callers that control
     * queueing should prefer {@link #admit(String, ActionStarter,
     * CompletionSupplier)} so capacity is reserved first.
     */
    public Operation registerQueued(String ownerSession,
                                    GuiActionDispatcher.ActionToken token,
                                    CompletionSupplier completionSupplier) {
        if (token == null) throw new IllegalArgumentException("action token is required");
        scanSafely();
        Operation operation;
        synchronized (lifecycleLock) {
            operation = reserveLocked(ownerSession, completionSupplier);
            operation.token = token;
        }
        observe(operation);
        return operation;
    }

    /** Return owner-scoped status, or {@code null} without leaking existence. */
    public JsonObject operationStatus(String ownerSession, String operationId) {
        Operation operation = owned(ownerSession, operationId);
        return operation == null ? null : statusJson(operation);
    }

    /**
     * Invalidate queued work. Started EDT work cannot be interrupted and will
     * remain running until its wrapper exits.
     */
    public boolean cancel(String ownerSession, String operationId) {
        Operation operation = owned(ownerSession, operationId);
        if (operation == null) return false;
        synchronized (operation) {
            observeLocked(operation);
            if (operation.terminalState != null) return false;
            GuiActionDispatcher.ActionToken token = operation.token;
            if (token == null || token.isFinished()) {
                observeLocked(operation);
                return false;
            }
            boolean invalidatedBeforeStart = token.invalidate();
            if (invalidatedBeforeStart) operation.cancelledBeforeStart = true;
            return invalidatedBeforeStart;
        }
    }

    public int activeCount() { return activeCount.get(); }
    public int retainedCount() { return operations.size(); }
    public boolean isShutdown() { return shutdown; }

    /**
     * Stop admission and invalidate queued actions. The one bounded scanner
     * stays alive only while admitted wrappers still need truthful completion
     * observation; it exits after the last one finishes.
     */
    public void shutdown() {
        List<Operation> snapshot;
        synchronized (lifecycleLock) {
            if (shutdown) return;
            accepting = false;
            shutdown = true;
            snapshot = new ArrayList<Operation>(operations.values());
        }
        for (Operation operation : snapshot) {
            synchronized (operation) {
                observeLocked(operation);
                if (operation.terminalState == null && operation.token != null
                        && operation.token.invalidate()) {
                    operation.cancelledBeforeStart = true;
                }
            }
        }
        scanSafely();
        stopScannerIfQuiescent();
    }

    @Override public void close() { shutdown(); }

    /** Test/integration seam for orderly teardown without creating a waiter thread. */
    public boolean awaitQuiescence(long timeout, TimeUnit unit)
            throws InterruptedException {
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (lifecycleLock) {
            while (activeCount.get() != 0) {
                if (remainingNanos <= 0L) return false;
                TimeUnit.NANOSECONDS.timedWait(lifecycleLock,
                        Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(10L)));
                remainingNanos = deadline - System.nanoTime();
            }
            return true;
        }
    }

    private Operation reserveLocked(String ownerSession,
                                    CompletionSupplier completionSupplier) {
        String owner = validateOwner(ownerSession);
        if (completionSupplier == null) {
            throw new IllegalArgumentException("completion supplier is required");
        }
        if (!accepting) throw new RejectedExecutionException("EDT operation registry is stopped");
        evictExpiredTerminals();
        if (activeCount.get() >= maxActive) {
            throw new RejectedExecutionException("EDT operation active capacity reached (max "
                    + maxActive + ")");
        }
        evictOldestTerminal(operations.size() - maxRetained + 1);
        if (operations.size() >= maxRetained) {
            throw new RejectedExecutionException("EDT operation retained capacity reached (max "
                    + maxRetained + ")");
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            Operation operation = new Operation(newId(), owner,
                    System.currentTimeMillis(), completionSupplier);
            if (operations.putIfAbsent(operation.id, operation) == null) {
                activeCount.incrementAndGet();
                return operation;
            }
        }
        throw new RejectedExecutionException("could not allocate unique EDT operation id");
    }

    private Operation owned(String ownerSession, String operationId) {
        if (ownerSession == null || !isValidOperationId(operationId)) return null;
        Operation operation = operations.get(operationId);
        return operation != null && ownerSession.equals(operation.ownerSession)
                ? operation : null;
    }

    private JsonObject statusJson(Operation operation) {
        observe(operation);
        synchronized (operation) {
            String state = stateLocked(operation);
            boolean terminal = operation.terminalState != null;
            boolean started = operation.token != null && operation.token.hasStarted();
            JsonObject json = new JsonObject();
            json.addProperty("operation_id", operation.id);
            json.addProperty("state", state);
            json.addProperty("terminal", terminal);
            json.addProperty("retry_safe", terminal
                    && STATE_FAILED.equals(state) && !started);
            json.addProperty("startedAt", operation.startedAt);
            long end = operation.endedAt;
            if (end > 0L) json.addProperty("endedAt", end);
            json.addProperty("elapsedMs", Math.max(0L,
                    (end > 0L ? end : System.currentTimeMillis()) - operation.startedAt));
            if (operation.result != null) {
                json.add("result", operation.result.deepCopy());
            }
            if (operation.resultTruncated) {
                json.addProperty("result_truncated", true);
                json.addProperty("result_original_bytes", operation.resultOriginalBytes);
            }
            if (operation.error != null) json.addProperty("error", operation.error);
            return json;
        }
    }

    private String stateLocked(Operation operation) {
        if (operation.terminalState != null) return operation.terminalState;
        return operation.token != null && operation.token.hasStarted()
                ? STATE_RUNNING : STATE_QUEUED;
    }

    private void observe(Operation operation) {
        synchronized (operation) { observeLocked(operation); }
    }

    private void observeLocked(Operation operation) {
        if (operation.terminalState != null) return;
        GuiActionDispatcher.ActionToken token = operation.token;
        if (token == null || !token.isFinished()) return;
        if (operation.cancelledBeforeStart) {
            finishFailureLocked(operation, "cancelled before execution", true);
            return;
        }
        try {
            if (!operation.completionSupplier.isReady()) {
                long now = System.nanoTime();
                if (!operation.completionWaitStarted) {
                    operation.completionWaitStartedNanos = now;
                    operation.completionWaitStarted = true;
                }
                if (now - operation.completionWaitStartedNanos
                        >= completionReadinessNanos) {
                    finishFailureLocked(operation,
                            "EDT operation completion readiness deadline exceeded",
                            false);
                }
                return;
            }
            JsonObject supplied = operation.completionSupplier.complete();
            if (supplied == null) {
                finishFailureLocked(operation, "EDT operation produced no result", false);
                return;
            }
            String encoded = supplied.toString();
            long bytes = utf8Length(encoded);
            if (bytes <= maxResultBytes) {
                operation.result = JsonParser.parseString(encoded).getAsJsonObject();
            } else {
                operation.resultTruncated = true;
                operation.resultOriginalBytes = bytes;
                finishFailureLocked(operation,
                        "EDT operation result exceeded retained byte limit", false);
                return;
            }
            finishTerminalLocked(operation, STATE_COMPLETED);
        } catch (Throwable failure) {
            finishFailureLocked(operation, messageFor(failure), false);
        }
    }

    private void finishFailure(Operation operation, Throwable failure,
                               boolean beforeStart) {
        synchronized (operation) {
            if (beforeStart) operation.cancelledBeforeStart = true;
            finishFailureLocked(operation, messageFor(failure), beforeStart);
        }
    }

    private void finishFailureLocked(Operation operation, String message,
                                     boolean beforeStart) {
        if (operation.terminalState != null) return;
        if (beforeStart) operation.cancelledBeforeStart = true;
        operation.error = boundedChars(message, maxErrorChars);
        finishTerminalLocked(operation, STATE_FAILED);
    }

    private void finishTerminalLocked(Operation operation, String state) {
        if (operation.terminalState != null) return;
        operation.terminalState = state;
        operation.endedAt = System.currentTimeMillis();
        operation.completionSupplier = null;
        if (operation.active) {
            operation.active = false;
            activeCount.decrementAndGet();
        }
        operation.terminalSignal.countDown();
        stopScannerIfQuiescent();
    }

    private void scanSafely() {
        try { scanAndEvict(); } catch (Throwable ignored) {
            // Registry observation is retried; it cannot alter EDT execution.
        }
    }

    private void scanAndEvict() {
        for (Operation operation : operations.values()) observe(operation);
        evictExpiredTerminals();
        evictOldestTerminal(operations.size() - maxRetained);
        stopScannerIfQuiescent();
    }

    private void evictExpiredTerminals() {
        long cutoff = System.currentTimeMillis() - retentionMs;
        for (Map.Entry<String, Operation> entry : operations.entrySet()) {
            Operation operation = entry.getValue();
            if (operation.terminalState != null && operation.endedAt > 0L
                    && operation.endedAt < cutoff) {
                operations.remove(entry.getKey(), operation);
            }
        }
    }

    private void evictOldestTerminal(int count) {
        if (count <= 0) return;
        List<Operation> terminal = new ArrayList<Operation>();
        for (Operation operation : operations.values()) {
            if (operation.terminalState != null) terminal.add(operation);
        }
        terminal.sort(new Comparator<Operation>() {
            @Override public int compare(Operation left, Operation right) {
                return Long.compare(left.endedAt, right.endedAt);
            }
        });
        for (int i = 0; i < count && i < terminal.size(); i++) {
            Operation operation = terminal.get(i);
            operations.remove(operation.id, operation);
        }
    }

    private void stopScannerIfQuiescent() {
        if (shutdown && activeCount.get() == 0) scanner.shutdown();
    }

    private String newId() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return "edt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean isValidOperationId(String operationId) {
        if (operationId == null || operationId.length() < 20
                || operationId.length() > MAX_OPERATION_ID_CHARS
                || !operationId.startsWith("edt_")) {
            return false;
        }
        for (int i = 4; i < operationId.length(); i++) {
            char value = operationId.charAt(i);
            if (!((value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '_' || value == '-')) {
                return false;
            }
        }
        return true;
    }

    private static String validateOwner(String ownerSession) {
        if (ownerSession == null || ownerSession.trim().isEmpty()) {
            throw new IllegalArgumentException("owner session is required");
        }
        if (ownerSession.length() > MAX_OWNER_CHARS) {
            throw new IllegalArgumentException("owner session is too long");
        }
        return ownerSession;
    }

    private static String messageFor(Throwable failure) {
        if (failure == null) return "EDT operation failed";
        String message = failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private static String boundedChars(String value, int maxChars) {
        String safe = value == null ? "EDT operation failed" : value;
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars);
    }

    private static long utf8Length(String value) {
        long bytes = 0L;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (codePoint <= 0x7f) bytes += 1L;
            else if (codePoint <= 0x7ff) bytes += 2L;
            else if (codePoint <= 0xffff) bytes += 3L;
            else bytes += 4L;
            i += Character.charCount(codePoint);
        }
        return bytes;
    }
}
