package imagejai.engine.automation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Readiness must be atomic, verifiable, and free of credentials. */
public class AutomationReadyFileTest {

    private static final String TOKEN = "loopback-secret-token-value";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private AutomationPolicy armedPolicy(File workspace) {
        Properties properties = new Properties();
        properties.setProperty(AutomationPolicy.PROP_ENABLED, "true");
        properties.setProperty(AutomationPolicy.PROP_WORKSPACE,
                workspace.getAbsolutePath());
        properties.setProperty(AutomationPolicy.PROP_READY_FILE,
                new File(workspace, "imagejai-ready.json").getAbsolutePath());
        AutomationPolicy policy = AutomationPolicy.fromProperties(properties);
        assertTrue(policy.isEnabled());
        return policy;
    }

    @Test
    public void readyDocumentCarriesOwnershipFactsAndNoSecret() throws Exception {
        AutomationPolicy policy = armedPolicy(temp.newFolder());
        JsonObject ready = AutomationReadyFile.describe(policy, 53211, "127.0.0.1",
                "1.9.0", "0.3.0");

        assertEquals(1, ready.get("schema_version").getAsInt());
        assertEquals(AutomationPolicy.PROTOCOL_VERSION,
                ready.get("protocol_version").getAsString());
        assertEquals(policy.instanceId(), ready.get("instance_id").getAsString());
        assertEquals(53211, ready.get("port").getAsInt());
        assertEquals("127.0.0.1", ready.get("host").getAsString());
        assertEquals("1.9.0", ready.get("server_version").getAsString());
        assertEquals("0.3.0", ready.get("plugin_version").getAsString());
        assertEquals(policy.workspaceId(), ready.get("workspace_id").getAsString());
        assertTrue(ready.get("test_automation").getAsBoolean());
        assertTrue(ready.get("pid").getAsLong() > 0L);
        assertTrue(ready.get("ready_at_epoch_ms").getAsLong() > 0L);

        String encoded = ready.toString();
        assertFalse(encoded.contains(TOKEN));
        assertFalse(encoded.contains("token"));
        // The workspace path itself stays inside the JVM; only its digest ships.
        assertFalse(encoded.contains(policy.workspace().toString().replace("\\", "\\\\")));
    }

    @Test
    public void writeIsAtomicAndDeleteRetiresReadiness() throws Exception {
        File workspace = temp.newFolder();
        AutomationPolicy policy = armedPolicy(workspace);
        Path target = policy.readyFile();

        assertTrue(AutomationReadyFile.write(policy, 7746, "127.0.0.1", "1.9.0", "0.3.0"));
        assertTrue(Files.isRegularFile(target));

        JsonObject parsed = JsonParser.parseString(
                new String(Files.readAllBytes(target), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(7746, parsed.get("port").getAsInt());

        // No temporary artefact is left behind for a reader to trip over.
        File[] leftovers = workspace.listFiles();
        assertEquals(1, leftovers == null ? 0 : leftovers.length);

        // A second write replaces the document in place, keeping one path valid.
        assertTrue(AutomationReadyFile.write(policy, 61000, "127.0.0.1", "1.9.0", "0.3.0"));
        parsed = JsonParser.parseString(
                new String(Files.readAllBytes(target), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(61000, parsed.get("port").getAsInt());

        assertTrue(AutomationReadyFile.delete(policy));
        assertFalse(Files.exists(target));
        assertFalse("deleting twice is not an error", AutomationReadyFile.delete(policy));
    }

    @Test
    public void aDisabledPolicyNeverWritesOrDeletesAnything() throws Exception {
        AutomationPolicy disabled = AutomationPolicy.disabled(
                AutomationPolicy.REASON_ENABLED_ABSENT);
        assertFalse(AutomationReadyFile.write(disabled, 7746, "127.0.0.1", "1.9.0", "0.3.0"));
        assertFalse(AutomationReadyFile.delete(disabled));
        assertFalse(AutomationReadyFile.write(null, 7746, "127.0.0.1", "1.9.0", "0.3.0"));
    }
}
