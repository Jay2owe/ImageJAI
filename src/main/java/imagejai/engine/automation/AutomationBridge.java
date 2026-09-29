package imagejai.engine.automation;

import imagejai.engine.EventBus;

/**
 * Lifecycle owner for the test automation bridge.
 *
 * <p>One object holds the identity registry, the UI service, the idle monitor,
 * the capture service, and the timing instrumentation, so the rest of the
 * plugin only has to ask for {@link #handler()} and, at shutdown,
 * {@link #close()}.</p>
 *
 * <p>When the startup policy is not armed the bridge still exists but installs
 * nothing: no event-queue push, no heartbeat thread, no event subscription, no
 * ready file. Production ImageJ-AI therefore runs exactly the code it ran
 * before, and every automation command answers
 * {@code test_automation_disabled}.</p>
 */
public final class AutomationBridge implements AutoCloseable {

    private static final Object SHARED_LOCK = new Object();
    private static AutomationBridge shared;

    private final AutomationPolicy policy;
    private final UiIdentityRegistry identities;
    private final UiAutomationService service;
    private final UiIdleMonitor idleMonitor;
    private final UiCaptureService captureService;
    private final InstrumentedEventQueue eventQueue;
    private final EdtHeartbeat heartbeat;
    private final UiPerformanceMonitor performanceMonitor;
    private final AutomationCommandHandler handler;
    private volatile boolean closed;

    private AutomationBridge(AutomationPolicy policy, EventBus eventBus,
                             boolean instrumentDispatch) {
        this.policy = policy == null ? AutomationPolicy.disabled(null) : policy;
        this.identities = new UiIdentityRegistry();
        this.service = new UiAutomationService(identities, eventBus);
        boolean armed = this.policy.isEnabled();
        InstrumentedEventQueue queue = null;
        EdtHeartbeat beat = null;
        if (armed && instrumentDispatch && !java.awt.GraphicsEnvironment.isHeadless()) {
            try {
                queue = InstrumentedEventQueue.install();
            } catch (Throwable notInstallable) {
                System.err.println("[ImageJAI-Automation] event-queue instrumentation "
                        + "unavailable: " + notInstallable.getClass().getSimpleName());
            }
            try {
                beat = new EdtHeartbeat();
                beat.start();
            } catch (Throwable notStartable) {
                beat = null;
            }
        }
        this.eventQueue = queue;
        this.heartbeat = beat;
        this.captureService = armed ? new UiCaptureService() : null;
        this.performanceMonitor = armed
                ? new UiPerformanceMonitor(queue, beat) : null;
        this.idleMonitor = armed
                ? new UiIdleMonitor(service, eventBus, queue) : null;
        if (this.idleMonitor != null) this.idleMonitor.start();
        this.handler = new AutomationCommandHandler(this.policy, service, idleMonitor,
                captureService, performanceMonitor, eventBus);
    }

    /**
     * The one bridge for this JVM, built from the startup policy the first time
     * it is needed.
     */
    public static AutomationBridge shared() {
        synchronized (SHARED_LOCK) {
            if (shared == null || shared.closed) {
                shared = new AutomationBridge(AutomationPolicy.current(),
                        EventBus.getInstance(), true);
            }
            return shared;
        }
    }

    /**
     * Test seam: an isolated bridge with an explicit policy and bus, and
     * without the process-wide dispatch instrumentation — a test must not push
     * and pop the JVM's system event queue on every method.
     */
    public static AutomationBridge forPolicy(AutomationPolicy policy, EventBus eventBus) {
        return new AutomationBridge(policy, eventBus, false);
    }

    /**
     * The shared registry, or {@code null} when the bridge is not armed. Used by
     * {@code DialogWatcher} so dialog lifecycle events can carry the same window
     * identity the UI tree reports.
     */
    public static UiIdentityRegistry sharedIdentitiesIfEnabled() {
        synchronized (SHARED_LOCK) {
            if (shared == null || shared.closed || !shared.policy.isEnabled()) {
                return null;
            }
            return shared.identities;
        }
    }

    public AutomationPolicy policy() { return policy; }
    public AutomationCommandHandler handler() { return handler; }
    public UiIdentityRegistry identities() { return identities; }
    public UiAutomationService service() { return service; }
    public UiIdleMonitor idleMonitor() { return idleMonitor; }
    public UiPerformanceMonitor performanceMonitor() { return performanceMonitor; }
    public boolean isEnabled() { return policy.isEnabled() && !closed; }

    /**
     * Publish readiness once the server knows its real bound port.
     *
     * @return true when a ready file was written
     */
    public boolean publishReady(int boundPort, String host, String serverVersion,
                                String pluginVersion) {
        if (!isEnabled()) return false;
        boolean written = AutomationReadyFile.write(policy, boundPort, host,
                serverVersion, pluginVersion);
        if (written) {
            System.err.println("[ImageJAI-Automation] test bridge ready on "
                    + host + ":" + boundPort + " (protocol "
                    + AutomationPolicy.PROTOCOL_VERSION + ")");
        }
        return written;
    }

    /** Retire readiness without tearing the bridge down. */
    public boolean retireReady() {
        return policy.isEnabled() && AutomationReadyFile.delete(policy);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            AutomationReadyFile.delete(policy);
        } catch (Throwable ignored) {
        }
        if (idleMonitor != null) idleMonitor.close();
        if (performanceMonitor != null) performanceMonitor.close();
        if (heartbeat != null) heartbeat.close();
        if (eventQueue != null) InstrumentedEventQueue.uninstall();
        identities.clear();
        synchronized (SHARED_LOCK) {
            if (shared == this) shared = null;
        }
    }
}
