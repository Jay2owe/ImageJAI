package imagejai.ui;

import imagejai.config.Settings;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
