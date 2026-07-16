package imagejai.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PipelineBuilderTest {

    @Test
    public void resumeStartsAtFirstIncompleteStep() {
        final List<String> executed = new ArrayList<String>();
        CommandEngine engine = new CommandEngine() {
            @Override public ExecutionResult executeMacro(String code) {
                executed.add(code);
                return ExecutionResult.success("ok", null,
                        Collections.<String>emptyList(), 1L);
            }
        };
        PipelineBuilder builder = new PipelineBuilder(engine);
        PipelineBuilder.PipelineStep first = step(1, "first", "first();", "success");
        PipelineBuilder.PipelineStep second = step(2, "second", "second();", "failed");
        PipelineBuilder.PipelineStep third = step(3, "third", "third();", "skipped");
        PipelineBuilder.Pipeline pipeline = new PipelineBuilder.Pipeline(
                "resume", Arrays.asList(first, second, third));
        pipeline.status = "failed";

        builder.resumePipeline(pipeline, null);

        assertEquals(Arrays.asList("second();", "third();"), executed);
        assertEquals("success", first.status);
        assertEquals("success", second.status);
        assertEquals("success", third.status);
        assertEquals("completed", pipeline.status);
    }

    @Test
    public void resumeOfCompletedPipelineDoesNotRerunFirstStep() {
        final int[] calls = {0};
        CommandEngine engine = new CommandEngine() {
            @Override public ExecutionResult executeMacro(String code) {
                calls[0]++;
                return ExecutionResult.success("ok", null,
                        Collections.<String>emptyList(), 1L);
            }
        };
        PipelineBuilder builder = new PipelineBuilder(engine);
        PipelineBuilder.Pipeline pipeline = new PipelineBuilder.Pipeline(
                "done", Arrays.asList(
                        step(1, "one", "one();", "success"),
                        step(2, "two", "two();", "success")));
        pipeline.status = "completed";

        builder.resumePipeline(pipeline, null);

        assertEquals(0, calls[0]);
        assertEquals("completed", pipeline.status);
        assertEquals(2, pipeline.currentStepIndex);
    }

    private static PipelineBuilder.PipelineStep step(
            int index, String description, String code, String status) {
        PipelineBuilder.PipelineStep step =
                new PipelineBuilder.PipelineStep(index, description, code);
        step.status = status;
        return step;
    }
}
