package imagejai.local;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tier-1 matcher backed by the baked phrasebook hash map.
 */
public class IntentMatcher {

    private static final Pattern PUNCT = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern WS = Pattern.compile("\\s+");

    private final IntentLibrary library;

    public IntentMatcher() {
        this(IntentLibrary.load());
    }

    public IntentMatcher(IntentLibrary library) {
        this.library = library == null ? IntentLibrary.load() : library;
    }

    public Optional<MatchedIntent> match(String input) {
        String intentId = library.phraseToIntentId().get(normalise(input));
        if (intentId != null) {
            return Optional.of(new MatchedIntent(
                    intentId,
                    Collections.<String, String>emptyMap()));
        }
        return Optional.empty();
    }

    public static String normalise(String input) {
        if (input == null) {
            return "";
        }
        String s = input.toLowerCase(Locale.ROOT);
        s = PUNCT.matcher(s).replaceAll(" ");
        return WS.matcher(s).replaceAll(" ").trim();
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
