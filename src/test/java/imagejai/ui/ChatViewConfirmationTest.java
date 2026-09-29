package imagejai.ui;

import imagejai.config.Settings;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatViewConfirmationTest {

    private static final class LiveChatView extends ChatView {
        LiveChatView() {
            super(new Settings());
        }

        @Override boolean isPanelLive() {
            return true;
        }
    }

    @Test
    public void cancelBeforeQueuedRenderSuppressesPromptAndLateCallback()
            throws Exception {
        LiveChatView view = createView();
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(2, TimeUnit.SECONDS));

        AtomicInteger callbacks = new AtomicInteger();
        assertTrue(view.confirm("queued", "Proceed?", Arrays.asList("Yes"),
                ignored -> callbacks.incrementAndGet()));
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread cancelThread = new Thread(
                () -> cancelled.set(view.cancelConfirmation("queued")),
                "confirm-cancel-test");
        cancelThread.start();
        awaitActiveCount(view, 0);

        releaseEdt.countDown();
        cancelThread.join(TimeUnit.SECONDS.toMillis(2));
        flushEdt();

        assertFalse(cancelThread.isAlive());
        assertTrue(cancelled.get());
        assertEquals(0, view.pendingConfirmCountForTest());
        assertEquals(0, callbacks.get());
    }

    @Test
    public void cancellingOldPromptLeavesNewerPromptClickable() throws Exception {
        LiveChatView view = createView();
        AtomicInteger oldCallbacks = new AtomicInteger();
        AtomicReference<String> newChoice = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(view.confirm("old", "Old?", Arrays.asList("Old option"),
                    ignored -> oldCallbacks.incrementAndGet()));
            assertTrue(view.confirm("new", "New?", Arrays.asList("New option"),
                    newChoice::set));
        });

        assertTrue(view.cancelConfirmation("old"));
        assertEquals(1, view.pendingConfirmCountForTest());
        JButton newer = firstButton((JPanel) view.confirmHostForTest().getComponent(0));
        SwingUtilities.invokeAndWait(newer::doClick);

        assertEquals(0, oldCallbacks.get());
        assertEquals("New option", newChoice.get());
        assertEquals(0, view.activeConfirmationCountForTest());
    }

    @Test
    public void clickAndCancelResolveOnceInEitherOrder() throws Exception {
        LiveChatView clickedFirst = createView();
        AtomicInteger clickCallbacks = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> assertTrue(clickedFirst.confirm(
                "click-first", "Choose", Arrays.asList("Go"),
                ignored -> clickCallbacks.incrementAndGet())));
        JButton clicked = firstButton(
                (JPanel) clickedFirst.confirmHostForTest().getComponent(0));
        SwingUtilities.invokeAndWait(clicked::doClick);
        assertFalse(clickedFirst.cancelConfirmation("click-first"));
        SwingUtilities.invokeAndWait(clicked::doClick);
        assertEquals(1, clickCallbacks.get());

        LiveChatView cancelledFirst = createView();
        AtomicInteger cancelCallbacks = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> assertTrue(cancelledFirst.confirm(
                "cancel-first", "Choose", Arrays.asList("Go"),
                ignored -> cancelCallbacks.incrementAndGet())));
        JButton stale = firstButton(
                (JPanel) cancelledFirst.confirmHostForTest().getComponent(0));
        assertTrue(cancelledFirst.cancelConfirmation("cancel-first"));
        SwingUtilities.invokeAndWait(() -> {
            stale.setEnabled(true);
            stale.doClick();
        });
        assertEquals(0, cancelCallbacks.get());
        assertEquals(0, cancelledFirst.pendingConfirmCountForTest());
    }

    @Test
    public void removalInvalidatesAllCallbacksAndClearsBoundedState()
            throws Exception {
        LiveChatView view = createView();
        AtomicInteger callbacks = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> assertTrue(view.confirm(
                "dispose", "Choose", Arrays.asList("Go"),
                ignored -> callbacks.incrementAndGet())));
        JButton stale = firstButton((JPanel) view.confirmHostForTest().getComponent(0));

        SwingUtilities.invokeAndWait(view::removeNotify);
        SwingUtilities.invokeAndWait(() -> {
            stale.setEnabled(true);
            stale.doClick();
        });

        assertEquals(0, callbacks.get());
        assertEquals(0, view.activeConfirmationCountForTest());
        assertEquals(0, view.pendingConfirmCountForTest());
    }

    @Test
    public void pendingConfirmationStateIsBounded() throws Exception {
        LiveChatView view = createView();
        AtomicInteger admitted = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            for (int index = 0; index < ChatView.MAX_PENDING_CONFIRMATIONS + 1; index++) {
                if (view.confirm("id-" + index, "Prompt", Arrays.asList("OK"),
                        ignored -> { })) {
                    admitted.incrementAndGet();
                }
            }
        });

        assertEquals(ChatView.MAX_PENDING_CONFIRMATIONS, admitted.get());
        assertEquals(ChatView.MAX_PENDING_CONFIRMATIONS,
                view.activeConfirmationCountForTest());
        assertEquals(ChatView.MAX_VISIBLE_CONFIRMATIONS,
                view.pendingConfirmCountForTest());
        SwingUtilities.invokeAndWait(view::removeNotify);
    }

    private static LiveChatView createView() throws Exception {
        AtomicReference<LiveChatView> ref = new AtomicReference<LiveChatView>();
        SwingUtilities.invokeAndWait(() -> ref.set(new LiveChatView()));
        return ref.get();
    }

    private static JButton firstButton(JPanel row) {
        for (Component component : row.getComponents()) {
            if (component instanceof JButton) return (JButton) component;
        }
        throw new AssertionError("confirmation button not found");
    }

    private static void awaitActiveCount(ChatView view, int expected)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (view.activeConfirmationCountForTest() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(2L);
        }
        assertEquals(expected, view.activeConfirmationCountForTest());
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }
}
