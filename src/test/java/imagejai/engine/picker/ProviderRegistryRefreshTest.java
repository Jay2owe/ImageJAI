package imagejai.engine.picker;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Phase D acceptance: per-provider refresh swap and the {@link
 * ProviderRegistry.RefreshWorker} dispatch shell that
 * {@link imagejai.ui.picker.ModelPickerButton#refreshProviderAsync}
 * drives. Whole-popup rebuilds were the original Phase D risk; this test
 * documents the immutable swap behaviour callers rely on.
 */
public class ProviderRegistryRefreshTest {

    @Test
    public void refreshProviderReplacesOnlyNamedProvider() {
        ProviderRegistry registry = ProviderRegistry.loadBundled();
        ProviderEntry originalAnthropic = registry.provider("anthropic");
        ProviderEntry originalOllama = registry.provider("ollama");
        assertNotNull(originalAnthropic);
        assertNotNull(originalOllama);

        ModelEntry replacement = new ModelEntry(
                "anthropic", "claude-haiku-4-5", "Claude Haiku 4.5", "test stub",
                ModelEntry.Tier.PAID, 200_000, true,
                ModelEntry.Reliability.HIGH, false, true, "");
        ProviderEntry newAnthropic = new ProviderEntry(
                "anthropic", "Anthropic", ProviderEntry.Status.READY, "",
                Collections.singletonList(replacement));

        ProviderRegistry next = registry.refreshProvider("anthropic", newAnthropic);

        assertNotSame("refreshProvider must return a new registry", registry, next);
        assertEquals(newAnthropic, next.provider("anthropic"));
        assertSame("ollama branch must not be rebuilt",
                originalOllama, next.provider("ollama"));
        assertNotNull(next.lookup("anthropic", "claude-haiku-4-5"));
        assertNull("old anthropic entry should be evicted",
                next.lookup("anthropic", "claude-sonnet-4-6"));
    }

    @Test
    public void refreshProviderForUnknownProviderReturnsSameRegistry() {
        ProviderRegistry registry = ProviderRegistry.loadBundled();
        ProviderEntry stub = new ProviderEntry(
                "not-a-real-provider", "stub", ProviderEntry.Status.READY, "",
                Collections.<ModelEntry>emptyList());
        assertSame(registry, registry.refreshProvider("not-a-real-provider", stub));
        assertSame(registry, registry.refreshProvider(null, stub));
        assertSame(registry, registry.refreshProvider("anthropic", null));
    }

    @Test
    public void refreshWorkerDispatchesResultToApplier() throws Exception {
        final ProviderEntry payload = new ProviderEntry(
                "groq", "Groq", ProviderEntry.Status.READY, "",
                Collections.<ModelEntry>emptyList());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ProviderEntry> receivedEntry = new AtomicReference<>();
        final AtomicReference<Throwable> receivedError = new AtomicReference<>();
        final AtomicReference<String> receivedId = new AtomicReference<>();

        ProviderRegistry.RefreshWorker worker = new ProviderRegistry.RefreshWorker(
                "groq",
                () -> payload,
                (providerId, newEntry, error) -> {
                    receivedId.set(providerId);
                    receivedEntry.set(newEntry);
                    receivedError.set(error);
                    latch.countDown();
                });
        worker.execute();
        assertTrue("RefreshWorker did not call applier in time",
                latch.await(5, TimeUnit.SECONDS));
        assertEquals("groq", receivedId.get());
        assertSame(payload, receivedEntry.get());
        assertNull(receivedError.get());
    }

    @Test
    public void refreshWorkerSurfacesFetcherFailureAsError() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> receivedError = new AtomicReference<>();
        final AtomicReference<ProviderEntry> receivedEntry = new AtomicReference<>();

        ProviderRegistry.RefreshWorker worker = new ProviderRegistry.RefreshWorker(
                "ollama-cloud",
                () -> { throw new java.io.IOException("simulated 503"); },
                (providerId, newEntry, error) -> {
                    receivedEntry.set(newEntry);
                    receivedError.set(error);
                    latch.countDown();
                });
        worker.execute();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNull(receivedEntry.get());
        assertNotNull(receivedError.get());
    }

    @Test
    public void canonicalProvidersStayOrderedAfterRefresh() {
        ProviderRegistry registry = ProviderRegistry.loadBundled();
        ProviderEntry stub = new ProviderEntry(
                "groq", "Groq", ProviderEntry.Status.READY, "",
                Collections.<ModelEntry>emptyList());
        ProviderRegistry next = registry.refreshProvider("groq", stub);

        // Ordering must follow CANONICAL_PROVIDERS so the dropdown doesn't
        // re-shuffle providers around the refreshed one (05 §3.9 flicker risk).
        Object[] keysBefore = streamProviderIds(registry).toArray();
        Object[] keysAfter = streamProviderIds(next).toArray();
        assertTrue(Arrays.equals(keysBefore, keysAfter));
    }

    @Test
    public void slowStaleRefreshCannotOverwriteNewerGeneration() throws Exception {
        ProviderRegistry.RefreshGeneration generation =
                new ProviderRegistry.RefreshGeneration();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch applied = new CountDownLatch(1);
        AtomicReference<String> appliedName = new AtomicReference<String>();
        AtomicInteger callbacks = new AtomicInteger();
        ProviderEntry stale = entry("groq", "stale");
        ProviderEntry fresh = entry("groq", "fresh");

        ProviderRegistry.RefreshWorker first = new ProviderRegistry.RefreshWorker(
                "groq", () -> {
                    firstStarted.countDown();
                    while (releaseFirst.getCount() > 0) {
                        try { releaseFirst.await(); }
                        catch (InterruptedException ignored) { }
                    }
                    return stale;
                }, (id, entry, error) -> {
                    callbacks.incrementAndGet();
                    appliedName.set(entry == null ? null : entry.displayName());
                    applied.countDown();
                }, generation);
        first.execute();
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        ProviderRegistry.RefreshWorker second = new ProviderRegistry.RefreshWorker(
                "groq", () -> fresh, (id, entry, error) -> {
                    callbacks.incrementAndGet();
                    appliedName.set(entry.displayName());
                    applied.countDown();
                }, generation);
        first.cancel(true);
        second.execute();
        assertTrue(applied.await(2, TimeUnit.SECONDS));
        releaseFirst.countDown();
        Thread.sleep(100L);

        assertEquals(1, callbacks.get());
        assertEquals("fresh", appliedName.get());
    }

    @Test
    public void cancelledGenerationIgnoresLateCompletion() throws Exception {
        ProviderRegistry.RefreshGeneration generation =
                new ProviderRegistry.RefreshGeneration();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        ProviderRegistry.RefreshWorker worker = new ProviderRegistry.RefreshWorker(
                "groq", () -> {
                    try { release.await(); } catch (InterruptedException ignored) { }
                    return entry("groq", "late");
                }, (id, entry, error) -> callbacks.incrementAndGet(), generation);
        worker.execute();
        generation.cancel();
        worker.cancel(true);
        release.countDown();
        Thread.sleep(100L);
        assertEquals(0, callbacks.get());
    }

    private static ProviderEntry entry(String provider, String displayName) {
        return new ProviderEntry(provider, displayName,
                ProviderEntry.Status.READY, "", Collections.<ModelEntry>emptyList());
    }

    private static java.util.stream.Stream<String> streamProviderIds(ProviderRegistry r) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (ProviderEntry p : r.providers()) ids.add(p.providerId());
        return ids.stream();
    }
}
