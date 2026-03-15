package imagejai.llm;

import com.google.gson.*;
import imagejai.config.Constants;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible API backend.
 * Works with OpenAI, Groq, Together, and any service implementing
 * the /chat/completions endpoint.
 */
public class OpenAICompatibleBackend implements LLMBackend {

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAICompatibleBackend(String baseUrl, String apiKey, String model) {
        this.baseUrl = (baseUrl != null && !baseUrl.isEmpty()) ? stripTrailingSlash(baseUrl) : Constants.DEFAULT_OPENAI_URL;
        this.apiKey = apiKey;
        this.model = (model != null && !model.isEmpty()) ? model : Constants.DEFAULT_OPENAI_MODEL;
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
            List<Message> test = java.util.Collections.singletonList(Message.user("Hello. Reply with OK."));
            LLMResponse resp = chat(test, "Reply briefly.");
            return resp.isSuccess() && resp.getContent() != null && !resp.getContent().isEmpty();
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
        return "OpenAI-compatible";
    }

    // --- Internal ---

    private LLMResponse doChat(List<Message> messages, String systemPrompt, byte[] imageBytes) {
        try {
            String url = baseUrl + "/chat/completions";
            String body = buildRequestBody(messages, systemPrompt, imageBytes);

            Map<String, String> headers = new HashMap<String, String>();
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.put("Authorization", "Bearer " + apiKey);
            }

            String response = HttpUtil.post(url, body, headers);
            return parseResponse(response);
        } catch (IOException e) {
            return LLMResponse.error("OpenAI API error: " + e.getMessage());
        }
    }

    private String buildRequestBody(List<Message> messages, String systemPrompt, byte[] imageBytes) {
        JsonObject request = new JsonObject();
        request.addProperty("model", model);

        JsonArray msgArray = new JsonArray();

        // System message
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            msgArray.add(sysMsg);
        }

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if ("system".equals(msg.getRole())) {
                continue;
            }

            JsonObject jsonMsg = new JsonObject();
            jsonMsg.addProperty("role", msg.getRole());

            boolean needsImageInContent = msg.hasImage() ||
                    (imageBytes != null && imageBytes.length > 0 && i == messages.size() - 1);

            if (needsImageInContent) {
                // Use content array format for vision
                JsonArray contentArray = new JsonArray();

                // Text part
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("type", "text");
                    textPart.addProperty("text", msg.getContent());
                    contentArray.add(textPart);
                }

                // Image from message itself
                if (msg.hasImage()) {
                    contentArray.add(buildImageUrlPart(msg.getImage()));
                }

                // Separate imageBytes on last message
                if (imageBytes != null && imageBytes.length > 0 && i == messages.size() - 1) {
                    contentArray.add(buildImageUrlPart(imageBytes));
                }

                jsonMsg.add("content", contentArray);
            } else {
                jsonMsg.addProperty("content", msg.getContent() != null ? msg.getContent() : "");
            }

            msgArray.add(jsonMsg);
        }

        request.add("messages", msgArray);
        return new Gson().toJson(request);
    }

    private JsonObject buildImageUrlPart(byte[] imageData) {
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/png;base64," + HttpUtil.encodeBase64(imageData));
        imagePart.add("image_url", imageUrl);
        return imagePart;
    }

    private LLMResponse parseResponse(String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();

            if (root.has("error")) {
                JsonObject err = root.getAsJsonObject("error");
                String msg = err.has("message") ? err.get("message").getAsString() : "Unknown API error";
                return LLMResponse.error(msg);
            }

            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return LLMResponse.error("No choices in API response");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            String content = message.has("content") ? message.get("content").getAsString() : "";

            // Extract token usage
            int tokens = 0;
            if (root.has("usage")) {
                JsonObject usage = root.getAsJsonObject("usage");
                if (usage.has("total_tokens")) {
                    tokens = usage.get("total_tokens").getAsInt();
                }
            }

            return LLMResponse.success(content, tokens);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse API response: " + e.getMessage());
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url != null && url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
