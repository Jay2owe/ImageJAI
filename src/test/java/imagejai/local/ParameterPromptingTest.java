package imagejai.local;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;
import imagejai.engine.CommandEngine;
import imagejai.engine.FrictionLog;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ParameterPromptingTest {

    @Test
    public void missingChannelPromptsAndParksPendingTurn() {
        LocalAssistant assistant = assistantWithRecordingIntents(new RecordingIntent(
                "image.switch_channel",
                Collections.singletonList(new SlotSpec("channel", "channel number", null))));

        AssistantReply reply = assistant.handle("switch to channel");

        assertTrue(reply.text().contains("channel number"));
        assertTrue(assistant.pendingTurnForTest().isPresent());
        assertEquals("channel", assistant.pendingTurnForTest().get().missingSlot());
    }

    @Test
    public void numericReplyFillsChannelAndExecutesHeldIntent() {
        RecordingIntent switchChannel = new RecordingIntent(
                "image.switch_channel",
                Collections.singletonList(new SlotSpec("channel", "channel number", null)));
        LocalAssistant assistant = assistantWithRecordingIntents(switchChannel);

        assistant.handle("switch to channel");
        AssistantReply reply = assistant.handle("2");

        assertEquals("ran image.switch_channel", reply.text());
        assertEquals("2", switchChannel.lastSlots.get("channel"));
        assertFalse(assistant.pendingTurnForTest().isPresent());
    }

    @Test
    public void numericReplyMayIncludeLeadingSlotWords() {
        RecordingIntent switchChannel = new RecordingIntent(
                "image.switch_channel",
                Collections.singletonList(new SlotSpec("channel", "channel number", null)));
        LocalAssistant assistant = assistantWithRecordingIntents(switchChannel);

        assistant.handle("switch to channel");
        assistant.handle("channel 3");

        assertEquals("3", switchChannel.lastSlots.get("channel"));
    }

    @Test
    public void nonNumericReplyDropsPendingTurnAndRunsFreshRequest() {
        RecordingIntent switchChannel = new RecordingIntent(
                "image.switch_channel",
                Collections.singletonList(new SlotSpec("channel", "channel number", null)));
        RecordingIntent closeAll = new RecordingIntent("image.close_all",
                Collections.<SlotSpec>emptyList());
        LocalAssistant assistant = assistantWithRecordingIntents(switchChannel, closeAll);

        assistant.handle("switch to channel");
        AssistantReply reply = assistant.handle("close all");

        assertEquals("ran image.close_all", reply.text());
        assertEquals(1, closeAll.executeCount);
        assertEquals(0, switchChannel.executeCount);
        assertFalse(assistant.pendingTurnForTest().isPresent());
    }

    @Test
    public void inlineChannelExecutesImmediatelyWithoutPrompt() {
        RecordingIntent switchChannel = new RecordingIntent(
                "image.switch_channel",
                Collections.singletonList(new SlotSpec("channel", "channel number", null)));
        LocalAssistant assistant = assistantWithRecordingIntents(switchChannel);

        AssistantReply reply = assistant.handle("switch to channel 2");

        assertEquals("ran image.switch_channel", reply.text());
        assertEquals("2", switchChannel.lastSlots.get("channel"));
        assertFalse(assistant.pendingTurnForTest().isPresent());
    }

    @Test
    public void subtractBackgroundUsesDefaultRadiusWithoutPrompting() {
        IntentLibrary library = IntentLibrary.load();
        RecordingFijiBridge fiji = new RecordingFijiBridge();
        LocalAssistant assistant = new LocalAssistant(library, new IntentMatcher(library),
                fiji, new FrictionLog());

        AssistantReply reply = assistant.handle("subtract background");

        assertTrue(reply.text().contains("rolling-ball radius 50.00 px"));
        assertEquals("run(\"Subtract Background...\", \"rolling=50.00\");", fiji.lastMacro);
        assertFalse(assistant.pendingTurnForTest().isPresent());
    }

    @Test
    public void multipleRequiredSlotsAreAskedOneAtATime() {
        RecordingIntent twoSlot = new RecordingIntent("test.two_slots",
                Arrays.asList(new SlotSpec("alpha", "alpha value", null),
                        new SlotSpec("beta", "beta value", null)));
        IntentLibrary library = IntentLibrary.load();
        library.register(twoSlot);
        library.addPhraseIfAbsent("future two slots", twoSlot.id());
        LocalAssistant assistant = new LocalAssistant(library, new IntentMatcher(library),
                new RecordingFijiBridge(), new FrictionLog());

        AssistantReply first = assistant.handle("future two slots");
        AssistantReply second = assistant.handle("5");
        assertEquals(0, twoSlot.executeCount);
        AssistantReply third = assistant.handle("7");

        assertTrue(first.text().contains("alpha value"));
        assertTrue(second.text().contains("beta value"));
        assertEquals(1, twoSlot.executeCount);
        assertEquals("ran test.two_slots", third.text());
        assertEquals("5", twoSlot.lastSlots.get("alpha"));
        assertEquals("7", twoSlot.lastSlots.get("beta"));
        assertFalse(assistant.pendingTurnForTest().isPresent());
    }

    @Test
    public void builtInRequiredSlotsAreLimitedToNavigationControls() {
        IntentLibrary library = IntentLibrary.load();

        assertRequiredSlot(library, "image.switch_channel", "channel");
        assertRequiredSlot(library, "image.jump_slice", "slice");
        assertRequiredSlot(library, "image.jump_frame", "frame");
        assertTrue(library.byId("preprocess.subtract_background").requiredSlots().isEmpty());
        assertTrue(library.byId("preprocess.gaussian_blur").requiredSlots().isEmpty());
    }

    private static LocalAssistant assistantWithRecordingIntents(RecordingIntent... intents) {
        IntentLibrary library = IntentLibrary.load();
        for (RecordingIntent intent : intents) {
            library.register(intent);
        }
        return new LocalAssistant(library, new IntentMatcher(library),
                new RecordingFijiBridge(), new FrictionLog());
    }

    private static void assertRequiredSlot(IntentLibrary library, String intentId, String slotName) {
        Intent intent = library.byId(intentId);
        assertNotNull(intent);
        assertEquals(1, intent.requiredSlots().size());
        assertEquals(slotName, intent.requiredSlots().get(0).name());
    }

    private static class RecordingIntent implements Intent {
        private final String id;
        private final List<SlotSpec> requiredSlots;
        private Map<String, String> lastSlots;
        private int executeCount;

        RecordingIntent(String id, List<SlotSpec> requiredSlots) {
            this.id = id;
            this.requiredSlots = requiredSlots;
        }

        public String id() {
            return id;
        }

        public String description() {
            return id;
        }

        public List<SlotSpec> requiredSlots() {
            return requiredSlots;
        }

        public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
            executeCount++;
            lastSlots = slots;
            return AssistantReply.text("ran " + id);
        }
    }

    private static class RecordingFijiBridge extends FijiBridge {
        private final ImagePlus image;
        private String lastMacro;

        RecordingFijiBridge() {
            super(new CommandEngine());
            ImageStack stack = new ImageStack(2, 2);
            stack.addSlice(new ByteProcessor(2, 2));
            image = new ImagePlus("test", stack);
        }

        public ImagePlus requireOpenImage() {
            return image;
        }

        public void runMacro(String code) {
            lastMacro = code;
        }
    }
}
