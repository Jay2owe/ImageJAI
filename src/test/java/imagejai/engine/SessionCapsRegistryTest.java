package imagejai.engine;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionCapsRegistryTest {

    @Test
    public void defaultIdsAreRandomAndTokensAreRequired() throws Exception {
        SessionCapsRegistry<String> registry = new SessionCapsRegistry<String>();
        SessionCapsRegistry.Created<String> first = registry.create("caps-a", "secret");
        SessionCapsRegistry.Created<String> second = registry.create("caps-b", "secret");

        assertNotEquals(first.id(), second.id());
        assertTrue(first.id().length() >= 32);
        assertEquals(SessionCapsRegistry.Status.TOKEN_MISMATCH,
                registry.lookup(first.id(), "wrong").status());
        assertEquals(SessionCapsRegistry.Status.TOKEN_MISMATCH,
                registry.lookup(first.id(), null).status());
        assertEquals(SessionCapsRegistry.Status.VALID,
                registry.lookup(first.id(), "secret").status());
    }

    @Test
    public void expiryUsesInjectedMonotonicClock() throws Exception {
        FakeClock clock = new FakeClock();
        SessionCapsRegistry<String> registry = registry(2, 1000L, clock);
        SessionCapsRegistry.Created<String> created = registry.create("fixed", "token");

        clock.wallMillis += 86_400_000L; // wall jumps do not expire a session
        assertEquals(SessionCapsRegistry.Status.VALID,
                registry.lookup(created.id(), "token").status());

        clock.nanos += 1_000_000_000L;
        assertEquals(SessionCapsRegistry.Status.EXPIRED,
                registry.lookup(created.id(), "token").status());
        assertEquals(0, registry.size());
    }

    @Test
    public void capacityRejectsWithoutEvictingLiveSessions() throws Exception {
        FakeClock clock = new FakeClock();
        SessionCapsRegistry<String> registry = registry(1, 1000L, clock);
        SessionCapsRegistry.Created<String> first = registry.create("first", "token");

        try {
            registry.create("second", "token");
            fail("capacity must be enforced before allocation");
        } catch (SessionCapsRegistry.CapacityException expected) {
            // expected
        }
        assertSame("first", registry.lookup(first.id(), "token").caps());

        clock.nanos += 1_000_000_001L;
        assertEquals("second", registry.create("second", "token").caps());
    }

    @Test
    public void concurrentCreatesCannotOvershootCapacity() throws Exception {
        FakeClock clock = new FakeClock();
        final SessionCapsRegistry<String> registry = registry(3, 1000L, clock);
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 20; i++) {
                workers.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            registry.create("caps", "token");
                            created.incrementAndGet();
                        } catch (SessionCapsRegistry.CapacityException expected) {
                            rejected.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
            start.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }

        assertEquals(3, created.get());
        assertEquals(17, rejected.get());
        assertEquals(3, registry.size());
    }

    @Test
    public void storedCapabilityReferenceCannotBeReplacedByLookup() throws Exception {
        FakeClock clock = new FakeClock();
        SessionCapsRegistry<Object> registry = registry(2, 1000L, clock);
        Object negotiated = new Object();
        SessionCapsRegistry.Created<Object> created = registry.create(negotiated, "token");

        assertSame(negotiated, registry.lookup(created.id(), "token").caps());
        assertSame(negotiated, registry.lookup(created.id(), "token").caps());
    }

    @Test
    public void revokeAllFailsClosedUntilExplicitActivation() throws Exception {
        FakeClock clock = new FakeClock();
        SessionCapsRegistry<String> registry = registry(2, 1000L, clock);
        SessionCapsRegistry.Created<String> created = registry.create("caps", "token");

        registry.revokeAll();

        assertEquals(SessionCapsRegistry.Status.REVOKED,
                registry.lookup(created.id(), "token").status());
        assertEquals(0, registry.size());
        try {
            registry.create("new", "token");
            fail("revoked registry must reject new sessions");
        } catch (IllegalStateException expected) {
            // expected
        }

        registry.activate();
        assertEquals(SessionCapsRegistry.Status.UNKNOWN,
                registry.lookup(created.id(), "token").status());
    }

    private static <C> SessionCapsRegistry<C> registry(
            int capacity, long ttlMillis, final FakeClock clock) {
        final AtomicInteger ids = new AtomicInteger();
        return new SessionCapsRegistry<C>(capacity, ttlMillis, clock, clock,
                new SessionCapsRegistry.IdSource() {
                    @Override
                    public String nextId() {
                        return String.format("%032d", ids.incrementAndGet());
                    }
                });
    }

    private static final class FakeClock
            implements SessionCapsRegistry.Ticker, SessionCapsRegistry.WallClock {
        long nanos;
        long wallMillis = 1_700_000_000_000L;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public long currentTimeMillis() {
            return wallMillis;
        }
    }
}
