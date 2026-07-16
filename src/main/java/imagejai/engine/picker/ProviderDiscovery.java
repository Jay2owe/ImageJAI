package imagejai.engine.picker;

import imagejai.engine.LaunchPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fan-out over the fifteen provider {@code /models} endpoints listed in
 * docs/multi_provider/02_curation_strategy.md §6, returning a
 * {@link MergeFunction.LiveResult} per provider so the merge layer can decide
 * whether to soft-deprecate or just fall back to cache.
 *
 * <p>Two providers are deliberately not contacted: Ollama Cloud and Perplexity
 * have no public listing endpoint, so callers must treat their curated entries
 * as authoritative (per 02 §6).
 *
 * <p>Phase G timeouts:
 * <ul>
 *   <li>5 s on cold-start fetch (cache miss, dropdown first opens)</li>
 *   <li>4 s on user-initiated refresh (the dropdown's ↻ button)</li>
 * </ul>
 */
public final class ProviderDiscovery {

    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    public static final int MAX_MODEL_IDS = 2000;
    public static final int MAX_TIMEOUT_MS = 10000;
    public static final int MAX_CONCURRENT_DISCOVERIES = 4;
    public static final int MAX_DISCOVER_ALL_MS = 30000;

    /** Endpoint metadata for one provider. */
    public static final class Endpoint {
        private static final Pattern CREDENTIAL_QUERY = Pattern.compile(
                "(?i)(?:[?&])(?:key|api_?key|token|access_token|secret|password)=");
        private final String providerId;
        private final String url;
        private final Map<String, String> headers;

        public Endpoint(String providerId, String url, Map<String, String> headers) {
            this.providerId = LaunchPolicy.requireProviderId(providerId);
            String candidateUrl = url == null ? "" : url.trim();
            if (candidateUrl.isEmpty() || CREDENTIAL_QUERY.matcher(candidateUrl).find()) {
                throw new IllegalArgumentException("Provider endpoint must not contain credentials.");
            }
            try {
                URI uri = new URI(candidateUrl);
                if (uri.getUserInfo() != null) {
                    throw new IllegalArgumentException(
                            "Provider endpoint must not contain credentials.");
                }
            } catch (URISyntaxException invalid) {
                throw new IllegalArgumentException("Provider endpoint is invalid.");
            }
            this.url = candidateUrl;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                    headers == null ? Collections.<String, String>emptyMap() : headers));
        }

        public String providerId() { return providerId; }
        public String url() { return url; }
        public Map<String, String> headers() { return headers; }
    }

    /** Functional interface so tests can swap the HTTP layer for a mock. */
    @FunctionalInterface
    public interface HttpFetcher {
        HttpResult fetch(Endpoint endpoint, Duration timeout);

        final class HttpResult {
            public final int status;
            public final String body;
            public final IOException error;

            public HttpResult(int status, String body) {
                this.status = status;
                this.body = body;
                this.error = null;
            }
            public HttpResult(IOException error) {
                this.status = -1;
                this.body = "";
                this.error = error;
            }
            public boolean ok() { return error == null && status >= 200 && status < 300; }
        }
    }

    /** Provider keys that have no live endpoint per 02 §6. */
    public static final Set<String> CURATED_ONLY;
    static {
        Set<String> s = new LinkedHashSet<String>();
        s.add("ollama-cloud");
        s.add("perplexity");
        CURATED_ONLY = Collections.unmodifiableSet(s);
    }

    /** Build the canonical endpoint table. Public for the tests to assert against. */
    public static Map<String, Endpoint> defaultEndpoints(Map<String, String> credentials) {
        if (credentials == null) credentials = Collections.emptyMap();
        Map<String, Endpoint> out = new LinkedHashMap<String, Endpoint>();
        out.put("ollama", new Endpoint("ollama",
                "http://localhost:11434/api/tags",
                Collections.<String, String>emptyMap()));
        out.put("openai", new Endpoint("openai",
                "https://api.openai.com/v1/models",
                authBearer(credentials.get("openai"))));
        out.put("anthropic", new Endpoint("anthropic",
                "https://api.anthropic.com/v1/models",
                anthropicHeaders(credentials.get("anthropic"))));
        out.put("gemini", new Endpoint("gemini",
                "https://generativelanguage.googleapis.com/v1beta/models",
                googleHeaders(credentials.get("gemini"))));
        out.put("groq", new Endpoint("groq",
                "https://api.groq.com/openai/v1/models",
                authBearer(credentials.get("groq"))));
        out.put("cerebras", new Endpoint("cerebras",
                "https://api.cerebras.ai/v1/models",
                authBearer(credentials.get("cerebras"))));
        out.put("openrouter", new Endpoint("openrouter",
                "https://openrouter.ai/api/v1/models",
                Collections.<String, String>emptyMap()));
        out.put("github-models", new Endpoint("github-models",
                "https://models.github.ai/catalog/models",
                authBearer(credentials.get("github-models"))));
        out.put("mistral", new Endpoint("mistral",
                "https://api.mistral.ai/v1/models",
                authBearer(credentials.get("mistral"))));
        out.put("together", new Endpoint("together",
                "https://api.together.xyz/v1/models",
                authBearer(credentials.get("together"))));
        out.put("huggingface", new Endpoint("huggingface",
                "https://router.huggingface.co/v1/models",
                authBearer(credentials.get("huggingface"))));
        out.put("deepseek", new Endpoint("deepseek",
                "https://api.deepseek.com/v1/models",
                authBearer(credentials.get("deepseek"))));
        out.put("xai", new Endpoint("xai",
                "https://api.x.ai/v1/models",
                authBearer(credentials.get("xai"))));
        // Keyless local OpenAI-compatible servers. They expose /v1/models on a
        // local port; whatever model the user has loaded is auto-discovered.
        // The base URL is overridable via the matching *_API_BASE credential.
        out.put("lmstudio", new Endpoint("lmstudio",
                localBase(credentials.get("lmstudio"), "http://localhost:1234") + "/v1/models",
                Collections.<String, String>emptyMap()));
        out.put("jan", new Endpoint("jan",
                localBase(credentials.get("jan"), "http://localhost:1337") + "/v1/models",
                Collections.<String, String>emptyMap()));
        out.put("llamacpp", new Endpoint("llamacpp",
                localBase(credentials.get("llamacpp"), "http://localhost:8080") + "/v1/models",
                Collections.<String, String>emptyMap()));
        out.put("vllm", new Endpoint("vllm",
                localBase(credentials.get("vllm"), "http://localhost:8000") + "/v1/models",
                Collections.<String, String>emptyMap()));
        return Collections.unmodifiableMap(out);
    }

    /**
     * Normalise a keyless local server's base URL to its host root so a
     * {@code /v1/models} path can be appended cleanly. Accepts a saved override
     * (which may already end in {@code /v1}) or falls back to the default host.
     */
    private static String localBase(String override, String defaultHost) {
        String base = (override == null || override.trim().isEmpty())
                ? defaultHost
                : override.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - "/v1".length());
        }
        return base;
    }

    private static Map<String, String> authBearer(String key) {
        if (key == null || key.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Bearer " + key);
        return h;
    }

    private static Map<String, String> anthropicHeaders(String key) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (key != null && !key.isEmpty()) {
            h.put("x-api-key", key);
        }
        h.put("anthropic-version", "2023-06-01");
        return h;
    }

    private static Map<String, String> googleHeaders(String key) {
        if (key == null || key.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("x-goog-api-key", key);
        return h;
    }

    private final Map<String, Endpoint> endpoints;
    private final HttpFetcher fetcher;
    private final Map<String, String> lastErrors = new ConcurrentHashMap<String, String>();

    public ProviderDiscovery(Map<String, Endpoint> endpoints, HttpFetcher fetcher) {
        this.endpoints = endpoints == null
                ? Collections.<String, Endpoint>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Endpoint>(endpoints));
        this.fetcher = fetcher == null ? defaultFetcher() : fetcher;
    }

    public Map<String, Endpoint> endpoints() {
        return endpoints;
    }

    /**
     * Most recent error string for {@code providerId}, or {@code null} if the
     * last {@link #discover(String, Duration)} call succeeded (or has not run).
     *
     * <p>Cleared on every successful call. Surfaced so {@code AiRootPanel} can
     * persist the failure reason via {@code Settings.setLastError} — which is
     * what {@code CachedErrorDialog} renders when the user clicks the ✗ status
     * icon. Without this method the {@code Settings.lastProviderErrors} map
     * stayed empty in production and the cached-error dialog had no body to
     * show.
     */
    public String lastErrorFor(String providerId) {
        return lastErrors.get(providerId);
    }

    /** Fetch a single provider, parse the response, return a merge-ready result. */
    public MergeFunction.LiveResult discover(String providerId, Duration timeout) {
        if (CURATED_ONLY.contains(providerId)) {
            // Curated-only providers never produce a discovery error — clear
            // any stale message left from a prior misconfiguration.
            lastErrors.remove(providerId);
            return MergeFunction.LiveResult.failure();
        }
        Endpoint endpoint = endpoints.get(providerId);
        if (endpoint == null) {
            String reason = "no endpoint configured for provider " + providerId;
            lastErrors.put(providerId, reason);
            return MergeFunction.LiveResult.failure(reason);
        }
        HttpFetcher.HttpResult response;
        try {
            response = fetcher.fetch(endpoint,
                    Duration.ofMillis(timeoutMillis(timeout)));
        } catch (Throwable failure) {
            String reason = "discovery failed (" + failure.getClass().getSimpleName() + ")";
            lastErrors.put(providerId, reason);
            return MergeFunction.LiveResult.failure(reason);
        }
        if (response == null) {
            String reason = "discovery returned no response";
            lastErrors.put(providerId, reason);
            return MergeFunction.LiveResult.failure(reason);
        }
        if (!response.ok()) {
            String reason = describeFailure(response, endpoint);
            lastErrors.put(providerId, reason);
            return MergeFunction.LiveResult.failure(reason);
        }
        if (response.body != null && (response.body.length() > MAX_RESPONSE_BYTES
                || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES)) {
            String reason = "provider response exceeded " + MAX_RESPONSE_BYTES + " byte limit";
            lastErrors.put(providerId, reason);
            return MergeFunction.LiveResult.failure(reason);
        }
        lastErrors.remove(providerId);
        Set<String> ids = parseModelIds(providerId, response.body);
        return MergeFunction.LiveResult.success(ids);
    }

    private static String describeFailure(HttpFetcher.HttpResult response, Endpoint endpoint) {
        if (response.error != null) {
            String message = redactSecrets(response.error.getMessage(), endpoint);
            String name = response.error.getClass().getSimpleName();
            return message == null || message.isEmpty()
                    ? name
                    : name + ": " + message;
        }
        String body = redactSecrets(response.body, endpoint).trim();
        if (body.length() > 240) {
            body = body.substring(0, 240) + "…";
        }
        if (body.isEmpty()) {
            return "HTTP " + response.status + " from provider";
        }
        return "HTTP " + response.status + " — " + body;
    }

    private static String redactSecrets(String text, Endpoint endpoint) {
        String safe = text == null ? "" : text;
        if (endpoint != null) {
            for (String value : endpoint.headers().values()) {
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                safe = safe.replace(value, "[REDACTED]");
                if (value.startsWith("Bearer ") && value.length() > "Bearer ".length()) {
                    safe = safe.replace(value.substring("Bearer ".length()), "[REDACTED]");
                }
            }
        }
        return safe.replaceAll(
                "(?i)([?&](?:key|api_?key|token|access_token|secret|password)=)[^&\\s]+",
                "$1[REDACTED]");
    }

    /**
     * Discover every provider in {@link #endpoints()} sequentially with the
     * given per-provider timeout. Production code should fan out across a
     * thread pool — Phase G uses sequential to keep test seams simple; the
     * wiring in {@link ProviderRegistry} parallelises explicitly.
     */
    public Map<String, MergeFunction.LiveResult> discoverAll(Duration timeout) {
        final List<String> providerIds = new ArrayList<String>(endpoints.keySet());
        Map<String, MergeFunction.LiveResult> out =
                new LinkedHashMap<String, MergeFunction.LiveResult>();
        if (providerIds.isEmpty()) return out;
        int threads = Math.min(MAX_CONCURRENT_DISCOVERIES, providerIds.size());
        ExecutorService executor = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private int sequence;
            @Override public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task,
                        "imagejai-provider-discovery-" + (++sequence));
                thread.setDaemon(true);
                return thread;
            }
        });
        List<Callable<MergeFunction.LiveResult>> tasks =
                new ArrayList<Callable<MergeFunction.LiveResult>>();
        for (final String providerId : providerIds) {
            tasks.add(new Callable<MergeFunction.LiveResult>() {
                @Override public MergeFunction.LiveResult call() {
                    return discover(providerId, timeout);
                }
            });
        }
        int perProviderMs = timeoutMillis(timeout);
        long waves = (providerIds.size() + threads - 1L) / threads;
        long totalMs = Math.min(MAX_DISCOVER_ALL_MS, perProviderMs * waves);
        try {
            List<Future<MergeFunction.LiveResult>> futures =
                    executor.invokeAll(tasks, Math.max(1L, totalMs), TimeUnit.MILLISECONDS);
            for (int i = 0; i < providerIds.size(); i++) {
                String providerId = providerIds.get(i);
                Future<MergeFunction.LiveResult> future = futures.get(i);
                if (future.isCancelled()) {
                    String reason = "discovery timed out";
                    lastErrors.put(providerId, reason);
                    out.put(providerId, MergeFunction.LiveResult.failure(reason));
                } else {
                    try {
                        out.put(providerId, future.get());
                    } catch (Exception failure) {
                        String reason = "discovery failed ("
                                + failure.getClass().getSimpleName() + ")";
                        lastErrors.put(providerId, reason);
                        out.put(providerId, MergeFunction.LiveResult.failure(reason));
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            for (String providerId : providerIds) {
                String reason = "discovery cancelled";
                lastErrors.put(providerId, reason);
                out.put(providerId, MergeFunction.LiveResult.failure(reason));
            }
        } finally {
            executor.shutdownNow();
        }
        return out;
    }

    static Set<String> parseModelIds(String providerId, String body) {
        Set<String> out = new LinkedHashSet<String>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        if ("ollama".equals(providerId)) {
            // {"models": [{"name": "llama3.2:3b"}, ...]} — Ollama-specific shape.
            for (String name : extractKeyFromArray(body, "name")) {
                out.add(name);
                if (out.size() >= MAX_MODEL_IDS) break;
            }
            return out;
        }
        if ("github-models".equals(providerId)) {
            // [{"name": "...", "publisher": "..."}, ...] — flat array.
            List<String> names = extractKeyFromArray(body, "name");
            List<String> publishers = extractKeyFromArray(body, "publisher");
            for (int i = 0; i < names.size(); i++) {
                String publisher = i < publishers.size() ? publishers.get(i) : "";
                out.add(publisher.isEmpty() ? names.get(i) : publisher + "/" + names.get(i));
                if (out.size() >= MAX_MODEL_IDS) break;
            }
            return out;
        }
        if ("gemini".equals(providerId)) {
            for (String name : extractKeyFromArray(body, "name")) {
                String stripped = name.startsWith("models/") ? name.substring("models/".length()) : name;
                out.add(stripped);
                if (out.size() >= MAX_MODEL_IDS) break;
            }
            return out;
        }
        // Default: OpenAI shape — {"data": [{"id": "..."}, ...]}.
        for (String id : extractKeyFromArray(body, "id")) {
            out.add(id);
            if (out.size() >= MAX_MODEL_IDS) break;
        }
        return out;
    }

    private static List<String> extractKeyFromArray(String body, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(body);
        List<String> out = new java.util.ArrayList<String>();
        while (m.find()) {
            out.add(m.group(1));
            if (out.size() >= MAX_MODEL_IDS) break;
        }
        return out;
    }

    public static HttpFetcher defaultFetcher() {
        return new HttpFetcher() {
            @Override
            public HttpResult fetch(Endpoint endpoint, Duration timeout) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(endpoint.url()).openConnection();
                    int timeoutMs = timeoutMillis(timeout);
                    // Never forward a credential header to a redirect target.
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(timeoutMs);
                    connection.setReadTimeout(timeoutMs);
                    connection.setRequestMethod("GET");
                    for (Map.Entry<String, String> h : endpoint.headers().entrySet()) {
                        connection.setRequestProperty(h.getKey(), h.getValue());
                    }
                    int status = connection.getResponseCode();
                    InputStream stream = status >= 400
                            ? connection.getErrorStream()
                            : connection.getInputStream();
                    return new HttpResult(status, readAll(stream));
                } catch (IOException ex) {
                    return new HttpResult(ex);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        };
    }

    private static int timeoutMillis(Duration timeout) {
        long millis = timeout == null ? 5000L : timeout.toMillis();
        if (millis <= 0L) {
            return 5000;
        }
        return millis > MAX_TIMEOUT_MS ? MAX_TIMEOUT_MS : (int) millis;
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream input = stream) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                if (out.size() + n > MAX_RESPONSE_BYTES) {
                    throw new IOException("provider response exceeded "
                            + MAX_RESPONSE_BYTES + " byte limit");
                }
                out.write(buffer, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
