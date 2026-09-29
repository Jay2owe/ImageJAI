package imagejai.engine.automation;

import net.imagej.ImageJService;
import org.scijava.plugin.Plugin;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;

/**
 * Starts the test automation bridge during Fiji startup.
 *
 * <p>Without this, {@code -Dimagejai.testAutomation.enabled=true} arms
 * {@link AutomationPolicy} and nothing ever consults it: the only caller of
 * {@link AutomationBridge#publishReady} sits inside {@code ImageJAIPlugin.run()},
 * which is a menu command, so an external harness had to invoke
 * {@code -run "AI Assistant"} just to make the bridge exist — building the whole
 * chat UI, and taking on every defect in it, to obtain a socket. A SciJava
 * service is instantiated when the Fiji context is created, which is the
 * earliest point at which this decision can honestly be made.</p>
 *
 * <h2>Why this is not a way in</h2>
 * <p>The policy is armed only by a system property on the JVM command line —
 * something only whoever launched this Fiji can set — and the plugin's own
 * {@code tcpServerEnabled} setting still has to be on. Arming it does not grant
 * anything either: the gated command surface additionally requires a session to
 * negotiate the {@code test_automation} capability with the per-installation
 * token, and that gate is untouched here. On an ordinary Fiji the property is
 * absent, this service returns immediately, and nothing starts.</p>
 */
@Plugin(type = Service.class)
public class AutomationBootstrapService extends AbstractService implements ImageJService {

    @Override
    public void initialize() {
        AutomationPolicy policy;
        try {
            policy = AutomationPolicy.current();
        } catch (Throwable unreadablePolicy) {
            // A malformed property must never stop Fiji from starting.
            System.err.println("[ImageJAI-Automation] could not read the startup "
                    + "policy: " + unreadablePolicy);
            return;
        }
        if (!policy.isEnabled()) return;
        Thread waiter = new Thread(new Runnable() {
            @Override public void run() { awaitImageJThenStart(); }
        }, "ImageJAI-automation-bootstrap");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Wait for ImageJ 1.x to finish coming up, then start the bridge.
     *
     * <p>Doing the work inside {@link #initialize()} is too early and actively
     * harmful: service loading happens before Fiji's {@code IJ1Patcher} has
     * instrumented the {@code ij.*} classes, and touching {@code ij.IJ} first
     * makes {@code fiji.Main}'s initialiser fail with an NPE — after which Fiji
     * never builds its main window at all. Verified: the very first build of
     * this hook did exactly that.</p>
     *
     * <p>The wait itself must therefore not load an {@code ij} class either,
     * which is why it matches on the window's class <em>name</em> rather than
     * asking {@code IJ.getInstance()}. Once the main window exists the patcher
     * has long since run and the plugin's own code is safe to touch.</p>
     */
    private void awaitImageJThenStart() {
        long deadline = System.currentTimeMillis() + STARTUP_WAIT_MS;
        boolean sawImageJ = false;
        while (System.currentTimeMillis() < deadline) {
            if (imageJMainWindowExists()) {
                sawImageJ = true;
                break;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!sawImageJ) {
            // Headless, or a Fiji that never opened its window. Starting anyway
            // is right — the bridge is the point of this JVM — but say so, so a
            // client that then sees an empty UI tree knows why.
            System.err.println("[ImageJAI-Automation] no ImageJ main window after "
                    + (STARTUP_WAIT_MS / 1000) + "s; starting the bridge anyway.");
        }
        try {
            startBridgeThroughImageJClassLoader();
        } catch (Throwable startupFailure) {
            // Report loudly, but never take Fiji down with us: a test harness
            // that cannot connect is a far better outcome than a Fiji that
            // cannot start.
            System.err.println("[ImageJAI-Automation] the boot hook failed to start "
                    + "the bridge: " + startupFailure);
            startupFailure.printStackTrace();
        }
    }

    /**
     * Start the bridge through the class of {@code ImageJAIPlugin} that ImageJ
     * itself would load.
     *
     * <p>ImageJ 1.x's {@code PluginClassLoader} searches the plugins folder
     * before delegating to its parent, so a jar that is also on Fiji's main
     * class path can end up loaded twice — once for the SciJava context that
     * created this service, once for the menu command. Two copies of the class
     * means two copies of its statics, so the "a server is already running"
     * guard in the boot path would be invisible to the panel and Fiji would end
     * up with two servers on two ports and two ready files. Resolving the class
     * through ImageJ's own loader makes both routes agree on one copy whichever
     * way that loader delegates.</p>
     */
    private static void startBridgeThroughImageJClassLoader() throws Exception {
        ClassLoader loader = null;
        try {
            loader = ij.IJ.getClassLoader();
        } catch (Throwable noImageJ) {
            // No ImageJ 1.x in this JVM (a bare unit-test context); the direct
            // call below is then the only copy there is.
        }
        if (loader == null) {
            imagejai.ImageJAIPlugin.startBridgeAtBoot();
            return;
        }
        Class<?> plugin = loader.loadClass("imagejai.ImageJAIPlugin");
        plugin.getMethod("startBridgeAtBoot").invoke(null);
    }

    /** How long to wait for ImageJ's main window before starting regardless. */
    private static final long STARTUP_WAIT_MS = 120_000L;
    private static final long POLL_MS = 200L;

    /**
     * True once ImageJ 1.x's main frame exists. Deliberately compared by class
     * name: naming the type would load {@code ij.ImageJ} from this thread and
     * reintroduce the very class-loading race this wait exists to avoid.
     */
    static boolean imageJMainWindowExists() {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) return false;
            java.awt.Window[] windows = java.awt.Window.getWindows();
            if (windows == null) return false;
            for (java.awt.Window window : windows) {
                if (window == null) continue;
                if ("ij.ImageJ".equals(window.getClass().getName())) {
                    return window.isShowing();
                }
            }
        } catch (Throwable notReady) {
            return false;
        }
        return false;
    }
}
