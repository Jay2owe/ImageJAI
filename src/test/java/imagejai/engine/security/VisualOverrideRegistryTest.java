package imagejai.engine.security;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VisualOverrideRegistryTest {

    @Test
    public void grantRequiresExactLiveRequestSessionAndImage() {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        VisualOverrideRegistry.PendingRequest pending =
                registry.request("session-a", "inspect focus", "image-a");

        assertFalse(registry.grant("session-b", pending.requestId, "inspect focus"));
        assertFalse(registry.grant("session-a", "wrong-id", "inspect focus"));
        assertTrue(registry.grant(
                "session-a", pending.requestId, "inspect focus"));
        assertFalse(registry.hasGrant("session-a", "image-b"));
        assertFalse(registry.consumeIfPresent("session-a", "image-b"));
        assertTrue(registry.hasGrant("session-a", "image-a"));
        assertTrue(registry.consumeIfPresent("session-a", "image-a"));
        assertFalse(registry.consumeIfPresent("session-a", "image-a"));
    }

    @Test
    public void replacementRequestInvalidatesStaleRequestId() {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        VisualOverrideRegistry.PendingRequest stale =
                registry.request("session-a", "first", "image-a");
        VisualOverrideRegistry.PendingRequest current =
                registry.request("session-a", "second", "image-a");

        assertNotEquals(stale.requestId, current.requestId);
        assertFalse(registry.grant("session-a", stale.requestId, "first"));
        assertTrue(registry.grant("session-a", current.requestId, "second"));
    }

    @Test
    public void requestIdsCarryTwoHundredFiftySixBitsOfRandomness() {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        String first = registry.request("a", "reason", "image-a").requestId;
        String second = registry.request("b", "reason", "image-a").requestId;

        assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
        assertTrue(second.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first, second);
    }

    @Test
    public void requestAndGrantMapsStayWithinDeclaredCaps() {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        List<VisualOverrideRegistry.PendingRequest> pending =
                new ArrayList<VisualOverrideRegistry.PendingRequest>();
        for (int i = 0; i < VisualOverrideRegistry.MAX_PENDING_REQUESTS; i++) {
            pending.add(registry.request("session-" + i, "reason", "image-a"));
        }
        assertEquals(VisualOverrideRegistry.MAX_PENDING_REQUESTS,
                registry.pendingCount());
        try {
            registry.request("overflow", "reason", "image-a");
            fail("pending request capacity should reject overflow");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("capacity"));
        }

        for (int i = 0; i < pending.size(); i++) {
            assertTrue(registry.grant("session-" + i,
                    pending.get(i).requestId, "reason"));
        }
        assertEquals(0, registry.pendingCount());
        assertEquals(VisualOverrideRegistry.MAX_GRANTS, registry.grantCount());

        VisualOverrideRegistry.PendingRequest extra =
                registry.request("grant-overflow", "reason", "image-a");
        assertFalse(registry.grant(
                "grant-overflow", extra.requestId, "reason"));
        assertEquals(VisualOverrideRegistry.MAX_GRANTS, registry.grantCount());
        assertEquals(2L, registry.rejectedEntryCount());
    }

    @Test
    public void exactGrantCanBeConsumedByOnlyOneConcurrentCapture()
            throws Exception {
        final VisualOverrideRegistry registry = new VisualOverrideRegistry();
        VisualOverrideRegistry.PendingRequest pending =
                registry.request("session-a", "reason", "image-a");
        assertTrue(registry.grant("session-a", pending.requestId, "reason"));
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 16; i++) {
                results.add(pool.submit(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        start.await();
                        return registry.consumeIfPresent("session-a", "image-a");
                    }
                }));
            }
            start.countDown();
            int consumed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) consumed++;
            }
            assertEquals(1, consumed);
        } finally {
            pool.shutdownNow();
        }
    }
}
