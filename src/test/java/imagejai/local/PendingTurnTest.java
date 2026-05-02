package imagejai.local;

import org.junit.Test;
import imagejai.engine.FrictionLog;
import imagejai.engine.IntentRouter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PendingTurnTest {

    @Test
    public void parameterFactoryBuildsImmutableTurn() {
        Map<String, String> slots = new LinkedHashMap<String, String>();
        slots.put("sigma", "2");

        PendingTurn turn = PendingTurn.parameter(
                "preprocess.gaussian_blur",
                slots,
                "radius",
                "What radius?",
                "50");
        slots.put("radius", "100");

        assertEquals(PendingTurn.Kind.PARAMETER, turn.kind());
        assertEquals("What radius?", turn.question());
        assertEquals("preprocess.gaussian_blur", turn.intentId());
        assertEquals("2", turn.filledSlots().get("sigma"));
        assertFalse(turn.filledSlots().containsKey("radius"));
        assertEquals("radius", turn.missingSlot());
        assertEquals("50", turn.defaultValue());
        assertTrue(turn.candidates().isEmpty());
        assertTrue(turn.createdAtMs() > 0);
    }

    @Test
    public void disambiguationFactoryBuildsImmutableTurn() {
        RankedPhrase first = new RankedPhrase("count frames", "image.stack_counts", 0.97);
        RankedPhrase second = new RankedPhrase("current position", "image.position", 0.94);
        java.util.List<RankedPhrase> candidates = new java.util.ArrayList<RankedPhrase>(
                Arrays.asList(first, second));

        PendingTurn turn = PendingTurn.disambiguation("Did you mean:", candidates);
        candidates.clear();

        assertEquals(PendingTurn.Kind.DISAMBIGUATION, turn.kind());
        assertEquals("Did you mean:", turn.question());
        assertEquals("", turn.intentId());
        assertTrue(turn.filledSlots().isEmpty());
        assertEquals("", turn.missingSlot());
        assertEquals(null, turn.defaultValue());
        assertEquals(2, turn.candidates().size());
        assertEquals("image.stack_counts", turn.candidates().get(0).intentId());
    }

    @Test
    public void resolutionStubsReturnEmptyForBothKinds() {
        LocalAssistant assistant = assistantWithHistory(null);
        PendingTurn parameter = PendingTurn.parameter("image.switch_channel",
                new LinkedHashMap<String, String>(), "channel", "Which channel?", null);
        PendingTurn disambiguation = PendingTurn.disambiguation("Did you mean:",
                Arrays.asList(new RankedPhrase("help", "slash.help", 0.95)));

        Optional<AssistantReply> parameterResolved = assistant.tryResolveForTest(parameter, "2");
        Optional<AssistantReply> disambiguationResolved = assistant.tryResolveForTest(disambiguation, "help");

        assertFalse(parameterResolved.isPresent());
        assertFalse(disambiguationResolved.isPresent());
    }

    @Test
    public void nonResolvingInputDropsPendingTurnAndFallsThrough() {
        FrictionLog log = new FrictionLog();
        IntentLibrary library = IntentLibrary.load();
        LocalAssistant assistant = new LocalAssistant(library, new IntentMatcher(library),
                null, log);
        assistant.parkPendingForTest(PendingTurn.parameter("image.switch_channel",
                new LinkedHashMap<String, String>(), "channel", "Which channel?", null));

        AssistantReply reply = assistant.handle("potato salad");

        assertFalse(assistant.pendingTurnForTest().isPresent());
        assertTrue(reply.text().contains("I don't recognise"));
        assertEquals("potato salad", log.snapshot().get(0).argsSummary);
    }

    @Test
    public void clearSlashCommandClearsPendingTurn() {
        RecordingHistory history = new RecordingHistory();
        LocalAssistant assistant = assistantWithHistory(history);
        assistant.parkPendingForTest(PendingTurn.parameter("image.switch_channel",
                new LinkedHashMap<String, String>(), "channel", "Which channel?", null));

        AssistantReply reply = assistant.handle("/clear");

        assertFalse(assistant.pendingTurnForTest().isPresent());
        assertTrue(history.cleared);
        assertEquals("", reply.text());
    }

    private static LocalAssistant assistantWithHistory(ChatHistoryController history) {
        IntentLibrary library = IntentLibrary.load();
        return new LocalAssistant(library, new IntentMatcher(library), null,
                new FrictionLog(), new IntentRouter(), history);
    }

    private static class RecordingHistory implements ChatHistoryController {
        boolean cleared;

        public boolean canClear() {
            return true;
        }

        public void clear() {
            cleared = true;
        }
    }
}
