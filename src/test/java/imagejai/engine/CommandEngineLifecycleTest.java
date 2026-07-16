package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.measure.ResultsTable;
import imagejai.engine.safeMode.SourceImageTagger;
import org.junit.After;
import org.junit.Test;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.SimpleBindings;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class CommandEngineLifecycleTest {

    @After
    public void cleanResults() {
        ResultsTable table = ResultsTable.getResultsTable();
        if (table != null) table.reset();
    }

    @Test
    public void everyCoreSurfaceUsesSharedCoordinatorExactlyOnce() throws Exception {
        SpyCoordinator coordinator = new SpyCoordinator();
        CommandEngine engine = new CommandEngine() {
            @Override public ExecutionResult executeMacroOnCurrentThread(
                    String code, DoubleConsumer progress) {
                return ExecutionResult.success(code, null,
                        Collections.<String>emptyList(), 1L);
            }
        };
        PipelineBuilder pipelines = new PipelineBuilder(engine);
        TCPCommandServer server = new TCPCommandServer(
                0, engine, null, pipelines, null, coordinator);
        server.scriptEngineResolverForTest = ignored -> new RecordingScriptEngine();
        TCPCommandServer.AgentCaps caps = caps("lifecycle-owner");
        try {
            assertSame(coordinator, server.getMutationCoordinator());

            JsonObject macro = server.dispatch(json(
                    "{\"command\":\"execute_macro\",\"code\":\"return 'ok';\"}"), caps);
            assertTrue(macro.toString(), macro.get("ok").getAsBoolean());

            JsonObject script = server.dispatch(json(
                    "{\"command\":\"run_script\",\"language\":\"test\","
                            + "\"code\":\"add-result\"}"), caps);
            assertTrue(script.toString(), script.get("ok").getAsBoolean());

            JsonObject pipeline = server.dispatch(json(
                    "{\"command\":\"run_pipeline\",\"steps\":["
                            + "{\"description\":\"one\",\"code\":\"one();\"},"
                            + "{\"description\":\"two\",\"code\":\"two();\"}]}"), caps);
            assertTrue(pipeline.toString(), pipeline.get("ok").getAsBoolean());

            JsonObject async = server.dispatch(json(
                    "{\"command\":\"execute_macro_async\",\"code\":\"async();\"}"), caps);
            String jobId = async.getAsJsonObject("result").get("job_id").getAsString();
            server.getJobRegistry().get(caps.sessionId, jobId).handle.awaitCompletion();

            caps.undo = true;
            JsonObject rewind = server.dispatch(json(
                    "{\"command\":\"rewind\",\"image_title\":\"missing\"}"), caps);
            assertTrue(rewind.toString(), rewind.get("ok").getAsBoolean());

            assertEquals(5, coordinator.sources.size());
            assertEquals(2, count(coordinator.sources, "macro"));
            assertEquals(1, count(coordinator.sources, "script"));
            assertEquals(1, count(coordinator.sources, "pipeline"));
            assertEquals(1, count(coordinator.sources, "rewind"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void scriptProductionPathRunsSourceTaggerInsideLifecycle() {
        ResultsTable table = ResultsTable.getResultsTable();
        table.reset();
        SpyCoordinator coordinator = new SpyCoordinator();
        CommandEngine engine = new CommandEngine();
        TCPCommandServer server = new TCPCommandServer(
                0, engine, null, new PipelineBuilder(engine), null, coordinator);
        server.scriptEngineResolverForTest = ignored -> new RecordingScriptEngine();
        TCPCommandServer.AgentCaps caps = caps("source-owner");
        caps.safeMode = true;
        caps.safeModeOptions.autoSourceImageColumn = true;
        try {
            JsonObject response = server.dispatch(json(
                    "{\"command\":\"run_script\",\"language\":\"test\","
                            + "\"code\":\"add-result\"}"), caps);
            assertTrue(response.toString(), response.get("ok").getAsBoolean());
            assertEquals(SourceImageTagger.UNKNOWN_LABEL,
                    table.getStringValue(SourceImageTagger.COLUMN_NAME, 0));
            assertEquals(1, coordinator.sources.size());
        } finally {
            server.stop();
        }
    }

    @Test
    public void commandEngineCompletesOnItsInjectedCoordinatorAndRejectsAfterShutdown() {
        MutationCoordinator coordinator = new MutationCoordinator();
        CommandEngine engine = new CommandEngine() {
            @Override public ExecutionResult executeMacroOnCurrentThread(
                    String code, DoubleConsumer progress) {
                return ExecutionResult.success(code, null,
                        Collections.<String>emptyList(), 1L);
            }
        };
        engine.setMutationCoordinator(coordinator);
        try {
            ExecutionResult completed = engine.executeMacro("return 'complete';");
            assertTrue(completed.getError(), completed.isSuccess());
            assertEquals(0, coordinator.activeCount());

            coordinator.shutdown();
            ExecutionResult rejected = engine.executeMacro("return 'late';");
            assertFalse(rejected.isSuccess());
            assertTrue(rejected.getError(),
                    rejected.getError().toLowerCase().contains("stopped")
                            || rejected.getError().toLowerCase().contains("shutdown"));
        } finally {
            coordinator.shutdown();
        }
    }

    private static TCPCommandServer.AgentCaps caps(String owner) {
        TCPCommandServer.AgentCaps caps = new TCPCommandServer.AgentCaps();
        caps.sessionId = owner;
        caps.fuzzyMatch = false;
        caps.histogram = false;
        caps.graphDelta = false;
        caps.warnings = false;
        caps.pulse = false;
        return caps;
    }

    private static int count(List<String> values, String target) {
        int count = 0;
        for (String value : values) if (target.equals(value)) count++;
        return count;
    }

    private static JsonObject json(String value) {
        return new JsonParser().parse(value).getAsJsonObject();
    }

    private static final class SpyCoordinator extends MutationCoordinator {
        final List<String> sources = new CopyOnWriteArrayList<String>();

        @Override public <T> Handle<T> submit(Request<T> request) {
            sources.add(request.sourceKind());
            return super.submit(request);
        }

        @Override public <T> Handle<T> submit(
                Request<T> request, Consumer<Handle<T>> onAdmitted) {
            sources.add(request.sourceKind());
            return super.submit(request, onAdmitted);
        }
    }

    private static final class RecordingScriptEngine extends AbstractScriptEngine {
        @Override public Object eval(String script, ScriptContext context) {
            if (script.contains("add-result")) {
                ResultsTable table = ResultsTable.getResultsTable();
                table.incrementCounter();
                table.setValue("Value", table.getCounter() - 1, 1.0);
            }
            return "script-ok";
        }

        @Override public Object eval(Reader reader, ScriptContext context) {
            return "script-ok";
        }

        @Override public Bindings createBindings() { return new SimpleBindings(); }

        @Override public ScriptEngineFactory getFactory() { return null; }
    }
}
