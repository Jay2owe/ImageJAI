package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration-style tests for the step 04 wiring in
 * {@link TCPCommandServer}:
 * <ul>
 *   <li>{@code fuzzy_match} capability surfaces in the hello enabled[] array.</li>
 *   <li>{@code list_commands} handler returns the registry snapshot.</li>
 *   <li>{@code include_classes=true} returns {@code [{name, class}, ...]}.</li>
 * </ul>
 * The handler is exercised via reflection since {@code handleListCommands} is
 * package-private — no socket, no live Fiji.
 */
public class TCPCommandServerFuzzyTest {

    private static TCPCommandServer newServer() {
        return new TCPCommandServer(0, null, null, null, null);
    }

    private static JsonObject parse(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }

    @Before
    public void installRegistry() {
        Map<String, String> cmds = new HashMap<String, String>();
        cmds.put("Gaussian Blur...",     "ij.plugin.filter.GaussianBlur");
        cmds.put("Median...",            "ij.plugin.filter.RankFilters");
        cmds.put("Analyze Particles...", "ij.plugin.filter.ParticleAnalyzer");
        MenuCommandRegistry.setForTesting(cmds);
    }

    @After
    public void clearRegistry() {
        MenuCommandRegistry.setForTesting(new HashMap<String, String>());
    }

    /** A hello with fuzzy_match on (the default) surfaces fuzzy_match in enabled[]. */
    @Test
    public void helloEnablesFuzzyMatchByDefault() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{}}");
        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("fuzzy_match must be on by default",
                enabledContains(enabled, "fuzzy_match"));
    }

    /** Explicitly opting out drops fuzzy_match from enabled[]. */
    @Test
    public void helloCanOptOutOfFuzzyMatch() {
        TCPCommandServer server = newServer();
        JsonObject req = parse(
                "{\"command\":\"hello\",\"agent\":\"tester\","
              + "\"capabilities\":{\"fuzzy_match\":false}}");
        JsonObject resp = server.handleHello(req, null);

        assertTrue(resp.get("ok").getAsBoolean());
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertFalse("fuzzy_match explicitly disabled",
                enabledContains(enabled, "fuzzy_match"));
    }

    /** list_commands default shape: count + commands[] of names. */
    @Test
    public void listCommandsReturnsNamesOnlyByDefault() throws Exception {
        TCPCommandServer server = newServer();
        JsonObject resp = invokeListCommands(server, parse("{\"command\":\"list_commands\"}"));
        assertTrue(resp.get("ok").getAsBoolean());

        JsonObject result = resp.getAsJsonObject("result");
        assertEquals(3, result.get("count").getAsInt());
        JsonArray cmds = result.getAsJsonArray("commands");
        assertEquals(3, cmds.size());
        // Default shape is a flat string array.
        assertTrue("entries are strings when include_classes is off/absent",
                cmds.get(0).isJsonPrimitive());
    }

    /** list_commands with include_classes=true returns {name, class} objects. */
    @Test
    public void listCommandsIncludeClasses() throws Exception {
        TCPCommandServer server = newServer();
        JsonObject resp = invokeListCommands(server,
                parse("{\"command\":\"list_commands\",\"include_classes\":true}"));

        assertTrue(resp.get("ok").getAsBoolean());
        JsonObject result = resp.getAsJsonObject("result");
        JsonArray cmds = result.getAsJsonArray("commands");
        assertTrue(cmds.size() > 0);

        JsonObject first = cmds.get(0).getAsJsonObject();
        assertTrue("entry has name",  first.has("name"));
        assertTrue("entry has class", first.has("class"));
        assertNotNull(first.get("name").getAsString());
    }

    private static JsonObject invokeListCommands(TCPCommandServer server, JsonObject req) throws Exception {
        Method m = TCPCommandServer.class
                .getDeclaredMethod("handleListCommands", JsonObject.class);
        m.setAccessible(true);
        return (JsonObject) m.invoke(server, req);
    }

    private static boolean enabledContains(JsonArray arr, String name) {
        for (int i = 0; i < arr.size(); i++) {
            if (name.equals(arr.get(i).getAsString())) return true;
        }
        return false;
    }
}
