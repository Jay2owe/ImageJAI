package imagejai.llm;

import imagejai.config.Settings;

/**
 * Factory for creating the appropriate LLM backend from user settings.
 */
public final class BackendFactory {

    private BackendFactory() {}

    /**
     * Create an LLM backend based on the current settings.
     *
     * @param settings user configuration
     * @return configured LLM backend
     * @throws IllegalArgumentException if the provider is unknown
     */
    public static LLMBackend create(Settings settings) {
        Settings.ModelConfig config = settings.getActiveConfig();
        if (config == null) {
            throw new IllegalArgumentException("No active model configuration");
        }

        String provider = config.provider != null ? config.provider.toLowerCase().trim() : "";
        // Resolve from the secrets store when the key is no longer carried
        // in-memory (it is no longer serialized to config.json — verifier #3).
        String apiKey = settings.resolveApiKey(config);

        switch (provider) {
            case "gemini":
                return new GeminiBackend(apiKey, config.model);
            case "ollama":
                return new OllamaBackend(config.url, config.model);
            case "openai":
                return new OpenAICompatibleBackend(config.url, apiKey, config.model);
            case "custom":
                return new OpenAICompatibleBackend(config.url, apiKey, config.model);
            default:
                throw new IllegalArgumentException("Unknown LLM provider: " + provider +
                        ". Supported: gemini, ollama, openai, custom");
        }
    }
}
