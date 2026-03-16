package imagejai.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import ij.IJ;
import imagejai.config.Constants;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
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
        } else if ("batch".equals(command)) {
            return handleBatch(request);
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
        // Using CommandEngine's executor/invokeAndWait causes deadlocks or hangs
        // because the executor thread lacks ImageJ's expected thread context.
        try {
            long startTime = System.currentTimeMillis();
            String macroReturn = IJ.runMacro(code);
            long elapsed = System.currentTimeMillis() - startTime;

            JsonObject result = new JsonObject();
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

            return successResponse(result);
        } catch (Exception e) {
            return errorResponse("Macro error: " + e.getMessage());
        }
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
}
