package imagejai.local;

import imagejai.config.Settings;
import imagejai.engine.FuzzyMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

/**
 * Tier-1 matcher backed by the baked phrasebook hash map.
 */
public class IntentMatcher {

    private static final Pattern PUNCT = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern PUNCT_EXCEPT_DECIMAL = Pattern.compile("[^a-z0-9. ]");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final double SUGGESTION_FLOOR = 0.70;
    private static final int MAX_TOP_K = 20;
    private static final Comparator<RankedPhrase> RANK_ORDER =
            new Comparator<RankedPhrase>() {
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
                    int byIntent = a.intentId().compareTo(b.intentId());
                    if (byIntent != 0) {
                        return byIntent;
                    }
                    return a.phrase().compareTo(b.phrase());
                }
            };

    private final IntentLibrary library;
    private final Settings settings;
    private final Map<String, String> exactPhrases;
    private final List<PreparedPhrase> corpus;
    private final AtomicInteger lastCandidateEvaluations = new AtomicInteger();

    public IntentMatcher() {
        this(IntentLibrary.load(), new Settings());
    }

    public IntentMatcher(IntentLibrary library) {
        this(library, new Settings());
    }

    public IntentMatcher(IntentLibrary library, Settings settings) {
        this.library = library == null ? IntentLibrary.load() : library;
        this.settings = settings == null ? new Settings() : settings;
        Map<String, String> exact = new LinkedHashMap<String, String>();
        List<PreparedPhrase> prepared = new ArrayList<PreparedPhrase>();
        Map<String, String> phraseToIntent = this.library.phraseToIntentId();
        for (String rawPhrase : this.library.allPhrases()) {
            String phrase = normalise(rawPhrase);
            String intentId = phraseToIntent.get(phrase);
            if (phrase.length() == 0 || intentId == null) {
                continue;
            }
            exact.put(phrase, intentId);
            prepared.add(new PreparedPhrase(phrase, sortTokens(phrase), intentId));
        }
        this.exactPhrases = Collections.unmodifiableMap(exact);
        this.corpus = Collections.unmodifiableList(prepared);
    }

    public Optional<MatchedIntent> match(String input) {
        String intentId = exactPhrases.get(normalise(input));
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
            lastCandidateEvaluations.set(0);
            return Collections.emptyList();
        }
        String key = normalise(input);
        if (key.length() == 0) {
            lastCandidateEvaluations.set(0);
            return Collections.emptyList();
        }
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Fuzzy intent matching must run off the Swing event thread");
        }

        int limit = Math.min(k, MAX_TOP_K);
        Map<String, RankedPhrase> bestByIntent = new HashMap<String, RankedPhrase>();
        String sortedKey = sortTokens(key);
        int evaluations = 0;
        for (PreparedPhrase phrase : corpus) {
            double score = similarity(key, sortedKey, phrase);
            evaluations++;
            RankedPhrase candidate = new RankedPhrase(
                    phrase.phrase, phrase.intentId, score);
            RankedPhrase previous = bestByIntent.get(phrase.intentId);
            if (previous == null || RANK_ORDER.compare(candidate, previous) < 0) {
                bestByIntent.put(phrase.intentId, candidate);
            }
        }
        lastCandidateEvaluations.set(evaluations);

        List<RankedPhrase> ranked = new ArrayList<RankedPhrase>(limit);
        for (RankedPhrase candidate : bestByIntent.values()) {
            insertBounded(ranked, candidate, limit);
        }

        if (ranked.isEmpty() || ranked.get(0).score() < SUGGESTION_FLOOR) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(ranked);
    }

    public Match2Result match2(String input, double margin) {
        List<RankedPhrase> top = topK(input, 2);
        if (top.isEmpty()) {
            return Match2Result.miss();
        }
        RankedPhrase best = top.get(0);
        if (best.score() < settings.getLocalAssistantFuzzyThreshold()) {
            return Match2Result.miss();
        }
        if (margin <= 0.0) {
            return Match2Result.confident(best);
        }
        Match2Result stackCountAmbiguity = stackCountAmbiguity(input, best, margin);
        if (stackCountAmbiguity != null) {
            return stackCountAmbiguity;
        }
        if (top.size() == 1) {
            return Match2Result.confident(best);
        }

        RankedPhrase runner = top.get(1);
        if (best.intentId().equals(runner.intentId())) {
            return Match2Result.confident(best);
        }

        String exactIntentId = exactPhrases.get(normalise(input));
        if (exactIntentId != null) {
            if (exactIntentId.equals(runner.intentId())
                    && Math.abs(best.score() - runner.score()) < 0.0000001) {
                return Match2Result.confident(best);
            }
            if (exactIntentId.equals(best.intentId())
                    && runner.intentId().startsWith("menu.")) {
                return Match2Result.confident(best);
            }
        }

        double gap = best.score() - runner.score();
        return gap < margin
                ? Match2Result.ambiguous(best, runner, gap)
                : Match2Result.confident(best);
    }

    private Match2Result stackCountAmbiguity(String input, RankedPhrase best,
                                             double margin) {
        String key = normalise(input);
        if (!"how many frames".equals(key)
                || library.byId("image.dimensions") == null
                || library.byId("image.frame_count") == null) {
            return null;
        }

        RankedPhrase frameCount = "image.frame_count".equals(best.intentId())
                ? best
                : new RankedPhrase("how many frames", "image.frame_count", best.score());
        RankedPhrase dimensions = "image.dimensions".equals(best.intentId())
                ? best
                : new RankedPhrase("image dimensions", "image.dimensions",
                        Math.max(0.0, best.score() - (margin / 2.0)));
        double gap = Math.abs(frameCount.score() - dimensions.score());
        return Match2Result.ambiguous(frameCount, dimensions, gap);
    }

    public static String normalise(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("/")) {
            int split = firstWhitespace(trimmed);
            String command = split < 0 ? trimmed : trimmed.substring(0, split);
            String args = split < 0 ? "" : WS.matcher(trimmed.substring(split).trim()).replaceAll(" ");
            return args.length() == 0
                    ? command.toLowerCase(Locale.ROOT)
                    : command.toLowerCase(Locale.ROOT) + " " + args;
        }
        String s = input.toLowerCase(Locale.ROOT);
        s = PUNCT.matcher(s).replaceAll(" ");
        return WS.matcher(s).replaceAll(" ").trim();
    }

    private static int firstWhitespace(String input) {
        for (int i = 0; i < input.length(); i++) {
            if (Character.isWhitespace(input.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static double similarity(String key, String sortedKey, PreparedPhrase phrase) {
        double direct = FuzzyMatcher.jaroWinkler(key, phrase.phrase);
        if (sortedKey.equals(key) && phrase.sortedTokens.equals(phrase.phrase)) {
            return direct;
        }
        return Math.max(direct, FuzzyMatcher.jaroWinkler(sortedKey, phrase.sortedTokens));
    }

    private static void insertBounded(List<RankedPhrase> ranked,
                                      RankedPhrase candidate, int limit) {
        if (limit <= 0) {
            return;
        }
        int index = 0;
        while (index < ranked.size()
                && RANK_ORDER.compare(candidate, ranked.get(index)) >= 0) {
            index++;
        }
        if (index >= limit) {
            return;
        }
        ranked.add(index, candidate);
        if (ranked.size() > limit) {
            ranked.remove(ranked.size() - 1);
        }
    }

    int corpusSizeForTest() {
        return corpus.size();
    }

    int lastCandidateEvaluationsForTest() {
        return lastCandidateEvaluations.get();
    }

    private static final class PreparedPhrase {
        final String phrase;
        final String sortedTokens;
        final String intentId;

        PreparedPhrase(String phrase, String sortedTokens, String intentId) {
            this.phrase = phrase;
            this.sortedTokens = sortedTokens;
            this.intentId = intentId;
        }
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
        String numericKey = normaliseNumbers(raw);
        if ("image.switch_channel".equals(intentId)) {
            putFirstInt(slots, "channel", key);
        } else if ("image.jump_slice".equals(intentId)) {
            putFirstInt(slots, "slice", key);
        } else if ("image.jump_frame".equals(intentId)) {
            putFirstInt(slots, "frame", key);
        } else if ("image.scale_by_factor".equals(intentId)
                || "image.scale".equals(intentId)) {
            if (key.contains("half")) {
                slots.put("factor", "0.5");
            } else if (key.contains("double")) {
                slots.put("factor", "2");
            } else {
                putFirstDouble(slots, "factor", numericKey);
            }
        } else if ("display.zoom".equals(intentId)) {
            putFirstDouble(slots, "percent", numericKey);
        } else if ("image.set_scale".equals(intentId)) {
            extractScaleSlots(slots, numericKey);
        } else if ("image.make_substack".equals(intentId)) {
            extractSubstackSlots(slots, key);
        } else if ("image.save_as".equals(intentId)) {
            extractSaveFormat(slots, key);
        } else if ("image.z_project".equals(intentId)) {
            extractProjection(slots, key);
        } else if ("image.convert_type".equals(intentId)) {
            extractImageType(slots, key);
        } else if ("preprocess.subtract_background".equals(intentId)
                || "preprocess.median_filter".equals(intentId)
                || "preprocess.mean_filter".equals(intentId)
                || "preprocess.variance".equals(intentId)
                || "preprocess.variance_filter".equals(intentId)
                || "preprocess.unsharp_mask".equals(intentId)) {
            putFirstDouble(slots, "radius", numericKey);
        } else if ("preprocess.gaussian_blur".equals(intentId)) {
            putFirstDouble(slots, "sigma", numericKey);
        } else if ("preprocess.bandpass_filter".equals(intentId)) {
            extractBandpassSlots(slots, numericKey);
        } else if ("segmentation.auto_threshold".equals(intentId)
                || "segment.auto_threshold".equals(intentId)) {
            extractThresholdSlots(slots, key);
        } else if ("segmentation.compare_thresholds".equals(intentId)
                || "segment.compare_thresholds".equals(intentId)) {
            extractThresholdMethods(slots, key);
        } else if ("segmentation.find_maxima".equals(intentId)
                || "segment.find_maxima".equals(intentId)) {
            putFirstDouble(slots, "prominence", numericKey);
        } else if ("segment.count_particles".equals(intentId)) {
            if (key.contains("nuclei") || key.contains("nucleus") || key.contains("dapi")) {
                slots.put("object_type", "nuclei");
            } else if (key.contains("cell")) {
                slots.put("object_type", "cells");
            }
        } else if ("measurement.set_measurements".equals(intentId)
                || "measure.set_measurements".equals(intentId)) {
            extractMeasurementKeys(slots, raw);
        }
        return slots;
    }

    private static String normaliseNumbers(String input) {
        String s = input == null ? "" : input.toLowerCase(Locale.ROOT);
        s = PUNCT_EXCEPT_DECIMAL.matcher(s).replaceAll(" ");
        return WS.matcher(s).replaceAll(" ").trim();
    }

    private static void extractSaveFormat(Map<String, String> slots, String key) {
        if (key.contains("png")) {
            slots.put("format", "png");
        } else if (key.contains("jpeg") || key.contains("jpg")) {
            slots.put("format", "jpeg");
        } else if (key.contains("tiff") || key.contains("tif")) {
            slots.put("format", "tiff");
        }
    }

    private static void extractProjection(Map<String, String> slots, String key) {
        if (key.contains("standard deviation") || key.contains("std dev")
                || key.contains("stdev") || key.contains(" sd ")) {
            slots.put("projection", "sd");
        } else if (key.contains("mean") || key.contains("average") || key.contains("avg")) {
            slots.put("projection", "mean");
        } else if (key.contains("sum")) {
            slots.put("projection", "sum");
        } else if (key.contains("max")) {
            slots.put("projection", "max");
        }
    }

    private static void extractImageType(Map<String, String> slots, String key) {
        if (key.contains("composite")) {
            slots.put("image_type", "composite");
        } else if (key.contains("rgb") || key.contains("color") || key.contains("colour")) {
            slots.put("image_type", "rgb");
        } else if (key.contains("32 bit") || key.contains("32bit")) {
            slots.put("image_type", "32bit");
        } else if (key.contains("16 bit") || key.contains("16bit")) {
            slots.put("image_type", "16bit");
        } else if (key.contains("8 bit") || key.contains("8bit")) {
            slots.put("image_type", "8bit");
        }
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

    private static void extractThresholdSlots(Map<String, String> slots, String key) {
        String method = findThresholdMethod(key);
        if (method.length() > 0) {
            slots.put("method", method);
        }
        if (key.contains("dark background") || key.contains("dark bg")
                || key.contains("fluorescence")) {
            slots.put("dark", "true");
        }
    }

    private static void extractThresholdMethods(Map<String, String> slots, String key) {
        java.util.List<String> methods = new java.util.ArrayList<String>();
        addIfPresent(methods, key, "otsu", "Otsu");
        addIfPresent(methods, key, "li", "Li");
        addIfPresent(methods, key, "triangle", "Triangle");
        addIfPresent(methods, key, "huang", "Huang");
        addIfPresent(methods, key, "maxentropy", "MaxEntropy");
        addIfPresent(methods, key, "max entropy", "MaxEntropy");
        addIfPresent(methods, key, "default", "Default");
        if (!methods.isEmpty()) {
            slots.put("methods", String.join(",", methods));
        }
    }

    private static String findThresholdMethod(String key) {
        if (key.contains("li")) return "Li";
        if (key.contains("triangle")) return "Triangle";
        if (key.contains("huang")) return "Huang";
        if (key.contains("maxentropy") || key.contains("max entropy")) return "MaxEntropy";
        if (key.contains("default")) return "Default";
        if (key.contains("otsu")) return "Otsu";
        return "";
    }

    private static void addIfPresent(java.util.List<String> methods, String key,
                                     String token, String method) {
        if (key.contains(token) && !methods.contains(method)) {
            methods.add(method);
        }
    }

    private static void extractBandpassSlots(Map<String, String> slots, String key) {
        java.util.regex.Matcher large = Pattern.compile("(?:large|filter large)\\s+(\\d+(?:\\.\\d+)?)").matcher(key);
        if (large.find()) {
            slots.put("large", large.group(1));
        }
        java.util.regex.Matcher small = Pattern.compile("(?:small|filter small)\\s+(\\d+(?:\\.\\d+)?)").matcher(key);
        if (small.find()) {
            slots.put("small", small.group(1));
        }
    }

    private static void extractMeasurementKeys(Map<String, String> slots, String raw) {
        String text = raw == null ? "" : raw.trim();
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?i)(?:set|add|choose) measurements?\\s+(.+)").matcher(text);
        if (matcher.find()) {
            slots.put("keys", matcher.group(1).replace(" and ", ","));
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
