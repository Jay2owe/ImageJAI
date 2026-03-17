package uk.ac.ucl.imagej.ai.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.measure.Calibration;
import ij.process.ImageStatistics;
import uk.ac.ucl.imagej.ai.config.Constants;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TCP server that accepts JSON commands from external clients (Claude CLI,
 * AgentConsole, scripts) and dispatches them to the engine layer.
 * <p>
 * Protocol: each connection sends one JSON command (UTF-8, newline-terminated)
 * and receives one JSON response back, then the connection is closed.
 */
public class TCPCommandServer {

    /**
     * Listener for server lifecycle events, used to show status in the UI.
     */
    public interface ServerListener {
        void onServerStarted(int port);
        void onServerStopped();
        void onClientConnected(String clientInfo);
        void onCommandReceived(String command);
        void onError(String error);
    }

    private static final Gson GSON = new GsonBuilder().create();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long MACRO_TIMEOUT_MS = 30000;
    private static final long PIPELINE_TIMEOUT_MS = 60000;

    private final int port;
    private final CommandEngine commandEngine;
    private final StateInspector stateInspector;
    private final PipelineBuilder pipelineBuilder;
    private final ExplorationEngine explorationEngine;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running;
    private ServerListener listener;

    // Cached 3D Viewer universe reference — survives across TCP calls
    private volatile Object cached3DUniverse;

    public TCPCommandServer(int port, CommandEngine commandEngine,
                            StateInspector stateInspector,
                            PipelineBuilder pipelineBuilder,
                            ExplorationEngine explorationEngine) {
        this.port = port;
        this.commandEngine = commandEngine;
        this.stateInspector = stateInspector;
        this.pipelineBuilder = pipelineBuilder;
        this.explorationEngine = explorationEngine;
    }

    /**
     * Start the TCP server on a background daemon thread.
     *
     * @param listener callback for server events (may be null)
     */
    public void start(ServerListener listener) {
        this.listener = listener;
        running = true;

        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runServer();
            }
        }, "imagej-ai-tcp-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * Stop the server and close the listening socket.
     */
    public void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (Exception e) {
                System.err.println("[ImageJAI-TCP] Error closing server socket: " + e.getMessage());
            }
        }
        if (listener != null) {
            listener.onServerStopped();
        }
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public int getPort() {
        return port;
    }

    // -----------------------------------------------------------------------
    // Server main loop
    // -----------------------------------------------------------------------

    private void runServer() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            System.err.println("[ImageJAI-TCP] Server listening on port " + port);
            if (listener != null) {
                listener.onServerStarted(port);
            }

            while (running) {
                try {
                    final Socket clientSocket = serverSocket.accept();
                    String clientInfo = clientSocket.getRemoteSocketAddress().toString();
                    if (listener != null) {
                        listener.onClientConnected(clientInfo);
                    }

                    Thread clientThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handleClient(clientSocket);
                        }
                    }, "imagej-ai-tcp-client");
                    clientThread.setDaemon(true);
                    clientThread.start();
                } catch (SocketException e) {
                    // Expected when server is stopped
                    if (running) {
                        System.err.println("[ImageJAI-TCP] Accept error: " + e.getMessage());
                    }
                }
            }
        } catch (java.net.BindException e) {
            String msg = "Port " + port + " already in use";
            System.err.println("[ImageJAI-TCP] " + msg);
            if (listener != null) {
                listener.onError(msg);
            }
        } catch (Exception e) {
            if (running) {
                String msg = "Server error: " + e.getMessage();
                System.err.println("[ImageJAI-TCP] " + msg);
                if (listener != null) {
                    listener.onError(msg);
                }
            }
        } finally {
            running = false;
        }
    }

    // -----------------------------------------------------------------------
    // Client handling
    // -----------------------------------------------------------------------

    private void handleClient(Socket socket) {
        BufferedReader reader = null;
        PrintWriter writer = null;
        try {
            socket.setSoTimeout(60000); // 60s read timeout
            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), UTF8));
            writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), UTF8), true);

            // Read one line (max 1MB enforced by checking length)
            String line = readLine(reader);
            if (line == null || line.trim().isEmpty()) {
                writer.println(errorJson("Empty request"));
                return;
            }

            if (line.length() > Constants.TCP_MAX_MESSAGE_SIZE) {
                writer.println(errorJson("Request too large (max " + Constants.TCP_MAX_MESSAGE_SIZE + " bytes)"));
                return;
            }

            // Parse and dispatch
            JsonObject response = dispatch(line.trim());
            writer.println(GSON.toJson(response));

        } catch (Exception e) {
            System.err.println("[ImageJAI-TCP] Client error: " + e.getMessage());
            if (writer != null) {
                try {
                    writer.println(errorJson("Server error: " + e.getMessage()));
                } catch (Exception ignored) {
                    // Client may have disconnected
                }
            }
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {}
            try {
                if (writer != null) writer.close();
            } catch (Exception ignored) {}
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Read a single line from the reader, enforcing max size.
     */
    private String readLine(BufferedReader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                continue;
            }
            sb.append((char) c);
            if (sb.length() > Constants.TCP_MAX_MESSAGE_SIZE) {
                return sb.toString(); // Will be rejected by size check
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // -----------------------------------------------------------------------
    // Command dispatch
    // -----------------------------------------------------------------------

    private JsonObject dispatch(String jsonStr) {
        JsonObject request;
        try {
            request = new JsonParser().parse(jsonStr).getAsJsonObject();
        } catch (Exception e) {
            return errorResponse("Invalid JSON: " + e.getMessage());
        }

        JsonElement cmdElement = request.get("command");
        if (cmdElement == null || !cmdElement.isJsonPrimitive()) {
            return errorResponse("Missing 'command' field");
        }
        String command = cmdElement.getAsString();

        if (listener != null) {
            listener.onCommandReceived(command);
        }

        if ("ping".equals(command)) {
            return handlePing();
        } else if ("execute_macro".equals(command)) {
            return handleExecuteMacro(request);
        } else if ("get_state".equals(command)) {
            return handleGetState();
        } else if ("get_image_info".equals(command)) {
            return handleGetImageInfo();
        } else if ("get_results_table".equals(command)) {
            return handleGetResultsTable();
        } else if ("capture_image".equals(command)) {
            return handleCaptureImage(request);
        } else if ("run_pipeline".equals(command)) {
            return handleRunPipeline(request);
        } else if ("explore_thresholds".equals(command)) {
            return handleExploreThresholds(request);
        } else if ("get_state_context".equals(command)) {
            return handleGetStateContext();
        } else if ("get_log".equals(command)) {
            return handleGetLog();
        } else if ("get_histogram".equals(command)) {
            return handleGetHistogram();
        } else if ("get_open_windows".equals(command)) {
            return handleGetOpenWindows();
        } else if ("get_metadata".equals(command)) {
            return handleGetMetadata();
        } else if ("batch".equals(command)) {
            return handleBatch(request);
        } else if ("get_pixels".equals(command)) {
            return handleGetPixels(request);
        } else if ("3d_viewer".equals(command)) {
            return handle3DViewer(request);
        } else if ("get_dialogs".equals(command)) {
            return handleGetDialogs();
        } else if ("close_dialogs".equals(command)) {
            return handleCloseDialogs(request);
        } else if ("close_windows".equals(command)) {
            return handleCloseDialogs(request);
        } else {
            return errorResponse("Unknown command: " + command);
        }
    }

    // -----------------------------------------------------------------------
    // Command handlers
    // -----------------------------------------------------------------------

    private JsonObject handlePing() {
        return successResponse(new JsonPrimitive("pong"));
    }

    private JsonObject handleExecuteMacro(JsonObject request) {
        JsonElement codeElement = request.get("code");
        if (codeElement == null || !codeElement.isJsonPrimitive()) {
            return errorResponse("Missing 'code' field for execute_macro");
        }
        final String code = codeElement.getAsString();

        // Call IJ.runMacro directly on this TCP handler thread.
        // The ImageJ macro interpreter handles its own EDT dispatch internally.
        JsonObject result = new JsonObject();
        boolean success = false;

        try {
            long startTime = System.currentTimeMillis();
            String macroReturn = IJ.runMacro(code);
            long elapsed = System.currentTimeMillis() - startTime;

            success = true;
            result.addProperty("success", true);
            result.addProperty("output", macroReturn != null ? macroReturn : "");
            result.addProperty("executionTimeMs", elapsed);

            // Capture state after execution
            try {
                ImageInfo active = stateInspector.getActiveImageInfo();
                if (active != null) {
                    JsonArray newImages = new JsonArray();
                    newImages.add(active.getTitle());
                    result.add("newImages", newImages);
                }
                String csv = stateInspector.getResultsTableCSV();
                if (csv != null && !csv.isEmpty()) {
                    result.addProperty("resultsTable", csv);
                }
            } catch (Exception ignore) {
                // State inspection is best-effort
            }
        } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("error", "Macro error: " + e.getMessage());
        }

        // ALWAYS check for dialogs — on success AND failure.
        // Dialogs (especially errors) are critical for the agent to see.
        try {
            JsonArray dialogs = detectOpenDialogs();
            if (dialogs.size() > 0) {
                result.add("dialogs", dialogs);
            }
        } catch (Exception ignore) {
            // Dialog detection is best-effort
        }

        return successResponse(result);
    }

    private JsonObject handleGetState() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject state = new JsonObject();

                    // Active image
                    ImageInfo active = stateInspector.getActiveImageInfo();
                    if (active != null) {
                        state.add("activeImage", imageInfoToJson(active));
                    } else {
                        state.add("activeImage", null);
                    }

                    // All images
                    List<ImageInfo> allImages = stateInspector.getAllImages();
                    JsonArray imagesArray = new JsonArray();
                    for (ImageInfo info : allImages) {
                        imagesArray.add(imageInfoToJson(info));
                    }
                    state.add("allImages", imagesArray);

                    // Results table
                    state.addProperty("resultsTable", stateInspector.getResultsTableCSV());

                    // Memory
                    MemoryInfo mem = stateInspector.getMemoryInfo();
                    JsonObject memJson = new JsonObject();
                    memJson.addProperty("usedMB", mem.getUsedMB());
                    memJson.addProperty("maxMB", mem.getMaxMB());
                    memJson.addProperty("freeMB", mem.getFreeMB());
                    memJson.addProperty("openImageCount", mem.getOpenImageCount());
                    state.add("memory", memJson);

                    holder[0] = state;
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(10000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting state");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleGetImageInfo() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ImageInfo info = stateInspector.getActiveImageInfo();
                    if (info != null) {
                        holder[0] = imageInfoToJson(info);
                    } else {
                        holder[0] = null;
                    }
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting image info");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        if (holder[0] == null) {
            return errorResponse("No active image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleGetResultsTable() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = stateInspector.getResultsTableCSV();
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting results table");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        return successResponse(new JsonPrimitive((String) holder[0]));
    }

    private JsonObject handleCaptureImage(JsonObject request) {
        JsonElement maxSizeElement = request.get("maxSize");
        final int maxSize = (maxSizeElement != null && maxSizeElement.isJsonPrimitive())
                ? maxSizeElement.getAsInt()
                : Constants.MAX_THUMBNAIL_SIZE;

        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ij.ImagePlus imp = ij.WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                    } else {
                        byte[] png = ImageCapture.captureImage(imp, maxSize);
                        if (png == null) {
                            holder[0] = "CAPTURE_FAILED";
                        } else {
                            JsonObject result = new JsonObject();
                            result.addProperty("base64", base64Encode(png));
                            result.addProperty("width", imp.getWidth());
                            result.addProperty("height", imp.getHeight());
                            holder[0] = result;
                        }
                    }
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(10000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out capturing image");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Capture error: " + ((Exception) holder[0]).getMessage());
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        if ("CAPTURE_FAILED".equals(holder[0])) {
            return errorResponse("Failed to capture image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleRunPipeline(JsonObject request) {
        JsonElement stepsElement = request.get("steps");
        if (stepsElement == null || !stepsElement.isJsonArray()) {
            return errorResponse("Missing 'steps' array for run_pipeline");
        }

        JsonArray stepsArray = stepsElement.getAsJsonArray();
        final List<PipelineBuilder.PipelineStep> steps = new ArrayList<PipelineBuilder.PipelineStep>();
        for (int i = 0; i < stepsArray.size(); i++) {
            JsonObject stepObj = stepsArray.get(i).getAsJsonObject();
            String desc = stepObj.has("description") ? stepObj.get("description").getAsString() : "Step " + (i + 1);
            String code = stepObj.has("code") ? stepObj.get("code").getAsString() : "";
            steps.add(new PipelineBuilder.PipelineStep(i + 1, desc, code));
        }

        if (steps.isEmpty()) {
            return errorResponse("Empty steps array");
        }

        PipelineBuilder.Pipeline pipeline = new PipelineBuilder.Pipeline("TCP Pipeline", steps);

        // executePipeline calls commandEngine.executeMacro() which handles EDT
        // dispatch internally, so call directly from TCP handler thread.
        try {
            pipelineBuilder.executePipeline(pipeline, null);
        } catch (Exception e) {
            return errorResponse("Pipeline error: " + e.getMessage());
        }

        PipelineBuilder.Pipeline result = pipeline;
        JsonObject resultJson = new JsonObject();
        resultJson.addProperty("status", result.status);
        JsonArray stepsResult = new JsonArray();
        for (PipelineBuilder.PipelineStep step : result.steps) {
            JsonObject stepJson = new JsonObject();
            stepJson.addProperty("index", step.index);
            stepJson.addProperty("description", step.description);
            stepJson.addProperty("status", step.status);
            stepJson.addProperty("executionTimeMs", step.executionTimeMs);
            if (step.result != null && !step.result.isSuccess()) {
                stepJson.addProperty("error", step.result.getError());
            }
            stepsResult.add(stepJson);
        }
        resultJson.add("steps", stepsResult);
        return successResponse(resultJson);
    }

    private JsonObject handleExploreThresholds(JsonObject request) {
        JsonElement methodsElement = request.get("methods");
        final String[] methods;
        if (methodsElement != null && methodsElement.isJsonArray()) {
            JsonArray arr = methodsElement.getAsJsonArray();
            methods = new String[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                methods[i] = arr.get(i).getAsString();
            }
        } else {
            methods = null; // Will use defaults
        }

        // exploreThresholds calls commandEngine.executeMacro() which handles EDT
        // dispatch internally, so call directly from TCP handler thread.
        ExplorationEngine.ExplorationReport report;
        try {
            report = explorationEngine.exploreThresholds(methods);
        } catch (Exception e) {
            return errorResponse("Exploration error: " + e.getMessage());
        }
        JsonObject resultJson = new JsonObject();
        if (report.recommended != null) {
            resultJson.addProperty("recommended", report.recommended.methodName);
        }
        resultJson.addProperty("reasoning", report.reasoning);

        JsonArray resultsArray = new JsonArray();
        for (ExplorationEngine.ExplorationResult r : report.results) {
            JsonObject rJson = new JsonObject();
            rJson.addProperty("method", r.methodName);
            rJson.addProperty("success", r.success);
            rJson.addProperty("objectCount", r.objectCount);
            rJson.addProperty("meanArea", r.meanArea);
            rJson.addProperty("meanCircularity", r.meanCircularity);
            rJson.addProperty("coverage", r.coverage);
            rJson.addProperty("summary", r.summary);
            resultsArray.add(rJson);
        }
        resultJson.add("results", resultsArray);
        return successResponse(resultJson);
    }

    private JsonObject handleGetStateContext() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = stateInspector.buildStateContext();
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting state context");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        return successResponse(new JsonPrimitive((String) holder[0]));
    }

    private JsonObject handleGetLog() {
        String log = IJ.getLog();
        return successResponse(new JsonPrimitive(log != null ? log : ""));
    }

    private JsonObject handleGetHistogram() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ImagePlus imp = WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                    } else {
                        ImageStatistics stats = imp.getStatistics();
                        JsonObject result = new JsonObject();
                        result.addProperty("min", stats.min);
                        result.addProperty("max", stats.max);
                        result.addProperty("mean", stats.mean);
                        result.addProperty("stdDev", stats.stdDev);
                        result.addProperty("nPixels", (long) stats.pixelCount);

                        JsonArray bins = new JsonArray();
                        if (stats.histogram != null) {
                            for (int i = 0; i < stats.histogram.length; i++) {
                                bins.add(new JsonPrimitive(stats.histogram[i]));
                            }
                        }
                        result.add("bins", bins);
                        holder[0] = result;
                    }
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting histogram");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleGetOpenWindows() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject result = new JsonObject();

                    // Image windows
                    JsonArray images = new JsonArray();
                    int[] ids = WindowManager.getIDList();
                    if (ids != null) {
                        for (int i = 0; i < ids.length; i++) {
                            ImagePlus imp = WindowManager.getImage(ids[i]);
                            if (imp != null) {
                                images.add(new JsonPrimitive(imp.getTitle()));
                            }
                        }
                    }
                    result.add("images", images);

                    // Non-image windows
                    JsonArray nonImages = new JsonArray();
                    Frame[] frames = WindowManager.getNonImageWindows();
                    if (frames != null) {
                        for (int i = 0; i < frames.length; i++) {
                            String title = frames[i].getTitle();
                            if (title != null && !title.isEmpty()) {
                                nonImages.add(new JsonPrimitive(title));
                            }
                        }
                    }
                    result.add("nonImages", nonImages);

                    holder[0] = result;
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting open windows");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleGetMetadata() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ImagePlus imp = WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                    } else {
                        JsonObject result = new JsonObject();
                        result.addProperty("title", imp.getTitle());

                        // Info property (often contains Bio-Formats metadata)
                        String info = (String) imp.getProperty("Info");
                        result.addProperty("info", info != null ? info : "");

                        // All properties
                        JsonObject propsJson = new JsonObject();
                        Properties props = imp.getProperties();
                        if (props != null) {
                            Enumeration<?> names = props.propertyNames();
                            while (names.hasMoreElements()) {
                                String key = names.nextElement().toString();
                                Object val = props.get(key);
                                if (val != null) {
                                    propsJson.addProperty(key, val.toString());
                                }
                            }
                        }
                        result.add("properties", propsJson);

                        // Calibration
                        Calibration cal = imp.getCalibration();
                        if (cal != null) {
                            JsonObject calJson = new JsonObject();
                            calJson.addProperty("pixelWidth", cal.pixelWidth);
                            calJson.addProperty("pixelHeight", cal.pixelHeight);
                            calJson.addProperty("pixelDepth", cal.pixelDepth);
                            calJson.addProperty("unit", cal.getUnit());
                            calJson.addProperty("timeUnit", cal.getTimeUnit());
                            calJson.addProperty("frameInterval", cal.frameInterval);
                            result.add("calibration", calJson);
                        }

                        holder[0] = result;
                    }
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting metadata");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    private JsonObject handleBatch(JsonObject request) {
        JsonElement commandsElement = request.get("commands");
        if (commandsElement == null || !commandsElement.isJsonArray()) {
            return errorResponse("Missing 'commands' array for batch");
        }

        JsonArray commands = commandsElement.getAsJsonArray();
        JsonArray results = new JsonArray();

        for (int i = 0; i < commands.size(); i++) {
            JsonElement elem = commands.get(i);
            if (elem.isJsonObject()) {
                JsonObject subResult = dispatch(GSON.toJson(elem));
                results.add(subResult);
            } else {
                results.add(errorResponse("Invalid batch command at index " + i));
            }
        }

        return successResponse(results);
    }

    // -----------------------------------------------------------------------
    // JSON helpers
    // -----------------------------------------------------------------------

    private JsonObject successResponse(JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("result", result);
        return response;
    }

    private JsonObject errorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("error", message);

        // ALWAYS attach open dialogs to error responses.
        // Errors are exactly when dialogs are most likely to appear.
        try {
            JsonArray dialogs = detectOpenDialogs();
            if (dialogs.size() > 0) {
                response.add("dialogs", dialogs);
            }
        } catch (Exception ignore) {
            // Best-effort
        }

        return response;
    }

    private String errorJson(String message) {
        JsonObject response = errorResponse(message);
        return GSON.toJson(response);
    }

    private JsonObject executionResultToJson(ExecutionResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("success", result.isSuccess());
        if (result.isSuccess()) {
            json.addProperty("output", result.getOutput() != null ? result.getOutput() : "");
            json.addProperty("resultsTable", result.getResultsTable() != null ? result.getResultsTable() : "");
            JsonArray newImages = new JsonArray();
            for (String img : result.getNewImages()) {
                newImages.add(new JsonPrimitive(img));
            }
            json.add("newImages", newImages);
            json.addProperty("executionTimeMs", result.getExecutionTimeMs());
        } else {
            json.addProperty("error", result.getError() != null ? result.getError() : "Unknown error");
        }
        return json;
    }

    private JsonObject imageInfoToJson(ImageInfo info) {
        JsonObject json = new JsonObject();
        json.addProperty("title", info.getTitle());
        json.addProperty("width", info.getWidth());
        json.addProperty("height", info.getHeight());
        json.addProperty("type", info.getType());
        json.addProperty("slices", info.getSlices());
        json.addProperty("channels", info.getChannels());
        json.addProperty("frames", info.getFrames());
        json.addProperty("calibration", info.getCalibration());
        json.addProperty("isStack", info.isStack());
        json.addProperty("isHyperstack", info.isHyperstack());
        return json;
    }

    /**
     * Base64-encode a byte array. Java 8 compatible using javax.xml.bind
     * or manual implementation since java.util.Base64 requires Java 8 update.
     */
    private static String base64Encode(byte[] data) {
        // java.util.Base64 is available in Java 8
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    /**
     * Return raw pixel data for the active image (or a region of it).
     * Supports optional parameters: x, y, width, height, slice.
     * Returns base64-encoded raw pixel values as floats (4 bytes each),
     * plus metadata for reconstruction.
     *
     * Request:
     *   {"command": "get_pixels"}                              — full current slice
     *   {"command": "get_pixels", "slice": 5}                  — full slice 5
     *   {"command": "get_pixels", "x":10, "y":10, "width":100, "height":100}  — region
     *   {"command": "get_pixels", "allSlices": true}           — entire stack
     */
    private JsonObject handleGetPixels(JsonObject request) {
        // Parse optional parameters
        final int reqX = request.has("x") ? request.get("x").getAsInt() : -1;
        final int reqY = request.has("y") ? request.get("y").getAsInt() : -1;
        final int reqW = request.has("width") ? request.get("width").getAsInt() : -1;
        final int reqH = request.has("height") ? request.get("height").getAsInt() : -1;
        final int reqSlice = request.has("slice") ? request.get("slice").getAsInt() : -1;
        final boolean allSlices = request.has("allSlices") && request.get("allSlices").getAsBoolean();

        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    ImagePlus imp = WindowManager.getCurrentImage();
                    if (imp == null) {
                        holder[0] = "NO_IMAGE";
                        return;
                    }

                    int imgW = imp.getWidth();
                    int imgH = imp.getHeight();
                    int nSlices = imp.getStackSize();

                    // Determine region
                    int x = reqX >= 0 ? Math.min(reqX, imgW - 1) : 0;
                    int y = reqY >= 0 ? Math.min(reqY, imgH - 1) : 0;
                    int w = reqW > 0 ? Math.min(reqW, imgW - x) : imgW - x;
                    int h = reqH > 0 ? Math.min(reqH, imgH - y) : imgH - y;

                    // Determine slices to extract
                    int startSlice, endSlice;
                    if (allSlices) {
                        startSlice = 1;
                        endSlice = nSlices;
                    } else if (reqSlice > 0) {
                        startSlice = Math.min(reqSlice, nSlices);
                        endSlice = startSlice;
                    } else {
                        startSlice = imp.getCurrentSlice();
                        endSlice = startSlice;
                    }
                    int sliceCount = endSlice - startSlice + 1;

                    // Safety: limit total pixels to avoid OOM
                    long totalPixels = (long) w * h * sliceCount;
                    if (totalPixels > 4000000) { // ~16MB as floats
                        holder[0] = new Exception("Region too large: " + totalPixels
                                + " pixels. Max 4M. Use x/y/width/height to crop.");
                        return;
                    }

                    // Extract pixel values as floats
                    float[] allPixels = new float[w * h * sliceCount];
                    int offset = 0;
                    for (int s = startSlice; s <= endSlice; s++) {
                        imp.setSliceWithoutUpdate(s);
                        ij.process.ImageProcessor ip = imp.getProcessor();
                        for (int py = y; py < y + h; py++) {
                            for (int px = x; px < x + w; px++) {
                                allPixels[offset++] = ip.getPixelValue(px, py);
                            }
                        }
                    }

                    // Convert float array to bytes then base64
                    byte[] rawBytes = new byte[allPixels.length * 4];
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(rawBytes);
                    buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    for (float v : allPixels) {
                        buf.putFloat(v);
                    }
                    String b64 = base64Encode(rawBytes);

                    JsonObject result = new JsonObject();
                    result.addProperty("x", x);
                    result.addProperty("y", y);
                    result.addProperty("width", w);
                    result.addProperty("height", h);
                    result.addProperty("sliceStart", startSlice);
                    result.addProperty("sliceEnd", endSlice);
                    result.addProperty("sliceCount", sliceCount);
                    result.addProperty("nPixels", allPixels.length);
                    result.addProperty("type", imp.getBitDepth() + "-bit");
                    result.addProperty("encoding", "base64_float32_le");
                    result.addProperty("data", b64);

                    holder[0] = result;
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(30000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out getting pixels");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }
        if ("NO_IMAGE".equals(holder[0])) {
            return errorResponse("No active image");
        }
        return successResponse((JsonObject) holder[0]);
    }

    /**
     * Control the 3D Viewer via reflection (avoids compile-time dependency).
     *
     * Actions:
     *   {"command": "3d_viewer", "action": "status"}
     *   {"command": "3d_viewer", "action": "add", "image": "title", "type": "volume", "threshold": 50}
     *   {"command": "3d_viewer", "action": "list"}
     *   {"command": "3d_viewer", "action": "snapshot", "width": 512, "height": 512}
     *   {"command": "3d_viewer", "action": "close"}
     *
     * type: "volume" (0), "orthoslice" (1), "surface" (2), "surface_plot" (3)
     */
    private JsonObject handle3DViewer(JsonObject request) {
        String action = request.has("action") ? request.get("action").getAsString() : "status";

        // Run directly on TCP handler thread — 3D Viewer operations (especially
        // addContent) block for a long time during rendering. Running on EDT
        // would freeze the entire UI. The 3D Viewer manages its own threading.
        try {
            JsonObject result = dispatch3DViewer(action, request);
            return successResponse(result);
        } catch (Exception e) {
            return errorResponse("3D Viewer error: " + e.getMessage());
        }
    }

    private JsonObject dispatch3DViewer(String action, JsonObject request) throws Exception {
        // Use reflection to access ij3d classes
        Class<?> universeClass;
        try {
            universeClass = Class.forName("ij3d.Image3DUniverse");
        } catch (ClassNotFoundException e) {
            JsonObject result = new JsonObject();
            result.addProperty("error", "3D Viewer plugin not installed");
            result.addProperty("installed", false);
            return result;
        }

        // Get or create the universe instance — check cache first
        Object universe = cached3DUniverse;

        // Verify cached reference is still valid (window might have been closed)
        if (universe != null) {
            try {
                java.lang.reflect.Method getCanvas = universeClass.getMethod("getCanvas");
                Object canvas = getCanvas.invoke(universe);
                if (canvas == null) {
                    universe = null; // Universe was closed
                    cached3DUniverse = null;
                }
            } catch (Exception e) {
                universe = null;
                cached3DUniverse = null;
            }
        }

        // Try the static accessor if no cached reference
        if (universe == null) {
            try {
                Class<?> viewerClass = Class.forName("ij3d.ImageJ_3D_Viewer");
                java.lang.reflect.Method getUniv = viewerClass.getMethod("getUniverse");
                universe = getUniv.invoke(null);
                if (universe != null) {
                    cached3DUniverse = universe;
                }
            } catch (Exception ignore) {
                // No universe available
            }
        }

        JsonObject result = new JsonObject();

        if ("status".equals(action)) {
            result.addProperty("installed", true);
            result.addProperty("open", universe != null);
            if (universe != null) {
                try {
                    // getContents() returns Iterator or Collection
                    java.lang.reflect.Method getContents = universeClass.getMethod("getContents");
                    Object contents = getContents.invoke(universe);
                    int count = 0;
                    JsonArray contentNames = new JsonArray();
                    if (contents instanceof java.util.Collection) {
                        for (Object c : (java.util.Collection<?>) contents) {
                            java.lang.reflect.Method getName = c.getClass().getMethod("getName");
                            String name = (String) getName.invoke(c);
                            contentNames.add(new JsonPrimitive(name != null ? name : "unnamed"));
                            count++;
                        }
                    }
                    result.addProperty("contentCount", count);
                    result.add("contents", contentNames);
                } catch (Exception e) {
                    result.addProperty("contentError", e.getMessage());
                }
            }
            return result;

        } else if ("add".equals(action)) {
            String imageName = request.has("image") ? request.get("image").getAsString() : null;
            if (imageName == null) {
                result.addProperty("error", "Missing 'image' parameter");
                return result;
            }

            // Find the ImagePlus
            ImagePlus imp = WindowManager.getImage(imageName);
            if (imp == null) {
                result.addProperty("error", "Image not found: " + imageName);
                return result;
            }

            String typeStr = request.has("type") ? request.get("type").getAsString() : "volume";
            int threshold = request.has("threshold") ? request.get("threshold").getAsInt() : 50;
            int resamplingFactor = request.has("resampling") ? request.get("resampling").getAsInt() : 1;

            // Map type string to int: volume=0, orthoslice=1, surface=2, surface_plot=3
            int typeInt = 0;
            if ("orthoslice".equals(typeStr)) typeInt = 1;
            else if ("surface".equals(typeStr)) typeInt = 2;
            else if ("surface_plot".equals(typeStr)) typeInt = 3;

            // Create universe if needed
            if (universe == null) {
                java.lang.reflect.Constructor<?> ctor = universeClass.getConstructor();
                universe = ctor.newInstance();
                cached3DUniverse = universe; // Cache immediately
                java.lang.reflect.Method show = universeClass.getMethod("show");
                show.invoke(universe);
                // Store it via the static setter if available
                try {
                    Class<?> viewerClass = Class.forName("ij3d.ImageJ_3D_Viewer");
                    java.lang.reflect.Field univField = viewerClass.getDeclaredField("univ");
                    univField.setAccessible(true);
                    univField.set(null, universe);
                } catch (Exception ignore) {}
            }

            // Try multiple addContent signatures — API varies between versions
            Object content = null;
            String methodUsed = "";

            // Attempt 1: addContent(ImagePlus, Color3f, String, int, boolean[], int, int)
            try {
                Class<?> color3fClass = Class.forName("org.scijava.vecmath.Color3f");
                java.lang.reflect.Constructor<?> colorCtor = color3fClass.getConstructor(float.class, float.class, float.class);
                Object white = colorCtor.newInstance(1.0f, 1.0f, 1.0f);

                java.lang.reflect.Method addContent = universeClass.getMethod(
                        "addContent", ImagePlus.class, color3fClass, String.class,
                        int.class, boolean[].class, int.class, int.class);
                boolean[] channels = new boolean[]{true, true, true};
                content = addContent.invoke(universe, imp, white, imageName,
                        threshold, channels, resamplingFactor, typeInt);
                methodUsed = "addContent(ImagePlus, Color3f, String, int, boolean[], int, int)";
            } catch (Exception e1) {
                // Attempt 2: addContent(ImagePlus, int, int) — simpler signature
                try {
                    java.lang.reflect.Method addContent = universeClass.getMethod(
                            "addContent", ImagePlus.class, int.class, int.class);
                    content = addContent.invoke(universe, imp, typeInt, resamplingFactor);
                    methodUsed = "addContent(ImagePlus, int, int)";
                } catch (Exception e2) {
                    // Attempt 3: addContent(ImagePlus, int) — simplest
                    try {
                        java.lang.reflect.Method addContent = universeClass.getMethod(
                                "addContent", ImagePlus.class, int.class);
                        content = addContent.invoke(universe, imp, typeInt);
                        methodUsed = "addContent(ImagePlus, int)";
                    } catch (Exception e3) {
                        // List available addContent methods for debugging
                        StringBuilder methods = new StringBuilder();
                        for (java.lang.reflect.Method m : universeClass.getMethods()) {
                            if ("addContent".equals(m.getName())) {
                                methods.append(m.toString()).append("; ");
                            }
                        }
                        result.addProperty("error", "No compatible addContent method found. Available: " + methods.toString());
                        return result;
                    }
                }
            }

            if (content != null) {
                // Set threshold if applicable
                try {
                    java.lang.reflect.Method setThreshold = content.getClass().getMethod("setThreshold", int.class);
                    setThreshold.invoke(content, threshold);
                } catch (Exception ignore) {}

                try {
                    java.lang.reflect.Method getName = content.getClass().getMethod("getName");
                    result.addProperty("added", (String) getName.invoke(content));
                } catch (Exception ignore) {
                    result.addProperty("added", imageName);
                }
                result.addProperty("success", true);
                result.addProperty("method", methodUsed);
            } else {
                // List available methods for debugging
                StringBuilder methods = new StringBuilder();
                for (java.lang.reflect.Method m : universeClass.getMethods()) {
                    if ("addContent".equals(m.getName())) {
                        methods.append(m.toString()).append("; ");
                    }
                }
                result.addProperty("error", "addContent returned null via " + methodUsed);
                result.addProperty("availableMethods", methods.toString());
            }
            return result;

        } else if ("list".equals(action)) {
            if (universe == null) {
                result.addProperty("open", false);
                result.add("contents", new JsonArray());
                return result;
            }
            result.addProperty("open", true);
            try {
                java.lang.reflect.Method getContents = universeClass.getMethod("getContents");
                Object contents = getContents.invoke(universe);
                JsonArray contentList = new JsonArray();
                if (contents instanceof java.util.Collection) {
                    for (Object c : (java.util.Collection<?>) contents) {
                        JsonObject entry = new JsonObject();
                        try {
                            java.lang.reflect.Method getName = c.getClass().getMethod("getName");
                            entry.addProperty("name", (String) getName.invoke(c));
                        } catch (Exception ignore) {}
                        try {
                            java.lang.reflect.Method isVisible = c.getClass().getMethod("isVisible");
                            entry.addProperty("visible", (Boolean) isVisible.invoke(c));
                        } catch (Exception ignore) {}
                        contentList.add(entry);
                    }
                }
                result.add("contents", contentList);
            } catch (Exception e) {
                result.addProperty("error", e.getMessage());
            }
            return result;

        } else if ("snapshot".equals(action)) {
            if (universe == null) {
                result.addProperty("error", "3D Viewer not open");
                return result;
            }
            int width = request.has("width") ? request.get("width").getAsInt() : 512;
            int height = request.has("height") ? request.get("height").getAsInt() : 512;

            try {
                java.lang.reflect.Method takeSnapshot = universeClass.getMethod("takeSnapshot", int.class, int.class);
                Object snapshot = takeSnapshot.invoke(universe, width, height);
                if (snapshot instanceof ImagePlus) {
                    ((ImagePlus) snapshot).show();
                    result.addProperty("success", true);
                    result.addProperty("title", ((ImagePlus) snapshot).getTitle());
                } else {
                    result.addProperty("error", "Snapshot did not return an ImagePlus");
                }
            } catch (Exception e) {
                result.addProperty("error", "Snapshot failed: " + e.getMessage());
            }
            return result;

        } else if ("capture".equals(action)) {
            // Use java.awt.Robot to screenshot the 3D Viewer window directly.
            // This avoids the broken takeSnapshot() that produces a tiny image.
            if (universe == null) {
                result.addProperty("error", "3D Viewer not open");
                return result;
            }
            try {
                // Find the 3D Viewer window
                java.awt.Window viewerWindow = null;
                java.awt.Window[] allWindows = java.awt.Window.getWindows();
                for (java.awt.Window w : allWindows) {
                    if (w.isShowing() && w.getClass().getName().contains("ImageWindow3D")) {
                        viewerWindow = w;
                        break;
                    }
                }
                // Fallback: find any Frame with "3D" in the title
                if (viewerWindow == null) {
                    for (java.awt.Window w : allWindows) {
                        if (w.isShowing() && w instanceof java.awt.Frame) {
                            String title = ((java.awt.Frame) w).getTitle();
                            if (title != null && title.contains("3D")) {
                                viewerWindow = w;
                                break;
                            }
                        }
                    }
                }

                if (viewerWindow == null) {
                    result.addProperty("error", "Could not find 3D Viewer window");
                    return result;
                }

                // Screenshot the window using Robot
                java.awt.Rectangle bounds = viewerWindow.getBounds();
                java.awt.Robot robot = new java.awt.Robot();
                java.awt.image.BufferedImage screenshot = robot.createScreenCapture(bounds);

                // Convert to ImagePlus and show
                ImagePlus snap = new ImagePlus("3D_Render", screenshot);
                snap.show();

                result.addProperty("success", true);
                result.addProperty("title", "3D_Render");
                result.addProperty("width", bounds.width);
                result.addProperty("height", bounds.height);
            } catch (Exception e) {
                result.addProperty("error", "Capture failed: " + e.getMessage());
            }
            return result;

        } else if ("fit".equals(action) || "reset_view".equals(action)) {
            if (universe == null) {
                result.addProperty("error", "3D Viewer not open");
                return result;
            }
            try {
                // Try resetView first — fits all content into view
                java.lang.reflect.Method resetView = universeClass.getMethod("resetView");
                resetView.invoke(universe);
                result.addProperty("success", true);
            } catch (Exception e) {
                // Try centerSelected as fallback
                try {
                    java.lang.reflect.Method cs = universeClass.getMethod("centerSelected",
                            Class.forName("ij3d.Content"));
                    java.lang.reflect.Method getSelected = universeClass.getMethod("getSelected");
                    Object selected = getSelected.invoke(universe);
                    if (selected != null) {
                        cs.invoke(universe, selected);
                    }
                    result.addProperty("success", true);
                } catch (Exception e2) {
                    result.addProperty("error", "Fit failed: " + e.getMessage());
                }
            }
            return result;

        } else if ("close".equals(action)) {
            if (universe != null) {
                try {
                    java.lang.reflect.Method close = universeClass.getMethod("close");
                    close.invoke(universe);
                    result.addProperty("closed", true);
                } catch (Exception e) {
                    result.addProperty("error", "Close failed: " + e.getMessage());
                }
            } else {
                result.addProperty("closed", false);
                result.addProperty("error", "3D Viewer not open");
            }
            return result;

        } else {
            result.addProperty("error", "Unknown action: " + action + ". Use: status, add, list, snapshot, close");
            return result;
        }
    }

    private JsonObject handleGetDialogs() {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    holder[0] = detectOpenDialogs();
                } catch (Exception e) {
                    holder[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(5000, TimeUnit.MILLISECONDS)) {
                return errorResponse("Timed out detecting dialogs");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResponse("Interrupted");
        }

        if (holder[0] instanceof Exception) {
            return errorResponse("Error: " + ((Exception) holder[0]).getMessage());
        }

        JsonObject result = new JsonObject();
        result.add("dialogs", (JsonArray) holder[0]);
        return successResponse(result);
    }

    /**
     * Scan all open windows for dialogs and extract their text content.
     * Returns a JsonArray of dialog objects with title, text, type, and buttons.
     */
    private JsonArray detectOpenDialogs() {
        JsonArray dialogs = new JsonArray();
        Window[] windows = Window.getWindows();

        for (Window win : windows) {
            if (!win.isShowing()) continue;

            // Only interested in Dialog windows (error popups, prompts, etc.)
            if (!(win instanceof Dialog)) continue;

            Dialog dlg = (Dialog) win;
            String title = dlg.getTitle();
            if (title == null) title = "";

            // Skip the AI Assistant window itself
            if (title.contains("AI Assistant")) continue;

            // Extract all text from the dialog's components
            StringBuilder textContent = new StringBuilder();
            List<String> buttonLabels = new ArrayList<String>();
            extractDialogContent(dlg, textContent, buttonLabels);

            JsonObject dialogInfo = new JsonObject();
            dialogInfo.addProperty("title", title);
            dialogInfo.addProperty("text", textContent.toString().trim());
            dialogInfo.addProperty("modal", dlg.isModal());

            JsonArray buttons = new JsonArray();
            for (String label : buttonLabels) {
                buttons.add(new JsonPrimitive(label));
            }
            dialogInfo.add("buttons", buttons);

            // Classify dialog type
            String text = textContent.toString().toLowerCase();
            if (text.contains("error") || text.contains("exception") || text.contains("failed")) {
                dialogInfo.addProperty("type", "error");
            } else if (text.contains("warning") || text.contains("caution")) {
                dialogInfo.addProperty("type", "warning");
            } else if (buttonLabels.contains("OK") && buttonLabels.contains("Cancel")) {
                dialogInfo.addProperty("type", "prompt");
            } else {
                dialogInfo.addProperty("type", "info");
            }

            dialogs.add(dialogInfo);
        }

        return dialogs;
    }

    /**
     * Recursively extract ALL readable content from a dialog's component tree.
     * Reads labels, text fields, dropdowns, checkboxes, sliders, spinners,
     * text areas, and buttons — everything needed to understand any dialog.
     */
    private void extractDialogContent(Container container, StringBuilder text, List<String> buttons) {
        for (Component comp : container.getComponents()) {
            // --- Labels ---
            if (comp instanceof javax.swing.JLabel) {
                String s = ((javax.swing.JLabel) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append(s.trim()).append("\n");
                }
            } else if (comp instanceof java.awt.Label) {
                String s = ((java.awt.Label) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append(s.trim()).append("\n");
                }

            // --- Buttons ---
            } else if (comp instanceof javax.swing.JButton) {
                String s = ((javax.swing.JButton) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    buttons.add(s.trim());
                }
            } else if (comp instanceof java.awt.Button) {
                String s = ((java.awt.Button) comp).getLabel();
                if (s != null && !s.trim().isEmpty()) {
                    buttons.add(s.trim());
                }

            // --- Text input fields ---
            } else if (comp instanceof javax.swing.JTextField) {
                String s = ((javax.swing.JTextField) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append("[field: ").append(s.trim()).append("]\n");
                }
            } else if (comp instanceof java.awt.TextField) {
                String s = ((java.awt.TextField) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append("[field: ").append(s.trim()).append("]\n");
                }

            // --- Text areas ---
            } else if (comp instanceof javax.swing.JTextArea) {
                String s = ((javax.swing.JTextArea) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append(s.trim()).append("\n");
                }
            } else if (comp instanceof java.awt.TextArea) {
                String s = ((java.awt.TextArea) comp).getText();
                if (s != null && !s.trim().isEmpty()) {
                    text.append(s.trim()).append("\n");
                }

            // --- Dropdowns / Choice ---
            } else if (comp instanceof javax.swing.JComboBox) {
                javax.swing.JComboBox<?> combo = (javax.swing.JComboBox<?>) comp;
                Object selected = combo.getSelectedItem();
                if (selected != null) {
                    text.append("[dropdown: ").append(selected.toString()).append("]\n");
                }
            } else if (comp instanceof java.awt.Choice) {
                String s = ((java.awt.Choice) comp).getSelectedItem();
                if (s != null) {
                    text.append("[dropdown: ").append(s).append("]\n");
                }

            // --- Checkboxes ---
            } else if (comp instanceof javax.swing.JCheckBox) {
                javax.swing.JCheckBox cb = (javax.swing.JCheckBox) comp;
                String label = cb.getText();
                if (label != null && !label.trim().isEmpty()) {
                    text.append("[checkbox: ").append(label.trim())
                        .append(" = ").append(cb.isSelected() ? "ON" : "OFF").append("]\n");
                }
            } else if (comp instanceof java.awt.Checkbox) {
                java.awt.Checkbox cb = (java.awt.Checkbox) comp;
                String label = cb.getLabel();
                if (label != null && !label.trim().isEmpty()) {
                    text.append("[checkbox: ").append(label.trim())
                        .append(" = ").append(cb.getState() ? "ON" : "OFF").append("]\n");
                }

            // --- Sliders ---
            } else if (comp instanceof javax.swing.JSlider) {
                javax.swing.JSlider slider = (javax.swing.JSlider) comp;
                text.append("[slider: ").append(slider.getValue())
                    .append(" (").append(slider.getMinimum())
                    .append("-").append(slider.getMaximum()).append(")]\n");
            } else if (comp instanceof java.awt.Scrollbar) {
                java.awt.Scrollbar sb = (java.awt.Scrollbar) comp;
                text.append("[scrollbar: ").append(sb.getValue())
                    .append(" (").append(sb.getMinimum())
                    .append("-").append(sb.getMaximum()).append(")]\n");

            // --- Spinners ---
            } else if (comp instanceof javax.swing.JSpinner) {
                javax.swing.JSpinner spinner = (javax.swing.JSpinner) comp;
                text.append("[spinner: ").append(spinner.getValue()).append("]\n");
            }

            // Catch-all: try reflection for unknown components (e.g. MultiLineLabel)
            // that have getText(), getLabel(), or getMessage() methods
            if (text.indexOf(comp.getClass().getSimpleName()) < 0) {
                // Only if we haven't already extracted from this component type above
                boolean alreadyHandled = (comp instanceof javax.swing.JLabel)
                        || (comp instanceof java.awt.Label)
                        || (comp instanceof javax.swing.JButton)
                        || (comp instanceof java.awt.Button)
                        || (comp instanceof javax.swing.JTextField)
                        || (comp instanceof java.awt.TextField)
                        || (comp instanceof javax.swing.JTextArea)
                        || (comp instanceof java.awt.TextArea)
                        || (comp instanceof javax.swing.JComboBox)
                        || (comp instanceof java.awt.Choice)
                        || (comp instanceof javax.swing.JCheckBox)
                        || (comp instanceof java.awt.Checkbox)
                        || (comp instanceof javax.swing.JSlider)
                        || (comp instanceof java.awt.Scrollbar)
                        || (comp instanceof javax.swing.JSpinner);

                if (!alreadyHandled) {
                    // Try getText()
                    try {
                        java.lang.reflect.Method m = comp.getClass().getMethod("getText");
                        Object val = m.invoke(comp);
                        if (val != null && !val.toString().trim().isEmpty()) {
                            text.append(val.toString().trim()).append("\n");
                        }
                    } catch (Exception ignore) {}

                    // Try getLabel()
                    try {
                        java.lang.reflect.Method m = comp.getClass().getMethod("getLabel");
                        Object val = m.invoke(comp);
                        if (val != null && !val.toString().trim().isEmpty()) {
                            text.append(val.toString().trim()).append("\n");
                        }
                    } catch (Exception ignore) {}

                    // Try getMessage()
                    try {
                        java.lang.reflect.Method m = comp.getClass().getMethod("getMessage");
                        Object val = m.invoke(comp);
                        if (val != null && !val.toString().trim().isEmpty()) {
                            text.append(val.toString().trim()).append("\n");
                        }
                    } catch (Exception ignore) {}
                }
            }

            // Recurse into child containers
            if (comp instanceof Container) {
                extractDialogContent((Container) comp, text, buttons);
            }
        }
    }

    private JsonObject handleCloseDialogs(JsonObject request) {
        JsonElement patternElement = request.get("pattern");
        final String pattern = (patternElement != null && patternElement.isJsonPrimitive())
                ? patternElement.getAsString()
                : null;

        final int[] closedCount = new int[1];
        final CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    java.awt.Window[] windows = java.awt.Window.getWindows();
                    for (java.awt.Window win : windows) {
                        if (win.isShowing() && (win instanceof java.awt.Dialog || win instanceof java.awt.Frame)) {
                            String title = "";
                            if (win instanceof java.awt.Dialog) title = ((java.awt.Dialog) win).getTitle();
                            else if (win instanceof java.awt.Frame) title = ((java.awt.Frame) win).getTitle();

                            if (title == null) title = "";

                            // Never close the main ImageJ/Fiji window or the AI Assistant window
                            if (title.equals("ImageJ") || title.equals("Fiji")
                                    || title.contains("AI Assistant")
                                    || title.contains("ImageJ")
                                    || title.contains("Startup")) {
                                continue;
                            }

                            // Also protect by checking if this is the IJ main frame
                            if (win == IJ.getInstance()) {
                                continue;
                            }

                            // Don't close image windows
                            if (win instanceof ImageWindow) {
                                continue;
                            }

                            if (pattern == null || title.toLowerCase().contains(pattern.toLowerCase())) {
                                win.setVisible(false);
                                win.dispose();
                                closedCount[0]++;
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        JsonObject result = new JsonObject();
        result.addProperty("closedCount", closedCount[0]);
        return successResponse(result);
    }
}
