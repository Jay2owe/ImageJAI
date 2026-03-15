package uk.ac.ucl.imagej.ai.llm;

/**
 * Chat message model for LLM conversations.
 * Supports text messages and optional image attachments for vision.
 */
public class Message {

    private String role;      // "system", "user", "assistant"
    private String content;   // text content
    private byte[] image;     // optional image bytes (PNG) for vision

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
        this.image = null;
    }

    public Message(String role, String content, byte[] image) {
        this.role = role;
        this.content = content;
        this.image = image;
    }

    // --- Getters ---

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public byte[] getImage() {
        return image;
    }

    public boolean hasImage() {
        return image != null && image.length > 0;
    }

    // --- Builder-style setters ---

    public Message withRole(String role) {
        this.role = role;
        return this;
    }

    public Message withContent(String content) {
        this.content = content;
        return this;
    }

    public Message withImage(byte[] image) {
        this.image = image;
        return this;
    }

    // --- Factory methods ---

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message userWithImage(String content, byte[] image) {
        return new Message("user", content, image);
    }

    @Override
    public String toString() {
        return "Message{role='" + role + "', content='" +
                (content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content) +
                "', hasImage=" + hasImage() + "}";
    }
}
