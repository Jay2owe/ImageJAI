package imagejai.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
