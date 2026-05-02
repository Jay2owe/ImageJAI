package imagejai.local;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.junit.Test;
import imagejai.engine.FrictionLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IntentMatcherBenchmarkTest {

    @Test
    public void reportsTop1Accuracy() throws IOException {
        IntentLibrary library = IntentLibrary.load();
        IntentMatcher matcher = new IntentMatcher(library);
        Gson gson = new Gson();

        int total = 0;
        int correct = 0;
        Path benchmark = Paths.get("tests", "benchmark", "biologist_phrasings.jsonl");
        BufferedReader reader = Files.newBufferedReader(benchmark, StandardCharsets.UTF_8);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) {
                    continue;
                }
                BenchmarkRow row = gson.fromJson(line, BenchmarkRow.class);
                Optional<IntentMatcher.MatchedIntent> matched = matcher.match(row.phrase);
                String actual = matched.isPresent() ? matched.get().intentId() : "";
                if (row.expectedIntentId.equals(actual)) {
                    correct++;
                }
                total++;
            }
        } finally {
            reader.close();
        }

        double percent = total == 0 ? 0.0 : (100.0 * correct) / total;
        System.out.println(String.format(Locale.ROOT,
                "IntentMatcherBenchmarkTest top-1 accuracy: %d/%d (%.1f%%)",
                correct,
                total,
                percent));
        assertTrue("benchmark must contain at least one row", total > 0);
        assertTrue("top-1 accuracy must improve over the stage 03 50.0% baseline",
                percent > 50.0);
    }

    @Test
    public void tier2FallbackMatchesTyposAndReorderedWords() {
        IntentLibrary library = IntentLibrary.load();
        IntentMatcher matcher = new IntentMatcher(library);

        Optional<IntentMatcher.MatchedIntent> typo = matcher.match("pixle siz");
        Optional<IntentMatcher.MatchedIntent> reordered = matcher.match("size pixel");

        assertTrue(typo.isPresent());
        assertEquals("image.pixel_size", typo.get().intentId());
        assertTrue(reordered.isPresent());
        assertEquals("image.pixel_size", reordered.get().intentId());
    }

    @Test
    public void topKRanksExistingPhrasebookCandidates() {
        IntentLibrary library = IntentLibrary.load();
        IntentMatcher matcher = new IntentMatcher(library);

        java.util.List<RankedPhrase> ranked = matcher.topK("pixle siz", 3);

        assertFalse(ranked.isEmpty());
        assertEquals("pixel size", ranked.get(0).phrase());
        assertEquals("image.pixel_size", ranked.get(0).intentId());
        assertTrue(ranked.get(0).score() >= 0.90);
        assertTrue(ranked.size() <= 3);
    }

    @Test
    public void localAssistantMissRecordsFrictionLogEntry() {
        FrictionLog log = new FrictionLog();
        IntentLibrary library = IntentLibrary.load();
        LocalAssistant assistant = new LocalAssistant(
                library,
                new IntentMatcher(library),
                null,
                log);

        AssistantReply reply = assistant.handle("potato salad");

        assertTrue(reply.text().contains("I don't recognise"));
        FrictionLog.FailureEntry entry = log.snapshot().get(0);
        assertEquals("local_assistant", entry.command);
        assertEquals("potato salad", entry.argsSummary);
        assertEquals("miss", entry.error);
    }

    private static class BenchmarkRow {
        String phrase;
        @SerializedName("expected_intent_id")
        String expectedIntentId;
    }
}
