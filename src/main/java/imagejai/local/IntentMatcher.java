package imagejai.local;

import imagejai.local.intents.HelpIntent;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal stage-02 matcher. Phrasebook and fuzzy matching land later.
 */
public class IntentMatcher {

    public Optional<MatchedIntent> match(String input) {
        String normalised = normalise(input);
        if ("help".equals(normalised)) {
            return Optional.of(new MatchedIntent(
                    HelpIntent.ID,
                    Collections.<String, String>emptyMap()));
        }
        return Optional.empty();
    }

    private String normalise(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public static class MatchedIntent {
        private final String intentId;
        private final Map<String, String> slots;

        public MatchedIntent(String intentId, Map<String, String> slots) {
            this.intentId = intentId;
            this.slots = slots == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(slots);
        }

        public String intentId() {
            return intentId;
        }

        public Map<String, String> slots() {
            return slots;
        }
    }
}
