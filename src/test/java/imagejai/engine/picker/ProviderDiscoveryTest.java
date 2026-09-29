package imagejai.engine.picker;

import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase G acceptance — docs/multi_provider/02_curation_strategy.md §6.
 *
 * <p>Verifies endpoint URLs + auth headers per provider, and that the two
 * providers without a public {@code /models} listing (Ollama Cloud,
 * Perplexity) are skipped at the registry level.
 */
public class ProviderDiscoveryTest {

    @Test
    public void anthropicEndpointHasCorrectAuthHeaders() {
        Map<String, String> creds = new LinkedHashMap<String, String>();
        creds.put("anthropic", "anthropic-test-key");
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(creds);
        ProviderDiscovery.Endpoint anthropic = endpoints.get("anthropic");
        assertNotNull(anthropic);
        assertEquals("https://api.anthropic.com/v1/models", anthropic.url());
        assertEquals("anthropic-test-key", anthropic.headers().get("x-api-key"));
        assertEquals("2023-06-01", anthropic.headers().get("anthropic-version"));
    }

    @Test
    public void geminiEndpointKeepsKeyInHeaderOnly() {
        Map<String, String> creds = new LinkedHashMap<String, String>();
        creds.put("gemini", "gemini-test-key");
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(creds);
        ProviderDiscovery.Endpoint endpoint = endpoints.get("gemini");
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models",
                endpoint.url());
        assertEquals("gemini-test-key", endpoint.headers().get("x-goog-api-key"));
        assertFalse(endpoint.url().contains("gemini-test-key"));
    }

    @Test
    public void groqAndCerebrasUseBearerAuth() {
        Map<String, String> creds = new LinkedHashMap<String, String>();
        creds.put("groq", "groq-test-key");
        creds.put("cerebras", "cer-test");
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(creds);
        assertEquals("Bearer groq-test-key",
                endpoints.get("groq").headers().get("Authorization"));
        assertEquals("Bearer cer-test",
                endpoints.get("cerebras").headers().get("Authorization"));
    }

    @Test
    public void ollamaCloudAndPerplexityAreCuratedOnly() {
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(Collections.<String, String>emptyMap());
        assertFalse("ollama-cloud must not have a discovery endpoint",
                endpoints.containsKey("ollama-cloud"));
        assertFalse("perplexity must not have a discovery endpoint",
                endpoints.containsKey("perplexity"));
        assertTrue(ProviderDiscovery.CURATED_ONLY.contains("ollama-cloud"));
        assertTrue(ProviderDiscovery.CURATED_ONLY.contains("perplexity"));
    }

    @Test
    public void discoverParsesOpenAIShapedResponse() {
        Map<String, ProviderDiscovery.Endpoint> endpoints = new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("openai", new ProviderDiscovery.Endpoint("openai",
                "https://api.openai.com/v1/models",
                Collections.singletonMap("Authorization", "Bearer openai-test-key")));
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(200,
                        "{\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"gpt-5-mini\"}]}");

        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, fetcher);
        MergeFunction.LiveResult result = discovery.discover("openai", Duration.ofSeconds(4));
        assertTrue(result.successful());
        assertTrue(result.modelIds().contains("gpt-5"));
        assertTrue(result.modelIds().contains("gpt-5-mini"));
    }

    @Test
    public void discoverHandlesOllamaShape() {
        Map<String, ProviderDiscovery.Endpoint> endpoints = new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("ollama", new ProviderDiscovery.Endpoint("ollama",
                "http://localhost:11434/api/tags",
                Collections.<String, String>emptyMap()));
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(200,
                        "{\"models\":[{\"name\":\"llama3.2:3b\"},{\"name\":\"qwen2.5:7b\"}]}");
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, fetcher);
        MergeFunction.LiveResult result = discovery.discover("ollama", Duration.ofSeconds(4));
        assertTrue(result.successful());
        assertEquals(2, result.modelIds().size());
        assertTrue(result.modelIds().contains("llama3.2:3b"));
    }

    @Test
    public void discoverHandlesGeminiModelsPathPrefix() {
        Map<String, ProviderDiscovery.Endpoint> endpoints = new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("gemini", new ProviderDiscovery.Endpoint("gemini",
                "https://generativelanguage.googleapis.com/v1beta/models",
                Collections.singletonMap("x-goog-api-key", "x")));
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(200,
                        "{\"models\":[{\"name\":\"models/gemini-2.5-pro\"}]}");
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, fetcher);
        MergeFunction.LiveResult result = discovery.discover("gemini", Duration.ofSeconds(4));
        assertTrue(result.successful());
        assertTrue(result.modelIds().contains("gemini-2.5-pro"));
    }

    @Test
    public void discoverReturnsFailureOnNon2xx() {
        Map<String, ProviderDiscovery.Endpoint> endpoints = new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("xai", new ProviderDiscovery.Endpoint("xai",
                "https://api.x.ai/v1/models",
                Collections.<String, String>emptyMap()));
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(503, "Service Unavailable");
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, fetcher);
        MergeFunction.LiveResult result = discovery.discover("xai", Duration.ofSeconds(4));
        assertFalse("5xx must produce failure() so MergeFunction does not "
                + "soft-deprecate (risk #10 in 07 §4)", result.successful());
    }

    @Test
    public void discoverReturnsFailureOnIOException() {
        Map<String, ProviderDiscovery.Endpoint> endpoints = new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("openrouter", new ProviderDiscovery.Endpoint("openrouter",
                "https://openrouter.ai/api/v1/models",
                Collections.<String, String>emptyMap()));
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(new IOException("connect timed out"));
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, fetcher);
        MergeFunction.LiveResult result = discovery.discover("openrouter", Duration.ofSeconds(4));
        assertFalse(result.successful());
    }

    @Test
    public void curatedOnlyProvidersAlwaysReturnFailure() {
        // No endpoint registered for ollama-cloud, but caller might still
        // request it — we expect a clean failure() rather than NPE.
        ProviderDiscovery discovery = new ProviderDiscovery(
                Collections.<String, ProviderDiscovery.Endpoint>emptyMap(),
                (endpoint, timeout) -> new ProviderDiscovery.HttpFetcher.HttpResult(200, ""));
        assertFalse(discovery.discover("ollama-cloud", Duration.ofSeconds(4)).successful());
        assertFalse(discovery.discover("perplexity", Duration.ofSeconds(4)).successful());
    }

    @Test
    public void allProvidersPlannedForButCuratedOnlyTwoAreSkipped() {
        // 13 cloud + 4 keyless local servers = 17 live endpoints; ollama-cloud
        // and perplexity remain curated-only (no live listing).
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(Collections.<String, String>emptyMap());
        assertEquals(17, endpoints.size());
        assertEquals(2, ProviderDiscovery.CURATED_ONLY.size());
        // The four keyless local OpenAI-compatible servers DO have live endpoints.
        assertTrue(endpoints.containsKey("lmstudio"));
        assertTrue(endpoints.containsKey("jan"));
        assertTrue(endpoints.containsKey("llamacpp"));
        assertTrue(endpoints.containsKey("vllm"));
    }

    @Test
    public void lastErrorFor_capturesIoExceptionMessage() {
        Map<String, ProviderDiscovery.Endpoint> endpoints = new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("xai", new ProviderDiscovery.Endpoint("xai",
                "https://api.x.ai/v1/models",
                Collections.<String, String>emptyMap()));
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(new IOException("connect timed out"));
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, fetcher);
        discovery.discover("xai", Duration.ofSeconds(4));
        String reason = discovery.lastErrorFor("xai");
        assertNotNull("error string should be populated after a failed discover", reason);
        assertTrue("expected IOException class name in error: " + reason,
                reason.contains("IOException"));
        assertTrue("expected error message in error: " + reason,
                reason.contains("connect timed out"));
    }

    @Test
    public void lastErrorFor_capturesHttp4xxBody() {
        Map<String, ProviderDiscovery.Endpoint> endpoints = new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("openai", new ProviderDiscovery.Endpoint("openai",
                "https://api.openai.com/v1/models",
                Collections.<String, String>emptyMap()));
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(401,
                        "{\"error\": {\"message\": \"invalid api key\"}}");
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, fetcher);
        discovery.discover("openai", Duration.ofSeconds(4));
        String reason = discovery.lastErrorFor("openai");
        assertNotNull(reason);
        assertTrue("expected HTTP 401 in error: " + reason,
                reason.contains("401"));
        assertTrue("expected upstream message in error: " + reason,
                reason.contains("invalid api key"));
    }

    @Test
    public void lastErrorRedactsCredentialEchoes() {
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("gemini", new ProviderDiscovery.Endpoint("gemini",
                "https://generativelanguage.googleapis.com/v1beta/models",
                Collections.singletonMap("x-goog-api-key", "super-secret-key")));
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints,
                (endpoint, timeout) -> new ProviderDiscovery.HttpFetcher.HttpResult(
                        new IOException("request rejected: super-secret-key")));

        discovery.discover("gemini", Duration.ofSeconds(1));

        assertFalse(discovery.lastErrorFor("gemini").contains("super-secret-key"));
        assertTrue(discovery.lastErrorFor("gemini").contains("[REDACTED]"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void endpointRejectsCredentialBearingUrl() {
        new ProviderDiscovery.Endpoint("gemini",
                "https://example.invalid/models?key=secret",
                Collections.<String, String>emptyMap());
    }

    @Test(expected = IllegalArgumentException.class)
    public void endpointRejectsUserInfoCredentials() {
        new ProviderDiscovery.Endpoint("openai",
                "https://user:secret@example.invalid/models",
                Collections.<String, String>emptyMap());
    }

    @Test
    public void lastErrorFor_clearsOnSuccess() {
        Map<String, ProviderDiscovery.Endpoint> endpoints = new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("groq", new ProviderDiscovery.Endpoint("groq",
                "https://api.groq.com/openai/v1/models",
                Collections.<String, String>emptyMap()));
        // First call fails, second succeeds — second must clear the cached
        // error so the ✗ icon reverts to ✓ on the next refresh.
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return new ProviderDiscovery.HttpFetcher.HttpResult(503, "service unavailable");
            }
            return new ProviderDiscovery.HttpFetcher.HttpResult(200,
                    "{\"data\": [{\"id\": \"llama-3.3-70b-versatile\"}]}");
        };
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, fetcher);
        discovery.discover("groq", Duration.ofSeconds(4));
        assertNotNull(discovery.lastErrorFor("groq"));
        discovery.discover("groq", Duration.ofSeconds(4));
        assertNull("success must clear the cached error",
                discovery.lastErrorFor("groq"));
    }

    @Test
    public void lastErrorFor_curatedOnlyProvidersStayClear() {
        ProviderDiscovery discovery = new ProviderDiscovery(
                Collections.<String, ProviderDiscovery.Endpoint>emptyMap(),
                (endpoint, timeout) -> new ProviderDiscovery.HttpFetcher.HttpResult(200, ""));
        discovery.discover("ollama-cloud", Duration.ofSeconds(4));
        assertNull("curated-only providers never produce a discovery error",
                discovery.lastErrorFor("ollama-cloud"));
    }

    @Test
    public void oversizedResponseIsRejectedAndObservable() {
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("groq", endpoint("groq"));
        char[] payload = new char[ProviderDiscovery.MAX_RESPONSE_BYTES + 1];
        java.util.Arrays.fill(payload, 'x');
        String oversized = new String(payload);
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints,
                (endpoint, timeout) ->
                        new ProviderDiscovery.HttpFetcher.HttpResult(200, oversized));

        MergeFunction.LiveResult result =
                discovery.discover("groq", Duration.ofSeconds(4));

        assertFalse(result.successful());
        assertTrue(discovery.lastErrorFor("groq").contains("exceeded"));
    }

    @Test
    public void parsedModelIdsAreCapped() {
        StringBuilder body = new StringBuilder("{\"data\":[");
        for (int i = 0; i < ProviderDiscovery.MAX_MODEL_IDS + 50; i++) {
            if (i > 0) body.append(',');
            body.append("{\"id\":\"model-").append(i).append("\"}");
        }
        body.append("]}");
        assertEquals(ProviderDiscovery.MAX_MODEL_IDS,
                ProviderDiscovery.parseModelIds("groq", body.toString()).size());
    }

    @Test
    public void discoveryTimeoutPassedToFetcherIsCapped() {
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        endpoints.put("groq", endpoint("groq"));
        AtomicReference<Duration> seen = new AtomicReference<Duration>();
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, (endpoint, timeout) -> {
            seen.set(timeout);
            return new ProviderDiscovery.HttpFetcher.HttpResult(200, "{\"data\":[]}");
        });
        discovery.discover("groq", Duration.ofDays(1));
        assertEquals(ProviderDiscovery.MAX_TIMEOUT_MS, seen.get().toMillis());
    }

    @Test
    public void discoverAllUsesBoundedConcurrentFanout() {
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                new LinkedHashMap<String, ProviderDiscovery.Endpoint>();
        for (String id : new String[] {"groq", "xai", "mistral", "openai"}) {
            endpoints.put(id, endpoint(id));
        }
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        ProviderDiscovery discovery = new ProviderDiscovery(endpoints, (endpoint, timeout) -> {
            int now = active.incrementAndGet();
            maximum.accumulateAndGet(now, Math::max);
            try { Thread.sleep(150L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
            return new ProviderDiscovery.HttpFetcher.HttpResult(200,
                    "{\"data\":[{\"id\":\"one\"}]}");
        });
        long start = System.nanoTime();
        Map<String, MergeFunction.LiveResult> results =
                discovery.discoverAll(Duration.ofSeconds(1));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(4, results.size());
        assertTrue(maximum.get() > 1);
        assertTrue("fanout was effectively sequential: " + elapsedMs, elapsedMs < 500L);
    }

    private static ProviderDiscovery.Endpoint endpoint(String providerId) {
        return new ProviderDiscovery.Endpoint(providerId,
                "https://example.invalid/models", Collections.<String, String>emptyMap());
    }

    @Test
    public void corruptUserModelOverridesExposeLoadError() throws Exception {
        Path file = Files.createTempDirectory("models-local-corrupt")
                .resolve("models_local.yaml");
        Files.write(file, "overrides: [unterminated".getBytes(StandardCharsets.UTF_8));
        ModelsLocalLoader loader = new ModelsLocalLoader(file);

        assertTrue(loader.load().isEmpty());
        assertFalse(loader.lastError().isEmpty());
    }
}
