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
 * through {@link ProviderDiscovery} using an in-memory candidate API key.
 *
 * <p>The verifier builds a one-off {@link ProviderDiscovery} instance with the
 * candidate and runs a single {@link ProviderDiscovery#discover(String,
 * Duration)} call. The wizard persists the candidate only after verification
 * succeeds. {@link #verify(String, int)} remains available for re-checking an
 * already-saved credential.
 *
 * <p>Ollama Cloud has no candidate-token endpoint in the discovery client.
 * Its authentication state is owned by {@code ollama signin}, so a pasted
 * token is explicitly reported as unverified and can never pass the wizard's
 * validate-before-persist gate. Other curated-only providers retain their
 * catalogue-only result for existing non-Ollama setup flows.
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
        return verifyCandidate(providerKey, readSavedApiKey(providerKey), timeoutMs);
    }

    @Override
    public Result verifyCandidate(String providerKey, String candidate, int timeoutMs) {
        if (providerKey == null || providerKey.isEmpty()) {
            return Result.failure("no provider key supplied");
        }
        if ("ollama-cloud".equals(providerKey)) {
            return Result.failure("unverified: Ollama Cloud has no authenticated "
                    + "candidate-token endpoint; token was not saved. Run 'ollama signin' "
                    + "and let Ollama manage the sign-in state");
        }
        if (ProviderDiscovery.CURATED_ONLY.contains(providerKey)) {
            return Result.success(
                    "no live /models endpoint — curated entries assumed authoritative");
        }
        String apiKey = candidate == null || candidate.trim().isEmpty()
                ? null : candidate.trim();
        // Local Ollama doesn't need a key — the daemon is unauthenticated.
        if (apiKey == null && !"ollama".equals(providerKey)) {
            return Result.failure("credential not found or supplied for validation");
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
