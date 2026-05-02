package imagejai.local;

import imagejai.engine.FrictionLog;
import imagejai.engine.FrictionLogJournal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads persisted Local Assistant misses and buckets them for /improve review.
 */
public final class ImproveAnalysis {
    public static final int MAX_RECENT_ENTRIES = 50_000;
    private static final double CLOSEST_INTENT_FLOOR = 0.70;

    private ImproveAnalysis() {
    }

    public static List<MissBucket> fromJournal(FrictionLogJournal journal,
                                               IntentMatcher matcher) {
        if (journal == null || matcher == null) {
            return java.util.Collections.emptyList();
        }

        Deque<FrictionLog.FailureEntry> recent = new ArrayDeque<FrictionLog.FailureEntry>();
        try (Stream<FrictionLog.FailureEntry> entries = journal.streamEntries()) {
            entries.filter(e -> "local_assistant".equals(e.command))
                    .filter(e -> "miss".equals(e.error))
                    .forEach(e -> {
                        recent.addLast(e);
                        while (recent.size() > MAX_RECENT_ENTRIES) {
                            recent.removeFirst();
                        }
                    });
        }

        Map<String, BucketAccumulator> buckets = new HashMap<String, BucketAccumulator>();
        for (FrictionLog.FailureEntry entry : recent) {
            String phrase = IntentMatcher.normalise(entry.argsSummary);
            if (phrase.length() == 0) {
                continue;
            }
            BucketAccumulator bucket = buckets.get(phrase);
            if (bucket == null) {
                bucket = new BucketAccumulator(phrase);
                buckets.put(phrase, bucket);
            }
            bucket.count++;
            if (entry.ts > bucket.lastSeenMs) {
                bucket.lastSeenMs = entry.ts;
            }
        }

        List<MissBucket> out = new ArrayList<MissBucket>();
        for (BucketAccumulator bucket : buckets.values()) {
            Optional<RankedPhrase> closest = Optional.empty();
            List<RankedPhrase> ranked = matcher.topK(bucket.phrase, 1);
            if (!ranked.isEmpty() && ranked.get(0).score() >= CLOSEST_INTENT_FLOOR) {
                closest = Optional.of(ranked.get(0));
            }
            out.add(new MissBucket(bucket.phrase, bucket.count, bucket.lastSeenMs, closest));
        }
        out.sort(Comparator
                .comparingInt(MissBucket::count).reversed()
                .thenComparing(Comparator.comparingLong(MissBucket::lastSeenMs).reversed())
                .thenComparing(MissBucket::phrase));
        return out;
    }

    private static final class BucketAccumulator {
        final String phrase;
        int count;
        long lastSeenMs;

        BucketAccumulator(String phrase) {
            this.phrase = phrase;
        }
    }

    public static final class MissBucket {
        private final String phrase;
        private final int count;
        private final long lastSeenMs;
        private final Optional<RankedPhrase> closestIntent;

        public MissBucket(String phrase, int count, long lastSeenMs,
                          Optional<RankedPhrase> closestIntent) {
            this.phrase = phrase == null ? "" : phrase;
            this.count = count;
            this.lastSeenMs = lastSeenMs;
            this.closestIntent = closestIntent == null
                    ? Optional.<RankedPhrase>empty()
                    : closestIntent;
        }

        public String phrase() {
            return phrase;
        }

        public int count() {
            return count;
        }

        public long lastSeenMs() {
            return lastSeenMs;
        }

        public Optional<RankedPhrase> closestIntent() {
            return closestIntent;
        }
    }
}
