package imagejai.ui.installer;

import org.junit.Test;
import imagejai.ui.installer.wizard.BrowserAuthWizard;
import imagejai.ui.installer.wizard.CredentialVerifier;
import imagejai.ui.installer.wizard.PaidWithCardWizard;
import imagejai.ui.installer.wizard.PureApiKeyWizard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase E acceptance: cloud-key wizards must run a synchronous refresh-and-test
 * step on Save with a 4 s timeout. The verifier is the seam tests use to
 * assert the contract (the wizard's modal dialog can't be exercised
 * headlessly).
 */
public class CredentialVerifierTest {

    @Test
    public void noopVerifierReturnsSuccess() {
        CredentialVerifier verifier = CredentialVerifier.noop();
        CredentialVerifier.Result r = verifier.verify("anthropic", 4000);
        assertNotNull(r);
        assertTrue(r.ok);
    }

    @Test
    public void failureResultPropagatesMessage() {
        CredentialVerifier.Result r = CredentialVerifier.Result.failure("HTTP 401 unauthorized");
        assertFalse(r.ok);
        assertEquals("HTTP 401 unauthorized", r.message);
    }

    @Test
    public void successResultPropagatesMessage() {
        CredentialVerifier.Result r = CredentialVerifier.Result.success("3 models");
        assertTrue(r.ok);
        assertEquals("3 models", r.message);
    }

    @Test
    public void verifyTimeoutBudgetIs4Seconds() {
        // Phase E acceptance: 4 s budget (06 §4.4).
        assertEquals(4000, PureApiKeyWizard.VERIFY_TIMEOUT_MS);
        assertEquals(4000, BrowserAuthWizard.VERIFY_TIMEOUT_MS);
    }

    @Test
    public void wizardConstructorAcceptsVerifierOverride() throws IOException {
        Path tmp = Files.createTempDirectory("verifier-ctor");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        AtomicInteger calls = new AtomicInteger(0);
        CredentialVerifier stub = (providerKey, timeoutMs) -> {
            calls.incrementAndGet();
            assertEquals(4000, timeoutMs);
            return CredentialVerifier.Result.success("ok");
        };
        // Just constructing — we can't trigger Save & test without a real EDT
        // dialog. The verifier hook is exercised by the wizard's save handler
        // (covered by manual test in the plan).
        PureApiKeyWizard pure = new PureApiKeyWizard("groq", "Groq",
                "https://example", creds, stub);
        BrowserAuthWizard browser = new BrowserAuthWizard("github-models",
                "GitHub", "https://example", "gh auth login", creds, stub);
        PaidWithCardWizard paid = new PaidWithCardWizard("anthropic", "Anthropic",
                "https://example", creds, stub);
        assertEquals("groq", pure.providerKey());
        assertEquals("github-models", browser.providerKey());
        assertEquals("anthropic", paid.providerKey());
        // Verifier is wired but not invoked at construction time.
        assertEquals(0, calls.get());
    }

    @Test
    public void nullVerifierIsTreatedAsNoop() throws IOException {
        Path tmp = Files.createTempDirectory("verifier-null");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        // null verifier must not break construction — the wizard substitutes
        // CredentialVerifier.noop() so the headless path stays clean.
        PureApiKeyWizard pure = new PureApiKeyWizard("groq", "Groq",
                "https://example", creds, null);
        assertNotNull(pure);
    }
}
