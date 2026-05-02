package imagejai.ui.installer;

import org.junit.Test;
import imagejai.engine.picker.ProviderRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MultiProviderPanelTest {

    @Test
    public void panelExposesAllFifteenCanonicalProviders() throws IOException {
        Path tmp = Files.createTempDirectory("panel-test");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        MultiProviderPanel panel = new MultiProviderPanel(ProviderRegistry.empty(), creds,
                providerKey -> null);

        assertEquals(15, panel.providerKeys().size());
        for (String key : ProviderRegistry.CANONICAL_PROVIDERS) {
            assertNotNull("missing card for " + key, panel.cardFor(key));
        }
    }

    @Test
    public void unconfiguredCloudProviderShowsNeedsSetup() throws IOException {
        Path tmp = Files.createTempDirectory("panel-test");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        MultiProviderPanel panel = new MultiProviderPanel(ProviderRegistry.empty(), creds,
                providerKey -> null);
        assertEquals(ProviderCard.Status.NEEDS_SETUP, panel.statusOf("anthropic"));
        assertEquals(ProviderCard.Status.NEEDS_SETUP, panel.statusOf("openai"));
        assertEquals(ProviderCard.Status.NEEDS_SETUP, panel.statusOf("gemini"));
    }

    @Test
    public void savedCredentialFlipsCardToReady() throws IOException {
        Path tmp = Files.createTempDirectory("panel-test");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        creds.saveApiKey("anthropic", "sk-ant-test");
        MultiProviderPanel panel = new MultiProviderPanel(ProviderRegistry.empty(), creds,
                providerKey -> null);
        assertEquals(ProviderCard.Status.READY, panel.statusOf("anthropic"));
    }

    @Test
    public void localOllamaReadsAsReadyEvenWithoutEnvFile() throws IOException {
        Path tmp = Files.createTempDirectory("panel-test");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        MultiProviderPanel panel = new MultiProviderPanel(ProviderRegistry.empty(), creds,
                providerKey -> null);
        // Local Ollama runs on the user's machine without a key — assume ready.
        assertEquals(ProviderCard.Status.READY, panel.statusOf("ollama"));
    }

    @Test
    public void refreshAllPicksUpFreshlyWrittenSecrets() throws IOException {
        Path tmp = Files.createTempDirectory("panel-test");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        MultiProviderPanel panel = new MultiProviderPanel(ProviderRegistry.empty(), creds,
                providerKey -> null);
        assertEquals(ProviderCard.Status.NEEDS_SETUP, panel.statusOf("groq"));
        creds.saveApiKey("groq", "gsk-test");
        panel.refreshAll();
        assertEquals(ProviderCard.Status.READY, panel.statusOf("groq"));
    }

    @Test
    public void scrollToUnknownProviderIsNoop() throws IOException {
        Path tmp = Files.createTempDirectory("panel-test");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        MultiProviderPanel panel = new MultiProviderPanel(ProviderRegistry.empty(), creds,
                providerKey -> null);
        panel.scrollTo("not-a-real-provider"); // must not throw
        assertTrue(true);
    }
}
