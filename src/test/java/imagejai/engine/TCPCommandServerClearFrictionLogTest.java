package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * safe_mode_v2 stage 08 — verify the {@code clear_friction_log} TCP
 * command is no longer agent-callable by default, and that the
 * operator-only escape hatch
 * {@code -Dimagejai.allow.clear_friction_log=true} re-enables it.
 *
 * <p>Real-world AI incidents say: never let the auditee edit the audit
 * log. This suite is the regression guard around that property.
 */
public class TCPCommandServerClearFrictionLogTest {

    private static final String FLAG = "imagejai.allow.clear_friction_log";

    private TCPCommandServer server;
    private String savedFlag;

    @Before
    public void setUp() {
        server = new TCPCommandServer(0, null, null, null, null);
        savedFlag = System.getProperty(FLAG);
        System.clearProperty(FLAG);
    }

    @After
    public void tearDown() {
        if (savedFlag == null) {
            System.clearProperty(FLAG);
        } else {
            System.setProperty(FLAG, savedFlag);
        }
    }

    /**
     * Default JVM (no {@code -D} flag) → dispatch returns the
     * removal-error envelope. The exact wording is asserted so a future
     * accidental rename of the message is caught.
     */
    @Test
    public void commandIsRejectedByDefault() {
        assertFalse("flag must be off in this test", TCPCommandServer.clearFrictionLogAllowed());

        JsonObject reply = server.dispatch(parse("{\"command\": \"clear_friction_log\"}"),
                new TCPCommandServer.AgentCaps());
        assertNotNull(reply);
        assertFalse("ok envelope should be false on rejection",
                reply.get("ok").getAsBoolean());
        String error = reply.get("error").getAsString();
        assertTrue("error should explain the removal: " + error,
                error.contains("clear_friction_log is no longer agent-callable"));
        assertTrue("error should name the escape-hatch flag: " + error,
                error.contains("imagejai.allow.clear_friction_log=true"));
    }

    /**
     * With the operator escape hatch on, the command works as it did
     * before stage 08 — clears the in-memory ring and reports the
     * pre-clear size in the {@code cleared} field.
     */
    @Test
    public void commandWorksWithOperatorFlag() {
        System.setProperty(FLAG, "true");
        try {
            assertTrue("flag must be on for this test",
                    TCPCommandServer.clearFrictionLogAllowed());

            JsonObject reply = server.dispatch(parse("{\"command\": \"clear_friction_log\"}"),
                    new TCPCommandServer.AgentCaps());
            assertNotNull(reply);
            assertTrue("ok envelope should be true when flag is on",
                    reply.get("ok").getAsBoolean());
            JsonObject result = reply.getAsJsonObject("result");
            assertNotNull("result body present", result);
            // No friction has been recorded in this test, so the count is 0.
            assertEquals(0, result.get("cleared").getAsInt());
        } finally {
            System.clearProperty(FLAG);
        }
    }

    /**
     * Property values other than the literal {@code true} (case-insensitive)
     * are treated as off — defends against an injected agent setting the
     * flag to {@code 1} or {@code yes} via a script.
     */
    @Test
    public void onlyLiteralTrueEnablesTheCommand() {
        for (String v : new String[]{"1", "yes", "TRUE-ish", " true", ""}) {
            System.setProperty(FLAG, v);
            assertFalse("value '" + v + "' should not enable the command",
                    TCPCommandServer.clearFrictionLogAllowed());
        }
        System.setProperty(FLAG, "true");
        assertTrue(TCPCommandServer.clearFrictionLogAllowed());
        System.setProperty(FLAG, "TRUE");
        assertTrue("'TRUE' is accepted (case-insensitive)",
                TCPCommandServer.clearFrictionLogAllowed());
    }

    private static JsonObject parse(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }
}
