package imagejai.ui;

import imagejai.config.Settings;
import imagejai.local.AssistantReply;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatViewClearTest {

    @Test
    public void unifiedClearRemovesRenderedHtmlAndNotifiesRemoteHistoryOnce()
            throws Exception {
        AtomicReference<ChatView> ref = new AtomicReference<ChatView>();
        SwingUtilities.invokeAndWait(() -> ref.set(new ChatView(new Settings())));
        ChatView view = ref.get();
        AtomicInteger clears = new AtomicInteger();
        view.addConversationClearListener(clears::incrementAndGet);

        view.appendHtml("<div id='stale-marker'>stale transcript</div>");
        flushEdt();
        assertTrue(view.renderedHtmlForTest().contains("stale transcript"));

        SwingUtilities.invokeAndWait(view::clearConversation);
        flushEdt();

        assertFalse(view.renderedHtmlForTest().contains("stale transcript"));
        assertFalse(view.renderedHtmlForTest().contains("Conversation cleared"));
        assertEquals(0, view.pendingConfirmCountForTest());
        assertEquals(1, clears.get());
    }

    @Test
    public void transcriptAndListenerFloodsRemainBoundedWithDropCounts()
            throws Exception {
        AtomicReference<ChatView> ref = new AtomicReference<ChatView>();
        SwingUtilities.invokeAndWait(() -> ref.set(new ChatView(new Settings())));
        ChatView view = ref.get();

        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 700; i++) {
                view.appendHtml("<div>entry-" + i + "</div>");
            }
        });
        for (int i = 0; i < ChatView.MAX_CHAT_LISTENERS + 10; i++) {
            view.addChatListener(new ChatPanel.ChatListener() {
                @Override public void onUserMessage(String message) { }
            });
            view.addConversationClearListener(new Runnable() {
                @Override public void run() { }
            });
        }
        flushEdt();

        assertEquals(ChatView.MAX_TRANSCRIPT_ENTRIES,
                view.transcriptEntryCountForTest());
        assertTrue(view.transcriptCharCountForTest()
                <= ChatView.MAX_TRANSCRIPT_CHARS);
        assertEquals(201L, view.droppedTranscriptEntryCountForTest());
        assertEquals(10L, view.rejectedChatListenerCountForTest());
        assertEquals(10L, view.rejectedClearListenerCountForTest());
    }

    @Test
    public void blockedEdtCannotAccumulateUnboundedRawTranscriptStrings()
            throws Exception {
        AtomicReference<ChatView> ref = new AtomicReference<ChatView>();
        SwingUtilities.invokeAndWait(() -> ref.set(new ChatView(new Settings())));
        ChatView view = ref.get();
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(2, TimeUnit.SECONDS));

        String raw = repeat('x', ChatView.MAX_TRANSCRIPT_FRAGMENT_CHARS * 2);
        for (int i = 0; i < 500; i++) view.appendHtml(raw);

        assertTrue(view.pendingTranscriptAppendCount()
                <= ChatView.MAX_PENDING_TRANSCRIPT_APPENDS);
        assertTrue(view.droppedPendingTranscriptAppendCount() >= 372L);
        view.setEnabled(false);
        view.setEnabled(true);
        releaseEdt.countDown();
        flushEdt();
        assertTrue("critical enabled state must not be dropped with display work",
                view.inputAreaForTest().isEnabled());
        assertTrue(view.sendButtonForTest().isEnabled());
    }

    @Test
    public void clearWhileLocalHandlerIsBlockedDoesNotSpawnQueuedWorkers()
            throws Exception {
        AtomicReference<ChatView> ref = new AtomicReference<ChatView>();
        SwingUtilities.invokeAndWait(() -> ref.set(new ChatView(new Settings())));
        ChatView view = ref.get();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        view.setLocalAssistantHandlerForTest(text -> {
            calls.incrementAndGet();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return AssistantReply.text("done");
        });

        SwingUtilities.invokeAndWait(() -> view.sendMessageForTest("first"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        for (int i = 0; i < 50; i++) {
            final int index = i;
            SwingUtilities.invokeAndWait(() -> {
                view.clearConversation();
                view.sendMessageForTest("again-" + index);
            });
        }

        assertEquals(1, calls.get());
        assertEquals(0, view.localAssistantQueuedTaskCountForTest());
        assertTrue(view.localAssistantBusyForTest());
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (view.localAssistantBusyForTest() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertFalse(view.localAssistantBusyForTest());
    }

    @Test
    public void confirmOptionsAreBoundedBeforeSwingAdmission() {
        List<String> options = new ArrayList<String>();
        for (int i = 0; i < 1000; i++) {
            options.add(repeat('x', ChatView.MAX_CONFIRM_OPTION_CHARS + 50));
        }

        List<String> bounded = ChatView.boundedConfirmOptions(options);

        assertEquals(ChatView.MAX_CONFIRM_OPTIONS, bounded.size());
        for (String option : bounded) {
            assertTrue(option.length() <= ChatView.MAX_CONFIRM_OPTION_CHARS);
        }
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
