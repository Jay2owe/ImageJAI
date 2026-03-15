package uk.ac.ucl.imagej.ai.llm;

/**
 * Response model from LLM backends.
 * Encapsulates the response text, success status, and token usage.
 */
public class LLMResponse {

    private final String content;
    private final boolean success;
    private final String error;
    private final int tokensUsed;

    private LLMResponse(String content, boolean success, String error, int tokensUsed) {
        this.content = content;
        this.success = success;
        this.error = error;
        this.tokensUsed = tokensUsed;
    }

    // --- Factory methods ---

    public static LLMResponse success(String content) {
        return new LLMResponse(content, true, null, 0);
    }

    public static LLMResponse success(String content, int tokensUsed) {
        return new LLMResponse(content, true, null, tokensUsed);
    }

    public static LLMResponse error(String errorMessage) {
        return new LLMResponse(null, false, errorMessage, 0);
    }

    // --- Getters ---

    public String getContent() {
        return content;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    public int getTokensUsed() {
        return tokensUsed;
    }

    @Override
    public String toString() {
        if (success) {
            return "LLMResponse{success, tokens=" + tokensUsed + ", content='" +
                    (content != null && content.length() > 80 ? content.substring(0, 80) + "..." : content) + "'}";
        } else {
            return "LLMResponse{error='" + error + "'}";
        }
    }
}
