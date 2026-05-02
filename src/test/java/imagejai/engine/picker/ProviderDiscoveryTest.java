package imagejai.engine.picker;

import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
        creds.put("anthropic", "sk-ant-test");
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(creds);
        ProviderDiscovery.Endpoint anthropic = endpoints.get("anthropic");
        assertNotNull(anthropic);
        assertEquals("https://api.anthropic.com/v1/models", anthropic.url());
        assertEquals("sk-ant-test", anthropic.headers().get("x-api-key"));
        assertEquals("2023-06-01", anthropic.headers().get("anthropic-version"));
    }

    @Test
    public void geminiEndpointEmbedsKeyInQueryString() {
        Map<String, String> creds = new LinkedHashMap<String, String>();
        creds.put("gemini", "AIza-test");
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(creds);
        assertTrue(endpoints.get("gemini").url().endsWith("?key=AIza-test"));
    }

    @Test
    public void groqAndCerebrasUseBearerAuth() {
        Map<String, String> creds = new LinkedHashMap<String, String>();
        creds.put("groq", "gsk_test");
        creds.put("cerebras", "cer-test");
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(creds);
        assertEquals("Bearer gsk_test",
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
                Collections.singletonMap("Authorization", "Bearer sk-test")));
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
                "https://generativelanguage.googleapis.com/v1beta/models?key=x",
                Collections.<String, String>emptyMap()));
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
    public void allFifteenProvidersPlannedForButCuratedOnlyTwoAreSkipped() {
        // 13 endpoints + 2 curated-only = 15 providers per 02 §6.
        Map<String, ProviderDiscovery.Endpoint> endpoints =
                ProviderDiscovery.defaultEndpoints(Collections.<String, String>emptyMap());
        assertEquals(13, endpoints.size());
        assertEquals(2, ProviderDiscovery.CURATED_ONLY.size());
    }
}
