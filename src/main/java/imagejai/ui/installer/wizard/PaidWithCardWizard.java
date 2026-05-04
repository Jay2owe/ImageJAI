package imagejai.ui.installer.wizard;

import imagejai.ui.installer.ProviderCredentials;

/**
 * Install shape #5 — pure API key plus a "billing required" notice. Used by
 * Anthropic and OpenAI: the request will be billed to whatever card / credit
 * balance the user has already set up on the provider's console.
 */
public class PaidWithCardWizard extends PureApiKeyWizard {

    public PaidWithCardWizard(String providerKey,
                              String displayName,
                              String signupUrl,
                              ProviderCredentials credentials) {
        super(providerKey, displayName, signupUrl, credentials);
    }

    public PaidWithCardWizard(String providerKey,
                              String displayName,
                              String signupUrl,
                              ProviderCredentials credentials,
                              CredentialVerifier verifier) {
        super(providerKey, displayName, signupUrl, credentials, verifier);
    }

    @Override
    protected String headerSubtext() {
        return "<font color='#a06000'>Note:</font> "
                + displayName + " requires an active billing balance or "
                + "subscription. ImageJAI does not charge you — the provider "
                + "does, per token, after this key is configured.<br>"
                + "Paste your API key. Stored locally — never uploaded.";
    }
}
