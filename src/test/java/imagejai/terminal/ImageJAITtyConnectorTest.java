package imagejai.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import imagejai.engine.security.OutboundPromptScrubber;
import imagejai.engine.security.PathTokenMap;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ImageJAITtyConnectorTest {

    @Test
    public void rejectedOversizedWriteNeverReachesPty() throws Exception {
        RecordingPty process = new RecordingPty();
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(
                new PathTokenMap(), null);
        ImageJAITtyConnector connector = new ImageJAITtyConnector(process, scrubber);

        try {
            connector.write(new byte[OutboundPromptScrubber.MAX_WRITE_BYTES + 1]);
            fail("oversized write should fail closed");
        } catch (OutboundPromptScrubber.PromptLimitException expected) {
            // The connector calls prepare before touching the PTY stream.
        }

        assertEquals(0, process.written.size());
    }

    private static final class RecordingPty extends PtyProcess {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private WinSize size = new WinSize(80, 24);

        @Override public OutputStream getOutputStream() { return written; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
        @Override public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
        @Override public boolean isAlive() { return true; }
        @Override public void setWinSize(WinSize size) { this.size = size; }
        @Override public WinSize getWinSize() { return size; }
    }
}
