package imagejai.llm;

import com.google.gson.*;
import imagejai.config.Constants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama local LLM backend.
 * Communicates with a locally-running Ollama instance via its REST API.
 */
public class OllamaBackend implements LLMBackend {

    private final String baseUrl;
    private final String model;

    public OllamaBackend(String baseUrl, String model) {
        this.baseUrl = (baseUrl != null && !baseUrl.isEmpty()) ? stripTrailingSlash(baseUrl) : Constants.DEFAULT_OLLAMA_URL;
        this.model = (model != null && !model.isEmpty()) ? model : Constants.DEFAULT_OLLAMA_MODEL;
    }

    @Override
    public LLMResponse chat(List<Message> messages, String systemPrompt) {
        return doChat(messages, systemPrompt, null);
    }

    @Override
    public LLMResponse chatWithVision(List<Message> messages, String systemPrompt, byte[] imageBytes) {
        return doChat(messages, systemPrompt, imageBytes);
    }

    @Override
    public boolean testConnection() {
        try {
            String response = HttpUtil.get(baseUrl + "/api/tags", null);
            // If we get a valid JSON response, the server is up
            new JsonParser().parse(response).getAsJsonObject();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "Ollama";
    }

    /**
     * List available models from the Ollama instance.
     *
     * @param baseUrl Ollama server URL
     * @return list of model names, or empty list on error
     */
    public static List<String> listModels(String baseUrl) {
        List<String> models = new ArrayList<String>();
        try {
            String url = stripTrailingSlash(baseUrl) + "/api/tags";
            String response = HttpUtil.get(url, null);
            JsonObject root = new JsonParser().parse(response).getAsJsonObject();
            if (root.has("models")) {
                JsonArray arr = root.getAsJsonArray("models");
                for (JsonElement el : arr) {
                    JsonObject modelObj = el.getAsJsonObject();
                    if (modelObj.has("name")) {
                        models.add(modelObj.get("name").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            // Return empty list on failure
        }
        return models;
    }

    // --- Internal ---

    private LLMResponse doChat(List<Message> messages, String systemPrompt, byte[] imageBytes) {
        try {
            String url = baseUrl + "/api/chat";
            String body = buildRequestBody(messages, systemPrompt, imageBytes);
            String response = HttpUtil.post(url, body, null);
            return parseResponse(response);
        } catch (IOException e) {
            return LLMResponse.error("Ollama error: " + e.getMessage());
        }
    }

    private String buildRequestBody(List<Message> messages, String systemPrompt, byte[] imageBytes) {
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("stream", false);

        JsonArray msgArray = new JsonArray();

        // Add system prompt as first message
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            msgArray.add(sysMsg);
        }

        for (Message msg : messages) {
            if ("system".equals(msg.getRole())) {
                continue; // Already handled above
            }

            JsonObject jsonMsg = new JsonObject();
            jsonMsg.addProperty("role", msg.getRole());
            jsonMsg.addProperty("content", msg.getContent() != null ? msg.getContent() : "");

            // Inline image from the message itself
            if (msg.hasImage()) {
                JsonArray images = new JsonArray();
                images.add(new JsonPrimitive(HttpUtil.encodeBase64(msg.getImage())));
                jsonMsg.add("images", images);
            }

            msgArray.add(jsonMsg);
        }

        // If separate imageBytes provided, attach to last user message
        if (imageBytes != null && imageBytes.length > 0 && msgArray.size() > 0) {
            JsonObject lastMsg = msgArray.get(msgArray.size() - 1).getAsJsonObject();
            JsonArray images = lastMsg.has("images") ? lastMsg.getAsJsonArray("images") : new JsonArray();
            images.add(new JsonPrimitive(HttpUtil.encodeBase64(imageBytes)));
            lastMsg.add("images", images);
        }

        request.add("messages", msgArray);
        return new Gson().toJson(request);
    }

    private LLMResponse parseResponse(String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();

            if (root.has("error")) {
                return LLMResponse.error("Ollama: " + root.get("error").getAsString());
            }

            JsonObject message = root.getAsJsonObject("message");
            if (message == null) {
                return LLMResponse.error("No message in Ollama response");
            }

            String content = message.has("content") ? message.get("content").getAsString() : "";

            // Ollama provides eval_count as approximate token usage
            int tokens = 0;
            if (root.has("eval_count")) {
                tokens = root.get("eval_count").getAsInt();
            }

            return LLMResponse.success(content, tokens);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse Ollama response: " + e.getMessage());
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
