package imagejai.local;

import org.junit.Test;
import imagejai.config.Settings;
import imagejai.engine.FrictionLog;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DisambiguationTest {

    @Test
    public void howManyFramesIsAmbiguousWithExpectedRunnerUp() {
        Match2Result result = matcherWithMargin(0.05).match2("how many frames", 0.05);

        assertEquals(Match2Result.Status.AMBIGUOUS, result.status());
        assertCandidateIntent(result, "image.dimensions");
        assertCandidateIntent(result, "image.frame_count");
    }

    @Test
    public void chipPhraseResolvesToThatIntent() {
        RecordingIntent dimensions = new RecordingIntent("image.dimensions");
        RecordingIntent frameCount = new RecordingIntent("image.frame_count");
        LocalAssistant assistant = assistantWithMargin(0.05, dimensions, frameCount);

        AssistantReply prompt = assistant.handle("how many frames");
        AssistantReply reply = assistant.handle(phraseForIntent(prompt, "image.frame_count"));

        assertTrue(prompt.isClarifying());
        assertEquals("ran image.frame_count", reply.text());
        assertEquals(1, frameCount.executeCount);
        assertEquals(0, dimensions.executeCount);
        assertFalse(assistant.pendingTurnForTest().isPresent());
    }

    @Test
    public void typedCandidatePhraseAlsoResolvesDisambiguation() {
        RecordingIntent dimensions = new RecordingIntent("image.dimensions");
        RecordingIntent frameCount = new RecordingIntent("image.frame_count");
        LocalAssistant assistant = assistantWithMargin(0.05, dimensions, frameCount);

        assistant.handle("how many frames");
        AssistantReply reply = assistant.handle("how many frame");

        assertEquals("ran image.frame_count", reply.text());
        assertEquals(1, frameCount.executeCount);
        assertEquals(0, dimensions.executeCount);
    }

    @Test
    public void freshRequestDropsPendingDisambiguation() {
        RecordingIntent dimensions = new RecordingIntent("image.dimensions");
        RecordingIntent frameCount = new RecordingIntent("image.frame_count");
        RecordingIntent closeAll = new RecordingIntent("image.close_all");
        LocalAssistant assistant = assistantWithMargin(0.05, dimensions, frameCount, closeAll);

        assistant.handle("how many frames");
        AssistantReply reply = assistant.handle("close all");

        assertEquals("ran image.close_all", reply.text());
        assertEquals(1, closeAll.executeCount);
        assertEquals(0, dimensions.executeCount);
        assertEquals(0, frameCount.executeCount);
        assertFalse(assistant.pendingTurnForTest().isPresent());
    }

    @Test
    public void confidentMatchDoesNotClarify() {
        RecordingIntent pixelSize = new RecordingIntent("image.pixel_size");
        LocalAssistant assistant = assistantWithMargin(0.05, pixelSize);

        Match2Result result = matcherWithMargin(0.05).match2("pixel size", 0.05);
        AssistantReply reply = assistant.handle("pixel size");

        assertEquals(Match2Result.Status.CONFIDENT, result.status());
        assertFalse(reply.isClarifying());
        assertFalse(assistant.pendingTurnForTest().isPresent());
    }

    @Test
    public void zeroMarginForcesConfidentResult() {
        Match2Result result = matcherWithMargin(0.0).match2("commands", 0.0);

        assertEquals(Match2Result.Status.CONFIDENT, result.status());
    }

    @Test
    public void sameIntentRunnerUpDoesNotTriggerAmbiguity() {
        Match2Result result = matcherWithMargin(0.10).match2("pixel size", 0.10);

        assertEquals(Match2Result.Status.CONFIDENT, result.status());
        assertEquals("image.pixel_size", result.best().intentId());
    }

    @Test
    public void showingDisambiguationRecordsFrictionEvent() {
        FrictionLog log = new FrictionLog();
        LocalAssistant assistant = assistantWithMarginAndLog(0.05, log,
                new RecordingIntent("image.dimensions"),
                new RecordingIntent("image.frame_count"));

        assistant.handle("how many frames");

        assertEquals("event=disambiguation_shown", log.snapshot().get(0).error);
    }

    private static IntentMatcher matcherWithMargin(double margin) {
        Settings settings = new Settings();
        settings.localAssistantDisambiguationMargin = margin;
        IntentLibrary library = IntentLibrary.load(settings);
        return new IntentMatcher(library, settings);
    }

    private static LocalAssistant assistantWithMargin(double margin, RecordingIntent... intents) {
        return assistantWithMarginAndLog(margin, new FrictionLog(), intents);
    }

    private static LocalAssistant assistantWithMarginAndLog(double margin, FrictionLog log,
                                                            RecordingIntent... intents) {
        Settings settings = new Settings();
        settings.localAssistantDisambiguationMargin = margin;
        IntentLibrary library = IntentLibrary.load(settings);
        for (RecordingIntent intent : intents) {
            library.register(intent);
        }
        return new LocalAssistant(library, new IntentMatcher(library, settings),
                new FijiBridge(null), log, settings);
    }

    private static void assertCandidateIntent(Match2Result result, String intentId) {
        assertTrue(result.best().intentId().equals(intentId)
                || result.runnerUp().intentId().equals(intentId));
    }

    private static String phraseForIntent(AssistantReply reply, String intentId) {
        for (RankedPhrase candidate : reply.clarificationCandidates()) {
            if (candidate.intentId().equals(intentId)) {
                return candidate.phrase();
            }
        }
        throw new AssertionError("No candidate for " + intentId);
    }

    private static class RecordingIntent implements Intent {
        private final String id;
        private int executeCount;

        RecordingIntent(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String description() {
            return id;
        }

        public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
            executeCount++;
            return AssistantReply.text("ran " + id);
        }
    }
}
