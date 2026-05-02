package imagejai.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reply produced by the offline Local Assistant.
 */
public class AssistantReply {

    private final String text;
    private final String macroEcho;
    private final List<String> attachments;
    private final boolean clarifying;
    private final List<RankedPhrase> clarificationCandidates;

    public AssistantReply(String text, String macroEcho, List<String> attachments) {
        this(text, macroEcho, attachments, false, Collections.<RankedPhrase>emptyList());
    }

    private AssistantReply(String text, String macroEcho, List<String> attachments,
                           boolean clarifying, List<RankedPhrase> clarificationCandidates) {
        this.text = text == null ? "" : text;
        this.macroEcho = macroEcho;
        this.attachments = attachments == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(attachments);
        this.clarifying = clarifying;
        this.clarificationCandidates = clarificationCandidates == null
                ? Collections.<RankedPhrase>emptyList()
                : Collections.unmodifiableList(new ArrayList<RankedPhrase>(clarificationCandidates));
    }

    public static AssistantReply text(String text) {
        return new AssistantReply(text, null, Collections.<String>emptyList());
    }

    public static AssistantReply withMacro(String text, String macroEcho) {
        return new AssistantReply(text, macroEcho, Collections.<String>emptyList());
    }

    public static AssistantReply clarifying(String question, List<RankedPhrase> candidates) {
        return new AssistantReply(question, null, Collections.<String>emptyList(),
                true, candidates);
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

    public boolean isClarifying() {
        return clarifying;
    }

    public List<RankedPhrase> clarificationCandidates() {
        return clarificationCandidates;
    }
}
