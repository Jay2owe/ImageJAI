package imagejai.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists plugin configuration to ~/.imagej-ai/config.json.
 * Per-machine (not in Dropbox).
 */
public class Settings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Configuration for a specific LLM model/provider.
     */
    public static class ModelConfig {
        public String id;
        public String name;
        public String provider; // gemini, ollama, openai, custom
        public String apiKey = "";
        public String model = "";
        public String url = "";

        public ModelConfig() {
            this.id = UUID.randomUUID().toString();
        }

        public ModelConfig(String name, String provider, String model) {
            this();
            this.name = name;
            this.provider = provider;
            this.model = model;
        }
        
        @Override
        public String toString() {
            return name != null ? name : (provider + " / " + model);
        }
    }

    // Persisted fields
    public List<ModelConfig> configs = new ArrayList<>();
    public String activeConfigId = "";
    
    // Legacy fields (kept for migration, marked transient so they aren't saved again)
    private transient String provider;
    private transient String apiKey;
    private transient String model;
    private transient String ollamaUrl;
    private transient String openaiUrl;

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
                    loaded.migrateIfNeeded();
                    return loaded;
                }
            } catch (Exception e) {
                System.err.println("[ImageJAI] Failed to load settings: " + e.getMessage());
            }
        }
        
        // No settings or load failed: create default Gemini config
        s.addDefaultConfig();
        return s;
    }

    private void migrateIfNeeded() {
        // If we have old-style settings but no configs list, migrate them
        if (configs.isEmpty() && provider != null) {
            ModelConfig legacy = new ModelConfig("Default Profile", provider, model);
            legacy.apiKey = apiKey;
            if ("ollama".equals(provider)) legacy.url = ollamaUrl;
            else if ("openai".equals(provider)) legacy.url = openaiUrl;
            
            configs.add(legacy);
            activeConfigId = legacy.id;
        }
        
        if (configs.isEmpty()) {
            addDefaultConfig();
        }
        
        // Ensure activeConfigId is valid
        if (activeConfigId == null || getActiveConfig() == null) {
            activeConfigId = configs.get(0).id;
        }
    }

    private void addDefaultConfig() {
        ModelConfig gemini = new ModelConfig("Gemini (Default)", "gemini", Constants.DEFAULT_GEMINI_MODEL);
        configs.add(gemini);
        activeConfigId = gemini.id;
    }

    public ModelConfig getActiveConfig() {
        for (ModelConfig c : configs) {
            if (c.id.equals(activeConfigId)) return c;
        }
        if (!configs.isEmpty()) return configs.get(0);
        return null;
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
        if (tcpServerEnabled) return false;
        ModelConfig active = getActiveConfig();
        if (active == null) return true;
        if ("ollama".equals(active.provider)) return false;
        return active.apiKey == null || active.apiKey.trim().isEmpty();
    }

    /**
     * Returns true if an API key is configured for the current provider.
     */
    public boolean hasApiKey() {
        ModelConfig active = getActiveConfig();
        if (active == null) return false;
        
        if ("gemini".equals(active.provider) || "openai".equals(active.provider) || "custom".equals(active.provider)) {
            return active.apiKey != null && !active.apiKey.trim().isEmpty();
        }
        if ("ollama".equals(active.provider)) {
            return true; // No key needed
        }
        return false;
    }

    /**
     * Get the per-machine config directory.
     */
    public static Path getConfigDir() {
        String home = System.getProperty("user.home");
        return Paths.get(home, Constants.CONFIG_DIR_NAME);
    }
}
