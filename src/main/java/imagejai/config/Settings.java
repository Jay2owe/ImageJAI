package imagejai.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import imagejai.engine.AgentLauncher;

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

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            // Never serialize ModelConfig.apiKey — secrets live in
            // <imagej-ai>/secrets/<provider>.env, never in config.json
            // (verifier #3). Deserialization stays intact so a legacy key in an
            // old config.json can be migrated to the secrets store on load.
            .addSerializationExclusionStrategy(new com.google.gson.ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(com.google.gson.FieldAttributes f) {
                    return f.getDeclaringClass() == ModelConfig.class
                            && "apiKey".equals(f.getName());
                }

                @Override
                public boolean shouldSkipClass(Class<?> clazz) {
                    return false;
                }
            })
            .create();

    /** Env-var name under which a legacy "custom" provider key is stored in custom.env. */
    public static final String CUSTOM_API_KEY_ENV = "CUSTOM_API_KEY";

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

    public String selectedAgentName = AgentLauncher.LOCAL_ASSISTANT_NAME;
    public double localAssistantFuzzyThreshold = 0.90;
    // Tuned against tests/benchmark/biologist_phrasings.jsonl in stage 03:
    // 0.02 misses "commands"; 0.05/0.08/0.10 catch the known misses.
    // Keep the smallest passing margin so confident phrases like "pixel size" stay quiet.
    public double localAssistantDisambiguationMargin = 0.05;
    public boolean expandMenuPhrasebook = false;

    public static final String DEFAULT_MINILM_MODEL_SHA256 =
            "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474";
    public boolean miniLmInstalled = false;
    public String miniLmModelSha256 = DEFAULT_MINILM_MODEL_SHA256;
    /**
     * One-launch consent for Claude's permission-skipping flag. AgentLauncher
     * consumes and clears this value when it constructs an approved Claude
     * command; it is deliberately false for new and migrated configurations.
     */
    public transient boolean claudeUseGsdFlag = false;
    public String gsdSkillsPath = "";

    public String claudeInstallCommand = "npm i -g @anthropic-ai/claude-code";
    public String codexInstallCommand = "npm i -g @openai/codex";
    public String aiderInstallCommand = "pip install aider-chat";
    public String geminiInstallCommand = "npm i -g @google/gemini-cli";
    public String ollamaInstallUrl = "https://ollama.com/download";
    public String gsdInstallCommand = "";
    public String gsdInstallDocsUrl =
            "https://www.claudepluginhub.com/commands/glittercowboy-get-shit-done/commands/gsd/help";

    /**
     * Stage 03 (embedded-agent-widget): when true, AgentLauncher launches the
     * agent's terminal UI inside the plugin frame (requires stage 05+). When
     * false, today's detached cmd.exe /c start behaviour is preserved.
     *
     * <p>Default is true so agent launches use the controllable in-window
     * terminal. The Settings dialog exposes this so users can still opt back
     * into a detached terminal.
     */
    public boolean agentEmbeddedTerminal = true;

    /**
     * Optional terminal-output audit log. Default stays false because terminal
     * scrollback may include pasted API keys, tokens, or paths the user did not
     * intend to persist.
     */
    public boolean persistScrollback = false;

    /**
     * Safe-mode v2 stage 02 master switch. When true, agent wrappers
     * negotiate {@code safe_mode=true} in the {@code hello} handshake;
     * when false, wrappers pass {@code safe_mode=false} so the user
     * gets the legacy unguarded fast path. Plumbed to launched agents
     * via the {@code IMAGEJAI_SAFE_MODE} environment variable.
     *
     * <p>Default true â€” Stage 02 makes safe mode the documented default.
     * Per plan: docs/safe_mode_v2/02_master-switch-and-caps.md.
     */
    public boolean safeModeEnabled = true;

    /**
     * Data Governance Privacy Posture for the current session. This remains
     * independent of safeModeEnabled: Safe Mode guards destructive ImageJ
     * actions, while Privacy Posture governs outbound data handling.
     */
    private PrivacyPosture privacyPosture = PrivacyPosture.PSEUDONYMISED;

    // === Multi-provider (Phase D) =====================================
    // Per docs/multi_provider/05_ui_design.md Â§9.4. Coexist with the v1
    // selectedAgentName above and v2's localAssistantDisambiguationMargin.
    // The new picker is gated by useMultiProviderPicker; existing users keep
    // today's flat agent dropdown until tier safety (Phase H) ships.
    public String selectedProvider;       // canonical hyphenated, e.g. "anthropic"
    public String selectedModelId;        // e.g. "claude-sonnet-4-6"
    public String defaultProvider;        // re-launch-last fallback
    public String defaultModelId;
    public int    refreshIntervalHours = 24;
    public boolean refreshOnStartup     = true;
    public boolean confirmPaidModels    = true;
    public boolean warnUncuratedModels  = true;
    public boolean budgetCeilingEnabled = false;
    public double  budgetCeilingUsd     = 1.00;
    public boolean showCurrencyFootnote = true;
    // Phase H flips the default to true (v4 milestone). Existing users with an
    // explicit useMultiProviderPicker=false in their settings.json keep the
    // old picker until they opt in via the Multi-Provider settings tab.
    public boolean useMultiProviderPicker = true;
    /** Per-machine dismissed-banner ids â€” written by Phase H's TierChangeBanner. */
    public java.util.Set<String> dismissedTierChangeBanners = new java.util.LinkedHashSet<>();
    /** Set after the first run where useMultiProviderPicker default flipped to true. */
    public boolean multiProviderFlipNoticeShown = false;
    /**
     * One-time migration for the hidden embedded-terminal flag. Before this
     * setting had UI, saved configs usually carried the old default false.
     */
    public boolean embeddedTerminalDefaultFlipApplied = false;

    /**
     * Per-provider cached error from the most recent {@code /models} probe â€”
     * surfaced by Phase E's CachedErrorDialog. Keyed by canonical hyphenated
     * provider id. Raw API keys are never stored here; those live in
     * {@code <imagej-ai>/secrets/&lt;provider&gt;.env}.
     */
    public java.util.Map<String, String> lastProviderErrors = new java.util.LinkedHashMap<>();

    // Transient
    private transient Path configPath;
    private transient imagejai.ui.installer.ProviderCredentials providerCredentials;
    /** Detached dialog copies must never write the live config file. */
    private transient boolean persistenceEnabled = true;

    public Settings() {
        configPath = getConfigDir().resolve("config.json");
    }

    /**
     * Deep, non-persisting working copy for transactional editors.
     * API keys are restored explicitly because production JSON intentionally
     * excludes them.
     */
    public synchronized Settings detachedCopy() {
        Settings copy = GSON.fromJson(GSON.toJson(this), Settings.class);
        if (copy == null) copy = new Settings();
        copy.configPath = configPath;
        copy.providerCredentials = providerCredentials;
        copy.persistenceEnabled = false;
        copy.claudeUseGsdFlag = claudeUseGsdFlag;
        copy.provider = provider;
        copy.apiKey = apiKey;
        copy.model = model;
        copy.ollamaUrl = ollamaUrl;
        copy.openaiUrl = openaiUrl;
        if (configs != null && copy.configs != null) {
            for (int i = 0; i < configs.size() && i < copy.configs.size(); i++) {
                ModelConfig original = configs.get(i);
                ModelConfig target = copy.configs.get(i);
                if (original != null && target != null) {
                    target.apiKey = original.apiKey == null ? "" : original.apiKey;
                }
            }
        }
        return copy;
    }

    /** Atomically replace live in-memory state with a detached snapshot. */
    public synchronized void applyFrom(Settings source) {
        if (source == null || source == this) return;
        Settings copy = source.detachedCopy();
        try {
            for (java.lang.reflect.Field field : Settings.class.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || "configPath".equals(field.getName())
                        || "providerCredentials".equals(field.getName())
                        || "persistenceEnabled".equals(field.getName())) {
                    continue;
                }
                field.setAccessible(true);
                field.set(this, field.get(copy));
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not apply settings snapshot", e);
        }
    }

    /** Values whose change requires rebuilding the live legacy chat backend. */
    public synchronized String backendFingerprint() {
        ModelConfig active = getActiveConfig();
        if (active == null) return "<none>";
        return String.valueOf(active.id) + '\u0000'
                + String.valueOf(active.provider) + '\u0000'
                + String.valueOf(active.model) + '\u0000'
                + String.valueOf(active.url) + '\u0000'
                + resolveApiKey(active) + '\u0000'
                + getPrivacyPosture().name();
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

    /**
     * Maps legacy {@link #selectedAgentName} display names (the v1 flat
     * {@code JComboBox} captions) to the new {@code "<provider>:<model_id>"}
     * key shape introduced by the multi-provider picker. Per
     * docs/multi_provider/05_ui_design.md Â§10.5 â€” used once at first launch on
     * the new build to seed {@link #selectedProvider} and {@link #selectedModelId}
     * so users don't lose their pick on the schema flip.
     *
     * <p>Visible (package-private) for the unit tests in
     * {@code SettingsLegacyMigrationTest} that pin every entry.
     */
    static java.util.Map<String, String> legacyAgentNameToNewKey() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("Claude Code", "cli:claude");
        m.put("Aider", "cli:aider");
        m.put("GitHub Copilot CLI", "cli:gh copilot");
        m.put("Gemini CLI", "cli:gemini");
        m.put("Open Interpreter", "cli:interpreter");
        m.put("Cline", "cli:cline");
        m.put("Codex CLI", "cli:codex");
        m.put("Gemma 4 31B", "cli:gemma4_31b_agent");
        m.put("Gemma 4 31B (Claude-style)", "cli:gemma4_31b_agent");
        m.put("Local Assistant", "cli:gemma4_31b_agent");
        return m;
    }

    /**
     * One-shot seed of {@link #selectedProvider}/{@link #selectedModelId} from
     * the legacy {@link #selectedAgentName} field. No-op once the new fields
     * carry a value, so subsequent runs leave the user's pick alone.
     */
    void seedMultiProviderFromLegacyAgentName() {
        if (selectedProvider != null && !selectedProvider.isEmpty()) {
            return;
        }
        if (selectedAgentName == null || selectedAgentName.trim().isEmpty()) {
            return;
        }
        String mapped = legacyAgentNameToNewKey().get(selectedAgentName);
        if (mapped == null) {
            return;
        }
        int colon = mapped.indexOf(':');
        if (colon <= 0 || colon == mapped.length() - 1) {
            return;
        }
        selectedProvider = mapped.substring(0, colon);
        selectedModelId = mapped.substring(colon + 1);
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
        if (miniLmModelSha256 == null || miniLmModelSha256.trim().isEmpty()) {
            miniLmModelSha256 = DEFAULT_MINILM_MODEL_SHA256;
        }
        if (gsdSkillsPath == null) gsdSkillsPath = "";
        if (claudeInstallCommand == null || claudeInstallCommand.trim().isEmpty()) {
            claudeInstallCommand = "npm i -g @anthropic-ai/claude-code";
        }
        if (codexInstallCommand == null || codexInstallCommand.trim().isEmpty()) {
            codexInstallCommand = "npm i -g @openai/codex";
        }
        if (aiderInstallCommand == null || aiderInstallCommand.trim().isEmpty()) {
            aiderInstallCommand = "pip install aider-chat";
        }
        if (geminiInstallCommand == null || geminiInstallCommand.trim().isEmpty()) {
            geminiInstallCommand = "npm i -g @google/gemini-cli";
        }
        if (ollamaInstallUrl == null || ollamaInstallUrl.trim().isEmpty()) {
            ollamaInstallUrl = "https://ollama.com/download";
        }
        if (gsdInstallCommand == null) gsdInstallCommand = "";
        if (gsdInstallDocsUrl == null || gsdInstallDocsUrl.trim().isEmpty()) {
            gsdInstallDocsUrl =
                    "https://www.claudepluginhub.com/commands/glittercowboy-get-shit-done/commands/gsd/help";
        }
        // Multi-provider Phase D Â§10.5: seed (selectedProvider, selectedModelId)
        // from the legacy selectedAgentName so users who configured an agent
        // before the new picker shipped don't lose their pick on first launch.
        if (privacyPosture == null) {
            privacyPosture = PrivacyPosture.PSEUDONYMISED;
        }
        if (!embeddedTerminalDefaultFlipApplied) {
            agentEmbeddedTerminal = true;
            embeddedTerminalDefaultFlipApplied = true;
        }
        seedMultiProviderFromLegacyAgentName();
        migrateLegacyApiKeysToSecrets();
    }

    /**
     * Relocate any legacy {@link ModelConfig#apiKey} carried by an old
     * config.json into the per-provider {@code .env} secrets store, so the
     * proxy path keeps working after apiKey stops being serialized (verifier
     * #3). The in-memory value is left intact for this session so the legacy
     * direct path is unaffected; it simply never reaches disk again. Providers
     * with no known env-var mapping (e.g. "custom"/"ollama") are skipped — their
     * key is also no longer persisted, but cannot be relocated automatically.
     * Never overwrites a key already present in the secrets store.
     */
    // Package-private so SettingsApiKeyResolutionTest can drive it directly.
    void migrateLegacyApiKeysToSecrets() {
        if (configs == null) {
            return;
        }
        for (ModelConfig c : configs) {
            if (c == null || c.apiKey == null || c.apiKey.trim().isEmpty()) {
                continue;
            }
            String prov = c.provider;
            if (prov == null || "ollama".equals(prov)) {
                continue;
            }
            try {
                if ("custom".equals(prov)) {
                    // Check the specific key, not hasCredentials("custom") — an
                    // unmapped provider reports "has credentials" for any
                    // non-empty custom.env, which would wrongly block migration.
                    String existing = providerCredentials().read("custom").get(CUSTOM_API_KEY_ENV);
                    if (existing == null || existing.isEmpty()) {
                        java.util.Map<String, String> entries =
                                new java.util.LinkedHashMap<String, String>();
                        entries.put(CUSTOM_API_KEY_ENV, c.apiKey.trim());
                        providerCredentials().saveEntries("custom", entries);
                    }
                } else if (imagejai.ui.installer.ProviderCredentials
                        .ENV_VAR_FOR_PROVIDER.containsKey(prov)) {
                    if (!providerCredentials().hasCredentials(prov)) {
                        providerCredentials().saveApiKey(prov, c.apiKey.trim());
                    }
                }
            } catch (Exception e) {
                System.err.println("[ImageJAI] Could not migrate " + prov
                        + " key to secrets store: " + e.getMessage());
            }
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

    public String getSelectedAgentName() {
        return selectedAgentName == null || selectedAgentName.trim().isEmpty()
                ? AgentLauncher.LOCAL_ASSISTANT_NAME
                : selectedAgentName;
    }

    public void setSelectedAgentName(String name) {
        selectedAgentName = name == null || name.trim().isEmpty()
                ? AgentLauncher.LOCAL_ASSISTANT_NAME
                : name;
        save();
    }

    public PrivacyPosture getPrivacyPosture() {
        if (privacyPosture == null) {
            privacyPosture = PrivacyPosture.PSEUDONYMISED;
        }
        return privacyPosture;
    }

    public void setPrivacyPosture(PrivacyPosture posture) {
        privacyPosture = posture == null
                ? PrivacyPosture.PSEUDONYMISED
                : posture;
    }

    public double getLocalAssistantFuzzyThreshold() {
        if (localAssistantFuzzyThreshold <= 0.0 || localAssistantFuzzyThreshold > 1.0) {
            return 0.90;
        }
        return localAssistantFuzzyThreshold;
    }

    public void setLocalAssistantFuzzyThreshold(double threshold) {
        if (threshold <= 0.0) {
            localAssistantFuzzyThreshold = 0.90;
        } else {
            localAssistantFuzzyThreshold = Math.min(1.0, threshold);
        }
        save();
    }

    public double getLocalAssistantDisambiguationMargin() {
        if (localAssistantDisambiguationMargin < 0.0 || localAssistantDisambiguationMargin > 1.0) {
            return 0.05;
        }
        return localAssistantDisambiguationMargin;
    }

    public void setLocalAssistantDisambiguationMargin(double margin) {
        if (margin < 0.0) {
            localAssistantDisambiguationMargin = 0.05;
        } else {
            localAssistantDisambiguationMargin = Math.min(1.0, margin);
        }
        save();
    }

    /**
     * Save current settings to disk atomically.
     *
     * <p>Writes to a sibling tmp file then renames over the live path
     * (E.7 in docs/multi_provider/08_verification_report.md). Two concurrent
     * Fiji instances dismissing different tier-change banners or saving
     * different {@link #lastProviderErrors} cannot race away half-written
     * files because the rename is the only step that mutates the user's
     * config.json.
     *
     * <p>{@code ATOMIC_MOVE} is best-effort: on filesystems that don't support
     * it (rare on local NTFS / ext4 / APFS) the rename falls back to a
     * REPLACE_EXISTING copy, which is still safer than the previous direct
     * truncate-and-rewrite path.
     */
    public synchronized void save() {
        if (!persistenceEnabled) {
            return;
        }
        try {
            Files.createDirectories(configPath.getParent());
            Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
            try {
                Files.move(tmp, configPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignore) {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("[ImageJAI] Failed to save settings: " + e.getMessage());
        }
    }

    /**
     * The effective API key for a config: the in-memory {@link ModelConfig#apiKey}
     * if present, otherwise the value relocated to the {@code <provider>.env}
     * secrets store. Lets the legacy direct backends keep working after apiKey
     * stopped being serialized to config.json (verifier #3 / #3-2).
     */
    public String resolveApiKey(ModelConfig config) {
        if (config == null) {
            return "";
        }
        if (config.apiKey != null && !config.apiKey.trim().isEmpty()) {
            return config.apiKey.trim();
        }
        String prov = config.provider;
        if (prov == null) {
            return "";
        }
        try {
            if ("custom".equals(prov)) {
                String v = providerCredentials().read("custom").get(CUSTOM_API_KEY_ENV);
                return v == null ? "" : v;
            }
            String envName = imagejai.ui.installer.ProviderCredentials
                    .ENV_VAR_FOR_PROVIDER.get(prov);
            if (envName != null) {
                String v = providerCredentials().read(prov).get(envName);
                return v == null ? "" : v;
            }
        } catch (Exception ignored) {
            // fall through to empty
        }
        return "";
    }

    /**
     * Returns true if this appears to be a first-run (no API key configured).
     */
    public boolean isFirstRun() {
        if (tcpServerEnabled) return false;
        ModelConfig active = getActiveConfig();
        if (active == null) return true;
        if ("ollama".equals(active.provider)) return false;
        String key = resolveApiKey(active);
        return key == null || key.trim().isEmpty();
    }

    /**
     * Returns true if an API key is configured for the current provider.
     */
    public boolean hasApiKey() {
        ModelConfig active = getActiveConfig();
        if (active == null) return false;

        if ("gemini".equals(active.provider) || "openai".equals(active.provider) || "custom".equals(active.provider)) {
            String key = resolveApiKey(active);
            return key != null && !key.trim().isEmpty();
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

    /**
     * Lazily-instantiated handle for the per-provider .env credential store.
     * Tests inject a custom store via {@link #setProviderCredentials}.
     */
    public imagejai.ui.installer.ProviderCredentials providerCredentials() {
        if (providerCredentials == null) {
            providerCredentials = new imagejai.ui.installer.ProviderCredentials();
        }
        return providerCredentials;
    }

    public void setProviderCredentials(imagejai.ui.installer.ProviderCredentials store) {
        this.providerCredentials = store;
    }

    /**
     * True when the named provider has a non-empty key on disk. Backed by the
     * {@code .env} files under {@code <imagej-ai>/secrets/} â€” never reads the
     * settings JSON, so exporting settings.json cannot leak credentials.
     */
    public boolean hasCredentialsFor(String providerKey) {
        if (providerKey == null) {
            return false;
        }
        if (imagejai.ui.installer.ProviderCredentials.isLocalDaemonProvider(providerKey)) {
            // Ollama (local AND cloud) runs keyless through the signed-in local
            // daemon — the launch never checks OLLAMA_API_KEY. Returning true
            // keeps the dropdown's status icon green for biologists who haven't
            // touched settings yet. A key is an optional override, not required.
            return true;
        }
        return providerCredentials().hasCredentials(providerKey);
    }

    public String lastErrorFor(String providerKey) {
        if (providerKey == null || lastProviderErrors == null) {
            return null;
        }
        return lastProviderErrors.get(providerKey);
    }

    public void setLastError(String providerKey, String message) {
        if (providerKey == null) {
            return;
        }
        if (lastProviderErrors == null) {
            lastProviderErrors = new java.util.LinkedHashMap<>();
        }
        if (message == null || message.isEmpty()) {
            lastProviderErrors.remove(providerKey);
        } else {
            lastProviderErrors.put(providerKey, message);
        }
        save();
    }
}

