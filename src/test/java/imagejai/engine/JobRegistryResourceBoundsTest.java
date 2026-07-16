package imagejai.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.function.DoubleConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JobRegistryResourceBoundsTest {
    @Test
    public void retainedExecutionResultTextAndImagesAreBoundedAndDisclosed() {
        String large = repeat('x', JobRegistry.MAX_RESULT_TEXT_BYTES + 17);
        List<String> images = new ArrayList<String>();
        for (int i = 0; i < JobRegistry.MAX_RESULT_IMAGES + 20; i++) {
            images.add("image-" + i);
        }

        JsonObject json = JobRegistry.executionResultToJson(
                ExecutionResult.success(large, large, images, 1L));

        assertEquals(JobRegistry.MAX_RESULT_TEXT_BYTES,
                json.get("output").getAsString().length());
        assertTrue(json.get("output_truncated").getAsBoolean());
        assertTrue(json.get("resultsTable_truncated").getAsBoolean());
        assertEquals(JobRegistry.MAX_RESULT_IMAGES,
                json.getAsJsonArray("newImages").size());
        assertTrue(json.get("newImages_truncated").getAsBoolean());
        assertEquals(images.size(), json.get("newImages_total").getAsInt());
    }

    @Test
    public void utf8TruncationRetainsOnlyWholeCodePoints() {
        String prefix = repeat('a', JobRegistry.MAX_RESULT_TEXT_BYTES - 1);
        JsonObject json = JobRegistry.executionResultToJson(
                ExecutionResult.success(prefix + "😀z", "", null, 1L));

        String retained = json.get("output").getAsString();
        assertEquals(prefix, retained);
        assertTrue(json.get("output_truncated").getAsBoolean());
        assertEquals(JobRegistry.MAX_RESULT_TEXT_BYTES - 1,
                json.get("output_returned_bytes").getAsInt());
    }

    @Test
    public void terminalJobReleasesCoordinatorRequestClosuresAndRawResult()
            throws Exception {
        final String large = repeat('x', JobRegistry.MAX_RESULT_TEXT_BYTES + 99);
        CommandEngine engine = new CommandEngine() {
            @Override public ExecutionResult executeMacroOnCurrentThread(
                    String code, DoubleConsumer progress) {
                return ExecutionResult.success(large, large,
                        Collections.singletonList("result"), 1L);
            }
        };
        JobRegistry registry = new JobRegistry(engine, new MutationCoordinator(1,
                new Object()));
        try {
            JobRegistry.Job job = registry.submit(
                    repeat('m', JobRegistry.MAX_RETAINED_CODE_CHARS + 100),
                    "owner", 0L, false, false, false, null);
            job.handle.awaitCompletion();

            assertTrue(job.handle.retainedPayloadReleasedForTest());
            assertTrue(job.codeTruncated);
            assertEquals(JobRegistry.MAX_RETAINED_CODE_CHARS, job.code.length());
            assertEquals(JobRegistry.MAX_RESULT_TEXT_BYTES,
                    job.result.get("output").getAsString().length());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    public void upstreamResultsTruncationMetadataSurvivesJobProjection() {
        StateInspector.BoundedCsv csv = new StateInspector.BoundedCsv(
                "Value\nkept\n", 9_000_000L, 11, 5000, 1);
        JsonObject json = JobRegistry.executionResultToJson(
                ExecutionResult.successWithBoundedResults(
                        "", csv, Collections.<String>emptyList(), 1L));

        assertTrue(json.get("resultsTable_truncated").getAsBoolean());
        assertEquals(9_000_000L,
                json.get("resultsTable_original_bytes").getAsLong());
        assertEquals(5000, json.get("resultsTable_total_rows").getAsInt());
        assertEquals(1, json.get("resultsTable_returned_rows").getAsInt());
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
