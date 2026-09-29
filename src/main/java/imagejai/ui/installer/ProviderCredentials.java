package imagejai.ui.installer;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.jna.platform.win32.Crypt32Util;
import imagejai.config.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared provider credentials for the Fiji plugin and Python console. On
 * Windows, values are protected for the current user with DPAPI and stored as
 * {@code <provider>.cred}. The bundled Python proxy reads the same format.
 * Existing plaintext {@code .env} files migrate after a successful read.
 * Other platforms retain owner-only {@code .env} files.
 */
public final class ProviderCredentials {

    private static final byte[] DPAPI_MAGIC =
            "IMAGEJAI-DPAPI-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final Gson GSON = new Gson();

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
        // Keyless local OpenAI-compatible servers — the env var holds an
        // optional base-URL override (port), never an API key.
        m.put("lmstudio", "LM_STUDIO_API_BASE");
        m.put("jan", "JAN_API_BASE");
        m.put("llamacpp", "LLAMACPP_API_BASE");
        m.put("vllm", "VLLM_API_BASE");
        ENV_VAR_FOR_PROVIDER = Collections.unmodifiableMap(m);
    }

    /**
     * Providers that run through a local daemon and need no configured API key.
     * Ollama's local daemon ({@code localhost:11434}) serves local models
     * directly and forwards {@code :cloud} / {@code -cloud} tags to ollama.com
     * once the user has signed in; the local OpenAI-compatible servers (LM
     * Studio, Jan, llama.cpp, vLLM) likewise need no key. A key, when present,
     * is an optional override, never a launch precondition.
     *
     * <p>Single source of truth lives in
     * {@link imagejai.engine.picker.ProviderRegistry#LOCAL_DAEMON_PROVIDERS};
     * this alias and delegate keep the existing installer/UI call sites stable.
     */
    public static final Set<String> LOCAL_DAEMON_PROVIDERS =
            imagejai.engine.picker.ProviderRegistry.LOCAL_DAEMON_PROVIDERS;

    /** True for providers that run keyless through a local daemon. */
    public static boolean isLocalDaemonProvider(String providerKey) {
        return imagejai.engine.picker.ProviderRegistry.isLocalDaemonProvider(providerKey);
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
        try {
            Map<String, String> entries = read(providerKey);
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
        Files.deleteIfExists(legacyFileFor(providerKey));
    }

    /** Return the on-disk path the env file for this provider would have. */
    public Path fileFor(String providerKey) {
        return secretsDir.resolve(providerKey + (isWindows() ? ".cred" : ".env"));
    }

    /** Read the env file's contents (KEY=VALUE pairs). Empty map if missing. */
    public Map<String, String> read(String providerKey) throws IOException {
        Path file = fileFor(providerKey);
        if (Files.isRegularFile(file)) {
            return isWindows() ? readProtectedFile(file) : readEnvFile(file);
        }
        Path legacy = legacyFileFor(providerKey);
        if (isWindows() && Files.isRegularFile(legacy)) {
            Map<String, String> entries = readEnvFile(legacy);
            write(providerKey, entries);
            Files.deleteIfExists(legacy);
            return entries;
        }
        return new LinkedHashMap<String, String>();
    }

    private void write(String providerKey, Map<String, String> entries) throws IOException {
        Files.createDirectories(secretsDir);
        Path file = fileFor(providerKey);
        if (isWindows()) {
            byte[] clear = GSON.toJson(entries).getBytes(StandardCharsets.UTF_8);
            byte[] protectedBytes = Crypt32Util.cryptProtectData(clear);
            byte[] encoded = Base64.getEncoder().encode(protectedBytes);
            byte[] protectedBody = new byte[DPAPI_MAGIC.length + encoded.length + 1];
            System.arraycopy(DPAPI_MAGIC, 0, protectedBody, 0, DPAPI_MAGIC.length);
            System.arraycopy(encoded, 0, protectedBody, DPAPI_MAGIC.length, encoded.length);
            protectedBody[protectedBody.length - 1] = '\n';
            Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(temp, protectedBody);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(legacyFileFor(providerKey));
            return;
        }
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

    private Path legacyFileFor(String providerKey) {
        return secretsDir.resolve(providerKey + ".env");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Map<String, String> readProtectedFile(Path file) throws IOException {
        byte[] body = Files.readAllBytes(file);
        if (body.length <= DPAPI_MAGIC.length) {
            throw new IOException("empty protected credential file " + file);
        }
        for (int i = 0; i < DPAPI_MAGIC.length; i++) {
            if (body[i] != DPAPI_MAGIC[i]) {
                throw new IOException("unsupported protected credential format " + file);
            }
        }
        String encoded = new String(
                body, DPAPI_MAGIC.length, body.length - DPAPI_MAGIC.length,
                StandardCharsets.US_ASCII).trim();
        try {
            byte[] clear = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(encoded));
            java.lang.reflect.Type type =
                    new TypeToken<Map<String, String>>() { }.getType();
            Map<String, String> values = GSON.fromJson(
                    new String(clear, StandardCharsets.UTF_8), type);
            return values == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<String, String>(values);
        } catch (RuntimeException e) {
            throw new IOException("could not decrypt " + file.getFileName(), e);
        }
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
