package imagejai.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Drives {@link LiteLlmProxyService#handleSidecarLine(String)} directly so the
 * cost- and billing-sentinel parsing is covered without spawning the real
 * Python sidecar.
 */
public class LiteLlmProxyServiceTest {

    @Test
    public void costSentinelNotifiesCostListener() {
        LiteLlmProxyService svc = new LiteLlmProxyService(null);
        AtomicReference<String> cost = new AtomicReference<String>();
        svc.addCostHeaderListener(new CostHeaderListener() {
            @Override
            public void onCostHeader(String value) {
                cost.set(value);
            }
        });
        svc.handleSidecarLine("[ImageJAI-LiteLLM-Cost] "
                + "{\"ts\":1.0,\"header\":\"x-litellm-response-cost\",\"cost\":\"0.0123\"}");
        assertEquals("0.0123", cost.get());
    }

    @Test
    public void billingSentinelNotifiesBillingListener() {
        LiteLlmProxyService svc = new LiteLlmProxyService(null);
        AtomicReference<String> provider = new AtomicReference<String>();
        AtomicInteger status = new AtomicInteger(-1);
        AtomicReference<String> message = new AtomicReference<String>();
        svc.addBillingFailureListener(new BillingFailureListener() {
            @Override
            public void onBillingFailure(String p, int s, String m) {
                provider.set(p);
                status.set(s);
                message.set(m);
            }
        });
        svc.handleSidecarLine("[ImageJAI-LiteLLM-Billing] "
                + "{\"event\":\"billing\",\"provider\":\"anthropic\",\"status\":401,"
                + "\"message\":\"no credit balance\"}");
        assertEquals("anthropic", provider.get());
        assertEquals(401, status.get());
        assertEquals("no credit balance", message.get());
    }

    @Test
    public void billingSentinelToleratesMissingProviderAndMessage() {
        LiteLlmProxyService svc = new LiteLlmProxyService(null);
        AtomicReference<String> provider = new AtomicReference<String>("unset");
        AtomicInteger status = new AtomicInteger(-1);
        svc.addBillingFailureListener(new BillingFailureListener() {
            @Override
            public void onBillingFailure(String p, int s, String m) {
                provider.set(p);
                status.set(s);
            }
        });
        svc.handleSidecarLine("[ImageJAI-LiteLLM-Billing] {\"status\":429}");
        assertEquals(429, status.get());
        assertNull(provider.get());
    }

    @Test
    public void malformedBillingLineDoesNotFire() {
        LiteLlmProxyService svc = new LiteLlmProxyService(null);
        AtomicInteger calls = new AtomicInteger(0);
        svc.addBillingFailureListener(new BillingFailureListener() {
            @Override
            public void onBillingFailure(String p, int s, String m) {
                calls.incrementAndGet();
            }
        });
        svc.handleSidecarLine("[ImageJAI-LiteLLM-Billing] not-json-at-all");
        svc.handleSidecarLine("[ImageJAI-LiteLLM-Billing] {\"provider\":\"x\"}"); // no status
        assertEquals(0, calls.get());
    }

    @Test
    public void unrelatedLinesAreIgnored() {
        LiteLlmProxyService svc = new LiteLlmProxyService(null);
        AtomicInteger billing = new AtomicInteger(0);
        AtomicInteger cost = new AtomicInteger(0);
        svc.addBillingFailureListener(new BillingFailureListener() {
            @Override
            public void onBillingFailure(String p, int s, String m) {
                billing.incrementAndGet();
            }
        });
        svc.addCostHeaderListener(new CostHeaderListener() {
            @Override
            public void onCostHeader(String value) {
                cost.incrementAndGet();
            }
        });
        svc.handleSidecarLine("[ImageJAI-LiteLLM] proxy ready on localhost:4000");
        assertEquals(0, billing.get());
        assertEquals(0, cost.get());
    }

    @Test
    public void rapidStartsLaunchExactlyOneSidecar() throws Exception {
        TestService svc = new TestService(true, false, true);
        svc.startAsync();
        assertTrue(svc.launchEntered.await(2, TimeUnit.SECONDS));
        svc.startAsync();
        assertEquals(1, svc.launches.get());

        svc.releaseLaunch.countDown();
        awaitLifecycle(svc, "RUNNING");
        assertTrue(svc.isReady());
        svc.startAsync();
        assertEquals(1, svc.launches.get());

        svc.shutdown();
        assertTrue(svc.lastProcess.destroyed.await(2, TimeUnit.SECONDS));
        assertEquals("SHUTDOWN", svc.lifecycleStateForTest());
    }

    @Test
    public void shutdownRacingStartupCannotLeaveAnOrphan() throws Exception {
        TestService svc = new TestService(true, false, true);
        svc.startAsync();
        assertTrue(svc.launchEntered.await(2, TimeUnit.SECONDS));

        svc.shutdown();
        svc.releaseLaunch.countDown();

        assertTrue(svc.processCreated.await(2, TimeUnit.SECONDS));
        assertTrue(svc.lastProcess.destroyed.await(2, TimeUnit.SECONDS));
        assertFalse(svc.isReady());
        assertEquals("SHUTDOWN", svc.lifecycleStateForTest());
        svc.startAsync();
        assertEquals("shutdown must reject restarts", 1, svc.launches.get());
    }

    @Test
    public void unhealthyStartupTerminatesAndReturnsToStopped() throws Exception {
        TestService svc = new TestService(false, false, false);
        svc.startAsync();
        assertTrue(svc.processCreated.await(2, TimeUnit.SECONDS));
        assertTrue(svc.lastProcess.destroyed.await(2, TimeUnit.SECONDS));
        awaitLifecycle(svc, "STOPPED");
        assertFalse(svc.isReady());
    }

    @Test
    public void launchFailureDoesNotStickInStartingState() throws Exception {
        TestService svc = new TestService(false, true, false);
        svc.startAsync();
        assertTrue(svc.launchEntered.await(2, TimeUnit.SECONDS));
        awaitLifecycle(svc, "STOPPED");
        assertFalse(svc.isReady());
        assertEquals(1, svc.launches.get());
    }

    @Test
    public void healthyChildExitClearsStateAndAllowsRestart() throws Exception {
        TestService svc = new TestService(true, false, false);
        svc.startAsync();
        awaitLifecycle(svc, "RUNNING");
        FakeProcess first = svc.lastProcess;
        assertTrue(svc.isReady());

        first.exitNormally();
        awaitLifecycle(svc, "STOPPED");
        assertFalse(svc.isReady());

        svc.startAsync();
        awaitLifecycle(svc, "RUNNING");
        assertEquals(2, svc.launches.get());
        assertTrue("restart must own a new child", first != svc.lastProcess);
        assertTrue(svc.isReady());

        FakeProcess second = svc.lastProcess;
        svc.shutdown();
        assertTrue(second.destroyed.await(2, TimeUnit.SECONDS));
        assertEquals("SHUTDOWN", svc.lifecycleStateForTest());
    }

    private static void awaitLifecycle(TestService svc, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (expected.equals(svc.lifecycleStateForTest())) return;
            Thread.sleep(10L);
        }
        assertEquals(expected, svc.lifecycleStateForTest());
    }

    private static final class TestService extends LiteLlmProxyService {
        final AtomicInteger launches = new AtomicInteger();
        final CountDownLatch launchEntered = new CountDownLatch(1);
        final CountDownLatch releaseLaunch;
        final CountDownLatch processCreated = new CountDownLatch(1);
        final Path providersDir;
        final boolean healthy;
        final boolean failLaunch;
        volatile FakeProcess lastProcess;

        TestService(boolean healthy, boolean failLaunch, boolean blockLaunch)
                throws IOException {
            super(null);
            this.healthy = healthy;
            this.failLaunch = failLaunch;
            this.releaseLaunch = new CountDownLatch(blockLaunch ? 1 : 0);
            this.providersDir = Files.createTempDirectory("imagejai-litellm-test-")
                    .resolve("agent").resolve("providers");
            Files.createDirectories(providersDir);
        }

        @Override
        protected Path resolveProvidersDir() {
            return providersDir;
        }

        @Override
        protected Process launchSidecar(Path providers, Path proxy, Path config)
                throws IOException {
            launches.incrementAndGet();
            launchEntered.countDown();
            try {
                if (!releaseLaunch.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("test launch gate timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            if (failLaunch) throw new IOException("synthetic launch failure");
            lastProcess = new FakeProcess();
            processCreated.countDown();
            return lastProcess;
        }

        @Override
        protected boolean waitHealthy(double timeoutSeconds) {
            return healthy;
        }
    }

    private static final class FakeProcess extends Process {
        final CountDownLatch destroyed = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        volatile boolean alive = true;

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException {
            exited.await();
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit)
                throws InterruptedException {
            return exited.await(timeout, unit);
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }
        void exitNormally() {
            alive = false;
            exited.countDown();
        }
        @Override public void destroy() {
            alive = false;
            destroyed.countDown();
            exited.countDown();
        }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
