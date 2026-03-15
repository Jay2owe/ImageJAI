package uk.ac.ucl.imagej.ai.llm;

import com.google.gson.*;
import uk.ac.ucl.imagej.ai.config.Constants;

import java.io.IOException;
import java.util.List;

/**
 * Google Gemini API backend.
 * Uses the generativelanguage REST API with API key authentication.
 */
public class GeminiBackend implements LLMBackend {

    private final String apiKey;
    private final String model;

    public GeminiBackend(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = (model != null && !model.isEmpty()) ? model : Constants.DEFAULT_GEMINI_MODEL;
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
        return "Gemini";
    }

    // --- Internal ---

    private LLMResponse doChat(List<Message> messages, String systemPrompt, byte[] imageBytes) {
        try {
            String url = Constants.GEMINI_API_BASE + model + ":generateContent?key=" + apiKey;
            String body = buildRequestBody(messages, systemPrompt, imageBytes);

            String response = executeWithRetry(url, body, 3);
            return parseResponse(response);
        } catch (IOException e) {
            return LLMResponse.error("Gemini API error: " + e.getMessage());
        }
    }

    private String executeWithRetry(String url, String body, int maxRetries) throws IOException {
        int attempt = 0;
        long backoffMs = 1000;

        while (true) {
            try {
                return HttpUtil.post(url, body, null);
            } catch (IOException e) {
                String msg = e.getMessage();
                boolean isRateLimit = msg != null && msg.contains("429");
                attempt++;
                if (!isRateLimit || attempt >= maxRetries) {
                    throw e;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry backoff", ie);
                }
                backoffMs *= 2;
            }
        }
    }

    private String buildRequestBody(List<Message> messages, String systemPrompt, byte[] imageBytes) {
        JsonObject request = new JsonObject();

        // System instruction
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sysInstruction = new JsonObject();
            JsonArray sysParts = new JsonArray();
            JsonObject sysText = new JsonObject();
            sysText.addProperty("text", systemPrompt);
            sysParts.add(sysText);
            sysInstruction.add("parts", sysParts);
            request.add("systemInstruction", sysInstruction);
        }

        // Contents array
        JsonArray contents = new JsonArray();
        for (Message msg : messages) {
            // Gemini uses "user" and "model" roles (not "assistant")
            String role = "assistant".equals(msg.getRole()) ? "model" : msg.getRole();
            // Skip system messages — they go in systemInstruction
            if ("system".equals(msg.getRole())) {
                continue;
            }

            JsonObject content = new JsonObject();
            content.addProperty("role", role);
            JsonArray parts = new JsonArray();

            // Text part
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                JsonObject textPart = new JsonObject();
                textPart.addProperty("text", msg.getContent());
                parts.add(textPart);
            }

            // Image part — attach to the message that has it, or to the last user message
            if (msg.hasImage()) {
                addImagePart(parts, msg.getImage());
            }

            content.add("parts", parts);
            contents.add(content);
        }

        // If separate imageBytes provided (from chatWithVision), attach to last user content
        if (imageBytes != null && imageBytes.length > 0) {
            if (contents.size() > 0) {
                JsonObject lastContent = contents.get(contents.size() - 1).getAsJsonObject();
                JsonArray lastParts = lastContent.getAsJsonArray("parts");
                addImagePart(lastParts, imageBytes);
            }
        }

        request.add("contents", contents);
        return new Gson().toJson(request);
    }

    private void addImagePart(JsonArray parts, byte[] imageData) {
        JsonObject imagePart = new JsonObject();
        JsonObject inlineData = new JsonObject();
        inlineData.addProperty("mime_type", "image/png");
        inlineData.addProperty("data", HttpUtil.encodeBase64(imageData));
        imagePart.add("inline_data", inlineData);
        parts.add(imagePart);
    }

    private LLMResponse parseResponse(String json) {
        try {
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();

            // Check for API error
            if (root.has("error")) {
                JsonObject err = root.getAsJsonObject("error");
                String msg = err.has("message") ? err.get("message").getAsString() : "Unknown Gemini error";
                return LLMResponse.error(msg);
            }

            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                return LLMResponse.error("No candidates in Gemini response");
            }

            JsonObject firstCandidate = candidates.get(0).getAsJsonObject();
            JsonObject content = firstCandidate.getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");

            StringBuilder text = new StringBuilder();
            for (JsonElement part : parts) {
                JsonObject partObj = part.getAsJsonObject();
                if (partObj.has("text")) {
                    text.append(partObj.get("text").getAsString());
                }
            }

            // Extract token count if available
            int tokens = 0;
            if (root.has("usageMetadata")) {
                JsonObject usage = root.getAsJsonObject("usageMetadata");
                if (usage.has("totalTokenCount")) {
                    tokens = usage.get("totalTokenCount").getAsInt();
                }
            }

            return LLMResponse.success(text.toString(), tokens);
        } catch (Exception e) {
            return LLMResponse.error("Failed to parse Gemini response: " + e.getMessage());
        }
    }
}
