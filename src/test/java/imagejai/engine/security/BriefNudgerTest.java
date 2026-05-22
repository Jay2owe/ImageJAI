package imagejai.engine.security;

import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
        BriefNudger nudger = new BriefNudger(
                new BriefNudger.ClipboardWriter() {
                    @Override public void write(String text) { clipboard.append(text); }
                },
                new BriefNudger.Toast() {
                    @Override public void show(String message) { }
                });

        nudger.deliver(brief(), BriefNudger.NudgeMechanism.TCP_POLLING, null);

        assertEquals("", clipboard.toString());
    }

    @Test
    public void embeddedFallbackWritesToAgentInput() {
        BriefNudger nudger = new BriefNudger();
        FakeSession session = new FakeSession();

        nudger.deliver(brief(), BriefNudger.NudgeMechanism.EMBEDDED_PTY, session);

        assertTrue(session.input.toString().contains("image-7a3f.lif:1"));
        assertTrue(session.input.toString().contains("get_pending_brief"));
    }

    private static Brief brief() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("channels", 4);
        metadata.put("dimensions", "64x64x5");
        return new Brief("s1", Arrays.asList("image-7a3f.lif:1"),
                "8 weeks, wild-type", metadata);
    }

    private static final class FakeSession implements AgentSession {
        final StringBuilder input = new StringBuilder();

        @Override public AgentLauncher.AgentInfo info() { return null; }
        @Override public void writeInput(String s) { input.append(s); }
        @Override public InputStream output() { return new ByteArrayInputStream(new byte[0]); }
        @Override public boolean isAlive() { return true; }
        @Override public int exitValue() { return -1; }
        @Override public void interrupt() { }
        @Override public void destroy() { }
    }
}
