package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.ResultsTable;
import imagejai.config.Constants;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    public CommandEngine() {
        this.inspector = new StateInspector();
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
     * Execute an ImageJ macro with a specified timeout.
     *
     * @param macroCode the ImageJ macro code to execute
     * @param timeoutMs maximum execution time in milliseconds
     * @return structured execution result
     */
    public ExecutionResult executeMacroWithTimeout(String macroCode, long timeoutMs) {
        if (macroCode == null || macroCode.trim().isEmpty()) {
            return ExecutionResult.failure("Empty macro code", 0);
        }

        long startTime = System.currentTimeMillis();
        long macroId = MACRO_ID_SEQ.incrementAndGet();
        publishMacroStarted(macroId, macroCode);

        // Snapshot state before execution
        final Set<String> imagesBefore = getOpenImageTitles();
        final int resultsRowsBefore = getResultsTableRowCount();

        // Run the macro with timeout using a background thread + EDT dispatch
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(new MacroTask(macroCode));
            String macroReturn;
            try {
                macroReturn = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                long elapsed = System.currentTimeMillis() - startTime;
                String err = "Macro execution timed out after " + timeoutMs + "ms";
                publishMacroCompleted(macroId, false, err, null);
                return ExecutionResult.failure(err, elapsed);
            } catch (ExecutionException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                Throwable cause = e.getCause();
                String msg = cause != null ? cause.getMessage() : e.getMessage();
                String err = "Macro error: " + msg;
                publishMacroCompleted(macroId, false, err, null);
                return ExecutionResult.failure(err, elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long elapsed = System.currentTimeMillis() - startTime;
                String err = "Macro execution interrupted";
                publishMacroCompleted(macroId, false, err, null);
                return ExecutionResult.failure(err, elapsed);
            }

            long elapsed = System.currentTimeMillis() - startTime;

            // Detect new images
            Set<String> imagesAfter = getOpenImageTitles();
            List<String> newImages = new ArrayList<String>();
            for (String title : imagesAfter) {
                if (!imagesBefore.contains(title)) {
                    newImages.add(title);
                }
            }

            // Capture ResultsTable if it grew
            String resultsCSV = null;
            int resultsRowsAfter = getResultsTableRowCount();
            if (resultsRowsAfter > resultsRowsBefore) {
                resultsCSV = inspector.getResultsTableCSV();
                // Notify the bus that the results table grew.
                JsonObject rdata = new JsonObject();
                rdata.addProperty("rows", resultsRowsAfter);
                rdata.addProperty("delta", resultsRowsAfter - resultsRowsBefore);
                bus.publish("results.changed", rdata);
            }

            // Build output string from macro return value
            String output = macroReturn != null ? macroReturn : "";

            publishMacroCompleted(macroId, true, null, newImages);
            return ExecutionResult.success(output, resultsCSV, newImages, elapsed);

        } finally {
            executor.shutdownNow();
        }
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

    private void publishMacroCompleted(long macroId, boolean success, String error, List<String> newImages) {
        try {
            JsonObject data = new JsonObject();
            data.addProperty("macro_id", macroId);
            data.addProperty("success", success);
            if (error != null) data.addProperty("error", error);
            if (newImages != null && !newImages.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (String t : newImages) arr.add(t);
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
    private static class MacroTask implements Callable<String> {
        private final String macroCode;

        MacroTask(String macroCode) {
            this.macroCode = macroCode;
        }

        @Override
        public String call() throws Exception {
            return IJ.runMacro(macroCode);
        }
    }

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
