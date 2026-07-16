package imagejai;

import imagejai.config.Settings;
import imagejai.llm.LLMBackend;
import imagejai.llm.LLMResponse;
import imagejai.llm.Message;
import imagejai.ui.ChatView;
import imagejai.ui.ChatSurface;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConversationLoopClearTest {

    @Test
    public void clearDropsTextAndImageAttachments() throws Exception {
        ConversationLoop loop = loop(new RecordingSurface());
        Field field = ConversationLoop.class.getDeclaredField("history");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Message> history = (List<Message>) field.get(loop);
        history.add(Message.userWithImage("look", new byte[]{1, 2, 3}));
        history.add(Message.assistant("answer"));

        loop.clearHistory();

        assertEquals(0, loop.historySizeForTest());
        assertFalse(loop.historyHasAttachmentsForTest());
    }

    @Test
    public void responseFromTurnClearedWhileModelWasPendingIsDiscarded() throws Exception {
        RecordingSurface surface = new RecordingSurface();
        ConversationLoop loop = loop(surface);
        BlockingBackend backend = new BlockingBackend();
        loop.setBackendForTest(backend);

        loop.onUserMessage("pending question");
        assertTrue(backend.entered.await(2, TimeUnit.SECONDS));
        loop.clearHistory();
        backend.release.countDown();
        assertTrue(backend.returned.await(2, TimeUnit.SECONDS));
        Thread.sleep(50L);

        assertEquals(0, loop.historySizeForTest());
        assertTrue(surface.assistantMessages.isEmpty());
    }

    @Test
    public void responseQueuedForSwingThreadBeforeClearCannotReappearAfterClear()
            throws Exception {
        final ChatView[] holder = new ChatView[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new ChatView(new Settings()));
        ChatView view = holder[0];
        ConversationLoop loop = loop(view);
        BlockingBackend backend = new BlockingBackend();
        loop.setBackendForTest(backend);
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);

        loop.onUserMessage("pending Swing response");
        assertTrue(backend.entered.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(2, TimeUnit.SECONDS));
        backend.release.countDown();
        assertTrue(backend.returned.await(2, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 2_000L;
        while (loop.historySizeForTest() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(2, loop.historySizeForTest());

        loop.clearHistory();
        view.clearConversation();
        releaseEdt.countDown();
        SwingUtilities.invokeAndWait(() -> { });

        java.lang.reflect.Method rendered = ChatView.class
                .getDeclaredMethod("renderedHtmlForTest");
        rendered.setAccessible(true);
        assertFalse(((String) rendered.invoke(view)).contains("stale answer"));
        assertEquals(0, loop.historySizeForTest());
    }

    private static ConversationLoop loop(ChatSurface surface) {
        Settings settings = new Settings();
        settings.configs.clear();
        Settings.ModelConfig config = new Settings.ModelConfig(
                "local", "ollama", "test-model");
        config.url = "http://127.0.0.1:11434";
        settings.configs.add(config);
        settings.activeConfigId = config.id;
        return new ConversationLoop(surface, settings);
    }

    private static final class BlockingBackend implements LLMBackend {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);

        public LLMResponse chat(List<Message> messages, String systemPrompt) {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            returned.countDown();
            return LLMResponse.success("stale answer");
        }

        public LLMResponse chatWithVision(List<Message> messages, String systemPrompt,
                                          byte[] imageBytes) {
            return chat(messages, systemPrompt);
        }

        public boolean testConnection() { return true; }
        public String getModelName() { return "test"; }
        public String getProviderName() { return "ollama"; }
    }

    private static final class RecordingSurface implements ChatSurface {
        final List<String> assistantMessages = new ArrayList<String>();

        public void setEnabled(boolean enabled) { }
        public void setThinking(boolean thinking) { }
        public void appendMessage(String role, String text) {
            if ("assistant".equals(role)) assistantMessages.add(text);
        }
        public void appendHtml(String html) { }
        public void setStatus(String status) { }
    }
}
