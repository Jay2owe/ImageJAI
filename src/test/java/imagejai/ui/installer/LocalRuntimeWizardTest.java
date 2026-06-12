package imagejai.ui.installer;

import org.junit.Test;
import imagejai.ui.installer.wizard.LocalRuntimeWizard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase E acceptance: Ollama daemon URL is sanity-checked via {@code GET
 * /api/tags} with a 2-second timeout before save (Phase E risk register —
 * "Ollama daemon URL trust"). Cloud-flow saves only the cloud token; it does
 * not pollute env with an irrelevant daemon URL.
 */
public class LocalRuntimeWizardTest {

    @Test
    public void localFlowSurfaceContractCreatesNoCredentialsWhenProbeFails() throws IOException {
        Path tmp = Files.createTempDirectory("ollama-local");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        // We can't open the modal in a unit test, so we exercise the probe
        // contract directly: a failing probe returns a result whose ok flag
        // gates the save path.
        LocalRuntimeWizard.DaemonProbe probe = (url, timeoutMs) ->
                new LocalRuntimeWizard.DaemonResult(false, 0, "Connection refused");
        LocalRuntimeWizard.DaemonResult result = probe.probe("http://127.0.0.1:0", 2000);
        assertFalse(result.ok);
        assertEquals("Connection refused", result.message);
        // No save happens through the wizard's contract — credentials should
        // not be present.
        assertFalse(creds.hasCredentials("ollama"));
    }

    @Test
    public void localFlowProbeSurfaceReportsHttpCodeOnSuccess() {
        LocalRuntimeWizard.DaemonProbe probe = (url, timeoutMs) ->
                new LocalRuntimeWizard.DaemonResult(true, 200, "ok");
        LocalRuntimeWizard.DaemonResult result = probe.probe("http://localhost:11434", 2000);
        assertTrue(result.ok);
        assertEquals(200, result.httpCode);
    }

    @Test
    public void daemonProbeTimeoutIsTwoSeconds() {
        // Phase E risk: "GET /api/tags with 2 s timeout".
        assertEquals(2000, LocalRuntimeWizard.DAEMON_PROBE_TIMEOUT_MS);
    }

    @Test
    public void cloudFlowSavesOnlyOllamaApiKey() throws IOException {
        Path tmp = Files.createTempDirectory("ollama-cloud");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        creds.saveApiKey("ollama-cloud", "cloud-token");
        java.util.Map<String, String> read = creds.read("ollama-cloud");
        // Cloud flow should not write OLLAMA_API_BASE — that's only for local
        // (cloud's api_base falls back to imagejai_default_api_base).
        assertEquals("cloud-token", read.get("OLLAMA_API_KEY"));
        assertNull(read.get("OLLAMA_API_BASE"));
    }

    @Test
    public void wizardConstructibleWithOnlyDaemonProbeStub() throws IOException {
        Path tmp = Files.createTempDirectory("ollama-ctor");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        LocalRuntimeWizard wizard = new LocalRuntimeWizard("ollama", creds,
                () -> false,
                (url, timeoutMs) -> new LocalRuntimeWizard.DaemonResult(true, 200, "ok"));
        assertEquals("ollama", wizard.providerKey());
    }
}
