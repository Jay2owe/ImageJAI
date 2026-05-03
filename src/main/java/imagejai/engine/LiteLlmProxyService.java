package imagejai.engine;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Starts and supervises the bundled LiteLLM Proxy Python sidecar.
 */
public class LiteLlmProxyService {
    private static final String LOG_PREFIX = "[ImageJAI-LiteLLM]";
    private static final String COST_PREFIX = "[ImageJAI-LiteLLM-Cost] ";
    private static final int DEFAULT_PORT = 4000;
    private static final int PORT_SCAN_LO = 4000;
    private static final int PORT_SCAN_HI = 4010;

    private final Path agentWorkspace;
    private final List<CostHeaderListener> costHeaderListeners =
            new CopyOnWriteArrayList<CostHeaderListener>();

    private volatile Process process;
    private volatile Path activeProvidersDir;
    private volatile int port = DEFAULT_PORT;
    private volatile boolean shutdown;

    public LiteLlmProxyService(String agentWorkspace) {
        this.agentWorkspace = agentWorkspace != null
                ? Paths.get(agentWorkspace).toAbsolutePath()
                : null;
    }

    public synchronized void startAsync() {
        if (process != null && process.isAlive()) {
            return;
        }
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

    public int getPort() {
        return port;
    }

    public synchronized void shutdown() {
        shutdown = true;
        Process running = process;
        if (running == null || !running.isAlive()) {
            return;
        }
        IJ.log(LOG_PREFIX + " stopping proxy sidecar");
        if (isWindows()) {
            try {
                String pid = processPid(running);
                if (pid != null) {
                    new ProcessBuilder("taskkill", "/PID", pid, "/T", "/F").start().waitFor();
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
                if (running.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.destroyForcibly();
        }
    }

    private void startBlocking() {
        try {
            Path providersDir = resolveProvidersDir();
            activeProvidersDir = providersDir;
            Path proxy = providersDir.resolve("proxy.py");
            Path config = providersDir.resolve("litellm.config.yaml");
            ProcessBuilder builder = new ProcessBuilder(
                    pythonCommand(), proxy.toString(), "--start", "--config", config.toString());
            builder.directory(providersDir.getParent().getParent().toFile());
            builder.redirectErrorStream(true);
            Process started = builder.start();
            process = started;
            startOutputPump(started);

            boolean ready = waitHealthy(8.0);
            if (ready) {
                IJ.log(LOG_PREFIX + " proxy ready on localhost:" + port);
            } else if (!shutdown) {
                IJ.log(LOG_PREFIX + " proxy did not become ready within 8s");
            }
        } catch (Exception e) {
            IJ.log(LOG_PREFIX + " failed to start proxy: " + e.getMessage());
        }
    }

    private Path resolveProvidersDir() throws IOException {
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

    private void handleSidecarLine(String line) {
        if (line.startsWith(COST_PREFIX)) {
            String value = extractCostValue(line.substring(COST_PREFIX.length()));
            if (value != null) {
                notifyCostHeader(value);
            }
        }
        if (line.contains("[ImageJAI-LiteLLM]")) {
            IJ.log(line);
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

    private boolean waitHealthy(double timeoutSeconds) {
        long deadline = System.nanoTime() + (long) (timeoutSeconds * 1_000_000_000L);
        while (System.nanoTime() < deadline) {
            updatePortFromFile();
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
            port = Integer.parseInt(text);
        } catch (Exception ignored) {
            port = DEFAULT_PORT;
        }
    }

    private boolean readinessOk(int candidatePort) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://localhost:" + candidatePort + "/health/readiness");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(600);
            connection.setReadTimeout(600);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
