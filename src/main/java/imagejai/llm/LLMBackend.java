package imagejai.llm;

import java.util.List;

/**
 * Interface for LLM backends. All implementations must handle
 * their own HTTP communication and response parsing.
 */
public interface LLMBackend {

    /**
     * Send a chat conversation to the LLM.
     *
     * @param messages     conversation history
     * @param systemPrompt system-level instructions
     * @return LLM response
     */
    LLMResponse chat(List<Message> messages, String systemPrompt);

    /**
     * Send a chat conversation with an image for vision analysis.
     *
     * @param messages     conversation history
     * @param systemPrompt system-level instructions
     * @param imageBytes   PNG image data
     * @return LLM response
     */
    LLMResponse chatWithVision(List<Message> messages, String systemPrompt, byte[] imageBytes);

    /**
     * Test whether the backend is reachable and the API key (if any) is valid.
     *
     * @return true if a simple request succeeds
     */
    boolean testConnection();

    /**
     * @return the model identifier (e.g. "gemini-2.0-flash", "llama3")
     */
    String getModelName();

    /**
     * @return the provider name (e.g. "Gemini", "Ollama", "OpenAI-compatible")
     */
    String getProviderName();
}
