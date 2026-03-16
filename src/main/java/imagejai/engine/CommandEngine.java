package imagejai.engine;

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

/**
 * Executes ImageJ macros and captures structured results.
 * <p>
 * Macro execution runs on the EDT (required by ImageJ). State snapshots are
 * taken before and after to detect new images and ResultsTable changes.
 */
public class CommandEngine {

    private final StateInspector inspector;

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
                return ExecutionResult.failure(
                        "Macro execution timed out after " + timeoutMs + "ms", elapsed);
            } catch (ExecutionException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                Throwable cause = e.getCause();
                String msg = cause != null ? cause.getMessage() : e.getMessage();
                return ExecutionResult.failure("Macro error: " + msg, elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                long elapsed = System.currentTimeMillis() - startTime;
                return ExecutionResult.failure("Macro execution interrupted", elapsed);
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
            }

            // Build output string from macro return value
            String output = macroReturn != null ? macroReturn : "";

            return ExecutionResult.success(output, resultsCSV, newImages, elapsed);

        } finally {
            executor.shutdownNow();
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
}
