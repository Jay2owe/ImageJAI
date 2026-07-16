package imagejai.ui;

import com.google.gson.JsonObject;
import imagejai.engine.EventBus;
import imagejai.engine.security.VisualOverrideRegistry;
import org.junit.Test;

import javax.swing.SwingUtilities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VisualOverrideNoticeTest {
    @Test
    public void visualRequestEventCanBeGrantedOnce() throws Exception {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        VisualOverrideNotice notice = new VisualOverrideNotice(
                EventBus.getInstance(), registry);
        try {
            VisualOverrideRegistry.PendingRequest pending =
                    registry.request("session-a", "check focus", "image-a");
            JsonObject frame = frame(
                    "session-a", "check focus", pending.requestId);

            notice.onEvent(frame);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                }
            });

            assertTrue(notice.isVisible());
            assertTrue(notice.detailTextForTest().contains("check focus"));

            notice.allowForTest();

            assertFalse(notice.isVisible());
            assertTrue(registry.hasGrant("session-a", "image-a"));
        } finally {
            notice.dispose();
        }
    }

    @Test
    public void visualRequestEventCanBeDenied() throws Exception {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        VisualOverrideRegistry.PendingRequest pending =
                registry.request("session-b", "check contamination", "image-b");
        VisualOverrideNotice notice = new VisualOverrideNotice(
                EventBus.getInstance(), registry);
        try {
            notice.onEvent(frame("session-b", "check contamination",
                    pending.requestId));
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                }
            });

            notice.denyForTest();

            assertFalse(notice.isVisible());
            assertFalse(registry.hasGrant("session-b"));
            assertFalse(registry.pending("session-b").isPresent());
        } finally {
            notice.dispose();
        }
    }

    private static JsonObject frame(String session, String reason, String requestId) {
        JsonObject data = new JsonObject();
        data.addProperty("session", session);
        data.addProperty("reason", reason);
        data.addProperty("request_id", requestId);
        JsonObject frame = new JsonObject();
        frame.addProperty("event", "data_governance.visual.requested");
        frame.add("data", data);
        return frame;
    }
}
