package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import imagejai.ui.ChatPanelController;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuiActionDispatcherTest {

    private static final class ImmediateConfirmController
            implements ChatPanelController {
        @Override public void inlineImage(Path path) { }
        @Override public void toast(String message, String level) { }
        @Override public void showMarkdown(String content) { }
        @Override public void highlightRoi(String imageTitle, int[] roiBounds) { }
        @Override public void focusImage(String imageTitle) { }
        @Override public void confirm(String prompt, List<String> options,
                                      Consumer<String> onChoice) {
            onChoice.accept(options.get(0));
        }
    }

    @Test
    public void clientConfirmationIdCorrelatesImmediateCallback() {
        EventBus bus = new EventBus(() -> 1L);
        List<JsonObject> frames = new ArrayList<JsonObject>();
        bus.subscribe("gui_action.confirm.resolved", frames::add);
        GuiActionDispatcher dispatcher = new GuiActionDispatcher(
                new ImmediateConfirmController(), bus);
        JsonObject request = confirmationRequest("confirm-client_123");

        JsonObject response = dispatcher.dispatch(request);

        assertTrue(response.get("ok").getAsBoolean());
        assertEquals("confirm-client_123", response.get("id").getAsString());
        assertEquals(1, frames.size());
        assertEquals("confirm-client_123", frames.get(0)
                .getAsJsonObject("data").get("id").getAsString());
        assertEquals("Yes", frames.get(0)
                .getAsJsonObject("data").get("choice").getAsString());
    }

    @Test
    public void rejectsUnboundedOrUnsafeClientConfirmationIds() {
        GuiActionDispatcher dispatcher = new GuiActionDispatcher(
                new ImmediateConfirmController(), new EventBus(() -> 1L));

        JsonObject oversized = dispatcher.dispatch(confirmationRequest(
                repeat('a', GuiActionDispatcher.MAX_CONFIRM_ID_CHARS + 1)));
        JsonObject unsafe = dispatcher.dispatch(confirmationRequest("raw id/line\n"));

        assertFalse(oversized.get("ok").getAsBoolean());
        assertFalse(unsafe.get("ok").getAsBoolean());
    }

    @Test
    public void confirmationCancellationPublishesCorrelatedCleanupFrame() {
        EventBus bus = new EventBus(() -> 1L);
        List<JsonObject> frames = new ArrayList<JsonObject>();
        bus.subscribe("gui_action.confirm.resolved", frames::add);
        GuiActionDispatcher dispatcher = new GuiActionDispatcher(
                new ImmediateConfirmController(), bus);
        JsonObject request = new JsonObject();
        request.addProperty("type", "confirm_cancel");
        request.addProperty("id", "confirm-timeout_1");

        JsonObject response = dispatcher.dispatch(request);

        assertTrue(response.get("ok").getAsBoolean());
        assertTrue(response.get("cancelled").getAsBoolean());
        assertEquals(1, frames.size());
        JsonObject data = frames.get(0).getAsJsonObject("data");
        assertEquals("confirm-timeout_1", data.get("id").getAsString());
        assertTrue(data.get("cancelled").getAsBoolean());
    }

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

    private static JsonObject confirmationRequest(String id) {
        JsonObject request = new JsonObject();
        request.addProperty("type", "confirm");
        request.addProperty("id", id);
        request.addProperty("prompt", "Proceed?");
        JsonArray options = new JsonArray();
        options.add("Yes");
        options.add("No");
        request.add("options", options);
        return request;
    }

    private static String repeat(char c, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int i = 0; i < count; i++) value.append(c);
        return value.toString();
    }
}
