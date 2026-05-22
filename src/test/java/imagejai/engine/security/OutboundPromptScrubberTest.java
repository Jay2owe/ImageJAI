package imagejai.engine.security;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OutboundPromptScrubberTest {
    @Test
    public void scrubReplacesKnownSensitiveStrings() {
        PathTokenMap map = new PathTokenMap(bytes(1));
        String token = map.tokenForPathString("C:\\study\\subject_017.lif");
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);

        String out = scrubber.scrub("please open C:\\study\\subject_017.lif");

        assertFalse(out.contains("subject_017"));
        assertTrue(out.contains(token));
    }

    @Test
    public void enterRewritesBufferedLineBeforeSending() {
        PathTokenMap map = new PathTokenMap(bytes(2));
        String token = map.tokenForPathString("C:\\study\\subject_017.lif");
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);

        scrubber.filter("open C:\\study\\subject_017.lif".getBytes(StandardCharsets.UTF_8));
        String enter = new String(scrubber.filter("\r".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        assertTrue("line kill should precede the replacement", enter.startsWith("\u0015"));
        assertFalse(enter.contains("subject_017"));
        assertTrue(enter.contains(token));
    }

    @Test
    public void rawOverrideAllowsOneEnterWithoutRewrite() {
        PathTokenMap map = new PathTokenMap(bytes(3));
        map.tokenForPathString("C:\\study\\subject_017.lif");
        OutboundPromptScrubber scrubber = new OutboundPromptScrubber(map, null);

        scrubber.filter("open C:\\study\\subject_017.lif".getBytes(StandardCharsets.UTF_8));
        scrubber.sendNextEnterRaw();
        String enter = new String(scrubber.filter("\r".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);

        assertEquals("\r", enter);
    }

    private static byte[] bytes(int value) {
        byte[] salt = new byte[32];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) value;
        }
        return salt;
    }
}
