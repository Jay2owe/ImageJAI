package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.ResultsTable;
import imagejai.config.Constants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;

/**
 * Executes ImageJ macros and captures structured results.
 * <p>
 * Macro execution runs on the EDT (required by ImageJ). State snapshots are
 * taken before and after to detect new images and ResultsTable changes.
 */
public class CommandEngine {

    private final StateInspector inspector;
    private final EventBus bus = EventBus.getInstance();
    private static final AtomicLong MACRO_ID_SEQ = new AtomicLong(0);
    private volatile MutationCoordinator mutationCoordinator;

    public CommandEngine() {
        this.inspector = new StateInspector();
    }

    /** Attach the server-owned coordinator before accepting mutation work. */
    void setMutationCoordinator(MutationCoordinator coordinator) {
        if (coordinator == null) throw new IllegalArgumentException("coordinator is required");
        synchronized (this) {
            if (mutationCoordinator != null && mutationCoordinator != coordinator
                    && mutationCoordinator.activeCount() > 0) {
                throw new IllegalStateException("cannot replace an active mutation coordinator");
            }
            mutationCoordinator = coordinator;
        }
    }

    private MutationCoordinator coordinator() {
        MutationCoordinator current = mutationCoordinator;
        if (current != null) return current;
        synchronized (this) {
            if (mutationCoordinator == null) {
                mutationCoordinator = new MutationCoordinator();
            }
            return mutationCoordinator;
        }
    }

    /**
     * Execute an ImageJ macro string with the default timeout.
     *
     * @param macroCode the ImageJ macro code to execute
     * @return structured execution result
     */
    public ExecutionResult executeMacro(String macroCode) {
        return executeMacroWithTimeout(macroCode, Constants.MACRO_TIMEOUT_MS);
    }

    /**
     * Execute a macro with an exact, unshown image as the current image on the
     * coordinator worker. This avoids title-based selection and lets callers
     * safely operate on private duplicates.
     */
    ExecutionResult executeMacroOnImage(String macroCode, ImagePlus image) {
        return executeMacroWithTarget(macroCode, Constants.MACRO_TIMEOUT_MS, image);
    }

    /**
     * Execute an ImageJ macro with a specified timeout.
     *
     * @param macroCode the ImageJ macro code to execute
     * @param timeoutMs maximum execution time in milliseconds
     * @return structured execution result
     */
    public ExecutionResult executeMacroWithTimeout(String macroCode, long timeoutMs) {
        return executeMacroWithTarget(macroCode, timeoutMs, null);
    }

    private ExecutionResult executeMacroWithTarget(String macroCode, long timeoutMs,
                                                   final ImagePlus targetImage) {
        if (macroCode == null || macroCode.trim().isEmpty()) {
            return ExecutionResult.failure("Empty macro code", 0);
        }
        final long startTime = System.currentTimeMillis();
        final String code = macroCode;
        MutationCoordinator.Handle<ExecutionResult> handle;
        try {
            MutationCoordinator.Request<ExecutionResult> request =
                    MutationCoordinator.Request.<ExecutionResult>builder()
                            .ownerSession("__imagejai_command_engine__")
                            .sourceKind("macro")
                            .code(code)
                            .timeoutMs(timeoutMs)
                            .operation(new MutationCoordinator.Operation<ExecutionResult>() {
                                @Override public ExecutionResult run() {
                                    if (targetImage == null) {
                                        return executeMacroOnCurrentThread(code, null);
                                    }
                                    ImagePlus previous = WindowManager.getCurrentImage();
                                    WindowManager.setTempCurrentImage(targetImage);
                                    try {
                                        return executeMacroOnCurrentThread(code, null);
                                    } finally {
                                        WindowManager.setTempCurrentImage(previous);
                                    }
                                }
                            })
                            .cancellationAction(new MutationCoordinator.CancellationAction() {
                                @Override public void cancel() {
                                    requestOwnedMacroAbort();
                                }
                            })
                            .build();
            MutationCoordinator activeCoordinator = coordinator();
            handle = activeCoordinator.isMonitorHeldByCurrentThread()
                    ? activeCoordinator.submitWhileMonitorHeld(request)
                    : activeCoordinator.submit(request);
        } catch (RejectedExecutionException e) {
            return ExecutionResult.failure(e.getMessage(),
                    System.currentTimeMillis() - startTime);
        }

        boolean interrupted = false;
        MutationCoordinator.Completion<ExecutionResult> completion;
        while (true) {
            try {
                completion = handle.awaitCompletion();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
                handle.cancel();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();

        if (completion.state() == MutationCoordinator.State.SUCCEEDED
                && completion.result() != null) {
            return completion.result();
        }
        String error;
        if (completion.state() == MutationCoordinator.State.TIMED_OUT) {
            error = "Macro execution timed out after " + timeoutMs + "ms";
        } else if (completion.state() == MutationCoordinator.State.CANCELLED) {
            error = "Macro execution interrupted";
        } else {
            Throwable failure = completion.error();
            String detail = failure == null ? "unknown error" : failure.getMessage();
            error = "Macro error: " + (detail == null ? "unknown error" : detail);
        }
        return ExecutionResult.failure(error, completion.elapsedMs());
    }

    private void publishMacroStarted(long macroId, String code) {
        try {
            JsonObject data = new JsonObject();
            data.addProperty("macro_id", macroId);
            String preview = code == null ? "" : code.trim();
            if (preview.length() > 160) preview = preview.substring(0, 160) + "...";
            data.addProperty("preview", preview);
            bus.publish("macro.started", data);
        } catch (Throwable ignore) {
        }
    }

    private void publishMacroCompleted(long macroId, boolean success,
                                       String error, List<String> newImages) {
        try {
            JsonObject data = new JsonObject();
            data.addProperty("macro_id", macroId);
            data.addProperty("success", success);
            if (error != null) data.addProperty("error", error);
            if (newImages != null && !newImages.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (String title : newImages) arr.add(title);
                data.add("new_images", arr);
            }
            bus.publish("macro.completed", data);
        } catch (Throwable ignore) {
        }
    }

    /**
     * Get the set of currently open image titles.
     */
    private Set<String> getOpenImageTitles() {
        final Set<String> titles = new HashSet<String>();
        // WindowManager must be accessed carefully; image list calls are generally safe
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) {
                    titles.add(imp.getTitle());
                }
            }
        }
        return titles;
    }

    /**
     * Get the current ResultsTable row count, or 0 if no table exists.
     */
    private int getResultsTableRowCount() {
        ResultsTable rt = ResultsTable.getResultsTable();
        return rt != null ? rt.getCounter() : 0;
    }

    /**
     * Callable that runs the macro. IJ.runMacro creates its own Interpreter
     * which handles EDT dispatch internally, so we run it directly on the
     * executor thread to avoid blocking the EDT event loop.
     */
    /**
     * Phase 3: run a macro on the calling thread (intended for the async job
     * worker — off both the EDT and the usual ExecutorService). Polls
     * {@code IJ.getInstance()}'s progress bar every 200ms on a helper thread
     * and feeds the fractional progress ([0,1]) to {@code progressCallback}.
     * Diffs the open-image list before/after so {@link ExecutionResult#getNewImages()}
     * is populated without any EDT round-trip.
     *
     * <p>Unlike {@link #executeMacro(String)} this method does not enforce a
     * timeout — async jobs run until they finish or are cancelled via
     * {@link JobRegistry#cancel(String)}.
     *
     * @param code             macro source
     * @param progressCallback receives values in [0.0, 1.0]; may be null
     * @return structured execution result
     */
    public ExecutionResult executeMacroOnCurrentThread(final String code,
                                                        final DoubleConsumer progressCallback) {
        if (code == null || code.trim().isEmpty()) {
            return ExecutionResult.failure("Empty macro code", 0);
        }

        long startTime = System.currentTimeMillis();
        long macroId = MACRO_ID_SEQ.incrementAndGet();
        publishMacroStarted(macroId, code);

        final Set<String> imagesBefore = getOpenImageTitles();
        final int resultsRowsBefore = getResultsTableRowCount();

        final AtomicBoolean done = new AtomicBoolean(false);
        Thread poller = null;
        if (progressCallback != null) {
            poller = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!done.get()) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ie) {
                            return;
                        }
                        if (done.get()) return;
                        double p = readProgressBarFraction();
                        if (p >= 0) {
                            try {
                                progressCallback.accept(p);
                            } catch (Throwable ignore) {
                                // Subscriber faults must not break the poller.
                            }
                        }
                    }
                }
            }, "ImageJAI-Macro-Progress");
            poller.setDaemon(true);
            poller.start();
        }

        try {
            String macroReturn;
            try {
                macroReturn = IJ.runMacro(code);
            } catch (Throwable t) {
                long elapsed = System.currentTimeMillis() - startTime;
                String msg = t.getMessage();
                String err = "Macro error: "
                        + (msg != null && !msg.isEmpty() ? msg : t.getClass().getSimpleName());
                publishMacroCompleted(macroId, false, err, null);
                return ExecutionResult.failure(err, elapsed);
            }

            long elapsed = System.currentTimeMillis() - startTime;

            Set<String> imagesAfter = getOpenImageTitles();
            List<String> newImages = new ArrayList<String>();
            for (String title : imagesAfter) {
                if (!imagesBefore.contains(title)) newImages.add(title);
            }

            String resultsCSV = null;
            int resultsRowsAfter = getResultsTableRowCount();
            if (resultsRowsAfter > resultsRowsBefore) {
                resultsCSV = inspector.getResultsTableCSV();
                JsonObject rdata = new JsonObject();
                rdata.addProperty("rows", resultsRowsAfter);
                rdata.addProperty("delta", resultsRowsAfter - resultsRowsBefore);
                bus.publish("results.changed", rdata);
            }

            String output = macroReturn != null ? macroReturn : "";
            publishMacroCompleted(macroId, true, null, newImages);
            return ExecutionResult.success(output, resultsCSV, newImages, elapsed);
        } finally {
            done.set(true);
            if (poller != null) poller.interrupt();
        }
    }

    /**
     * Request ImageJ's process-global macro abort. This is safe only when the
     * caller has proved it owns the shared mutation monitor; the coordinator
     * is the sole production caller.
     */
    static void requestOwnedMacroAbort() {
        try {
            Class<?> macroClass = Class.forName("ij.Macro");
            java.lang.reflect.Method abort = macroClass.getMethod("abort");
            abort.invoke(null);
        } catch (Throwable ignore) {
            // Worker interruption remains the fallback.
        }
    }

    /**
     * Read ImageJ's current progress bar as a fraction in [0, 1], or -1 if
     * no bar is active / unreadable. Uses reflection so the method degrades
     * gracefully across ImageJ versions that rename internal fields.
     */
    private static double readProgressBarFraction() {
        try {
            ij.ImageJ inst = IJ.getInstance();
            if (inst == null) return -1;
            java.lang.reflect.Field pbField = inst.getClass().getDeclaredField("progressBar");
            pbField.setAccessible(true);
            Object pb = pbField.get(inst);
            if (pb == null) return -1;

            java.lang.reflect.Field showField = pb.getClass().getDeclaredField("showBar");
            showField.setAccessible(true);
            if (!showField.getBoolean(pb)) return -1;

            java.lang.reflect.Field widthField = pb.getClass().getDeclaredField("width");
            widthField.setAccessible(true);
            java.lang.reflect.Field canvasField = pb.getClass().getDeclaredField("canvasWidth");
            canvasField.setAccessible(true);
            int barW = widthField.getInt(pb);
            int canvasW = canvasField.getInt(pb);
            if (canvasW <= 0) return -1;
            double frac = (double) barW / (double) canvasW;
            if (frac < 0) return 0;
            if (frac > 1) return 1;
            return frac;
        } catch (Throwable t) {
            return -1;
        }
    }
}
