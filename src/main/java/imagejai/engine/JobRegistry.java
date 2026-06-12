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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

/**
 * Phase 3: registry of asynchronous macro jobs.
 *
 * <p>Each {@link #submit(String)} call spawns a dedicated daemon worker thread
 * that runs the macro off the EDT via
 * {@link CommandEngine#executeMacroOnCurrentThread(String, DoubleConsumer)}.
 * Progress, completion, failure and cancellation are published as
 * {@code job.*} events on the {@link EventBus}.
 *
 * <p>Bounded retention: a janitor daemon prunes completed/failed/cancelled
 * jobs older than {@link #RETENTION_MS} ms and caps the total number of
 * tracked jobs at {@link #MAX_JOBS}. Eviction prefers terminal jobs first.
 *
 * <p>Thread-safe; in-memory only.
 */
public class JobRegistry {

    /** How often the janitor runs. */
    public static final long JANITOR_INTERVAL_MS = 60_000L;
    /** Completed jobs older than this are evicted. */
    public static final long RETENTION_MS = 60L * 60_000L;
    /** Hard cap on retained job entries. */
    public static final int MAX_JOBS = 256;

    public static final String STATE_RUNNING = "running";
    public static final String STATE_COMPLETED = "completed";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_CANCELLED = "cancelled";

    /** Public job state. Fields are volatile because the worker thread mutates them. */
    public static class Job {
        public final String id;
        public final String code;
        public volatile String state = STATE_RUNNING;
        public volatile double progress = 0.0;
        public volatile JsonObject result;
        public volatile String error;
        public final long startedAt;
        public volatile long endedAt;

        // Worker is set after the Job is registered to avoid leaking a
        // partially-initialised reference into the worker's run() body.
        volatile Thread worker;
        // Flips true the first time cancel() is requested. Lets the worker
        // distinguish a user-cancellation from a macro that just threw.
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        Job(String id, String code) {
            this.id = id;
            this.code = code;
            this.startedAt = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<String, Job>();
    private final EventBus bus = EventBus.getInstance();
    private final CommandEngine commandEngine;
    private final Thread janitor;
    private volatile boolean shutdown = false;

    public JobRegistry(CommandEngine commandEngine) {
        this.commandEngine = commandEngine;
        this.janitor = new Thread(new Runnable() {
            @Override
            public void run() {
                janitorLoop();
            }
        }, "ImageJAI-JobRegistry-Janitor");
        this.janitor.setDaemon(true);
        this.janitor.start();
    }

    /**
     * Submit a macro for asynchronous execution. Returns immediately with the
     * Job (state="running", worker thread already started).
     */
    public Job submit(final String code) {
        final String id = newId();
        final Job job = new Job(id, code == null ? "" : code);
        jobs.put(id, job);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runJob(job);
            }
        }, "ImageJAI-Job-" + id);
        t.setDaemon(true);
        job.worker = t;
        // Publish job.started before starting the thread so a subscriber
        // that reacts to the started event sees state="running" if it
        // immediately calls job_status.
        bus.publish("job.started", startFrame(job));
        t.start();
        enforceCap();
        return job;
    }

    public Job get(String id) {
        if (id == null) return null;
        return jobs.get(id);
    }

    /** All jobs, newest-started first. */
    public List<Job> list() {
        List<Job> out = new ArrayList<Job>(jobs.values());
        Collections.sort(out, new Comparator<Job>() {
            @Override
            public int compare(Job a, Job b) {
                return Long.compare(b.startedAt, a.startedAt);
            }
        });
        return out;
    }

    public int size() {
        return jobs.size();
    }

    /**
     * Request cancellation of a running job. First calls {@code ij.Macro.abort()}
     * (via reflection so the registry doesn't compile-time-depend on it via this
     * class); then interrupts the worker thread as a fallback. Returns true if
     * the cancel signal was delivered, false if the job is unknown or already
     * in a terminal state.
     */
    public boolean cancel(String id) {
        Job j = get(id);
        if (j == null) return false;
        if (!STATE_RUNNING.equals(j.state)) return false;
        j.cancelled.set(true);
        try {
            Class<?> macroClass = Class.forName("ij.Macro");
            java.lang.reflect.Method abort = macroClass.getMethod("abort");
            abort.invoke(null);
        } catch (Throwable ignore) {
            // ij.Macro absent or signature changed — fall through to interrupt.
        }
        Thread w = j.worker;
        if (w != null && w.isAlive()) {
            w.interrupt();
        }
        return true;
    }

    /** Cancel every running job. Used by the TCP server's stop() hook. */
    public void cancelAll() {
        for (Job j : jobs.values()) {
            if (STATE_RUNNING.equals(j.state)) cancel(j.id);
        }
    }

    /** Cancel everything, stop the janitor, prepare for plugin teardown. */
    public void shutdown() {
        shutdown = true;
        cancelAll();
        janitor.interrupt();
    }

    /** On-the-wire JSON representation of a Job. */
    public JsonObject toJson(Job j) {
        JsonObject o = new JsonObject();
        o.addProperty("job_id", j.id);
        o.addProperty("state", j.state);
        o.addProperty("progress", j.progress);
        o.addProperty("startedAt", j.startedAt);
        long end = j.endedAt;
        long elapsed;
        if (end > 0) {
            o.addProperty("endedAt", end);
            elapsed = end - j.startedAt;
        } else {
            elapsed = System.currentTimeMillis() - j.startedAt;
        }
        o.addProperty("elapsedMs", elapsed);
        if (j.result != null) o.add("result", j.result);
        if (j.error != null) o.addProperty("error", j.error);
        // Short preview lets the agent recognise the job it submitted without
        // echoing arbitrarily large macro bodies back through every status call.
        String preview = j.code == null ? "" : j.code.trim();
        if (preview.length() > 160) preview = preview.substring(0, 160) + "...";
        o.addProperty("preview", preview);
        return o;
    }

    // ------------------------------------------------------------------
    // Internal: id generation, worker, frames, janitor
    // ------------------------------------------------------------------

    private String newId() {
        for (int i = 0; i < 32; i++) {
            String id = "j_" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000000));
            if (!jobs.containsKey(id)) return id;
        }
        // Practically unreachable — fall back to a nanosecond-keyed id.
        return "j_" + Long.toHexString(System.nanoTime());
    }

    private void runJob(final Job job) {
        try {
            DoubleConsumer cb = new DoubleConsumer() {
                @Override
                public void accept(double pct) {
                    if (Double.isNaN(pct) || pct < 0) return;
                    if (pct > 1.0) pct = 1.0;
                    job.progress = pct;
                    bus.publish("job.progress", progressFrame(job));
                }
            };
            ExecutionResult r = commandEngine.executeMacroOnCurrentThread(job.code, cb);
            // Cancellation may have raced the macro to completion; prefer the
            // cancelled state if the user signalled it before we got back.
            if (job.cancelled.get()) {
                job.error = "cancelled";
                job.state = STATE_CANCELLED;
                bus.publish("job.failed", failedFrame(job));
                return;
            }
            if (r.isSuccess()) {
                job.result = executionResultToJson(r);
                job.progress = 1.0;
                job.state = STATE_COMPLETED;
                bus.publish("job.completed", completedFrame(job));
            } else {
                job.error = r.getError() != null ? r.getError() : "macro error";
                job.state = STATE_FAILED;
                bus.publish("job.failed", failedFrame(job));
            }
        } catch (Throwable t) {
            if (job.cancelled.get()) {
                job.error = "cancelled";
                job.state = STATE_CANCELLED;
            } else {
                String m = t.getMessage();
                job.error = (m != null && !m.isEmpty()) ? m : t.getClass().getSimpleName();
                job.state = STATE_FAILED;
            }
            bus.publish("job.failed", failedFrame(job));
        } finally {
            job.endedAt = System.currentTimeMillis();
        }
    }

    /** Mirror of TCPCommandServer.executionResultToJson — kept here so the registry is self-contained. */
    static JsonObject executionResultToJson(ExecutionResult r) {
        JsonObject json = new JsonObject();
        json.addProperty("success", r.isSuccess());
        if (r.isSuccess()) {
            json.addProperty("output", r.getOutput() != null ? r.getOutput() : "");
            json.addProperty("resultsTable", r.getResultsTable() != null ? r.getResultsTable() : "");
            JsonArray newImages = new JsonArray();
            for (String img : r.getNewImages()) {
                newImages.add(img);
            }
            json.add("newImages", newImages);
            json.addProperty("executionTimeMs", r.getExecutionTimeMs());
        } else {
            json.addProperty("error", r.getError() != null ? r.getError() : "Unknown error");
        }
        return json;
    }

    private JsonObject startFrame(Job j) {
        JsonObject d = new JsonObject();
        d.addProperty("job_id", j.id);
        d.addProperty("startedAt", j.startedAt);
        String preview = j.code == null ? "" : j.code.trim();
        if (preview.length() > 160) preview = preview.substring(0, 160) + "...";
        d.addProperty("preview", preview);
        return d;
    }

    private JsonObject progressFrame(Job j) {
        JsonObject d = new JsonObject();
        d.addProperty("job_id", j.id);
        d.addProperty("progress", j.progress);
        d.addProperty("elapsedMs", System.currentTimeMillis() - j.startedAt);
        return d;
    }

    private JsonObject completedFrame(Job j) {
        JsonObject d = new JsonObject();
        d.addProperty("job_id", j.id);
        d.addProperty("state", j.state);
        d.addProperty("elapsedMs", j.endedAt - j.startedAt);
        if (j.result != null) d.add("result", j.result);
        return d;
    }

    private JsonObject failedFrame(Job j) {
        JsonObject d = new JsonObject();
        d.addProperty("job_id", j.id);
        d.addProperty("state", j.state);
        long end = j.endedAt > 0 ? j.endedAt : System.currentTimeMillis();
        d.addProperty("elapsedMs", end - j.startedAt);
        if (j.error != null) d.addProperty("error", j.error);
        return d;
    }

    private void enforceCap() {
        if (jobs.size() <= MAX_JOBS) return;
        // Evict terminal jobs oldest-first; only touch running jobs if the
        // table is *still* over cap after pruning all terminal entries.
        List<Job> all = new ArrayList<Job>(jobs.values());
        Collections.sort(all, new Comparator<Job>() {
            @Override
            public int compare(Job a, Job b) {
                int as = isTerminal(a.state) ? 0 : 1;
                int bs = isTerminal(b.state) ? 0 : 1;
                if (as != bs) return Integer.compare(as, bs);
                return Long.compare(a.startedAt, b.startedAt);
            }
        });
        int toRemove = jobs.size() - MAX_JOBS;
        for (int i = 0; i < toRemove && i < all.size(); i++) {
            jobs.remove(all.get(i).id);
        }
    }

    private void janitorLoop() {
        while (!shutdown) {
            try {
                Thread.sleep(JANITOR_INTERVAL_MS);
            } catch (InterruptedException ie) {
                if (shutdown) return;
                Thread.currentThread().interrupt();
                continue;
            }
            try {
                long cutoff = System.currentTimeMillis() - RETENTION_MS;
                Iterator<Map.Entry<String, Job>> it = jobs.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Job> e = it.next();
                    Job j = e.getValue();
                    if (isTerminal(j.state) && j.endedAt > 0 && j.endedAt < cutoff) {
                        it.remove();
                    }
                }
                enforceCap();
            } catch (Throwable ignore) {
                // Janitor must never die — swallow and retry next cycle.
            }
        }
    }

    static boolean isTerminal(String state) {
        return STATE_COMPLETED.equals(state)
                || STATE_FAILED.equals(state)
                || STATE_CANCELLED.equals(state);
    }
}
