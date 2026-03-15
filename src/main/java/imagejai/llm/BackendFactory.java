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
        String provider = settings.provider != null ? settings.provider.toLowerCase().trim() : "";

        switch (provider) {
            case "gemini":
                return new GeminiBackend(settings.apiKey, settings.model);
            case "ollama":
                return new OllamaBackend(settings.ollamaUrl, settings.model);
            case "openai":
                return new OpenAICompatibleBackend(settings.openaiUrl, settings.apiKey, settings.model);
            case "custom":
                return new OpenAICompatibleBackend(settings.customUrl, settings.customApiKey, settings.customModel);
            default:
                throw new IllegalArgumentException("Unknown LLM provider: " + provider +
                        ". Supported: gemini, ollama, openai, custom");
        }
    }
}
