package imagejai.local;

import ij.ImagePlus;
import ij.WindowManager;
import org.junit.Test;
import imagejai.engine.FrictionLog;
import imagejai.engine.IntentRouter;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConversationMemoryTest {

    @Test
    public void repeatThatRerunsLastPixelSizeIntent() {
        RecordingIntent pixelSize = new RecordingIntent("image.pixel_size");
        LocalAssistant assistant = assistantWith(pixelSize, new ConversationContext());

        assistant.handle("pixel size");
        AssistantReply reply = assistant.handle("do that again");

        assertEquals("ran image.pixel_size", reply.text());
        assertEquals(2, pixelSize.executeCount);
    }

    @Test
    public void clearResetsRepeatContext() {
        RecordingIntent pixelSize = new RecordingIntent("image.pixel_size");
        LocalAssistant assistant = assistantWith(pixelSize, new ConversationContext());

        assistant.handle("pixel size");
        assistant.handle("/clear");
        AssistantReply reply = assistant.handle("do that again");

        assertTrue(reply.text().contains("I don't recognise"));
        assertEquals(1, pixelSize.executeCount);
    }

    @Test
    public void staleContextDoesNotRepeatIntent() {
        AtomicLong now = new AtomicLong(1_000L);
        RecordingIntent pixelSize = new RecordingIntent("image.pixel_size");
        LocalAssistant assistant = assistantWith(pixelSize,
                new ConversationContext(now::get));

        assistant.handle("pixel size");
        now.addAndGet(ConversationContext.IDLE_SUNSET_MS + 1L);
        AssistantReply reply = assistant.handle("do that again");

        assertTrue(reply.text().contains("I don't recognise"));
        assertEquals(1, pixelSize.executeCount);
    }

    @Test
    public void noImageReplyDoesNotReplaceSuccessfulContext() {
        RecordingIntent pixelSize = new RecordingIntent("image.pixel_size");
        LocalAssistant assistant = assistantWith(pixelSize, new ConversationContext());

        assistant.handle("pixel size");
        assistant.handle("close active image");
        AssistantReply reply = assistant.handle("do that again");

        assertEquals("ran image.pixel_size", reply.text());
        assertEquals(2, pixelSize.executeCount);
    }

    @Test
    public void nextImageWithoutOpenImagesDegradesGracefully() {
        closeOpenImages();
        LocalAssistant assistant = assistantWith(new ConversationContext());

        AssistantReply reply = assistant.handle("the next image");

        assertTrue(reply.text().contains("No images are open"));
    }

    @Test
    public void measureItWithoutRoiSelectionFallsThrough() {
        ConversationContext ctx = new ConversationContext();
        ctx.recordIntentRun("image.pixel_size", Collections.<String, String>emptyMap());

        Optional<PronounResolver.Rewrite> rewrite =
                new PronounResolver().resolve("measure it", ctx);

        assertFalse(rewrite.isPresent());
    }

    private static LocalAssistant assistantWith(ConversationContext ctx) {
        return assistantWith(null, ctx);
    }

    private static LocalAssistant assistantWith(RecordingIntent intent,
                                               ConversationContext ctx) {
        IntentLibrary library = IntentLibrary.load();
        if (intent != null) {
            library.register(intent);
        }
        return new LocalAssistant(library, new IntentMatcher(library), null,
                new FrictionLog(), new IntentRouter(), new ClearableHistory(),
                new SlashCommandRegistry(), new imagejai.config.Settings(),
                ctx, new PronounResolver());
    }

    private static void closeOpenImages() {
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus image = WindowManager.getImage(id);
                if (image != null) {
                    image.close();
                }
            }
        }
        WindowManager.setTempCurrentImage(null);
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

    private static class ClearableHistory implements ChatHistoryController {
        public boolean canClear() {
            return true;
        }

        public void clear() {
        }
    }
}
