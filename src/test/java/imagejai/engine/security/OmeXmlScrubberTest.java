package imagejai.engine.security;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OmeXmlScrubberTest {
    @Test
    public void stripsPhiTagsButPreservesPixelsAndChannels() {
        String xml = "<OME>"
                + "<Experimenter>Jane Participant</Experimenter>"
                + "<Description>Study MOAB2 subject 017</Description>"
                + "<AcquisitionDate>2026-05-01</AcquisitionDate>"
                + "<InstrumentRef ID=\"Microscope:serial-123\"/>"
                + "<InstrumentSerialNumber>SN-PRIVATE-9</InstrumentSerialNumber>"
                + "<XMLAnnotation><Value>Participant note</Value></XMLAnnotation>"
                + "<Pixels SizeX=\"10\"><Channel ID=\"Channel:0\"/></Pixels>"
                + "<StageLabel Name=\"SCN-017\"/>"
                + "</OME>";

        String scrubbed = new OmeXmlScrubber().scrub(xml);

        assertFalse(scrubbed.contains("Jane Participant"));
        assertFalse(scrubbed.contains("Study MOAB2"));
        assertFalse(scrubbed.contains("2026-05-01"));
        assertFalse(scrubbed.contains("serial-123"));
        assertFalse(scrubbed.contains("SN-PRIVATE-9"));
        assertFalse(scrubbed.contains("Participant note"));
        assertFalse(scrubbed.contains("SCN-017"));
        assertTrue(scrubbed.contains("<Pixels SizeX=\"10\">"));
        assertTrue(scrubbed.contains("<Channel ID=\"Channel:0\"/>"));
    }
}
