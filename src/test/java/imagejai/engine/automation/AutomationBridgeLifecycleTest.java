package imagejai.engine.automation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * Bridge lifecycle: nothing is installed on a normal Fiji, everything is
 * removed on shutdown, and readiness follows the socket rather than the
 * process.
 */
public class AutomationBridgeLifecycleTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private AutomationPolicy armedPolicy() throws Exception {
        File workspace = temp.newFolder();
        Properties properties = new Properties();
        properties.setProperty(AutomationPolicy.PROP_ENABLED, "true");
        properties.setProperty(AutomationPolicy.PROP_WORKSPACE,
                workspace.getAbsolutePath());
        properties.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(workspace, "ready.json").getAbsolutePath());
        return AutomationPolicy.fromProperties(properties);
    }

    @Test
    public void aDisabledPolicyInstallsAndStartsNothing() {
        AutomationBridge bridge = AutomationBridge.forPolicy(
                AutomationPolicy.disabled(AutomationPolicy.REASON_ENABLED_ABSENT), null);
        try {
            assertFalse(bridge.isEnabled());
            assertNull(bridge.idleMonitor());
            assertNull(bridge.performanceMonitor());
            assertNotNull("commands still answer, with a refusal", bridge.handler());
            assertFalse(bridge.publishReady(7746, "127.0.0.1", "1.9.0", "0.3.0"));
            assertFalse(bridge.retireReady());
            assertNull(AutomationBridge.sharedIdentitiesIfEnabled());
        } finally {
            bridge.close();
        }
    }

    @Test
    public void anArmedBridgeExposesItsServicesAndCleansUpOnClose() throws Exception {
        AutomationPolicy policy = armedPolicy();
        AutomationBridge bridge = AutomationBridge.forPolicy(policy, null);
        try {
            assertTrue(bridge.isEnabled());
            assertNotNull(bridge.idleMonitor());
            assertNotNull(bridge.performanceMonitor());
            assertNotNull(bridge.service());

            assertTrue(bridge.publishReady(53_211, "127.0.0.1", "1.9.0", "0.3.0"));
            assertTrue(Files.isRegularFile(policy.readyFile()));
        } finally {
            bridge.close();
        }

        // Shutdown retires readiness so a dead instance cannot look alive.
        assertFalse(Files.exists(policy.readyFile()));
        assertFalse(bridge.isEnabled());
        // Closing twice is not an error.
        bridge.close();
    }

    @Test
    public void retiringReadinessKeepsTheBridgeUsable() throws Exception {
        AutomationPolicy policy = armedPolicy();
        AutomationBridge bridge = AutomationBridge.forPolicy(policy, null);
        try {
            assertTrue(bridge.publishReady(53_211, "127.0.0.1", "1.9.0", "0.3.0"));
            assertTrue(bridge.retireReady());
            assertFalse(Files.exists(policy.readyFile()));
            // A stop/start cycle re-publishes on the new port.
            assertTrue(bridge.isEnabled());
            assertTrue(bridge.publishReady(53_212, "127.0.0.1", "1.9.0", "0.3.0"));
        } finally {
            bridge.close();
        }
    }

    @Test
    public void dispatchInstrumentationInstallsOnceAndUninstallsCleanly()
            throws Exception {
        assumeFalse("no event queue to instrument", GraphicsEnvironment.isHeadless());
        // Production never arms the bridge, so nothing should be installed
        // here; if some other suite left one behind, this test is not the place
        // to tear it down.
        org.junit.Assume.assumeTrue("an instrumented queue is already installed",
                InstrumentedEventQueue.installed() == null);

        InstrumentedEventQueue first = InstrumentedEventQueue.install();
        try {
            assertNotNull(first);
            assertTrue("installing twice returns the same queue",
                    first == InstrumentedEventQueue.install());
            assertTrue(first == InstrumentedEventQueue.installed());
        } finally {
            InstrumentedEventQueue.uninstall();
        }

        assertNull(InstrumentedEventQueue.installed());
        // The pop is scheduled on the event thread; make sure it ran and the
        // queue still dispatches afterwards.
        final boolean[] dispatched = new boolean[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { dispatched[0] = true; }
        });
        assertTrue(dispatched[0]);
    }
}
