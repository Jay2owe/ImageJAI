package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ij.IJ;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Starts and supervises the bundled LiteLLM Proxy Python sidecar.
 */
public class LiteLlmProxyService {
    private enum Lifecycle {
        STOPPED, STARTING, RUNNING, SHUTDOWN
    }
    private static final String LOG_PREFIX = "[ImageJAI-LiteLLM]";
    private static final String COST_PREFIX = "[ImageJAI-LiteLLM-Cost] ";
    private static final String BILLING_PREFIX = "[ImageJAI-LiteLLM-Billing] ";
    private static final int DEFAULT_PORT = 4000;
    private static final int PORT_SCAN_LO = 4000;
    private static final int PORT_SCAN_HI = 4010;
    private static final double STARTUP_READY_TIMEOUT_SECONDS = 20.0;

    private final Path agentWorkspace;
    private final List<CostHeaderListener> costHeaderListeners =
            new CopyOnWriteArrayList<CostHeaderListener>();
    private final List<BillingFailureListener> billingFailureListeners =
            new CopyOnWriteArrayList<BillingFailureListener>();

    private volatile Process process;
    private volatile Path activeProvidersDir;
    private volatile Path activeRuntimeDir;
    private volatile String masterKey;
    private volatile int port = DEFAULT_PORT;
    private volatile boolean shutdown;
    private volatile boolean ready;
    private volatile Lifecycle lifecycle = Lifecycle.STOPPED;

    public LiteLlmProxyService(String agentWorkspace) {
        this.agentWorkspace = agentWorkspace != null
                ? Paths.get(agentWorkspace).toAbsolutePath()
                : null;
    }

    public synchronized void startAsync() {
        if (shutdown || lifecycle != Lifecycle.STOPPED) {
            return;
        }
        lifecycle = Lifecycle.STARTING;
        Thread starter = new Thread(new Runnable() {
            @Override
            public void run() {
                startBlocking();
            }
        }, "ImageJAI-litellm-proxy-start");
        starter.setDaemon(true);
        starter.start();
    }

    public void addCostHeaderListener(CostHeaderListener listener) {
        if (listener != null) {
            costHeaderListeners.add(listener);
        }
    }

    public void removeCostHeaderListener(CostHeaderListener listener) {
        costHeaderListeners.remove(listener);
    }

    public void addBillingFailureListener(BillingFailureListener listener) {
        if (listener != null) {
            billingFailureListeners.add(listener);
        }
    }

    public void removeBillingFailureListener(BillingFailureListener listener) {
        billingFailureListeners.remove(listener);
    }

    public int getPort() {
        return port;
    }

    /**
     * True once the sidecar's readiness endpoint has confirmed the live port,
     * so {@link #getPort()} is trustworthy. The agent launcher only exports the
     * port after this flips, so the Python client never receives a stale 4000
     * during async startup (the client scans for the live port otherwise).
     */
    public boolean isReady() {
        return ready;
    }

    public synchronized void shutdown() {
        shutdown = true;
        ready = false;
        lifecycle = Lifecycle.SHUTDOWN;
        Process running = process;
        try {
            if (running != null) {
                IJ.log(LOG_PREFIX + " stopping proxy sidecar");
                terminateProcess(running);
            }
        } finally {
            process = null;
            cleanupRuntimeState();
        }
    }

    private void startBlocking() {
        Process started = null;
        boolean running = false;
        try {
            if (!startupAllowed()) return;
            Path providersDir = resolveProvidersDir();
            activeProvidersDir = providersDir;
            Path proxy = providersDir.resolve("proxy.py");
            Path config = providersDir.resolve("litellm.config.yaml");
            if (!startupAllowed()) return;
            started = launchSidecar(providersDir, proxy, config);
            synchronized (this) {
                if (!shutdown && lifecycle == Lifecycle.STARTING) {
                    process = started;
                }
            }
            if (process != started) return;
            startOutputPump(started);

            boolean healthy = waitHealthy(STARTUP_READY_TIMEOUT_SECONDS);
            synchronized (this) {
                if (healthy && !shutdown && lifecycle == Lifecycle.STARTING
                        && process == started && started.isAlive()) {
                    ready = true;
                    lifecycle = Lifecycle.RUNNING;
                    running = true;
                }
            }
            if (running) {
                startExitWatcher(started);
                IJ.log(LOG_PREFIX + " proxy ready on localhost:" + port);
            } else if (!shutdown) {
                IJ.log(LOG_PREFIX + " proxy did not become ready within "
                        + (int) STARTUP_READY_TIMEOUT_SECONDS + "s");
            }
        } catch (Exception e) {
            if (!shutdown) {
                IJ.log(LOG_PREFIX + " failed to start proxy: " + e.getMessage());
            }
        } finally {
            if (!running) {
                if (started != null) terminateProcess(started);
                synchronized (this) {
                    if (process == started) process = null;
                    ready = false;
                    if (!shutdown) lifecycle = Lifecycle.STOPPED;
                }
                cleanupRuntimeState();
            }
        }
    }

    private synchronized boolean startupAllowed() {
        return !shutdown && lifecycle == Lifecycle.STARTING;
    }

    protected Process launchSidecar(Path providersDir, Path proxy, Path config)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                pythonCommand(), proxy.toString(), "--start", "--config", config.toString());
        builder.directory(providersDir.getParent().getParent().toFile());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    protected Path resolveProvidersDir() throws IOException {
        if (agentWorkspace != null) {
            Path fromWorkspace = agentWorkspace.resolve("providers");
            if (Files.isRegularFile(fromWorkspace.resolve("proxy.py"))
                    && Files.isRegularFile(fromWorkspace.resolve("litellm.config.yaml"))) {
                return fromWorkspace;
            }
        }

        Path tempRoot = Paths.get(System.getProperty("java.io.tmpdir"),
                "imagejai-litellm-sidecar");
        Path providers = tempRoot.resolve("agent").resolve("providers");
        Files.createDirectories(providers);
        copyResource("/agent/providers/proxy.py", providers.resolve("proxy.py"));
        copyResource("/agent/providers/litellm.config.yaml",
                providers.resolve("litellm.config.yaml"));
        copyResource("/agent/providers/requirements.txt",
                providers.resolve("requirements.txt"));
        return providers;
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = LiteLlmProxyService.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing bundled resource " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void startOutputPump(final Process started) {
        Thread pump = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        started.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        handleSidecarLine(line);
                    }
                } catch (IOException e) {
                    if (!shutdown) {
                        IJ.log(LOG_PREFIX + " log stream failed: " + e.getMessage());
                    }
                }
            }
        }, "ImageJAI-litellm-proxy-log");
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * Reaps the exact process promoted to RUNNING and makes a later restart
     * possible if that child exits unexpectedly. The identity check prevents
     * a delayed watcher from clearing state owned by a replacement process.
     */
    private void startExitWatcher(final Process started) {
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    started.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronized (LiteLlmProxyService.this) {
                    if (process != started) {
                        return;
                    }
                    process = null;
                    ready = false;
                    if (!shutdown) {
                        lifecycle = Lifecycle.STOPPED;
                        IJ.log(LOG_PREFIX + " proxy sidecar exited");
                    }
                    // Keep cleanup under the lifecycle lock so startAsync()
                    // cannot publish a replacement while old runtime state is
                    // still being removed.
                    cleanupRuntimeState();
                }
            }
        }, "ImageJAI-litellm-proxy-exit");
        watcher.setDaemon(true);
        watcher.start();
    }

    // Package-private so LiteLlmProxyServiceTest can drive sentinel parsing
    // without spawning the real sidecar.
    void handleSidecarLine(String line) {
        if (line.startsWith(COST_PREFIX)) {
            String value = extractCostValue(line.substring(COST_PREFIX.length()));
            if (value != null) {
                notifyCostHeader(value);
            }
        }
        if (line.startsWith(BILLING_PREFIX)) {
            handleBillingLine(line.substring(BILLING_PREFIX.length()));
        }
        if (line.contains("[ImageJAI-LiteLLM]")) {
            IJ.log(line);
        }
    }

    private void handleBillingLine(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("status") || obj.get("status").isJsonNull()) {
                return;
            }
            int status = obj.get("status").getAsInt();
            String provider = obj.has("provider") && !obj.get("provider").isJsonNull()
                    ? obj.get("provider").getAsString()
                    : null;
            String message = obj.has("message") && !obj.get("message").isJsonNull()
                    ? obj.get("message").getAsString()
                    : null;
            notifyBillingFailure(provider, status, message);
        } catch (RuntimeException e) {
            // Malformed sentinel — never let a bad log line kill the pump.
        }
    }

    private void notifyBillingFailure(String provider, int status, String message) {
        for (BillingFailureListener listener : billingFailureListeners) {
            try {
                listener.onBillingFailure(provider, status, message);
            } catch (RuntimeException e) {
                IJ.log(LOG_PREFIX + " billing listener failed: " + e.getMessage());
            }
        }
    }

    private String extractCostValue(String json) {
        int marker = json.indexOf("\"cost\"");
        if (marker < 0) {
            return null;
        }
        int colon = json.indexOf(':', marker);
        int firstQuote = json.indexOf('"', colon + 1);
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (colon < 0 || firstQuote < 0 || secondQuote < 0) {
            return null;
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    private void notifyCostHeader(String value) {
        for (CostHeaderListener listener : costHeaderListeners) {
            try {
                listener.onCostHeader(value);
            } catch (RuntimeException e) {
                IJ.log(LOG_PREFIX + " cost listener failed: " + e.getMessage());
            }
        }
    }

    protected boolean waitHealthy(double timeoutSeconds) {
        long deadline = System.nanoTime() + (long) (timeoutSeconds * 1_000_000_000L);
        while (System.nanoTime() < deadline) {
            if (shutdown) return false;
            updatePortFromFile();
            updateAuthFromFile();
            if (readinessOk(port)) {
                return true;
            }
            int scanned = scanForLiveProxy();
            if (scanned > 0) {
                port = scanned;
                return true;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void terminateProcess(Process running) {
        if (running == null || !running.isAlive()) {
            return;
        }
        if (isWindows()) {
            try {
                String pid = processPid(running);
                if (pid != null) {
                    new ProcessBuilder("taskkill", "/PID", pid, "/T", "/F")
                            .start().waitFor();
                } else {
                    running.destroy();
                    if (!running.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        running.destroyForcibly();
                    }
                }
            } catch (Exception e) {
                running.destroyForcibly();
            }
        } else {
            running.destroy();
            try {
                if (!running.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    running.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.destroyForcibly();
            }
        }
    }

    String lifecycleStateForTest() {
        return lifecycle.name();
    }

    /**
     * Falls back to probing 4000-4010 when {@code proxy.port} is stale.
     *
     * Recovers the live proxy port if the file lists a port the sidecar no
     * longer occupies (Python-side crash mid-rewrite, leftover file from a
     * prior session, etc). Returns 0 when no live readiness endpoint is found
     * in the scan range.
     */
    private int scanForLiveProxy() {
        for (int candidate = PORT_SCAN_LO; candidate <= PORT_SCAN_HI; candidate++) {
            if (candidate == port) {
                continue;
            }
            if (readinessOk(candidate)) {
                return candidate;
            }
        }
        return 0;
    }

    private void updatePortFromFile() {
        Path providers = activeProvidersDir;
        if (providers == null) {
            return;
        }
        Path portFile = providers.resolve("proxy.port");
        if (!Files.isRegularFile(portFile)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(portFile), StandardCharsets.UTF_8).trim();
            int candidate = Integer.parseInt(text);
            if (candidate >= PORT_SCAN_LO && candidate <= PORT_SCAN_HI) {
                port = candidate;
            }
        } catch (Exception ignored) {
            port = DEFAULT_PORT;
        }
    }

    private boolean readinessOk(int candidatePort) {
        String key = masterKey;
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://localhost:" + candidatePort + "/health/readiness");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(600);
            connection.setReadTimeout(600);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void updateAuthFromFile() {
        Path providers = activeProvidersDir;
        if (providers == null) {
            return;
        }
        Path authFile = providers.resolve("proxy.auth");
        Path stateFile = providers.resolve("proxy.runtime.json");
        try {
            String key = new String(Files.readAllBytes(authFile), StandardCharsets.UTF_8).trim();
            if (!key.isEmpty()) {
                masterKey = key;
            }
            if (Files.isRegularFile(stateFile)) {
                JsonObject state = JsonParser.parseString(
                        new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (state.has("runtime_dir") && !state.get("runtime_dir").isJsonNull()) {
                    activeRuntimeDir = Paths.get(state.get("runtime_dir").getAsString())
                            .toAbsolutePath().normalize();
                }
            }
        } catch (Exception ignored) {
            // Startup polling retries until both private state files exist.
        }
    }

    private void cleanupRuntimeState() {
        Path providers = activeProvidersDir;
        String key = masterKey;
        Path runtimeDir = activeRuntimeDir;
        if (providers != null) {
            deleteIfContentMatches(providers.resolve("proxy.auth"), key);
            deleteIfContentMatches(providers.resolve("proxy.port"), Integer.toString(port));
            Path stateFile = providers.resolve("proxy.runtime.json");
            try {
                if (Files.isRegularFile(stateFile)) {
                    JsonObject state = JsonParser.parseString(
                            new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    String recorded = state.has("runtime_dir")
                            ? state.get("runtime_dir").getAsString() : "";
                    if (runtimeDir != null && runtimeDir.toString().equals(recorded)) {
                        Files.deleteIfExists(stateFile);
                    }
                }
            } catch (Exception ignored) {
                // A stale state file is safer than deleting an unverified path.
            }
        }
        deleteVerifiedRuntimeDirectory(runtimeDir);
        activeRuntimeDir = null;
        masterKey = null;
    }

    private void deleteIfContentMatches(Path path, String expected) {
        if (path == null || expected == null || expected.isEmpty()) {
            return;
        }
        try {
            String actual = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
            if (expected.equals(actual)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Ownership could not be proven; leave the file in place.
        }
    }

    private void deleteVerifiedRuntimeDirectory(Path candidate) {
        if (candidate == null) {
            return;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        Path tempRoot = Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path name = normalized.getFileName();
        if (parent == null || name == null || !parent.equals(tempRoot)
                || !name.toString().startsWith("imagejai-litellm-")) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup after the child process has stopped.
                }
            });
        } catch (IOException ignored) {
            // Already removed by Python's atexit handler or never published.
        }
    }

    private String pythonCommand() {
        String configured = System.getenv("IMAGEJAI_PYTHON");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return isWindows() ? "python" : "python3";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String processPid(Process process) {
        try {
            java.lang.reflect.Method method = Process.class.getMethod("pid");
            Object value = method.invoke(process);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
