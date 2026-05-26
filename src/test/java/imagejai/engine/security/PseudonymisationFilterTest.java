package imagejai.engine.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import imagejai.config.PrivacyPosture;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PseudonymisationFilterTest {
    @Test
    public void standardModeLeavesPayloadUntouched() {
        PathTokenMap map = new PathTokenMap(bytes(1));
        PseudonymisationFilter filter = filter(map);
        JsonObject response = okObject();
        response.getAsJsonObject("result").addProperty("path", "MOAB2/subject_017.lif");

        filter.apply(response, "get_state", PrivacyPosture.STANDARD, "s");

        assertEquals("MOAB2/subject_017.lif",
                response.getAsJsonObject("result").get("path").getAsString());
        assertFalse(response.has("_governance"));
    }

    @Test
    public void pathFieldsAndFilenamesAreTokenisedWithGovernance() {
        PathTokenMap map = new PathTokenMap(bytes(2));
        PseudonymisationFilter filter = filter(map);
        JsonObject response = okObject();
        JsonObject result = response.getAsJsonObject("result");
        result.addProperty("path", "C:\\study\\MOAB2_subject_017.lif");
        result.addProperty("title", "MOAB2_subject_017.lif");

        filter.apply(response, "get_state", PrivacyPosture.PSEUDONYMISED, "s");
        String serialised = response.toString();

        assertFalse(serialised.contains("MOAB2_subject_017"));
        assertTrue(serialised.contains("image-"));
        assertTrue(response.has("_governance"));
        assertTrue(governanceFields(response).contains("path"));
    }

    @Test
    public void omeXmlIsScrubbedInsideMetadataStrings() {
        PathTokenMap map = new PathTokenMap(bytes(3));
        PseudonymisationFilter filter = filter(map);
        JsonObject response = okObject();
        response.getAsJsonObject("result").addProperty("info",
                "[OME-XML: <OME><Experimenter>Jane</Experimenter>"
                        + "<Pixels SizeX=\"8\"/></OME>]");

        filter.apply(response, "get_metadata", PrivacyPosture.PSEUDONYMISED, "s");
        String out = response.toString();

        assertFalse(out.contains("Jane"));
        assertTrue(out.contains("Pixels"));
        assertTrue(governanceFields(response).contains("ome_xml"));
    }

    @Test
    public void resultsTableLabelsAndNonNumericSlicesAreTokenised() {
        PathTokenMap map = new PathTokenMap(bytes(4));
        PseudonymisationFilter filter = filter(map);
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("result",
                "Label,Slice,Area\n"
                        + "MOAB2_subject_017_cell_3,12,44\n"
                        + "Other,image.lif,55");

        filter.apply(response, "get_results_table", PrivacyPosture.PSEUDONYMISED, "s");
        String csv = response.get("result").getAsString();

        assertFalse(csv.contains("MOAB2_subject_017"));
        assertFalse(csv.contains("image.lif"));
        assertTrue(csv.contains(",12,44"));
        assertTrue(governanceFields(response).contains("results_table"));
    }

    @Test
    public void freeTextScrubReplacesRegisteredPathsInErrors() {
        PathTokenMap map = new PathTokenMap(bytes(5));
        String token = map.tokenForPathString("C:\\study\\subject_017.lif");
        PseudonymisationFilter filter = filter(map);
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("error", "Could not open C:\\study\\subject_017.lif");

        filter.apply(response, "execute_macro", PrivacyPosture.PSEUDONYMISED, "s");

        assertFalse(response.toString().contains("subject_017"));
        assertTrue(response.toString().contains(token));
    }

    @Test
    public void freeTextScrubUsesLongestFirst() {
        PathTokenMap map = new PathTokenMap(bytes(6));
        String longToken = map.tokenForSensitiveText("alpha beta", "label");
        String shortToken = map.tokenForSensitiveText("alpha", "label");
        PseudonymisationFilter filter = filter(map);

        String scrubbed = filter.freeTextScrubString("alpha beta alpha");

        assertEquals(longToken + " " + shortToken, scrubbed);
    }

    @Test
    public void governanceBlockIsPresentEvenWhenNothingChanged() {
        PseudonymisationFilter filter = filter(new PathTokenMap(bytes(7)));
        JsonObject response = okObject();
        response.getAsJsonObject("result").addProperty("status", "pong");

        filter.apply(response, "ping", PrivacyPosture.PSEUDONYMISED, "s");

        assertTrue(response.has("_governance"));
        assertEquals(0, response.getAsJsonObject("_governance")
                .getAsJsonArray("fields_pseudonymised").size());
    }

    @Test
    public void failClosedDropsOriginalPayloadInPseudonymisedMode() {
        PseudonymisationFilter filter = new PseudonymisationFilter(
                new PathTokenMap(bytes(8)), new OmeXmlScrubber(),
                new CaptureHandler(new BurnInDetector(), new VisualOverrideRegistry())) {
            @Override
            protected void beforeFiltering(JsonObject response, String command,
                                           PrivacyPosture posture) {
                throw new IllegalStateException("boom");
            }
        };
        JsonObject response = okObject();
        response.getAsJsonObject("result").addProperty("path", "C:\\secret\\subject_017.lif");

        filter.apply(response, "get_state", PrivacyPosture.PSEUDONYMISED, "s");

        assertFalse(response.get("ok").getAsBoolean());
        assertEquals("redaction_failed", response.get("error").getAsString());
        assertFalse(response.toString().contains("subject_017"));
        assertTrue(response.getAsJsonObject("_governance")
                .get("redaction_failed").getAsBoolean());
    }

    @Test
    public void reverseResolutionReturnsRealPathAndSeries() {
        PathTokenMap map = new PathTokenMap(bytes(9));
        String token = map.tokenForSeries(Paths.get("study", "subject_017.lif"), 3);

        PathTokenMap.ResolvedTarget target = map.resolve(token).get();

        assertEquals(Paths.get("study", "subject_017.lif"), target.realPath());
        assertEquals(3, target.series());
    }

    @Test
    public void fiftyKilobytePayloadFiltersUnderTenMillisecondsOnAverage() {
        PathTokenMap map = new PathTokenMap(bytes(18));
        PseudonymisationFilter filter = filter(map);
        map.tokenForPathString("C:\\study\\MOAB2_subject_017.lif");

        for (int i = 0; i < 10; i++) {
            filter.apply(realisticPayload(), "get_state",
                    PrivacyPosture.PSEUDONYMISED, "s");
        }

        int runs = 80;
        JsonObject[] payloads = new JsonObject[runs];
        for (int i = 0; i < runs; i++) {
            payloads[i] = realisticPayload();
        }
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            filter.apply(payloads[i], "get_state",
                    PrivacyPosture.PSEUDONYMISED, "s");
        }
        double averageMs = (System.nanoTime() - start) / 1_000_000.0 / runs;

        assertTrue("average filter time was " + averageMs + " ms", averageMs < 10.0);
    }

    private static PseudonymisationFilter filter(PathTokenMap map) {
        return new PseudonymisationFilter(map, new OmeXmlScrubber(),
                new CaptureHandler(new BurnInDetector(), new VisualOverrideRegistry()));
    }

    private static JsonObject okObject() {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("result", new JsonObject());
        return response;
    }

    private static JsonObject realisticPayload() {
        JsonObject response = okObject();
        JsonObject result = response.getAsJsonObject("result");
        result.addProperty("path", "C:\\study\\MOAB2_subject_017.lif");
        result.addProperty("info", "<OME><Experimenter>Jane</Experimenter>"
                + "<Pixels SizeX=\"512\" SizeY=\"512\"/></OME>");
        JsonArray rows = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("Label", "MOAB2_subject_017_cell_3");
        row.addProperty("Slice", "12");
        row.addProperty("Area", "44");
        rows.add(row);
        result.add("results", rows);
        StringBuilder log = new StringBuilder(50 * 1024);
        while (log.length() < 50 * 1024) {
            log.append("Processed C:\\study\\MOAB2_subject_017.lif with channel=2; ");
            log.append("mean=123.4 area=44.0 status=ok\n");
        }
        result.addProperty("log", log.toString());
        return response;
    }

    private static String governanceFields(JsonObject response) {
        JsonArray fields = response.getAsJsonObject("_governance")
                .getAsJsonArray("fields_pseudonymised");
        return fields.toString();
    }

    private static byte[] bytes(int value) {
        byte[] salt = new byte[32];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) value;
        }
        return salt;
    }
}
