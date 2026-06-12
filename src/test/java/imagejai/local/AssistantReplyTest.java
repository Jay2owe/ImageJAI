package imagejai.local;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AssistantReplyTest {

    @Test
    public void textRepliesAreNotClarifying() {
        AssistantReply reply = AssistantReply.text("hello");

        assertEquals("hello", reply.text());
        assertFalse(reply.isClarifying());
        assertTrue(reply.clarificationCandidates().isEmpty());
    }

    @Test
    public void clarifyingReplyRoundTripsCandidates() {
        RankedPhrase first = new RankedPhrase("count frames", "image.stack_counts", 0.97);
        RankedPhrase second = new RankedPhrase("current position", "image.position", 0.94);
        List<RankedPhrase> candidates = new ArrayList<RankedPhrase>(Arrays.asList(first, second));

        AssistantReply reply = AssistantReply.clarifying("Did you mean:", candidates);
        candidates.clear();

        assertEquals("Did you mean:", reply.text());
        assertTrue(reply.isClarifying());
        assertEquals(2, reply.clarificationCandidates().size());
        assertEquals(first, reply.clarificationCandidates().get(0));
        assertEquals(second, reply.clarificationCandidates().get(1));
    }
}
