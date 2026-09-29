package imagejai;

import imagejai.engine.MutationCoordinator;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ImageJAIPluginLifecycleTest {

    @After
    public void cleanUpSession() {
        ImageJAIPlugin.PluginSession session = ImageJAIPlugin.activeSession;
        if (session != null) session.close();
        ImageJAIPlugin.activeSession = null;
    }

    @Test
    public void delayedCloseOnlyShutsDownTheSessionThatOwnedTheWindow() {
        MutationCoordinator oldCoordinator = new MutationCoordinator();
        MutationCoordinator currentCoordinator = new MutationCoordinator();
        ImageJAIPlugin.PluginSession oldSession = session(oldCoordinator);
        ImageJAIPlugin.PluginSession currentSession = session(currentCoordinator);
        ImageJAIPlugin.activeSession = currentSession;

        oldSession.close();

        assertSame(currentSession, ImageJAIPlugin.activeSession);
        assertFalse(oldCoordinator.isAccepting());
        assertTrue(currentCoordinator.isAccepting());

        currentSession.close();
        assertNull(ImageJAIPlugin.activeSession);
        assertFalse(currentCoordinator.isAccepting());
    }

    @Test
    public void repeatedCloseIsSafe() {
        MutationCoordinator coordinator = new MutationCoordinator();
        ImageJAIPlugin.PluginSession session = session(coordinator);
        ImageJAIPlugin.activeSession = session;

        session.close();
        session.close();

        assertNull(ImageJAIPlugin.activeSession);
        assertFalse(coordinator.isAccepting());
    }

    /**
     * A window that adopted a boot-started server does not own it. Closing the
     * panel while a harness is mid-session must not stop the socket or tear
     * down the mutation coordinator the running server is using.
     */
    @Test
    public void closingAnAdoptedSessionLeavesTheBootBridgeAlone() {
        MutationCoordinator coordinator = new MutationCoordinator();
        ImageJAIPlugin.PluginSession adopted = new ImageJAIPlugin.PluginSession(
                null, null, null, null, null, coordinator, null, null, false);
        ImageJAIPlugin.activeSession = adopted;

        adopted.close();

        assertTrue("a boot-started coordinator outlives the window",
                coordinator.isAccepting());
        assertNull(ImageJAIPlugin.activeSession);
    }

    @Test
    public void bootBridgeStaysShutWhenTheStartupPolicyIsNotArmed() {
        // No -Dimagejai.testAutomation.enabled, so nothing may start: the
        // property is the only key that arms the boot path at all.
        assertFalse(ImageJAIPlugin.startBridgeAtBoot());
    }

    private static ImageJAIPlugin.PluginSession session(
            MutationCoordinator coordinator) {
        return new ImageJAIPlugin.PluginSession(null, null, null, null,
                null, coordinator, null, null, true);
    }
}
