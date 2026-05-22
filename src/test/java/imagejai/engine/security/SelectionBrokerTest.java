package imagejai.engine.security;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelectionBrokerTest {
    @Test
    public void consumeReturnsBriefOnce() {
        SelectionBroker broker = new SelectionBroker();
        Brief brief = new Brief("s1", Arrays.asList("image-7a3f.lif:1"),
                "8 weeks, wild-type", Collections.<String, Object>emptyMap());

        broker.enqueue(brief);

        assertTrue(broker.hasPending("s1"));
        assertEquals(brief, broker.peek("s1").get());
        assertEquals(brief, broker.consume("s1").get());
        assertFalse(broker.consume("s1").isPresent());
        assertFalse(broker.hasPending("s1"));
    }
}
