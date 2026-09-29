package imagejai.engine.automation;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The startup gate. Every case here is a way the bridge must stay inert, plus
 * the single combination that arms it.
 */
public class AutomationPolicyTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Properties workspaceProperties(File workspace, String readyFileName) {
        Properties properties = new Properties();
        properties.setProperty(AutomationPolicy.PROP_ENABLED, "true");
        properties.setProperty(AutomationPolicy.PROP_WORKSPACE,
                workspace.getAbsolutePath());
        properties.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(workspace, readyFileName).getAbsolutePath());
        return properties;
    }

    @Test
    public void absentPropertyLeavesTheBridgeInert() {
        AutomationPolicy policy = AutomationPolicy.fromProperties(new Properties());
        assertFalse(policy.isEnabled());
        assertEquals(AutomationPolicy.REASON_ENABLED_ABSENT, policy.disabledReason());
        assertEquals("", policy.instanceId());
        assertEquals(-1, policy.requestedPort());
    }

    @Test
    public void nullPropertiesFailClosed() {
        AutomationPolicy policy = AutomationPolicy.fromProperties(null);
        assertFalse(policy.isEnabled());
    }

    @Test
    public void anythingOtherThanTrueIsRefused() throws Exception {
        for (String value : new String[] {"1", "yes", "TRUE ", "on", "false", ""}) {
            Properties properties = workspaceProperties(temp.newFolder(), "ready.json");
            properties.setProperty(AutomationPolicy.PROP_ENABLED, value);
            AutomationPolicy policy = AutomationPolicy.fromProperties(properties);
            if ("TRUE ".equals(value)) {
                // Trimmed and case-insensitive: this one is a genuine "true".
                assertTrue(value, policy.isEnabled());
            } else {
                assertFalse(value, policy.isEnabled());
            }
        }
    }

    @Test
    public void workspaceMustBeDeclaredAbsoluteAndReal() throws Exception {
        File workspace = temp.newFolder();

        Properties missing = workspaceProperties(workspace, "ready.json");
        missing.remove(AutomationPolicy.PROP_WORKSPACE);
        assertEquals(AutomationPolicy.REASON_WORKSPACE_ABSENT,
                AutomationPolicy.fromProperties(missing).disabledReason());

        Properties relative = workspaceProperties(workspace, "ready.json");
        relative.setProperty(AutomationPolicy.PROP_WORKSPACE, "relative/run");
        assertEquals(AutomationPolicy.REASON_WORKSPACE_NOT_ABSOLUTE,
                AutomationPolicy.fromProperties(relative).disabledReason());

        Properties unreadable = workspaceProperties(workspace, "ready.json");
        unreadable.setProperty(AutomationPolicy.PROP_WORKSPACE,
                new File(workspace, "does-not-exist").getAbsolutePath());
        assertEquals(AutomationPolicy.REASON_WORKSPACE_UNREADABLE,
                AutomationPolicy.fromProperties(unreadable).disabledReason());

        File file = temp.newFile("not-a-directory.txt");
        Properties notADirectory = workspaceProperties(workspace, "ready.json");
        notADirectory.setProperty(AutomationPolicy.PROP_WORKSPACE,
                file.getAbsolutePath());
        assertEquals(AutomationPolicy.REASON_WORKSPACE_NOT_A_DIRECTORY,
                AutomationPolicy.fromProperties(notADirectory).disabledReason());
    }

    @Test
    public void readyFileMustLiveInsideTheDeclaredWorkspace() throws Exception {
        File workspace = temp.newFolder("run");
        File elsewhere = temp.newFolder("elsewhere");

        Properties outside = workspaceProperties(workspace, "ready.json");
        outside.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(elsewhere, "ready.json").getAbsolutePath());
        assertEquals(AutomationPolicy.REASON_READY_FILE_OUTSIDE_WORKSPACE,
                AutomationPolicy.fromProperties(outside).disabledReason());

        Properties relative = workspaceProperties(workspace, "ready.json");
        relative.setProperty(AutomationPolicy.PROP_READY_FILE, "ready.json");
        assertEquals(AutomationPolicy.REASON_READY_FILE_NOT_ABSOLUTE,
                AutomationPolicy.fromProperties(relative).disabledReason());

        Properties missingParent = workspaceProperties(workspace, "ready.json");
        missingParent.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(new File(workspace, "nope"), "ready.json").getAbsolutePath());
        assertEquals(AutomationPolicy.REASON_READY_FILE_PARENT_MISSING,
                AutomationPolicy.fromProperties(missingParent).disabledReason());

        Properties absent = workspaceProperties(workspace, "ready.json");
        absent.remove(AutomationPolicy.PROP_READY_FILE);
        assertEquals(AutomationPolicy.REASON_READY_FILE_ABSENT,
                AutomationPolicy.fromProperties(absent).disabledReason());
    }

    @Test
    public void traversalOutOfTheWorkspaceIsRefused() throws Exception {
        File workspace = temp.newFolder("run");
        Properties properties = workspaceProperties(workspace, "ready.json");
        properties.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(workspace, "..").getAbsolutePath()
                        + File.separator + "escaped.json");
        assertEquals(AutomationPolicy.REASON_READY_FILE_OUTSIDE_WORKSPACE,
                AutomationPolicy.fromProperties(properties).disabledReason());
    }

    @Test
    public void portOverrideIsValidatedAndOptional() throws Exception {
        File workspace = temp.newFolder();

        AutomationPolicy noPort = AutomationPolicy.fromProperties(
                workspaceProperties(workspace, "ready.json"));
        assertEquals(-1, noPort.requestedPort());

        Properties ephemeral = workspaceProperties(workspace, "ready.json");
        ephemeral.setProperty(AutomationPolicy.PROP_PORT, "0");
        assertEquals(0, AutomationPolicy.fromProperties(ephemeral).requestedPort());

        for (String bad : new String[] {"-1", "70000", "not-a-port"}) {
            Properties invalid = workspaceProperties(workspace, "ready.json");
            invalid.setProperty(AutomationPolicy.PROP_PORT, bad);
            AutomationPolicy policy = AutomationPolicy.fromProperties(invalid);
            assertFalse(bad, policy.isEnabled());
            assertEquals(bad, AutomationPolicy.REASON_PORT_INVALID,
                    policy.disabledReason());
        }
    }

    @Test
    public void bothGatesTurnedArmsTheBridgeWithStableIdentity() throws Exception {
        File workspace = temp.newFolder();
        AutomationPolicy policy = AutomationPolicy.fromProperties(
                workspaceProperties(workspace, "ready.json"));

        assertTrue(policy.isEnabled());
        assertEquals(AutomationPolicy.REASON_ENABLED, policy.disabledReason());
        assertNotNull(policy.workspace());
        assertNotNull(policy.readyFile());
        assertTrue(policy.instanceId().length() >= 32);
        assertTrue(policy.workspaceId().startsWith("sha256:"));
        assertTrue(policy.startedAtEpochMs() > 0L);

        // Two policies over the same workspace agree on the workspace digest so
        // the harness can verify it provisioned this instance, and disagree on
        // instance id so a stale ready file cannot impersonate a new run.
        AutomationPolicy second = AutomationPolicy.fromProperties(
                workspaceProperties(workspace, "ready.json"));
        assertEquals(policy.workspaceId(), second.workspaceId());
        assertFalse(policy.instanceId().equals(second.instanceId()));
    }

    @Test
    public void containmentAcceptsOnlyPathsUnderTheWorkspace() throws Exception {
        File workspace = temp.newFolder("run");
        File elsewhere = temp.newFolder("elsewhere");
        AutomationPolicy policy = AutomationPolicy.fromProperties(
                workspaceProperties(workspace, "ready.json"));

        Path inside = policy.workspace().resolve("evidence").resolve("shot.png");
        assertTrue(policy.contains(inside));
        assertTrue(policy.contains(policy.readyFile()));
        assertFalse(policy.contains(elsewhere.toPath().resolve("shot.png")));
        assertFalse(policy.contains(policy.workspace().resolve("..").resolve("x")));
        assertFalse(policy.contains(null));
        assertFalse(AutomationPolicy.disabled("x").contains(inside));
    }

    @Test
    public void refusalReasonsNeverEchoAHostPath() throws Exception {
        File workspace = temp.newFolder("secret-run-directory");
        Properties properties = workspaceProperties(workspace, "ready.json");
        properties.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(temp.newFolder("other"), "ready.json").getAbsolutePath());
        AutomationPolicy policy = AutomationPolicy.fromProperties(properties);
        assertFalse(policy.isEnabled());
        assertFalse(policy.disabledReason().contains(File.separator));
        assertFalse(policy.disabledReason().contains("secret-run-directory"));
    }

    @Test
    public void theGatedCommandSurfaceIsFixedAndImmutable() {
        assertEquals(9, AutomationPolicy.COMMANDS.size());
        assertTrue(AutomationPolicy.COMMANDS.contains("get_ui_tree"));
        assertTrue(AutomationPolicy.COMMANDS.contains("perform_ui_action"));
        assertTrue(AutomationPolicy.COMMANDS.contains("capture_ui"));
        try {
            AutomationPolicy.COMMANDS.add("execute_macro");
            org.junit.Assert.fail("the gated command list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // The advertised surface is a constant, not caller-extensible.
        }
    }
}
