package imagejai.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Persists plugin configuration to ~/.imagej-ai/config.json.
 * Per-machine (not in Dropbox).
 */
public class Settings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Persisted fields
    public String provider = "gemini";        // gemini, ollama, openai, custom
    public String apiKey = "";
    public String model = Constants.DEFAULT_GEMINI_MODEL;
    public String ollamaUrl = Constants.DEFAULT_OLLAMA_URL;
    public String openaiUrl = Constants.DEFAULT_OPENAI_URL;
    public String customUrl = "";
    public String customApiKey = "";
    public String customModel = "";
    public boolean visionEnabled = true;
    public boolean autoScreenshot = false;     // Auto-capture after each command
    public int maxHistory = Constants.MAX_CONVERSATION_HISTORY;
    public boolean tcpServerEnabled = false;  // Off by default
    public int tcpPort = Constants.DEFAULT_TCP_PORT;

    // Transient
    private transient Path configPath;

    public Settings() {
        configPath = getConfigDir().resolve("config.json");
    }

    /**
     * Load settings from disk, or return defaults if no file exists.
     */
    public static Settings load() {
        Settings s = new Settings();
        if (Files.exists(s.configPath)) {
            try (Reader r = new InputStreamReader(
                    new FileInputStream(s.configPath.toFile()), StandardCharsets.UTF_8)) {
                Settings loaded = GSON.fromJson(r, Settings.class);
                if (loaded != null) {
                    loaded.configPath = s.configPath;
                    return loaded;
                }
            } catch (Exception e) {
                System.err.println("[ImageJAI] Failed to load settings: " + e.getMessage());
            }
        }
        return s;
    }

    /**
     * Save current settings to disk.
     */
    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(configPath.toFile()), StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            System.err.println("[ImageJAI] Failed to save settings: " + e.getMessage());
        }
    }

    /**
     * Returns true if this appears to be a first-run (no API key configured).
     */
    public boolean isFirstRun() {
        if ("ollama".equals(provider)) {
            return false; // Ollama doesn't need a key
        }
        return apiKey == null || apiKey.trim().isEmpty();
    }

    /**
     * Get the per-machine config directory.
     */
    public static Path getConfigDir() {
        String home = System.getProperty("user.home");
        return Paths.get(home, Constants.CONFIG_DIR_NAME);
    }
}
