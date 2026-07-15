package imagejai.engine.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import imagejai.config.PrivacyPosture;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import imagejai.test.Benchmark;

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
    public void resultsArrayLabelsAndPlainTextSlicesAreTokenised() {
        PathTokenMap map = new PathTokenMap(bytes(14));
        PseudonymisationFilter filter = filter(map);
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        JsonArray rows = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("Label", "MOAB2_subject_017_cell_3");
        row.addProperty("Slice", "subject_017_zplane");
        row.addProperty("Area", "44");
        rows.add(row);
        response.add("results", rows);

        filter.apply(response, "get_state", PrivacyPosture.PSEUDONYMISED, "s");

        String out = response.toString();
        assertFalse(out.contains("MOAB2_subject_017"));
        assertFalse(out.contains("subject_017_zplane"));
        assertTrue(out.contains("\"Area\":\"44\""));
        assertTrue(governanceFields(response).contains("results_table"));
    }

    @Test
    public void getPixelsBinaryDataContractIsPreserved() {
        PathTokenMap map = new PathTokenMap(bytes(15));
        PseudonymisationFilter filter = filter(map);
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        JsonObject result = new JsonObject();
        result.addProperty("encoding", "base64_float32_le");
        result.addProperty("data", "QUJDREVGRw==");
        result.addProperty("width", 2);
        result.addProperty("height", 2);
        response.add("result", result);

        filter.apply(response, "get_pixels", PrivacyPosture.PSEUDONYMISED, "s");

        assertEquals("QUJDREVGRw==",
                response.getAsJsonObject("result").get("data").getAsString());
        assertTrue(response.has("_governance"));
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
    public void freeTextScrubDoesNotRescanInsertedTokens() {
        PathTokenMap map = new PathTokenMap(bytes(16));
        String longToken = map.tokenForSensitiveText("alpha beta", "alpha");
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
    public void failClosedAlsoAppliesInOnPremisesMode() {
        PseudonymisationFilter filter = new PseudonymisationFilter(
                new PathTokenMap(bytes(17)), new OmeXmlScrubber(),
                new CaptureHandler(new BurnInDetector(), new VisualOverrideRegistry())) {
            @Override
            protected void beforeFiltering(JsonObject response, String command,
                                           PrivacyPosture posture) {
                throw new IllegalStateException("boom");
            }
        };
        JsonObject response = okObject();
        response.getAsJsonObject("result").addProperty("path", "C:\\secret\\subject_017.lif");

        filter.apply(response, "get_state", PrivacyPosture.ON_PREMISES, "s");

        assertFalse(response.get("ok").getAsBoolean());
        assertEquals("redaction_failed", response.get("error").getAsString());
        assertFalse(response.toString().contains("subject_017"));
        assertEquals("On-premises",
                response.getAsJsonObject("_governance").get("posture").getAsString());
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
    public void windowTitleWithPathAndSeriesYieldsOneStableTokenAcrossFields() {
        PathTokenMap map = new PathTokenMap(bytes(30));
        PseudonymisationFilter filter = filter(map);
        String realPath = "C:\\study\\H31L21.MOAB2.4Weeks.SBB.lif";
        // The file's own path token (what open_image returns).
        String pathToken = map.tokenForPathString(realPath);

        String titleLH = realPath + " - NLGF11_LH_SCN";
        String titleRH = realPath + " - NGF11_RH_SCN";

        // get_open_windows: array under non-path key.
        JsonObject windows = new JsonObject();
        windows.addProperty("ok", true);
        JsonObject windowsResult = new JsonObject();
        JsonArray images = new JsonArray();
        images.add(titleLH);
        images.add(titleRH);
        windowsResult.add("images", images);
        windows.add("result", windowsResult);
        filter.apply(windows, "get_open_windows", PrivacyPosture.PSEUDONYMISED, "s");

        // get_active_layer / get_state: scalar under the path-typed "title" key
        // (the field that used to swallow the " - series" suffix into the token).
        JsonObject state = new JsonObject();
        state.addProperty("ok", true);
        JsonObject stateResult = new JsonObject();
        stateResult.addProperty("title", titleRH);
        state.add("result", stateResult);
        filter.apply(state, "get_state", PrivacyPosture.PSEUDONYMISED, "s");

        String lh = windows.getAsJsonObject("result").getAsJsonArray("images")
                .get(0).getAsString();
        String rh = windows.getAsJsonObject("result").getAsJsonArray("images")
                .get(1).getAsString();
        String titleFieldRh = state.getAsJsonObject("result").get("title").getAsString();

        // One stable base token for the file, everywhere; series kept in plain
        // text as a suffix (never folded into the token).
        assertEquals(pathToken + " - NLGF11_LH_SCN", lh);
        assertEquals(pathToken + " - NGF11_RH_SCN", rh);
        assertEquals(pathToken + " - NGF11_RH_SCN", titleFieldRh);
        // The series name must not have leaked into the token itself.
        assertFalse(pathToken.contains("SCN"));
        // No real identifiers survive.
        assertFalse(windows.toString().contains("H31L21"));
        assertFalse(state.toString().contains("H31L21"));
    }

    @Test
    public void cleanPathTitleFieldsStillTokeniseWholeValue() {
        PathTokenMap map = new PathTokenMap(bytes(31));
        PseudonymisationFilter filter = filter(map);
        JsonObject response = okObject();
        JsonObject result = response.getAsJsonObject("result");
        // A full-path title (this project's window-title format) tokenises to
        // the same token as the path field — one stable token per file.
        result.addProperty("path", "C:\\study\\MOAB2_subject_017.lif");
        result.addProperty("title", "C:\\study\\MOAB2_subject_017.lif");

        filter.apply(response, "get_state", PrivacyPosture.PSEUDONYMISED, "s");

        String expected = map.tokenForPathString("C:\\study\\MOAB2_subject_017.lif");
        assertEquals(expected, result.get("path").getAsString());
        assertEquals(expected, result.get("title").getAsString());
        // The fix kept whole-value tokenisation for clean paths intact.
        assertTrue(expected.startsWith("image-"));
        assertFalse(expected.contains("MOAB2"));
    }

    @Test
    public void directoryTitleFieldIsStillTokenisedWholeWithNoLeak() {
        PathTokenMap map = new PathTokenMap(bytes(32));
        PseudonymisationFilter filter = filter(map);
        JsonObject response = okObject();
        response.getAsJsonObject("result").addProperty("directory", "C:\\study\\Patient_Jane");

        filter.apply(response, "get_state", PrivacyPosture.PSEUDONYMISED, "s");

        // A bare directory (no extension) must not leak even though it never
        // matches the substring tokeniser.
        assertFalse(response.toString().contains("Patient_Jane"));
        assertTrue(governanceFields(response).contains("path"));
    }

    @Test
    public void reverseResolveTextRestoresWindowTitleTokenInMacro() {
        PathTokenMap map = new PathTokenMap(bytes(20));
        PseudonymisationFilter filter = filter(map);
        // Outbound the filter tokenises the path portion of the window title;
        // the agent then builds a macro from the pseudonymised title.
        String token = map.tokenForPathString("H31L21.MOAB2.4Weeks.SBB.lif");
        String agentMacro = "selectWindow(\"" + token + " - NGF11_RH_SCN\");";

        String reversed = filter.reverseResolveText(agentMacro);

        assertEquals("selectWindow(\"H31L21.MOAB2.4Weeks.SBB.lif - NGF11_RH_SCN\");",
                reversed);
    }

    @Test
    public void reverseResolveTextLeavesUnknownTokensUntouched() {
        PathTokenMap map = new PathTokenMap(bytes(21));
        PseudonymisationFilter filter = filter(map);
        String macro = "selectWindow(\"image-dead.lif - SCN\");";

        assertEquals(macro, filter.reverseResolveText(macro));
    }

    @Test
    public void deTokeniseRequestReversesMacroCodeButNotPersistenceCommands() {
        PathTokenMap map = new PathTokenMap(bytes(22));
        PseudonymisationFilter filter = filter(map);
        String token = map.tokenForPathString("subject_017.lif");

        JsonObject exec = new JsonObject();
        exec.addProperty("code", "selectWindow(\"" + token + " - SCN\");");
        filter.deTokeniseRequest(exec, "execute_macro", PrivacyPosture.PSEUDONYMISED);
        assertTrue(exec.get("code").getAsString().contains("subject_017.lif"));
        assertFalse(exec.get("code").getAsString().contains(token));

        // intent_teach persists its macro to disk — the token must survive so
        // no real path is ever written out of the JVM.
        JsonObject teach = new JsonObject();
        teach.addProperty("macro", "selectWindow(\"" + token + " - SCN\");");
        filter.deTokeniseRequest(teach, "intent_teach", PrivacyPosture.PSEUDONYMISED);
        assertTrue(teach.get("macro").getAsString().contains(token));
        assertFalse(teach.get("macro").getAsString().contains("subject_017.lif"));
    }

    @Test
    public void deTokeniseRequestReversesPipelineStepCode() {
        PathTokenMap map = new PathTokenMap(bytes(23));
        PseudonymisationFilter filter = filter(map);
        String token = map.tokenForPathString("subject_017.lif");
        JsonObject request = new JsonObject();
        JsonArray steps = new JsonArray();
        JsonObject step = new JsonObject();
        step.addProperty("code", "selectWindow(\"" + token + " - SCN\");");
        steps.add(step);
        request.add("steps", steps);

        filter.deTokeniseRequest(request, "run_pipeline", PrivacyPosture.PSEUDONYMISED);

        assertTrue(request.getAsJsonArray("steps").get(0).getAsJsonObject()
                .get("code").getAsString().contains("subject_017.lif"));
    }

    @Test
    public void deTokeniseRequestIsNoOpInStandardMode() {
        PathTokenMap map = new PathTokenMap(bytes(24));
        PseudonymisationFilter filter = filter(map);
        String token = map.tokenForPathString("subject_017.lif");
        JsonObject request = new JsonObject();
        request.addProperty("code", "selectWindow(\"" + token + "\");");

        filter.deTokeniseRequest(request, "execute_macro", PrivacyPosture.STANDARD);

        assertEquals("selectWindow(\"" + token + "\");",
                request.get("code").getAsString());
    }

    @Test
    public void roundTripTokeniseThenReverseYieldsOriginalTitle() {
        PathTokenMap map = new PathTokenMap(bytes(25));
        PseudonymisationFilter filter = filter(map);
        // Outbound: a get_open_windows-style response carries the real title.
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        JsonArray images = new JsonArray();
        images.add("subject_017.lif - NGF11_RH_SCN");
        response.add("images", images);
        filter.apply(response, "get_open_windows", PrivacyPosture.PSEUDONYMISED, "s");
        String pseudonymisedTitle = response.getAsJsonArray("images").get(0).getAsString();
        assertFalse(pseudonymisedTitle.contains("subject_017"));

        // Inbound: the agent echoes that title into a macro.
        String reversed = filter.reverseResolveText(
                "selectWindow(\"" + pseudonymisedTitle + "\");");

        assertEquals("selectWindow(\"subject_017.lif - NGF11_RH_SCN\");", reversed);
    }

    @Test
    @Category(Benchmark.class)
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
