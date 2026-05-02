package imagejai.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One pending clarification owned by a Local Assistant chat session.
 */
public final class PendingTurn {
    public enum Kind {
        PARAMETER,
        DISAMBIGUATION
    }

    private final Kind kind;
    private final String question;
    private final String intentId;
    private final Map<String, String> filledSlots;
    private final String missingSlot;
    private final String defaultValue;
    private final List<RankedPhrase> candidates;
    private final long createdAtMs;

    private PendingTurn(Kind kind, String question, String intentId,
                        Map<String, String> filledSlots, String missingSlot,
                        String defaultValue, List<RankedPhrase> candidates,
                        long createdAtMs) {
        this.kind = kind;
        this.question = question == null ? "" : question;
        this.intentId = intentId == null ? "" : intentId;
        this.filledSlots = filledSlots == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(filledSlots));
        this.missingSlot = missingSlot == null ? "" : missingSlot;
        this.defaultValue = defaultValue;
        this.candidates = candidates == null
                ? Collections.<RankedPhrase>emptyList()
                : Collections.unmodifiableList(new ArrayList<RankedPhrase>(candidates));
        this.createdAtMs = createdAtMs;
    }

    public static PendingTurn parameter(String intentId, Map<String, String> filledSlots,
                                        String missingSlot, String question,
                                        String defaultValue) {
        return new PendingTurn(Kind.PARAMETER, question, intentId, filledSlots,
                missingSlot, defaultValue, Collections.<RankedPhrase>emptyList(),
                System.currentTimeMillis());
    }

    public static PendingTurn disambiguation(String question, List<RankedPhrase> candidates) {
        return new PendingTurn(Kind.DISAMBIGUATION, question, "", Collections.<String, String>emptyMap(),
                "", null, candidates, System.currentTimeMillis());
    }

    public Kind kind() {
        return kind;
    }

    public String question() {
        return question;
    }

    public String intentId() {
        return intentId;
    }

    public Map<String, String> filledSlots() {
        return filledSlots;
    }

    public String missingSlot() {
        return missingSlot;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public List<RankedPhrase> candidates() {
        return candidates;
    }

    public long createdAtMs() {
        return createdAtMs;
    }
}
