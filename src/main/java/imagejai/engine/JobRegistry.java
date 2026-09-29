package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Session-owned view of asynchronous macro jobs.
 *
 * <p>{@link MutationCoordinator} owns admission, worker creation,
 * serialization, cancellation, timeout, and terminal completion. This class
 * owns only retained job records and their protocol/event representation.</p>
 */
public class JobRegistry {

    public static final long JANITOR_INTERVAL_MS = 60_000L;
    public static final long RETENTION_MS = 60L * 60_000L;
    public static final int MAX_JOBS = 256;
    public static final int MAX_RETAINED_CODE_CHARS = 65_536;
    public static final int MAX_RESULT_TEXT_BYTES = 1_048_576;
    public static final int MAX_RESULT_IMAGES = 256;
    public static final int MAX_IMAGE_NAME_CHARS = 1024;
    public static final int MAX_ERROR_CHARS = 16_384;

    public static final String STATE_RUNNING = "running";
    public static final String STATE_CANCEL_REQUESTED = "cancel_requested";
    public static final String STATE_TIMEOUT_REQUESTED = "timeout_requested";
    public static final String STATE_COMPLETED = "completed";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_CANCELLED = "cancelled";
    public static final String STATE_TIMED_OUT = "timed_out";
    /** Internal routing metadata removed before a job event reaches the wire. */
    static final String EVENT_OWNER_FIELD = "_owner_session";

    private static final String TRUSTED_OWNER = "__imagejai_in_process__";

    /** Public retained job state. Terminal fields change only after worker exit. */
    public static class Job {
        public final String id;
        public final String code;
        public final boolean codeTruncated;
        public volatile String state = STATE_RUNNING;
        public volatile double progress = 0.0;
        public volatile JsonObject result;
        public volatile String error;
        public final long startedAt;
        public volatile long endedAt;
        final String ownerSession;
        final MutationCoordinator.Handle<ExecutionResult> handle;

        Job(MutationCoordinator.Handle<ExecutionResult> handle, String code) {
            this.handle = handle;
            this.id = handle.id();
            String submitted = code == null ? "" : code;
            this.codeTruncated = submitted.length() > MAX_RETAINED_CODE_CHARS;
            this.code = codeTruncated
                    ? submitted.substring(0, MAX_RETAINED_CODE_CHARS)
                    : submitted;
            this.ownerSession = handle.ownerSession();
            this.startedAt = handle.startedAtMs();
        }
    }

    private static final class MacroFailure extends Exception {
        MacroFailure(String message) { super(message); }
    }

    private final ConcurrentHashMap<String, Job> jobs =
            new ConcurrentHashMap<String, Job>();
    private final EventBus bus = EventBus.getInstance();
    private final CommandEngine commandEngine;
    private final MutationCoordinator coordinator;
    private final Thread janitor;
    private final Object admissionLock = new Object();
    private final AtomicLong rejectedAdmissions = new AtomicLong(0L);
    private volatile boolean shutdown;

    public JobRegistry(CommandEngine commandEngine) {
        this(commandEngine, new MutationCoordinator());
        if (commandEngine != null) commandEngine.setMutationCoordinator(coordinator);
    }

    public JobRegistry(CommandEngine commandEngine, MutationCoordinator coordinator) {
        if (coordinator == null) throw new IllegalArgumentException("coordinator is required");
        this.commandEngine = commandEngine;
        this.coordinator = coordinator;
        this.janitor = new Thread(new Runnable() {
            @Override public void run() { janitorLoop(); }
        }, "ImageJAI-JobRegistry-Janitor");
        this.janitor.setDaemon(true);
        this.janitor.start();
    }

    /** Trusted in-process compatibility overload. Network callers use an owner. */
    public Job submit(String code) {
        return submit(code, TRUSTED_OWNER, 0L, false, false, false, null);
    }

    public Job submit(final String code,
                      final String ownerSession,
                      long timeoutMs,
                      boolean safetyEnabled,
                      boolean undoEnabled,
                      boolean provenanceEnabled,
                      MutationCoordinator.Lifecycle<ExecutionResult> lifecycle) {
        if (shutdown) throw new RejectedExecutionException("job registry is stopped");
        if (ownerSession == null || ownerSession.trim().isEmpty()) {
            throw new IllegalArgumentException("owner session is required");
        }
        if (commandEngine == null) {
            throw new RejectedExecutionException("macro engine is unavailable");
        }

        final MutationCoordinator.Lifecycle<ExecutionResult> delegate = lifecycle == null
                ? new MutationCoordinator.Lifecycle<ExecutionResult>() {}
                : lifecycle;
        final AtomicReference<Job> jobRef = new AtomicReference<Job>();

        MutationCoordinator.Lifecycle<ExecutionResult> wrapped =
                new MutationCoordinator.Lifecycle<ExecutionResult>() {
            @Override public void checkSafety() throws Exception {
                delegate.checkSafety();
            }

            @Override public void beforeMutation() throws Exception {
                delegate.beforeMutation();
            }

            @Override public void afterMutation(
                    MutationCoordinator.Outcome<ExecutionResult> outcome) throws Exception {
                delegate.afterMutation(outcome);
            }

            @Override public void onCompletion(
                    MutationCoordinator.Completion<ExecutionResult> completion) {
                Job job = jobRef.get();
                try {
                    if (job != null) finishJob(job, completion);
                } finally {
                    try {
                        delegate.onCompletion(completion);
                    } finally {
                        if (job != null) job.handle.releaseRetainedPayload();
                    }
                }
            }
        };

        MutationCoordinator.Request<ExecutionResult> request =
                MutationCoordinator.Request.<ExecutionResult>builder()
                        .ownerSession(ownerSession)
                        .sourceKind("macro")
                        .code(code)
                        .timeoutMs(timeoutMs)
                        .safetyEnabled(safetyEnabled)
                        .undoEnabled(undoEnabled)
                        .provenanceEnabled(provenanceEnabled)
                        .operation(new MutationCoordinator.Operation<ExecutionResult>() {
                            @Override public ExecutionResult run() throws Exception {
                                Job job = jobRef.get();
                                DoubleConsumer progress = new DoubleConsumer() {
                                    @Override public void accept(double pct) {
                                        Job current = jobRef.get();
                                        if (current == null || Double.isNaN(pct) || pct < 0) return;
                                        current.progress = Math.min(1.0, pct);
                                        bus.publish("job.progress", progressFrame(current));
                                    }
                                };
                                ExecutionResult result = commandEngine.executeMacroOnCurrentThread(
                                        code == null ? "" : code, progress);
                                if (result == null) throw new MacroFailure("macro returned no result");
                                if (!result.isSuccess()) {
                                    throw new MacroFailure(result.getError() == null
                                            ? "macro error" : result.getError());
                                }
                                return result;
                            }
                        })
                        .cancellationAction(new MutationCoordinator.CancellationAction() {
                            @Override public void cancel() {
                                CommandEngine.requestOwnedMacroAbort();
                            }
                        })
                        .lifecycle(wrapped)
                        .build();

        synchronized (admissionLock) {
            makeRoomForAdmission();
            if (jobs.size() >= MAX_JOBS) {
                rejectedAdmissions.incrementAndGet();
                throw new RejectedExecutionException("job registry capacity reached (max "
                        + MAX_JOBS + ")");
            }
        try {
        coordinator.submit(request,
                new Consumer<MutationCoordinator.Handle<ExecutionResult>>() {
                    @Override public void accept(
                            MutationCoordinator.Handle<ExecutionResult> handle) {
                        Job job = new Job(handle, code);
                        Job prior = jobs.putIfAbsent(job.id, job);
                        if (prior != null) {
                            throw new IllegalStateException("cryptographic job id collision");
                        }
                        jobRef.set(job);
                        try {
                            bus.publish("job.started", startFrame(job));
                        } catch (Throwable ignored) {
                            // Event telemetry cannot invalidate admission.
                        }
                    }
                });
        } catch (RuntimeException failure) {
            Job admitted = jobRef.get();
            if (admitted != null) jobs.remove(admitted.id, admitted);
            throw failure;
        }
        }
        enforceCap();
        return jobRef.get();
    }

    /** Owner-scoped lookup for protocol callers. */
    public Job get(String ownerSession, String id) {
        if (id == null || ownerSession == null) return null;
        Job job = jobs.get(id);
        return job != null && ownerSession.equals(job.ownerSession) ? job : null;
    }

    /** Trusted in-process lookup retained for plugin tests/admin code. */
    public Job get(String id) {
        return id == null ? null : jobs.get(id);
    }

    public List<Job> list(String ownerSession) {
        List<Job> out = new ArrayList<Job>();
        if (ownerSession != null) {
            for (Job job : jobs.values()) {
                if (ownerSession.equals(job.ownerSession)) out.add(job);
            }
        }
        sortNewestFirst(out);
        return out;
    }

    /** Trusted in-process list retained for diagnostics. */
    public List<Job> list() {
        List<Job> out = new ArrayList<Job>(jobs.values());
        sortNewestFirst(out);
        return out;
    }

    private static void sortNewestFirst(List<Job> jobs) {
        Collections.sort(jobs, new Comparator<Job>() {
            @Override public int compare(Job a, Job b) {
                return Long.compare(b.startedAt, a.startedAt);
            }
        });
    }

    public int size() { return jobs.size(); }

    public long rejectedAdmissionCount() { return rejectedAdmissions.get(); }

    public boolean cancel(String ownerSession, String id) {
        Job job = get(ownerSession, id);
        return requestCancel(job);
    }

    /** Trusted in-process cancellation retained for plugin teardown/tests. */
    public boolean cancel(String id) {
        return requestCancel(get(id));
    }

    private boolean requestCancel(Job job) {
        if (job == null || job.handle.isTerminal()) return false;
        boolean accepted = job.handle.cancel();
        refreshTransientState(job);
        return accepted;
    }

    public void cancelAll() {
        for (Job job : jobs.values()) requestCancel(job);
    }

    public void shutdown() {
        shutdown = true;
        coordinator.shutdown();
        janitor.interrupt();
    }

    public JsonObject toJson(Job job) {
        refreshTransientState(job);
        JsonObject o = new JsonObject();
        o.addProperty("job_id", job.id);
        o.addProperty("state", job.state);
        o.addProperty("progress", job.progress);
        o.addProperty("startedAt", job.startedAt);
        long end = job.endedAt;
        long elapsed;
        if (end > 0L) {
            o.addProperty("endedAt", end);
            elapsed = end - job.startedAt;
        } else {
            elapsed = System.currentTimeMillis() - job.startedAt;
        }
        o.addProperty("elapsedMs", Math.max(0L, elapsed));
        o.addProperty("cancelRequested", job.handle.isCancellationRequested());
        o.addProperty("workerExited", job.handle.isWorkerExited());
        if (job.result != null) o.add("result", job.result);
        if (job.error != null) o.addProperty("error", job.error);
        o.addProperty("code_truncated", job.codeTruncated);
        String preview = job.code == null ? "" : job.code.trim();
        if (preview.length() > 160) preview = preview.substring(0, 160) + "...";
        o.addProperty("preview", preview);
        return o;
    }

    /** Bounded list representation; full retained results are status-only. */
    public JsonObject toSummaryJson(Job job) {
        JsonObject summary = toJson(job);
        boolean available = summary.has("result");
        summary.remove("result");
        summary.addProperty("result_available", available);
        return summary;
    }

    private void refreshTransientState(Job job) {
        if (job == null || isTerminal(job.state)) return;
        MutationCoordinator.State state = job.handle.state();
        if (state == MutationCoordinator.State.TIMEOUT_REQUESTED) {
            job.state = STATE_TIMEOUT_REQUESTED;
        } else if (state == MutationCoordinator.State.CANCEL_REQUESTED) {
            job.state = STATE_CANCEL_REQUESTED;
        } else {
            job.state = STATE_RUNNING;
        }
    }

    private void finishJob(Job job,
                           MutationCoordinator.Completion<ExecutionResult> completion) {
        job.endedAt = completion.endedAtMs();
        MutationCoordinator.State state = completion.state();
        if (state == MutationCoordinator.State.CANCELLED) {
            job.state = STATE_CANCELLED;
            job.error = "cancelled";
            publish("job.failed", failedFrame(job));
        } else if (state == MutationCoordinator.State.TIMED_OUT) {
            job.state = STATE_TIMED_OUT;
            job.error = "timed out";
            publish("job.failed", failedFrame(job));
        } else if (state == MutationCoordinator.State.SUCCEEDED
                && completion.result() != null
                && completion.result().isSuccess()) {
            job.result = executionResultToJson(completion.result());
            job.progress = 1.0;
            job.state = STATE_COMPLETED;
            publish("job.completed", completedFrame(job));
        } else {
            Throwable error = completion.error();
            String message = error == null ? "macro error" : error.getMessage();
            job.error = boundedChars(message == null || message.isEmpty()
                    ? (error == null ? "macro error" : error.getClass().getSimpleName())
                    : message, MAX_ERROR_CHARS);
            job.state = STATE_FAILED;
            publish("job.failed", failedFrame(job));
        }
    }

    private void publish(String topic, JsonObject frame) {
        try {
            bus.publish(topic, frame);
        } catch (Throwable ignored) {
            // Events are observers; they cannot change terminal state.
        }
    }

    static JsonObject executionResultToJson(ExecutionResult r) {
        JsonObject json = new JsonObject();
        json.addProperty("success", r.isSuccess());
        if (r.isSuccess()) {
            addBoundedUtf8(json, "output", r.getOutput(), MAX_RESULT_TEXT_BYTES);
            addBoundedUtf8(json, "resultsTable", r.getResultsTable(),
                    MAX_RESULT_TEXT_BYTES);
            if (r.isResultsTableTruncated()) {
                json.addProperty("resultsTable_truncated", true);
                json.addProperty("resultsTable_original_bytes",
                        r.getResultsTableOriginalBytes());
                json.addProperty("resultsTable_returned_bytes",
                        utf8Length(json.get("resultsTable").getAsString()));
                json.addProperty("resultsTable_total_rows",
                        r.getResultsTableTotalRows());
                json.addProperty("resultsTable_returned_rows",
                        r.getResultsTableReturnedRows());
            }
            JsonArray newImages = new JsonArray();
            List<String> images = r.getNewImages() == null
                    ? Collections.<String>emptyList() : r.getNewImages();
            int retained = Math.min(images.size(), MAX_RESULT_IMAGES);
            for (int i = 0; i < retained; i++) {
                newImages.add(boundedChars(images.get(i), MAX_IMAGE_NAME_CHARS));
            }
            json.add("newImages", newImages);
            json.addProperty("newImages_truncated", images.size() > retained);
            json.addProperty("newImages_total", images.size());
            json.addProperty("executionTimeMs", r.getExecutionTimeMs());
        } else {
            json.addProperty("error", boundedChars(
                    r.getError() != null ? r.getError() : "Unknown error",
                    MAX_ERROR_CHARS));
        }
        return json;
    }

    private static void addBoundedUtf8(JsonObject target, String key,
                                       String value, int byteLimit) {
        String original = value == null ? "" : value;
        long originalBytes = utf8Length(original);
        if (originalBytes <= byteLimit) {
            target.addProperty(key, original);
            target.addProperty(key + "_truncated", false);
            target.addProperty(key + "_original_bytes", originalBytes);
            return;
        }
        int chars = 0;
        int used = 0;
        while (chars < original.length()) {
            int cp = original.codePointAt(chars);
            int cpBytes = utf8CodePointBytes(cp);
            if (used + cpBytes > byteLimit) break;
            used += cpBytes;
            chars += Character.charCount(cp);
        }
        target.addProperty(key, original.substring(0, chars));
        target.addProperty(key + "_truncated", true);
        target.addProperty(key + "_original_bytes", originalBytes);
        target.addProperty(key + "_returned_bytes", used);
    }

    private static String boundedChars(String value, int maxChars) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars);
    }

    private static long utf8Length(String value) {
        long bytes = 0L;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            bytes += utf8CodePointBytes(cp);
            i += Character.charCount(cp);
        }
        return bytes;
    }

    private static int utf8CodePointBytes(int cp) {
        if (cp <= 0x7f) return 1;
        if (cp <= 0x7ff) return 2;
        if (cp <= 0xffff) return 3;
        return 4;
    }

    private JsonObject startFrame(Job job) {
        JsonObject d = new JsonObject();
        d.addProperty(EVENT_OWNER_FIELD, job.ownerSession);
        d.addProperty("job_id", job.id);
        d.addProperty("startedAt", job.startedAt);
        String preview = job.code == null ? "" : job.code.trim();
        if (preview.length() > 160) preview = preview.substring(0, 160) + "...";
        d.addProperty("preview", preview);
        return d;
    }

    private JsonObject progressFrame(Job job) {
        JsonObject d = new JsonObject();
        d.addProperty(EVENT_OWNER_FIELD, job.ownerSession);
        d.addProperty("job_id", job.id);
        d.addProperty("progress", job.progress);
        d.addProperty("elapsedMs", System.currentTimeMillis() - job.startedAt);
        return d;
    }

    private JsonObject completedFrame(Job job) {
        JsonObject d = new JsonObject();
        d.addProperty(EVENT_OWNER_FIELD, job.ownerSession);
        d.addProperty("job_id", job.id);
        d.addProperty("state", job.state);
        d.addProperty("elapsedMs", job.endedAt - job.startedAt);
        if (job.result != null) d.add("result", job.result);
        return d;
    }

    private JsonObject failedFrame(Job job) {
        JsonObject d = new JsonObject();
        d.addProperty(EVENT_OWNER_FIELD, job.ownerSession);
        d.addProperty("job_id", job.id);
        d.addProperty("state", job.state);
        d.addProperty("elapsedMs", Math.max(0L, job.endedAt - job.startedAt));
        if (job.error != null) d.addProperty("error", job.error);
        return d;
    }

    private void enforceCap() {
        if (jobs.size() <= MAX_JOBS) return;
        List<Job> terminal = new ArrayList<Job>();
        for (Job job : jobs.values()) {
            if (isTerminal(job.state)) terminal.add(job);
        }
        Collections.sort(terminal, new Comparator<Job>() {
            @Override public int compare(Job a, Job b) {
                return Long.compare(a.endedAt, b.endedAt);
            }
        });
        int remove = jobs.size() - MAX_JOBS;
        for (int i = 0; i < remove && i < terminal.size(); i++) {
            jobs.remove(terminal.get(i).id, terminal.get(i));
        }
    }

    /** Evict the oldest terminal records before any coordinator/thread admission. */
    private void makeRoomForAdmission() {
        if (jobs.size() < MAX_JOBS) return;
        List<Job> terminal = new ArrayList<Job>();
        for (Job job : jobs.values()) if (isTerminal(job.state)) terminal.add(job);
        Collections.sort(terminal, new Comparator<Job>() {
            @Override public int compare(Job a, Job b) {
                return Long.compare(a.endedAt, b.endedAt);
            }
        });
        for (Job job : terminal) {
            if (jobs.size() < MAX_JOBS) break;
            jobs.remove(job.id, job);
        }
    }

    private void janitorLoop() {
        while (!shutdown) {
            try {
                Thread.sleep(JANITOR_INTERVAL_MS);
            } catch (InterruptedException e) {
                if (shutdown) return;
                continue;
            }
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            try {
                Iterator<Map.Entry<String, Job>> it = jobs.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Job> entry = it.next();
                    Job job = entry.getValue();
                    if (isTerminal(job.state) && job.endedAt > 0L
                            && job.endedAt < cutoff) {
                        jobs.remove(entry.getKey(), job);
                    }
                }
                enforceCap();
            } catch (Throwable ignored) {
                // Retry next cycle; the janitor must not affect execution.
            }
        }
    }

    static boolean isTerminal(String state) {
        return STATE_COMPLETED.equals(state)
                || STATE_FAILED.equals(state)
                || STATE_CANCELLED.equals(state)
                || STATE_TIMED_OUT.equals(state);
    }
}
