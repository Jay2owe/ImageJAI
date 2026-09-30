package imagejai.engine.security;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BriefNudgerTest {
    @Test
    public void clipboardDeliveryWritesNudgeAndToast() {
        final StringBuilder clipboard = new StringBuilder();
        final StringBuilder toast = new StringBuilder();
        BriefNudger nudger = new BriefNudger(
                new BriefNudger.ClipboardWriter() {
                    @Override public void write(String text) { clipboard.append(text); }
                },
                new BriefNudger.Toast() {
                    @Override public void show(String message) { toast.append(message); }
                });

        nudger.deliver(brief(), BriefNudger.NudgeMechanism.CLIPBOARD, null);

        assertTrue(clipboard.toString().contains("image-7a3f.lif:1"));
        assertTrue(clipboard.toString().contains("get_pending_brief"));
        assertTrue(toast.toString().contains("Ctrl+V"));
    }

    @Test
    public void tcpPollingDoesNothing() {
        final StringBuilder clipboard = new StringBuilder();
        final StringBuilder toast = new StringBuilder();
        BriefNudger nudger = new BriefNudger(
                new BriefNudger.ClipboardWriter() {
                    @Override public void write(String text) { clipboard.append(text); }
                },
                new BriefNudger.Toast() {
                    @Override public void show(String message) { toast.append(message); }
                });

        nudger.deliver(brief(), BriefNudger.NudgeMechanism.TCP_POLLING, null);

        assertEquals("", clipboard.toString());
        assertEquals("", toast.toString());
    }

    private static Brief brief() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("channels", 4);
        metadata.put("dimensions", "64x64x5");
        return new Brief("s1", Arrays.asList("image-7a3f.lif:1"),
                "8 weeks, wild-type", metadata);
    }
}
