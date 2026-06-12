package imagejai.engine;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Step 12 (docs/tcp_upgrade/12_per_agent_telemetry.md) — verify that
 * {@link FrictionLog} rows carry an {@code agent_id} tag after the signature
 * sweep, and that the back-compat overload leaves the field empty rather
 * than dropping the row.
 */
public class FrictionLogAgentIdTest {

    @Test
    public void newWriteSignaturePropagatesAgentId() {
        FrictionLog log = new FrictionLog();
        log.record("gemma-4-31b", "execute_macro", "code=print(1)", "boom");
        List<FrictionLog.FailureEntry> recent = log.recent(10);
        assertEquals(1, recent.size());
        assertEquals("gemma-4-31b", recent.get(0).agentId);
        assertEquals("execute_macro", recent.get(0).command);
    }

    @Test
    public void legacyWriteSignatureLeavesAgentIdEmpty() {
        FrictionLog log = new FrictionLog();
        log.record("execute_macro", "code=print(1)", "boom");
        FrictionLog.FailureEntry e = log.recent(1).get(0);
        assertEquals("back-compat overload → empty agent_id", "", e.agentId);
    }

    @Test
    public void nullAgentIdIsCoercedToEmptyString() {
        FrictionLog log = new FrictionLog();
        log.record(null, "execute_macro", "", "err");
        assertEquals("", log.recent(1).get(0).agentId);
    }
}
