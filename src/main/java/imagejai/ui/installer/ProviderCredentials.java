package imagejai.ui.installer;

import imagejai.config.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes per-provider credentials as {@code <provider>.env} files in
 * a dedicated secrets directory. Default location is
 * {@code <imagej-ai-config-dir>/secrets/}. The bundled
 * {@code agent/providers/proxy.py} loads the same files into the LiteLLM
 * sidecar's environment before launch.
 *
 * <p>Stored as plain {@code KEY=VALUE} lines so the proxy can source them
 * without a YAML dependency. Phase E ships plaintext-on-disk only — OS keychain
 * integration is explicitly out of scope per
 * docs/multi_provider/07_implementation_plan.md §E risks.
 */
public final class ProviderCredentials {

    /** Directory name beneath the imagej-ai config root. */
    public static final String SECRETS_DIRNAME = "secrets";

    /**
     * Mapping from canonical hyphenated provider key to the env-var name used
     * by {@code litellm.config.yaml}. Mirrors the config file directly.
     */
    public static final Map<String, String> ENV_VAR_FOR_PROVIDER;
    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("anthropic", "ANTHROPIC_API_KEY");
        m.put("openai", "OPENAI_API_KEY");
        m.put("gemini", "GEMINI_API_KEY");
        m.put("groq", "GROQ_API_KEY");
        m.put("cerebras", "CEREBRAS_API_KEY");
        m.put("openrouter", "OPENROUTER_API_KEY");
        m.put("github-models", "GITHUB_TOKEN");
        m.put("mistral", "MISTRAL_API_KEY");
        m.put("together", "TOGETHER_API_KEY");
        m.put("huggingface", "HUGGINGFACE_API_KEY");
        m.put("deepseek", "DEEPSEEK_API_KEY");
        m.put("xai", "XAI_API_KEY");
        m.put("perplexity", "PERPLEXITY_API_KEY");
        m.put("ollama-cloud", "OLLAMA_API_KEY");
        m.put("ollama", "OLLAMA_API_BASE");
        ENV_VAR_FOR_PROVIDER = Collections.unmodifiableMap(m);
    }

    private final Path secretsDir;

    /** Use the default {@code ~/.imagej-ai/secrets/} location. */
    public ProviderCredentials() {
        this(Settings.getConfigDir().resolve(SECRETS_DIRNAME));
    }

    public ProviderCredentials(Path secretsDir) {
        this.secretsDir = secretsDir;
    }

    public Path secretsDir() {
        return secretsDir;
    }

    /** True when a non-empty env file exists for this provider. */
    public boolean hasCredentials(String providerKey) {
        if (providerKey == null) {
            return false;
        }
        Path file = fileFor(providerKey);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Map<String, String> entries = readEnvFile(file);
            String envName = ENV_VAR_FOR_PROVIDER.get(providerKey);
            if (envName == null) {
                return !entries.isEmpty();
            }
            String value = entries.get(envName);
            return value != null && !value.isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Persist a single provider key. Overwrites any previous value. The file
     * permission is best-effort restricted to owner-only on POSIX; on Windows
     * we rely on the home directory ACL.
     */
    public void saveApiKey(String providerKey, String apiKey) throws IOException {
        if (providerKey == null || apiKey == null) {
            throw new IllegalArgumentException("providerKey and apiKey must be non-null");
        }
        String envName = ENV_VAR_FOR_PROVIDER.get(providerKey);
        if (envName == null) {
            throw new IllegalArgumentException("unknown provider " + providerKey);
        }
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put(envName, apiKey);
        write(providerKey, entries);
    }

    /**
     * Persist arbitrary KEY=VALUE pairs (used by the local-runtime + cloud flow
     * which writes both an Ollama URL and a cloud token).
     */
    public void saveEntries(String providerKey, Map<String, String> entries) throws IOException {
        if (providerKey == null || entries == null) {
            throw new IllegalArgumentException("providerKey and entries must be non-null");
        }
        write(providerKey, entries);
    }

    /** Remove the env file for this provider. */
    public void clear(String providerKey) throws IOException {
        if (providerKey == null) {
            return;
        }
        Files.deleteIfExists(fileFor(providerKey));
    }

    /** Return the on-disk path the env file for this provider would have. */
    public Path fileFor(String providerKey) {
        return secretsDir.resolve(providerKey + ".env");
    }

    /** Read the env file's contents (KEY=VALUE pairs). Empty map if missing. */
    public Map<String, String> read(String providerKey) throws IOException {
        Path file = fileFor(providerKey);
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<String, String>();
        }
        return readEnvFile(file);
    }

    private void write(String providerKey, Map<String, String> entries) throws IOException {
        Files.createDirectories(secretsDir);
        Path file = fileFor(providerKey);
        StringBuilder body = new StringBuilder();
        body.append("# ImageJAI credentials for ").append(providerKey).append('\n');
        body.append("# Loaded by agent/providers/proxy.py before LiteLLM starts.\n");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            body.append(key).append('=').append(value).append('\n');
        }
        Files.write(file, body.toString().getBytes(StandardCharsets.UTF_8));
        restrictPermissions(file);
    }

    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> owner = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, owner);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX filesystems — rely on the user's home ACL.
        }
    }

    private static Map<String, String> readEnvFile(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            // Strip optional surrounding quotes.
            if (value.length() >= 2
                    && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }
}
