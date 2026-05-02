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
        double roundedPercent = Math.round(percent * 10.0) / 10.0;
        System.out.println(String.format(Locale.ROOT,
                "IntentMatcherBenchmarkTest top-1 accuracy: %d/%d (%.1f%%)",
                correct,
                total,
                percent));
        assertTrue("benchmark must contain at least one row", total > 0);
        assertTrue("top-1 accuracy must stay at least 97.4%",
                roundedPercent >= 97.4);
    }

    @Test
    public void knownBenchmarkMissesAreDisambiguated() throws IOException {
        IntentLibrary library = IntentLibrary.load();
        IntentMatcher matcher = new IntentMatcher(library);
        Gson gson = new Gson();

        int checked = 0;
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
                if (!isKnownMiss(row.phrase)) {
                    continue;
                }
                Optional<IntentMatcher.MatchedIntent> matched = matcher.match(row.phrase);
                boolean top1Hit = matched.isPresent()
                        && row.expectedIntentId.equals(matched.get().intentId());
                Match2Result match2 = matcher.match2(row.phrase, 0.05);
                boolean ambiguousHit = match2.isAmbiguous()
                        && candidateHasIntent(match2, row.expectedIntentId);
                assertTrue(row.phrase + " must be top-1 or disambiguated",
                        top1Hit || ambiguousHit);
                checked++;
            }
        } finally {
            reader.close();
        }
        assertEquals(3, checked);
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
        // After the LLM-distilled phrasebook lands, the top candidate may be
        // "pixle size" (typo phrasing the LLM anticipated) rather than the
        // canonical "pixel size" — both belong to image.pixel_size, which is
        // what Tier 2 ranking is supposed to surface. Validate the intent and
        // score; the specific phrase string is implementation detail.
        assertEquals("image.pixel_size", ranked.get(0).intentId());
        assertTrue(ranked.get(0).score() >= 0.90);
        assertTrue(ranked.size() <= 3);
    }

    @Test
    public void slotAwareControlIntentsExtractNumbersAndRanges() {
        IntentLibrary library = IntentLibrary.load();
        IntentMatcher matcher = new IntentMatcher(library);

        IntentMatcher.MatchedIntent channel = matcher.match("switch to channel 2").get();
        assertEquals("image.switch_channel", channel.intentId());
        assertEquals("2", channel.slots().get("channel"));

        IntentMatcher.MatchedIntent substack = matcher.match("make substack slices 5-20").get();
        assertEquals("image.make_substack", substack.intentId());
        assertEquals("5-20", substack.slots().get("slices"));

        IntentMatcher.MatchedIntent scale = matcher.match("set scale 100 px equals 10 um").get();
        assertEquals("image.set_scale", scale.intentId());
        assertEquals("100", scale.slots().get("pixels"));
        assertEquals("10", scale.slots().get("distance"));
        assertEquals("um", scale.slots().get("unit"));
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

    private static boolean isKnownMiss(String phrase) {
        return "how many frames".equals(phrase)
                || "what can you do".equals(phrase)
                || "commands".equals(phrase);
    }

    private static boolean candidateHasIntent(Match2Result result, String intentId) {
        return (result.best() != null && intentId.equals(result.best().intentId()))
                || (result.runnerUp() != null && intentId.equals(result.runnerUp().intentId()));
    }
}
