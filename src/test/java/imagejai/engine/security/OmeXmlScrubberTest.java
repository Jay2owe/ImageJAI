package imagejai.engine.security;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OmeXmlScrubberTest {
    @Test
    public void stripsPhiTagsButPreservesPixelsAndChannels() {
        String xml = "<OME>"
                + "<Experimenter>Jane Participant</Experimenter>"
                + "<AcquisitionDate>2026-05-01</AcquisitionDate>"
                + "<Pixels SizeX=\"10\"><Channel ID=\"Channel:0\"/></Pixels>"
                + "<StageLabel Name=\"SCN-017\"/>"
                + "</OME>";

        String scrubbed = new OmeXmlScrubber().scrub(xml);

        assertFalse(scrubbed.contains("Jane Participant"));
        assertFalse(scrubbed.contains("2026-05-01"));
        assertFalse(scrubbed.contains("SCN-017"));
        assertTrue(scrubbed.contains("<Pixels SizeX=\"10\">"));
        assertTrue(scrubbed.contains("<Channel ID=\"Channel:0\"/>"));
    }
}
