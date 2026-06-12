package imagejai.config;

import imagejai.ui.installer.ProviderCredentials;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifier #3 / #3-2 / #4-1: ModelConfig.apiKey is no longer serialized to
 * config.json; the legacy direct-backend path must still find the key via the
 * per-provider {@code <provider>.env} secrets store. Pins the resolver and the
 * load-time migration so the regression that broke the legacy chat path after
 * restart cannot reappear.
 */
public class SettingsApiKeyResolutionTest {

    private Settings settingsWithTempSecrets() throws Exception {
        Path dir = Files.createTempDirectory("imagejai-secrets-test");
        Settings s = new Settings();
        s.setProviderCredentials(new ProviderCredentials(dir));
        return s;
    }

    @Test
    public void resolveReturnsInMemoryKeyWhenPresent() throws Exception {
        Settings s = settingsWithTempSecrets();
        Settings.ModelConfig c = new Settings.ModelConfig("p", "gemini", "m");
        c.apiKey = "in-mem-key";
        assertEquals("in-mem-key", s.resolveApiKey(c));
    }

    @Test
    public void resolveReadsMappedProviderFromSecretsWhenInMemoryEmpty() throws Exception {
        Settings s = settingsWithTempSecrets();
        s.providerCredentials().saveApiKey("gemini", "secret-gemini");
        Settings.ModelConfig c = new Settings.ModelConfig("p", "gemini", "m");
        c.apiKey = "";
        assertEquals("secret-gemini", s.resolveApiKey(c));
    }

    @Test
    public void resolveReadsCustomFromSecrets() throws Exception {
        Settings s = settingsWithTempSecrets();
        Map<String, String> e = new LinkedHashMap<String, String>();
        e.put(Settings.CUSTOM_API_KEY_ENV, "secret-custom");
        s.providerCredentials().saveEntries("custom", e);
        Settings.ModelConfig c = new Settings.ModelConfig("p", "custom", "m");
        c.apiKey = "";
        assertEquals("secret-custom", s.resolveApiKey(c));
    }

    @Test
    public void resolveEmptyWhenNoKeyAnywhere() throws Exception {
        Settings s = settingsWithTempSecrets();
        Settings.ModelConfig c = new Settings.ModelConfig("p", "gemini", "m");
        c.apiKey = "";
        assertEquals("", s.resolveApiKey(c));
    }

    @Test
    public void migrationRelocatesLegacyMappedKey() throws Exception {
        Settings s = settingsWithTempSecrets();
        s.configs.clear();
        Settings.ModelConfig c = new Settings.ModelConfig("p", "gemini", "m");
        c.apiKey = "legacy-gem";
        s.configs.add(c);

        s.migrateLegacyApiKeysToSecrets();

        assertTrue(s.providerCredentials().hasCredentials("gemini"));
        assertEquals("legacy-gem",
                s.providerCredentials().read("gemini").get("GEMINI_API_KEY"));
        // In-memory value is retained for the session (only config.json drops it).
        assertEquals("legacy-gem", s.resolveApiKey(c));
    }

    @Test
    public void migrationRelocatesLegacyCustomKey() throws Exception {
        Settings s = settingsWithTempSecrets();
        s.configs.clear();
        Settings.ModelConfig c = new Settings.ModelConfig("p", "custom", "m");
        c.apiKey = "legacy-custom";
        s.configs.add(c);

        s.migrateLegacyApiKeysToSecrets();

        assertEquals("legacy-custom",
                s.providerCredentials().read("custom").get(Settings.CUSTOM_API_KEY_ENV));
    }

    @Test
    public void migrationNeverOverwritesExistingSecret() throws Exception {
        Settings s = settingsWithTempSecrets();
        s.providerCredentials().saveApiKey("gemini", "newer-key");
        s.configs.clear();
        Settings.ModelConfig c = new Settings.ModelConfig("p", "gemini", "m");
        c.apiKey = "older-legacy-key";
        s.configs.add(c);

        s.migrateLegacyApiKeysToSecrets();

        assertEquals("newer-key",
                s.providerCredentials().read("gemini").get("GEMINI_API_KEY"));
    }
}
