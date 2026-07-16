package imagejai.engine;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuiActionDispatcherTest {

    @Test
    public void invalidatedQueuedSwingActionCannotRunLate() throws Exception {
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));

        AtomicInteger executions = new AtomicInteger();
        GuiActionDispatcher.ActionToken token =
                GuiActionDispatcher.queueSwingAction(executions::incrementAndGet);
        assertTrue("queued action must be cancellable before it starts",
                token.invalidate());

        releaseBlocker.countDown();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, executions.get());
        assertFalse(token.hasStarted());
        assertTrue(token.isFinished());
    }

    @Test
    public void onlyVerifiedCancelRouteIsActivatedDuringProbeCleanup() {
        AtomicInteger cancelled = new AtomicInteger();
        JPanel cancellable = new JPanel();
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> cancelled.incrementAndGet());
        cancellable.add(cancel);

        assertTrue(TCPCommandServer.hasCancelButton(cancellable));
        assertTrue(TCPCommandServer.clickCancelButton(cancellable));
        assertEquals(1, cancelled.get());

        AtomicInteger pluginAction = new AtomicInteger();
        JPanel unsupported = new JPanel();
        JButton run = new JButton("Run");
        run.addActionListener(event -> pluginAction.incrementAndGet());
        unsupported.add(run);
        assertFalse(TCPCommandServer.hasCancelButton(unsupported));
        assertEquals("unsupported dialog must not trigger its action",
                0, pluginAction.get());
    }
}
