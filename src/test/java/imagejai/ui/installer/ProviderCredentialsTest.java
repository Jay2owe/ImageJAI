package imagejai.ui.installer;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProviderCredentialsTest {

    @Test
    public void hasCredentialsFalseWhenFileMissing() throws IOException {
        Path tmp = Files.createTempDirectory("creds-test");
        ProviderCredentials store = new ProviderCredentials(tmp);
        assertFalse(store.hasCredentials("anthropic"));
    }

    @Test
    public void saveApiKeyRoundTripsAndReportsHasCredentials() throws IOException {
        Path tmp = Files.createTempDirectory("creds-test");
        ProviderCredentials store = new ProviderCredentials(tmp);
        store.saveApiKey("anthropic", "sk-ant-test-12345");

        assertTrue(store.hasCredentials("anthropic"));
        Map<String, String> read = store.read("anthropic");
        assertEquals("sk-ant-test-12345", read.get("ANTHROPIC_API_KEY"));
    }

    @Test
    public void saveApiKeyOverwritesPriorValue() throws IOException {
        Path tmp = Files.createTempDirectory("creds-test");
        ProviderCredentials store = new ProviderCredentials(tmp);
        store.saveApiKey("groq", "first-key");
        store.saveApiKey("groq", "second-key");
        assertEquals("second-key", store.read("groq").get("GROQ_API_KEY"));
    }

    @Test
    public void saveEntriesPreservesMultipleKeys() throws IOException {
        Path tmp = Files.createTempDirectory("creds-test");
        ProviderCredentials store = new ProviderCredentials(tmp);
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put("OLLAMA_API_BASE", "http://192.168.1.10:11434");
        entries.put("OLLAMA_API_KEY", "cloud-token-xyz");
        store.saveEntries("ollama-cloud", entries);

        Map<String, String> read = store.read("ollama-cloud");
        assertEquals("http://192.168.1.10:11434", read.get("OLLAMA_API_BASE"));
        assertEquals("cloud-token-xyz", read.get("OLLAMA_API_KEY"));
    }

    @Test
    public void clearRemovesEnvFile() throws IOException {
        Path tmp = Files.createTempDirectory("creds-test");
        ProviderCredentials store = new ProviderCredentials(tmp);
        store.saveApiKey("openai", "sk-test");
        assertTrue(store.hasCredentials("openai"));
        store.clear("openai");
        assertFalse(store.hasCredentials("openai"));
        assertFalse(Files.exists(store.fileFor("openai")));
    }

    @Test
    public void hasCredentialsRejectsEmptyValue() throws IOException {
        Path tmp = Files.createTempDirectory("creds-test");
        ProviderCredentials store = new ProviderCredentials(tmp);
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put("ANTHROPIC_API_KEY", "");
        store.saveEntries("anthropic", entries);
        assertFalse(store.hasCredentials("anthropic"));
    }

    @Test
    public void readStripsCommentsAndQuotes() throws IOException {
        Path tmp = Files.createTempDirectory("creds-test");
        Path file = tmp.resolve("xai.env");
        Files.write(file,
                ("# header comment\nXAI_API_KEY=\"quoted-value\"\nUNKNOWN=plain\n").getBytes());
        ProviderCredentials store = new ProviderCredentials(tmp);
        Map<String, String> read = store.read("xai");
        assertEquals("quoted-value", read.get("XAI_API_KEY"));
        assertEquals("plain", read.get("UNKNOWN"));
    }
}
