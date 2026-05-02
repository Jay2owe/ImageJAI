package imagejai.local;

import imagejai.config.Settings;
import imagejai.engine.FuzzyMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
    private static final double SUGGESTION_FLOOR = 0.70;

    private final IntentLibrary library;
    private final Settings settings;

    public IntentMatcher() {
        this(IntentLibrary.load(), new Settings());
    }

    public IntentMatcher(IntentLibrary library) {
        this(library, new Settings());
    }

    public IntentMatcher(IntentLibrary library, Settings settings) {
        this.library = library == null ? IntentLibrary.load() : library;
        this.settings = settings == null ? new Settings() : settings;
    }

    public Optional<MatchedIntent> match(String input) {
        String intentId = library.phraseToIntentId().get(normalise(input));
        if (intentId != null) {
            return Optional.of(new MatchedIntent(
                    intentId,
                    extractSlots(intentId, input)));
        }
        List<RankedPhrase> ranked = topK(input, 1);
        if (!ranked.isEmpty()
                && ranked.get(0).score() >= settings.getLocalAssistantFuzzyThreshold()) {
            return Optional.of(new MatchedIntent(
                    ranked.get(0).intentId(),
                    extractSlots(ranked.get(0).intentId(), input)));
        }
        return Optional.empty();
    }

    public List<RankedPhrase> topK(String input, int k) {
        if (k <= 0) {
            return Collections.emptyList();
        }
        String key = normalise(input);
        if (key.length() == 0) {
            return Collections.emptyList();
        }

        List<RankedPhrase> ranked = new ArrayList<RankedPhrase>();
        Map<String, String> phraseToIntent = library.phraseToIntentId();
        String sortedKey = sortTokens(key);
        for (String phrase : library.allPhrases()) {
            String intentId = phraseToIntent.get(phrase);
            double score = similarity(key, sortedKey, phrase);
            ranked.add(new RankedPhrase(phrase, intentId, score));
        }

        Collections.sort(ranked, new Comparator<RankedPhrase>() {
            @Override
            public int compare(RankedPhrase a, RankedPhrase b) {
                int byScore = Double.compare(b.score(), a.score());
                if (byScore != 0) {
                    return byScore;
                }
                int byLength = Integer.compare(a.phrase().length(), b.phrase().length());
                if (byLength != 0) {
                    return byLength;
                }
                return a.phrase().compareTo(b.phrase());
            }
        });

        if (ranked.isEmpty() || ranked.get(0).score() < SUGGESTION_FLOOR) {
            return Collections.emptyList();
        }
        int limit = Math.min(k, ranked.size());
        return Collections.unmodifiableList(new ArrayList<RankedPhrase>(ranked.subList(0, limit)));
    }

    public static String normalise(String input) {
        if (input == null) {
            return "";
        }
        String s = input.toLowerCase(Locale.ROOT);
        s = PUNCT.matcher(s).replaceAll(" ");
        return WS.matcher(s).replaceAll(" ").trim();
    }

    private static double similarity(String key, String sortedKey, String phrase) {
        double direct = FuzzyMatcher.jaroWinkler(key, phrase);
        String sortedPhrase = sortTokens(phrase);
        if (sortedKey.equals(key) && sortedPhrase.equals(phrase)) {
            return direct;
        }
        return Math.max(direct, FuzzyMatcher.jaroWinkler(sortedKey, sortedPhrase));
    }

    private static String sortTokens(String input) {
        String normalised = normalise(input);
        if (normalised.length() == 0) {
            return "";
        }
        String[] parts = normalised.split(" ");
        java.util.Arrays.sort(parts);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static Map<String, String> extractSlots(String intentId, String input) {
        Map<String, String> slots = new HashMap<String, String>();
        String raw = input == null ? "" : input;
        String key = normalise(raw);
        if ("image.switch_channel".equals(intentId)) {
            putFirstInt(slots, "channel", key);
        } else if ("image.jump_slice".equals(intentId)) {
            putFirstInt(slots, "slice", key);
        } else if ("image.jump_frame".equals(intentId)) {
            putFirstInt(slots, "frame", key);
        } else if ("image.scale_by_factor".equals(intentId)) {
            if (key.contains("half")) {
                slots.put("factor", "0.5");
            } else if (key.contains("double")) {
                slots.put("factor", "2");
            } else {
                putFirstDouble(slots, "factor", key);
            }
        } else if ("display.zoom".equals(intentId)) {
            putFirstDouble(slots, "percent", key);
        } else if ("image.set_scale".equals(intentId)) {
            extractScaleSlots(slots, key);
        } else if ("image.make_substack".equals(intentId)) {
            extractSubstackSlots(slots, key);
        }
        return slots;
    }

    private static void putFirstInt(Map<String, String> slots, String name, String key) {
        java.util.regex.Matcher matcher = Pattern.compile("\\b(\\d+)\\b").matcher(key);
        if (matcher.find()) {
            slots.put(name, matcher.group(1));
        }
    }

    private static void putFirstDouble(Map<String, String> slots, String name, String key) {
        java.util.regex.Matcher matcher = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\b").matcher(key);
        if (matcher.find()) {
            slots.put(name, matcher.group(1));
        }
    }

    private static void extractScaleSlots(Map<String, String> slots, String key) {
        java.util.regex.Matcher matcher = Pattern.compile(
                "(\\d+(?:\\.\\d+)?)\\s*(?:px|pixel|pixels)\\s*(?:equals|equal|is|to)?\\s*(\\d+(?:\\.\\d+)?)\\s*([a-z]+)")
                .matcher(key);
        if (matcher.find()) {
            slots.put("pixels", matcher.group(1));
            slots.put("distance", matcher.group(2));
            slots.put("unit", matcher.group(3));
        }
    }

    private static void extractSubstackSlots(Map<String, String> slots, String key) {
        putRangeSlot(slots, key, "channels", "(?:channels|channel|c)");
        putRangeSlot(slots, key, "slices", "(?:slices|slice|z)");
        putRangeSlot(slots, key, "frames", "(?:frames|frame|time|t)");
    }

    private static void putRangeSlot(Map<String, String> slots, String key,
                                     String slot, String labelPattern) {
        java.util.regex.Matcher matcher = Pattern.compile(labelPattern
                + "\\s+(\\d+)\\s*(?:to|through|-)?\\s*(\\d+)?").matcher(key);
        if (matcher.find()) {
            String start = matcher.group(1);
            String end = matcher.group(2);
            slots.put(slot, end == null ? start : start + "-" + end);
        }
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
