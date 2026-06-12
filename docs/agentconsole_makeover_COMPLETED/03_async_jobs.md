# Phase 3 — Async Jobs with IDs

## Motivation

Today `execute_macro` is **strictly synchronous**. The TCP server dispatches to `CommandEngine` on the EDT and the socket blocks until the macro finishes. Fine for `run("Gaussian Blur...")`. Catastrophic for StarDist on a 100-slice stack, Weka training, large Stitching jobs, deconvolution — anything over ~60s hits the TCP read timeout and the agent sees a spurious failure even though Fiji is still working correctly.

Workarounds today: bump the timeout to huge values, which then masks legitimate hangs; or split a workflow into many tiny macros, which is brittle.

AgentConsole's answer is **job IDs + completion events**: the agent submits work, gets a handle back, does other things, and is notified on completion via the bus (Phase 2).

Metaphor: you stop standing next to the oven watching the soup. Set a timer, leave the kitchen, come back when it beeps.

## End Goal

- `execute_macro_async {"code": "..."}` → returns immediately with `{"ok": true, "job_id": "j_abc123", "started_at": ...}`.
- `job_status {"job_id": "j_abc123"}` → `{"state": "running", "elapsed": 12.3, "progress": 0.4}` or `{"state": "completed", "result": {...}}` or `{"state": "failed", "error": "..."}`.
- `job_cancel {"job_id": ...}` → attempts `IJ.getInstance().quit()`-free cancellation via `Macro.abort()` or thread interrupt.
- Bus publishes `job.started`, `job.progress`, `job.completed`, `job.failed` (Phase 2 integration).
- Jobs have a bounded retention window (completed jobs kept for 1 hour, then garbage-collected).

## Scope

### Server-side: `engine/JobRegistry.java` (new)

- Generates job IDs (`j_` + 6 hex chars).
- Maintains `ConcurrentHashMap<String, Job>` with status, progress, result, error, start/end timestamps.
- Background janitor thread prunes completed jobs >1h old and caps total jobs at 256.

### Server-side: `engine/CommandEngine.java`

- New `executeMacroAsync(code, progressCallback)` — runs macro on a dedicated `Thread`, not the EDT. Uses `IJ.runMacro` which is thread-safe for most macros.
- Progress estimation: poll `IJ.getProgressBar()` value at 200ms intervals.
- On completion, diff the image list pre/post to populate `result.newImages`.

### Server-side: `engine/TCPCommandServer.java`

- Three new commands: `execute_macro_async`, `job_status`, `job_cancel`.
- Also `job_list` → all active + recently completed jobs, for visibility.
- Publishes `job.*` events to the bus as state transitions happen.

### Client-side: `agent/ij.py`

- `python ij.py async 'run("StarDist 2D"); ...'` → prints job ID.
- `python ij.py job j_abc123` → prints status or final result.
- `python ij.py wait j_abc123` → blocks until complete, respects `--timeout`; uses subscription channel if available, polling otherwise.
- `python ij.py jobs` → lists all.

### Client-side: a thin convenience wrapper

- `python ij.py run_patient 'code'` = async + wait, auto-reconnects, survives TCP timeouts. The default experience for "just run this and tell me when it's done".

## Implementation Sketch

```java
// JobRegistry.java
public class JobRegistry {
    public static class Job {
        public final String id;
        public volatile String state = "running";
        public volatile double progress = 0.0;
        public volatile JsonObject result;
        public volatile String error;
        public final long startedAt = System.currentTimeMillis();
        public volatile long endedAt;
        public final Thread worker;
        // ...
    }
    public Job submit(String code) {
        String id = "j_" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0xffffff));
        Thread t = new Thread(() -> runJob(id, code), "ImageJAI-Job-" + id);
        Job j = new Job(id, t);
        jobs.put(id, j);
        t.start();
        bus.publish("job.started", jobFrame(j));
        return j;
    }
}
```

```java
private void runJob(String id, String code) {
    Job j = jobs.get(id);
    try {
        ExecutionResult r = commandEngine.executeMacroOnCurrentThread(code, pct -> {
            j.progress = pct;
            bus.publish("job.progress", ...);
        });
        j.result = r.toJson();
        j.state = "completed";
        bus.publish("job.completed", ...);
    } catch (Exception e) {
        j.error = e.getMessage();
        j.state = "failed";
        bus.publish("job.failed", ...);
    } finally {
        j.endedAt = System.currentTimeMillis();
    }
}
```

## Impact

- 10-minute StarDist runs stop timing out. The agent fires them and moves on.
- Parallelism becomes possible: segment channel 1 while measuring channel 2 — two job IDs, both progressing.
- Subscribers (Phase 2) get `job.completed` automatically → async workflows close the loop with no polling.
- The synchronous `execute_macro` stays untouched for fast operations; users pick sync or async based on expected runtime.

## Validation

1. Submit a deliberately slow macro (`wait(30000); return;`). Agent gets job ID within 100ms. `job_status` after 5s shows `running`, after 30s shows `completed`.
2. Cancel a running job → state flips to `cancelled` within 1s.
3. Fire 3 jobs concurrently → all 3 progress in parallel (subject to EDT contention for any UI-touching parts).
4. Completed job readable via `job_status` for ≥1h after completion; gone after.
5. Agent TCP socket closes mid-job → job continues. Agent reconnects, `job_status` still finds it.

## Risks

- **Thread-safety of ImageJ macros.** Most macros are fine off-EDT, but anything that creates windows must dispatch to EDT internally (ImageJ already does this). Document: macros that call `getString()` / `waitForUser()` should NOT be async — they need EDT + will deadlock.
- **Race on `WindowManager`.** Two concurrent jobs each calling `IJ.openImage` may clobber each other's "current image". Mitigation: document that async jobs should explicitly reference images by title, not by "current".
- **Orphan threads on plugin shutdown.** Register a shutdown hook that cancels all running jobs when Fiji closes.
- **Progress reporting lies.** `IJ.getProgressBar()` only reflects the most-recent plugin's progress. Document the limitation; offer a manual `setJobProgress(0.5)` macro function for plugins that can self-report.
