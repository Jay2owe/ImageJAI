package imagejai.ui.installer.wizard;

import imagejai.engine.picker.ProviderRegistry;
import imagejai.ui.installer.MultiProviderPanel;
import imagejai.ui.installer.ProviderCredentials;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalRuntimeWizardSecurityTest {

    @Test
    public void cloudInstructionsDelegateAuthenticationAndPromiseNoStorage() {
        String instructions = LocalRuntimeWizard.cloudSignInInstructions();
        assertTrue(instructions.contains("ollama signin"));
        assertTrue(instructions.contains("does not receive, verify, or store"));
    }

    @Test
    public void defaultPanelWiresProductionVerifierIntoRuntimeWizard() throws Exception {
        Path root = Files.createTempDirectory("ollama-cloud-wiring");
        ProviderCredentials credentials = new ProviderCredentials(root);
        MultiProviderPanel panel = new MultiProviderPanel(
                ProviderRegistry.empty(), credentials);
        Field factoryField = MultiProviderPanel.class.getDeclaredField("wizardFactory");
        factoryField.setAccessible(true);
        MultiProviderPanel.WizardFactory factory =
                (MultiProviderPanel.WizardFactory) factoryField.get(panel);
        LocalRuntimeWizard wizard =
                (LocalRuntimeWizard) factory.wizardFor("ollama-cloud");
        Field verifierField = LocalRuntimeWizard.class.getDeclaredField("verifier");
        verifierField.setAccessible(true);

        assertTrue(verifierField.get(wizard)
                instanceof ProviderDiscoveryCredentialVerifier);
    }

    @Test
    public void rejectedCandidateIsRedactedAndNeverPersisted() throws Exception {
        Path root = Files.createTempDirectory("ollama-cloud-reject");
        ProviderCredentials credentials = new ProviderCredentials(root);
        final String candidate = "candidate-secret-123";
        CredentialVerifier verifier = new CredentialVerifier() {
            @Override public Result verify(String providerKey, int timeoutMs) {
                return Result.failure("saved-key path must not run");
            }

            @Override public Result verifyCandidate(
                    String providerKey, String value, int timeoutMs) {
                assertEquals(candidate, value);
                assertEquals(LocalRuntimeWizard.VERIFY_TIMEOUT_MS, timeoutMs);
                return Result.failure("provider rejected " + value);
            }
        };
        LocalRuntimeWizard wizard = wizard(credentials, verifier);

        CredentialVerifier.ValidationWorker worker =
                wizard.cloudValidationWorker(candidate, null);
        worker.execute();
        CredentialVerifier.Result result = worker.get();

        assertFalse(result.ok);
        assertFalse(result.message.contains(candidate));
        assertTrue(result.message.contains("[REDACTED]"));
        assertTrue(credentials.read("ollama-cloud").isEmpty());
    }

    @Test
    public void productionVerifierCannotPersistArbitraryCloudCandidate() throws Exception {
        Path root = Files.createTempDirectory("ollama-cloud-production-reject");
        ProviderCredentials credentials = new ProviderCredentials(root);
        LocalRuntimeWizard wizard = wizard(credentials,
                new ProviderDiscoveryCredentialVerifier(credentials,
                        (endpoint, timeout) -> {
                            throw new AssertionError("cloud verification must not hit network");
                        }));

        CredentialVerifier.ValidationWorker worker =
                wizard.cloudValidationWorker("arbitrary-token", null);
        worker.execute();
        CredentialVerifier.Result result = worker.get();

        assertFalse(result.ok);
        assertTrue(result.message.contains("unverified"));
        assertTrue(credentials.read("ollama-cloud").isEmpty());
    }

    @Test
    public void acceptedCandidatePersistsOnlyAfterValidation() throws Exception {
        Path root = Files.createTempDirectory("ollama-cloud-accept");
        ProviderCredentials credentials = new ProviderCredentials(root);
        CredentialVerifier verifier = new CredentialVerifier() {
            @Override public Result verify(String providerKey, int timeoutMs) {
                return Result.failure("saved-key path must not run");
            }

            @Override public Result verifyCandidate(
                    String providerKey, String value, int timeoutMs) {
                assertTrue(credentialsReadEmpty(credentials));
                return Result.success("accepted");
            }
        };
        LocalRuntimeWizard wizard = wizard(credentials, verifier);

        CredentialVerifier.ValidationWorker worker =
                wizard.cloudValidationWorker("accepted-token", null);
        worker.execute();
        CredentialVerifier.Result result = worker.get();

        assertTrue(result.ok);
        assertEquals("accepted-token",
                credentials.read("ollama-cloud").get("OLLAMA_API_KEY"));
        assertFalse(credentials.read("ollama-cloud").containsKey("OLLAMA_API_BASE"));
    }

    private static LocalRuntimeWizard wizard(
            ProviderCredentials credentials, CredentialVerifier verifier) {
        return new LocalRuntimeWizard("ollama-cloud", credentials,
                () -> true,
                (url, timeoutMs) -> new LocalRuntimeWizard.DaemonResult(
                        true, 200, "ok"), verifier);
    }

    private static boolean credentialsReadEmpty(ProviderCredentials credentials) {
        try {
            return credentials.read("ollama-cloud").isEmpty();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
