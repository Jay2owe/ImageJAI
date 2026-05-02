package imagejai.local;

import imagejai.local.ImproveAnalysis.MissBucket;

import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Multi-turn state machine for /improve.
 */
public final class ImproveSession {
    public enum Phase {
        BUCKET_DECISION,
        NEW_INTENT_DESCRIPTION,
        NEW_INTENT_SEED,
        DIFF_CONFIRM,
        DONE
    }

    private final List<MissBucket> buckets;
    private final Path yamlPath;
    private final IntentsYamlWriter writer;
    private final List<IntentsYamlWriter.Change> changes =
            new ArrayList<IntentsYamlWriter.Change>();
    private int index;
    private Phase phase = Phase.BUCKET_DECISION;
    private String pendingDescription = "";
    private String pendingIntentId = "";

    private ImproveSession(List<MissBucket> buckets, Path yamlPath,
                           IntentsYamlWriter writer) {
        this.buckets = buckets == null
                ? java.util.Collections.<MissBucket>emptyList()
                : new ArrayList<MissBucket>(buckets);
        this.yamlPath = yamlPath;
        this.writer = writer == null ? new IntentsYamlWriter() : writer;
    }

    public static ImproveSession start(List<MissBucket> buckets, Path yamlPath,
                                       IntentsYamlWriter writer) {
        return new ImproveSession(buckets, yamlPath, writer);
    }

    public AssistantReply handle(String input) {
        String value = input == null ? "" : input.trim();
        switch (phase) {
            case BUCKET_DECISION:
                return handleBucketDecision(value);
            case NEW_INTENT_DESCRIPTION:
                return handleNewIntentDescription(value);
            case NEW_INTENT_SEED:
                return handleNewIntentSeed(value);
            case DIFF_CONFIRM:
                return handleDiffConfirm(value);
            case DONE:
            default:
                return AssistantReply.text("Improve session is complete.");
        }
    }

    public String firstPrompt() {
        return "Reading " + journalLabel() + " ... " + totalMisses() + " misses found.\n\n"
                + "Top " + buckets.size() + " misses (by count):\n"
                + bucketPrompt();
    }

    public boolean isDone() {
        return phase == Phase.DONE;
    }

    public Phase phase() {
        return phase;
    }

    public List<IntentsYamlWriter.Change> changes() {
        return java.util.Collections.unmodifiableList(changes);
    }

    private AssistantReply handleBucketDecision(String value) {
        if (index >= buckets.size()) {
            return presentDiff();
        }
        String choice = value.toLowerCase(Locale.ROOT);
        if ("a".equals(choice)) {
            MissBucket bucket = buckets.get(index);
            if (!bucket.closestIntent().isPresent()) {
                return AssistantReply.text("There is no closest intent above 0.7 for this miss.\n\n"
                        + bucketPrompt());
            }
            RankedPhrase closest = bucket.closestIntent().get();
            changes.add(IntentsYamlWriter.Change.alias(closest.intentId(), bucket.phrase()));
            index++;
            return continueOrDiff("Aliased \"" + bucket.phrase() + "\" to "
                    + closest.intentId() + ".");
        }
        if ("n".equals(choice)) {
            phase = Phase.NEW_INTENT_DESCRIPTION;
            return AssistantReply.text("Describe this intent in one line.");
        }
        if ("s".equals(choice)) {
            index++;
            return continueOrDiff("Skipped.");
        }
        if ("q".equals(choice)) {
            return presentDiff();
        }
        return AssistantReply.text("Choose a, n, s, or q.\n\n" + bucketPrompt());
    }

    private AssistantReply handleNewIntentDescription(String value) {
        if (value.length() == 0) {
            return AssistantReply.text("Describe this intent in one line.");
        }
        pendingDescription = value;
        pendingIntentId = IntentsYamlWriter.deriveUserIntentId(value);
        phase = Phase.NEW_INTENT_SEED;
        return AssistantReply.text("Add one extra seed phrase for " + pendingIntentId
                + ", or press enter to use only \"" + buckets.get(index).phrase() + "\".");
    }

    private AssistantReply handleNewIntentSeed(String value) {
        MissBucket bucket = buckets.get(index);
        List<String> seeds = new ArrayList<String>();
        seeds.add(bucket.phrase());
        if (value.length() > 0
                && !IntentMatcher.normalise(value).equals(IntentMatcher.normalise(bucket.phrase()))) {
            seeds.add(value);
        }
        changes.add(IntentsYamlWriter.Change.newIntent(
                pendingIntentId, pendingDescription, seeds));
        String id = pendingIntentId;
        pendingIntentId = "";
        pendingDescription = "";
        index++;
        phase = Phase.BUCKET_DECISION;
        return continueOrDiff("Added new intent " + id + ".");
    }

    private AssistantReply handleDiffConfirm(String value) {
        String choice = value.toLowerCase(Locale.ROOT);
        if ("y".equals(choice) || "yes".equals(choice)) {
            try {
                writer.apply(yamlPath, changes);
                phase = Phase.DONE;
                return AssistantReply.text("Updated tools/intents.yaml. To regenerate the phrasebook, run:\n"
                        + "  python tools/phrasebook_build.py\n"
                        + "  mvn package");
            } catch (IOException | RuntimeException e) {
                phase = Phase.DONE;
                return AssistantReply.text("Could not update tools/intents.yaml: "
                        + e.getMessage());
            }
        }
        if ("n".equals(choice) || "no".equals(choice)) {
            phase = Phase.DONE;
            return AssistantReply.text("No changes written.");
        }
        return AssistantReply.text("Apply? [y/n]");
    }

    private AssistantReply continueOrDiff(String prefix) {
        if (index >= buckets.size()) {
            AssistantReply diff = presentDiff();
            return AssistantReply.text(prefix + "\n\n" + diff.text());
        }
        return AssistantReply.text(prefix + "\n\n" + bucketPrompt());
    }

    private AssistantReply presentDiff() {
        if (changes.isEmpty()) {
            phase = Phase.DONE;
            return AssistantReply.text("No changes to apply.");
        }
        phase = Phase.DIFF_CONFIRM;
        try {
            String diff = writer.diff(yamlPath, changes);
            return AssistantReply.text("Diff against tools/intents.yaml:\n\n"
                    + diff + "\nApply? [y/n]");
        } catch (IOException | RuntimeException e) {
            phase = Phase.DONE;
            return AssistantReply.text("Could not prepare tools/intents.yaml diff: "
                    + e.getMessage());
        }
    }

    private String bucketPrompt() {
        MissBucket bucket = buckets.get(index);
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(index + 1).append(". \"").append(bucket.phrase()).append("\" (")
                .append(bucket.count()).append("x, last seen ")
                .append(formatLastSeen(bucket.lastSeenMs())).append(")\n");
        if (bucket.closestIntent().isPresent()) {
            RankedPhrase closest = bucket.closestIntent().get();
            sb.append("     Closest existing intent: ").append(closest.intentId())
                    .append(" (score ")
                    .append(String.format(Locale.ROOT, "%.2f", closest.score()))
                    .append(")\n");
            sb.append("     [a] alias to ").append(closest.intentId())
                    .append("  [n] new intent  [s] skip  [q] quit");
        } else {
            sb.append("     Closest existing intent: (no match above 0.7)\n");
            sb.append("     [n] new intent  [s] skip  [q] quit");
        }
        return sb.toString();
    }

    private int totalMisses() {
        int total = 0;
        for (MissBucket bucket : buckets) {
            total += bucket.count();
        }
        return total;
    }

    private String journalLabel() {
        return "~/.imagej-ai/friction.jsonl";
    }

    private static String formatLastSeen(long lastSeenMs) {
        if (lastSeenMs <= 0L) {
            return "unknown";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .format(new Date(lastSeenMs));
    }
}
