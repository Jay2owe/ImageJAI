package imagejai.local;

import java.util.Collections;
import java.util.List;

/**
 * Reply produced by the offline Local Assistant.
 */
public class AssistantReply {

    private final String text;
    private final String macroEcho;
    private final List<String> attachments;

    public AssistantReply(String text, String macroEcho, List<String> attachments) {
        this.text = text == null ? "" : text;
        this.macroEcho = macroEcho;
        this.attachments = attachments == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(attachments);
    }

    public static AssistantReply text(String text) {
        return new AssistantReply(text, null, Collections.<String>emptyList());
    }

    public static AssistantReply withMacro(String text, String macroEcho) {
        return new AssistantReply(text, macroEcho, Collections.<String>emptyList());
    }

    public String text() {
        return text;
    }

    public String macroEcho() {
        return macroEcho;
    }

    public List<String> attachments() {
        return attachments;
    }
}
