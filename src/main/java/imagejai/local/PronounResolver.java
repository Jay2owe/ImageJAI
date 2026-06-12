package imagejai.local;

import ij.measure.ResultsTable;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic pronoun/back-reference rewrites for narrow Local Assistant cases.
 */
public class PronounResolver {

    private static final Pattern REPEAT = Pattern.compile(
            "^(do that again|repeat that|run that again)\\s*[.!?]?\\s*$");
    private static final Pattern MEASURE_IT = Pattern.compile(
            "^measure\\s+(it|that)\\s*[.!?]?\\s*$");
    private static final Pattern NEXT_IMAGE = Pattern.compile(
            "^the\\s+next\\s+image\\s*[.!?]?\\s*$");
    private static final Pattern SAVE_RESULTS = Pattern.compile(
            "^save\\s+(those|the)\\s+results\\s*[.!?]?\\s*$");

    public Optional<Rewrite> resolve(String input, ConversationContext ctx) {
        if (ctx == null || ctx.isStale()) {
            return Optional.empty();
        }
        String s = input == null ? "" : input.toLowerCase(Locale.ROOT).trim();

        if (REPEAT.matcher(s).matches()) {
            Optional<String> id = ctx.lastIntentId();
            Optional<Map<String, String>> slots = ctx.lastSlots();
            if (id.isPresent() && slots.isPresent()) {
                return Optional.of(new Rewrite(id.get(), slots.get()));
            }
        }

        if (MEASURE_IT.matcher(s).matches() && ctx.lastRoiIndex().isPresent()) {
            return Optional.of(new Rewrite("measurement.measure_rois",
                    Collections.<String, String>emptyMap()));
        }

        if (NEXT_IMAGE.matcher(s).matches()) {
            return Optional.of(new Rewrite("image.next_open_image",
                    Collections.<String, String>emptyMap()));
        }

        if (SAVE_RESULTS.matcher(s).matches() && resultsPending()) {
            return Optional.of(new Rewrite("results.save_csv",
                    Collections.<String, String>emptyMap()));
        }

        return Optional.empty();
    }

    private static boolean resultsPending() {
        ResultsTable table = ResultsTable.getResultsTable();
        return table != null && table.getCounter() > 0;
    }

    public static class Rewrite {
        private final String intentId;
        private final Map<String, String> slots;

        public Rewrite(String intentId, Map<String, String> slots) {
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
