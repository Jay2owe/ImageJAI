package imagejai.ui.installer.wizard;

import imagejai.engine.picker.MergeFunction;
import imagejai.engine.picker.ProviderDiscovery;
import imagejai.ui.installer.ProviderCredentials;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Production {@link CredentialVerifier} that fires one {@code /models} probe
 * through {@link ProviderDiscovery} using the freshly-saved API key.
 *
 * <p>Wires the cross-phase carry-over from Phase E: the wizard saves
 * {@code <provider>.env}, then this verifier reads the env file, builds a
 * one-off {@link ProviderDiscovery} instance with that credential, and runs a
 * single {@link ProviderDiscovery#discover(String, Duration)} call. Success when
 * the endpoint returns 2xx with at least one model id; failure carries the
 * verifier-side reason ({@code "no models returned"}, {@code "endpoint
 * returned 401 — key rejected"}, etc.).
 *
 * <p>Curated-only providers ({@link ProviderDiscovery#CURATED_ONLY} —
 * Ollama Cloud, Perplexity) skip the probe and report success: there is no live
 * endpoint to test against, so the wizard's "Save & test" button cannot do
 * better than trust the curated catalogue (see 02 §6).
 */
public final class ProviderDiscoveryCredentialVerifier implements CredentialVerifier {

    private final ProviderCredentials credentials;
    private final ProviderDiscovery.HttpFetcher fetcher;

    public ProviderDiscoveryCredentialVerifier(ProviderCredentials credentials) {
        this(credentials, ProviderDiscovery.defaultFetcher());
    }

    /** Test seam — lets unit tests inject a fake fetcher. */
    public ProviderDiscoveryCredentialVerifier(ProviderCredentials credentials,
                                               ProviderDiscovery.HttpFetcher fetcher) {
        this.credentials = credentials;
        this.fetcher = fetcher == null ? ProviderDiscovery.defaultFetcher() : fetcher;
    }

    @Override
    public Result verify(String providerKey, int timeoutMs) {
        if (providerKey == null || providerKey.isEmpty()) {
            return Result.failure("no provider key supplied");
        }
        if (ProviderDiscovery.CURATED_ONLY.contains(providerKey)) {
            return Result.success(
                    "no live /models endpoint — curated entries assumed authoritative");
        }
        String apiKey = readSavedApiKey(providerKey);
        // Local Ollama doesn't need a key — the daemon is unauthenticated.
        if (apiKey == null && !"ollama".equals(providerKey)) {
            return Result.failure(
                    "key not found on disk — was the wizard's save step skipped?");
        }
        Map<String, String> creds = new LinkedHashMap<String, String>();
        if (apiKey != null) {
            creds.put(providerKey, apiKey);
        }
        ProviderDiscovery discovery = new ProviderDiscovery(
                ProviderDiscovery.defaultEndpoints(creds), fetcher);
        Duration timeout = Duration.ofMillis(timeoutMs <= 0 ? 4000 : timeoutMs);
        MergeFunction.LiveResult live = discovery.discover(providerKey, timeout);
        if (!live.successful()) {
            return Result.failure("endpoint did not respond — check network / key");
        }
        if (live.modelIds().isEmpty()) {
            return Result.failure("endpoint returned no models — key may lack scope");
        }
        return Result.success("verified — " + live.modelIds().size() + " models reachable");
    }

    private String readSavedApiKey(String providerKey) {
        if (credentials == null) {
            return null;
        }
        String envName = ProviderCredentials.ENV_VAR_FOR_PROVIDER.get(providerKey);
        if (envName == null) {
            return null;
        }
        try {
            Map<String, String> entries = credentials.read(providerKey);
            String value = entries.get(envName);
            return value == null || value.isEmpty() ? null : value;
        } catch (IOException ex) {
            return null;
        }
    }
}
