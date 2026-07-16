package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Stage 01 of {@code docs/safe_mode_v2/} — verify that {@code batch},
 * {@code run}, and {@code intent} thread the originating socket's
 * {@link TCPCommandServer.AgentCaps} through to every nested dispatch.
 *
 * <p>Before this fix, nested calls fell back to {@link
 * TCPCommandServer#DEFAULT_CAPS} (every safe-mode flag off), letting any
 * agent defeat the safe-mode gate by wrapping a destructive macro inside a
 * {@code batch}. The witness recorder set on the server captures the caps
 * used for each {@code dispatchInternal} invocation; the assertions below
 * prove the inner caps are reference-identical to the outer caps.
 *
 * <p>Tests construct the server with null engines (no Fiji needed) and use
 * package-private test seams ({@link TCPCommandServer#capsWitnessForTest},
 * {@link TCPCommandServer#executeMacroForTest}) to keep the inner handlers
 * fast and headless.
 */
public class TCPCommandServerBatchCapsTest {

    private TCPCommandServer server;
    private List<TCPCommandServer.AgentCaps> witness;
    private Path tempStore;

    @Before
    public void setUp() throws IOException {
        server = new TCPCommandServer(0, null, null, null, null);
        witness = new ArrayList<TCPCommandServer.AgentCaps>();
        server.capsWitnessForTest = witness;
        // Redirect the intent router to a temp file so teach() does not
        // pollute the user's home directory.
        tempStore = Files.createTempFile("intent-caps-test-", ".json");
        Files.deleteIfExists(tempStore);
        server.intentRouter = new IntentRouter(tempStore);
    }

    @After
    public void tearDown() throws IOException {
        TCPCommandServer.executeMacroForTest = null;
        if (server != null) server.stop();
        if (tempStore != null) {
            Files.deleteIfExists(tempStore);
            Path tmp = tempStore.resolveSibling(
                    tempStore.getFileName().toString() + ".tmp");
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * A {@code batch} dispatched with caps {@code S} must invoke every
     * sub-command with the same {@code S} reference — proving the bypass
     * is closed.
     */
    @Test
    public void batchThreadsCallerCapsToEverySubCommand() {
        TCPCommandServer.AgentCaps outer = new TCPCommandServer.AgentCaps();
        outer.safeMode = true;
        outer.agent = "test-agent";

        JsonObject req = parse(
                "{\"command\":\"batch\",\"commands\":["
              + "{\"command\":\"ping\"},"
              + "{\"command\":\"ping\"}"
              + "]}");

        JsonObject resp = server.dispatch(req, outer);

        assertTrue("batch must succeed", resp.get("ok").getAsBoolean());
        // Witness order: outer batch dispatch + 2 inner ping dispatches.
        assertEquals("witness must record outer + 2 inner dispatches",
                3, witness.size());
        assertSame("outer batch sees the caller caps",
                outer, witness.get(0));
        assertSame("first sub-command inherits caller caps (was the bug)",
                outer, witness.get(1));
        assertSame("second sub-command inherits caller caps",
                outer, witness.get(2));
    }

    /**
     * Without the fix, the legacy single-arg {@code dispatch} substituted
     * {@link TCPCommandServer#DEFAULT_CAPS} for nested calls. Pin the
     * regression: a custom-cap caller must NEVER see {@code DEFAULT_CAPS}
     * inside a batch.
     */
    @Test
    public void batchNeverFallsBackToDefaultCaps() {
        TCPCommandServer.AgentCaps outer = new TCPCommandServer.AgentCaps();
        outer.safeMode = true;

        JsonObject req = parse(
                "{\"command\":\"batch\",\"commands\":["
              + "{\"command\":\"ping\"}]}");

        server.dispatch(req, outer);

        for (int i = 0; i < witness.size(); i++) {
            assertTrue("witness[" + i + "] must NOT be DEFAULT_CAPS",
                    witness.get(i) != TCPCommandServer.DEFAULT_CAPS);
            assertTrue("witness[" + i + "] must carry safeMode=true",
                    witness.get(i).safeMode);
        }
    }

    /**
     * The {@code run} chain handler ({@code |||}-delimited) must inherit
     * caps the same way batch does.
     */
    @Test
    public void runChainThreadsCallerCapsToEverySegment() {
        TCPCommandServer.AgentCaps outer = new TCPCommandServer.AgentCaps();
        outer.safeMode = true;

        JsonObject req = parse(
                "{\"command\":\"run\",\"chain\":\"ping ||| ping ||| ping\"}");

        JsonObject resp = server.dispatch(req, outer);

        assertTrue("run must succeed", resp.get("ok").getAsBoolean());
        // Outer run + 3 inner ping segments.
        assertEquals("witness must record outer + 3 chain segments",
                4, witness.size());
        for (int i = 0; i < witness.size(); i++) {
            assertSame("witness[" + i + "] must equal caller caps",
                    outer, witness.get(i));
        }
    }

    /**
     * The {@code intent} handler resolves a phrase to a macro and re-enters
     * dispatch — the new behaviour, replacing the old direct call to
     * {@code handleExecuteMacro(req, DEFAULT_CAPS)} that was the original
     * bypass.
     */
    @Test
    public void intentReentersDispatchWithCallerCaps() {
        // Intercept execute_macro so the test never hits the EDT path. The
        // hook records the caps it observed so the assertion below is a
        // direct equality check, not just a witness sweep.
        final TCPCommandServer.AgentCaps[] capsSeenByMacro =
                new TCPCommandServer.AgentCaps[1];
        TCPCommandServer.executeMacroForTest =
                new java.util.function.BiFunction<JsonObject,
                        TCPCommandServer.AgentCaps, JsonObject>() {
                    @Override
                    public JsonObject apply(JsonObject req,
                                            TCPCommandServer.AgentCaps caps) {
                        capsSeenByMacro[0] = caps;
                        JsonObject result = new JsonObject();
                        result.addProperty("success", true);
                        JsonObject ok = new JsonObject();
                        ok.addProperty("ok", true);
                        ok.add("result", result);
                        return ok;
                    }
                };

        // Register a phrase against the temp router.
        server.intentRouter.teach("safe mode test", "0;", "no-op for test");

        TCPCommandServer.AgentCaps outer = new TCPCommandServer.AgentCaps();
        outer.safeMode = true;
        outer.agent = "intent-caller";

        JsonObject req = parse(
                "{\"command\":\"intent\",\"phrase\":\"safe mode test\"}");

        JsonObject resp = server.dispatch(req, outer);

        assertNotNull("intent must produce a response", resp);
        assertSame("nested execute_macro must receive caller caps "
                        + "(was DEFAULT_CAPS before stage 01 fix)",
                outer, capsSeenByMacro[0]);
        // Witness has [intent, execute_macro]. Both must be the same caps.
        assertEquals(2, witness.size());
        assertSame(outer, witness.get(0));
        assertSame(outer, witness.get(1));
    }

    /**
     * A direct (non-nested) dispatch with explicit caps still records the
     * caller's reference — guards against the new overload silently
     * swapping in DEFAULT_CAPS.
     */
    @Test
    public void directDispatchWithCustomCapsRecordsThoseCaps() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;

        JsonObject req = parse("{\"command\":\"ping\"}");
        JsonObject resp = server.dispatch(req, caps);

        assertTrue(resp.get("ok").getAsBoolean());
        assertEquals(1, witness.size());
        assertSame(caps, witness.get(0));
    }

    /**
     * A caller with {@code safe_mode=false} must see {@code safe_mode=false}
     * propagate into every sub-command. Mirrors the {@code safe_mode=true}
     * path so the regression catches an inverted-default bug too.
     */
    @Test
    public void safeModeFalseAlsoPropagatesToSubCommands() {
        TCPCommandServer.AgentCaps outer = new TCPCommandServer.AgentCaps();
        outer.safeMode = false;
        outer.agent = "fast-path";

        JsonObject req = parse(
                "{\"command\":\"batch\",\"commands\":["
              + "{\"command\":\"ping\"},{\"command\":\"ping\"}]}");

        server.dispatch(req, outer);

        assertEquals(3, witness.size());
        for (int i = 0; i < witness.size(); i++) {
            assertSame(outer, witness.get(i));
            assertTrue("safeMode=false must survive nesting",
                    !witness.get(i).safeMode);
        }
    }

    /**
     * Sanity: invalid sub-commands inside a batch produce an error envelope
     * for that step but still record the caller's caps for the steps that
     * did dispatch — i.e. caps inheritance is not corrupted by a malformed
     * sibling element.
     */
    @Test
    public void batchSurvivesMalformedSubCommandWhilePreservingCaps() {
        TCPCommandServer.AgentCaps outer = new TCPCommandServer.AgentCaps();
        outer.safeMode = true;

        // Mix of a valid ping and a non-object element (handleBatch returns
        // an error envelope for non-object items without dispatching).
        JsonObject req = parse(
                "{\"command\":\"batch\",\"commands\":["
              + "{\"command\":\"ping\"},42]}");

        JsonObject resp = server.dispatch(req, outer);

        assertTrue(resp.get("ok").getAsBoolean());
        // Outer batch + 1 ping dispatch (the literal 42 is rejected
        // without ever being dispatched).
        assertEquals(2, witness.size());
        assertSame(outer, witness.get(0));
        assertSame(outer, witness.get(1));
        JsonObject batch = resp.getAsJsonObject("result");
        JsonArray results = batch.getAsJsonArray("results");
        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals(1, batch.get("firstFailureIndex").getAsInt());
        assertEquals(0, results.get(0).getAsJsonObject().get("index").getAsInt());
        assertTrue(results.get(0).getAsJsonObject().has("response"));
    }

    @Test
    public void batchReportsFailureIndexAndRetainsPriorResults() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.safeMode = true;
        JsonObject req = parse(
                "{\"command\":\"batch\",\"commands\":["
              + "{\"command\":\"ping\"},"
              + "{\"command\":\"not_a_real_command\"},"
              + "{\"command\":\"ping\"}]}");

        JsonObject response = server.dispatch(req, caps);
        JsonObject batch = response.getAsJsonObject("result");
        JsonArray results = batch.getAsJsonArray("results");

        assertEquals(1, batch.get("firstFailureIndex").getAsInt());
        assertEquals(3, batch.get("executed").getAsInt());
        assertEquals(3, results.size());
        assertTrue(results.get(0).getAsJsonObject()
                .getAsJsonObject("response").get("ok").getAsBoolean());
        assertEquals(2, results.get(2).getAsJsonObject().get("index").getAsInt());
    }

    @Test
    public void batchCanHaltAfterIndexedFailureWithoutDiscardingSuccess() {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        JsonObject req = parse(
                "{\"command\":\"batch\",\"halt_on_error\":true,\"commands\":["
              + "{\"command\":\"ping\"},"
              + "{\"command\":\"not_a_real_command\"},"
              + "{\"command\":\"ping\"}]}");

        JsonObject batch = server.dispatch(req, caps).getAsJsonObject("result");
        assertEquals(1, batch.get("firstFailureIndex").getAsInt());
        assertEquals(2, batch.get("executed").getAsInt());
        assertEquals(3, batch.get("total").getAsInt());
        assertTrue(batch.get("halted").getAsBoolean());
        assertEquals(2, batch.getAsJsonArray("results").size());
    }

    @Test
    public void throwingSubCommandRetainsPriorIndexedResults() {
        TCPCommandServer.executeMacroForTest = (request, caps) -> {
            throw new IllegalStateException("synthetic batch failure");
        };
        JsonObject req = parse(
                "{\"command\":\"batch\",\"halt_on_error\":true,\"commands\":["
              + "{\"command\":\"ping\"},"
              + "{\"command\":\"execute_macro\",\"code\":\"x\"},"
              + "{\"command\":\"ping\"}]}");

        JsonObject batch = server.dispatch(req, new TCPCommandServer.AgentCaps())
                .getAsJsonObject("result");
        JsonArray results = batch.getAsJsonArray("results");

        assertEquals(1, batch.get("firstFailureIndex").getAsInt());
        assertEquals(2, batch.get("executed").getAsInt());
        assertTrue(results.get(0).getAsJsonObject()
                .getAsJsonObject("response").get("ok").getAsBoolean());
        JsonObject failure = results.get(1).getAsJsonObject();
        assertEquals(1, failure.get("index").getAsInt());
        assertTrue(failure.getAsJsonObject("response").toString()
                .contains("synthetic batch failure"));
    }

    @Test
    public void nestedSixtyFourBySixtyFourBatchDispatchesAtMostSixtyFourLeaves() {
        CountingServer counting = new CountingServer();
        try {
            JsonObject outer = nestedBatchGrid("ping");

            JsonObject result = counting.dispatch(
                    outer, new TCPCommandServer.AgentCaps()).getAsJsonObject("result");

            assertTrue(counting.leafDispatches.get()
                    <= TCPCommandServer.MAX_COMPOUND_WORK);
            assertEquals(TCPCommandServer.MAX_COMPOUND_WORK,
                    result.get("work_executed").getAsInt());
            assertTrue(result.get("work_budget_exhausted").getAsBoolean());
            assertEquals(1, result.get("executed").getAsInt());
            JsonObject indexed = result.getAsJsonArray("results")
                    .get(0).getAsJsonObject();
            assertEquals(0, indexed.get("index").getAsInt());
            JsonObject nested = indexed.getAsJsonObject("response")
                    .getAsJsonObject("result");
            assertEquals(63, nested.get("executed").getAsInt());
            assertEquals(63, nested.getAsJsonArray("results").size());
            assertEquals(63, nested.get("budget_exhausted_at_index").getAsInt());
        } finally {
            counting.stop();
        }
    }

    @Test
    public void throwingNestedLeavesAreChargedBeforeDispatch() {
        AtomicInteger attempts = new AtomicInteger();
        TCPCommandServer.executeMacroForTest = (request, caps) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("synthetic leaf failure");
        };

        JsonObject result = server.dispatch(
                nestedBatchGrid("execute_macro"),
                new TCPCommandServer.AgentCaps()).getAsJsonObject("result");

        assertTrue(attempts.get() <= TCPCommandServer.MAX_COMPOUND_WORK);
        assertEquals(63, attempts.get());
        assertEquals(TCPCommandServer.MAX_COMPOUND_WORK,
                result.get("work_executed").getAsInt());
        assertTrue(result.get("work_budget_exhausted").getAsBoolean());
    }

    private static JsonObject nestedBatchGrid(String leafCommand) {
        JsonObject outer = new JsonObject();
        outer.addProperty("command", "batch");
        JsonArray outerCommands = new JsonArray();
        for (int i = 0; i < TCPCommandServer.MAX_BATCH_COMMANDS; i++) {
            JsonObject nested = new JsonObject();
            nested.addProperty("command", "batch");
            JsonArray leaves = new JsonArray();
            for (int j = 0; j < TCPCommandServer.MAX_BATCH_COMMANDS; j++) {
                JsonObject leaf = new JsonObject();
                leaf.addProperty("command", leafCommand);
                if ("execute_macro".equals(leafCommand)) leaf.addProperty("code", "x");
                leaves.add(leaf);
            }
            nested.add("commands", leaves);
            outerCommands.add(nested);
        }
        outer.add("commands", outerCommands);
        return outer;
    }

    private static final class CountingServer extends TCPCommandServer {
        final AtomicInteger leafDispatches = new AtomicInteger();

        CountingServer() { super(0, null, null, null, null); }

        @Override JsonObject dispatch(JsonObject request, AgentCaps caps) {
            if (request != null && "ping".equals(
                    request.has("command") ? request.get("command").getAsString() : "")) {
                leafDispatches.incrementAndGet();
            }
            return super.dispatch(request, caps);
        }
    }

    private static JsonObject parse(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }
}
