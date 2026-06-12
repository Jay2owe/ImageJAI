package imagejai.ui.installer;

import org.junit.Test;
import imagejai.engine.picker.ProviderDiscovery;
import imagejai.ui.installer.wizard.CredentialVerifier;
import imagejai.ui.installer.wizard.ProviderDiscoveryCredentialVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase G cross-phase carry-over from Phase E: the wizards' production
 * verifier fires a {@code /models} probe through {@link ProviderDiscovery}
 * using the freshly-saved key, and surfaces the outcome to the wizard's status
 * line.
 */
public class ProviderDiscoveryCredentialVerifierTest {

    @Test
    public void successWhenEndpointReturnsModels() throws IOException {
        Path tmp = Files.createTempDirectory("verifier-ok");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        creds.saveApiKey("groq", "sk-test");
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(200,
                        "{\"data\":[{\"id\":\"llama-3.3-70b\"}," +
                                "{\"id\":\"qwen-2.5-coder-32b\"}]}");
        ProviderDiscoveryCredentialVerifier verifier =
                new ProviderDiscoveryCredentialVerifier(creds, fetcher);
        CredentialVerifier.Result result = verifier.verify("groq", 4000);
        assertTrue("expected success, got: " + result.message, result.ok);
        assertTrue(result.message.contains("2 models"));
    }

    @Test
    public void failureWhenEndpointReturnsEmpty() throws IOException {
        Path tmp = Files.createTempDirectory("verifier-empty");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        creds.saveApiKey("groq", "sk-test");
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(200, "{\"data\":[]}");
        ProviderDiscoveryCredentialVerifier verifier =
                new ProviderDiscoveryCredentialVerifier(creds, fetcher);
        CredentialVerifier.Result result = verifier.verify("groq", 4000);
        assertFalse(result.ok);
        assertTrue(result.message.toLowerCase().contains("no models"));
    }

    @Test
    public void failureWhenEndpointRejects() throws IOException {
        Path tmp = Files.createTempDirectory("verifier-401");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        creds.saveApiKey("groq", "sk-bad");
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(401, "{\"error\":\"bad key\"}");
        ProviderDiscoveryCredentialVerifier verifier =
                new ProviderDiscoveryCredentialVerifier(creds, fetcher);
        CredentialVerifier.Result result = verifier.verify("groq", 4000);
        assertFalse(result.ok);
    }

    @Test
    public void failureWhenKeyNotSaved() throws IOException {
        Path tmp = Files.createTempDirectory("verifier-nokey");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        // No saveApiKey — key absent from disk.
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) -> {
            throw new AssertionError("fetcher should not be called when key missing");
        };
        ProviderDiscoveryCredentialVerifier verifier =
                new ProviderDiscoveryCredentialVerifier(creds, fetcher);
        CredentialVerifier.Result result = verifier.verify("groq", 4000);
        assertFalse(result.ok);
        assertTrue(result.message.contains("not found"));
    }

    @Test
    public void curatedOnlyProvidersSkipNetwork() throws IOException {
        Path tmp = Files.createTempDirectory("verifier-curated");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) -> {
            throw new AssertionError("must not call fetcher for curated-only provider");
        };
        ProviderDiscoveryCredentialVerifier verifier =
                new ProviderDiscoveryCredentialVerifier(creds, fetcher);
        // ollama-cloud and perplexity have no live /models — verifier reports
        // success without touching the network.
        assertTrue(verifier.verify("ollama-cloud", 4000).ok);
        assertTrue(verifier.verify("perplexity", 4000).ok);
    }

    @Test
    public void timeoutPropagatesToFetcher() throws IOException {
        Path tmp = Files.createTempDirectory("verifier-timeout");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        creds.saveApiKey("groq", "sk-test");
        AtomicReference<Duration> seen = new AtomicReference<>();
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) -> {
            seen.set(timeout);
            return new ProviderDiscovery.HttpFetcher.HttpResult(200,
                    "{\"data\":[{\"id\":\"llama\"}]}");
        };
        ProviderDiscoveryCredentialVerifier verifier =
                new ProviderDiscoveryCredentialVerifier(creds, fetcher);
        verifier.verify("groq", 4000);
        assertNotNull(seen.get());
        assertEquals(4000, seen.get().toMillis());
    }

    @Test
    public void failureWhenFetcherErrors() throws IOException {
        Path tmp = Files.createTempDirectory("verifier-net-error");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        creds.saveApiKey("groq", "sk-test");
        ProviderDiscovery.HttpFetcher fetcher = (endpoint, timeout) ->
                new ProviderDiscovery.HttpFetcher.HttpResult(
                        new java.io.IOException("connection refused"));
        ProviderDiscoveryCredentialVerifier verifier =
                new ProviderDiscoveryCredentialVerifier(creds, fetcher);
        CredentialVerifier.Result result = verifier.verify("groq", 4000);
        assertFalse(result.ok);
        assertTrue(result.message.contains("did not respond"));
    }
}
