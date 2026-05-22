package imagejai.engine.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import imagejai.config.PrivacyPosture;
import imagejai.engine.TCPCommandServer;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PseudonymisationFilterExhaustiveTest {
    private static final String RAW_PATH =
            "C:\\study\\MOAB2\\subject_017_visit3\\MOAB2_subject_017_visit3.lif";
    private static final String RAW_FILE = "MOAB2_subject_017_visit3.lif";
    private static final String RAW_LABEL = "MOAB2_subject_017_visit3_cell_4";
    private static final String RAW_EXPERIMENTER = "Jane Donor subject_017";
    private static final String[] ORIGINALS = new String[] {
            "MOAB2", "subject_017", "visit3", "Jane Donor"
    };

    @Test
    public void everyKnownCommandHasPseudonymisedRedactionPositiveFixture()
            throws Exception {
        LinkedHashMap<String, JsonObject> fixtures = fixtures();
        assertEquals(TCPCommandServer.knownCommands(),
                new ArrayList<String>(fixtures.keySet()));

        int salt = 1;
        for (Map.Entry<String, JsonObject> entry : fixtures.entrySet()) {
            PathTokenMap map = new PathTokenMap(bytes(salt++));
            registerKnownSensitiveStrings(map);
            PseudonymisationFilter filter = filter(map);
            JsonObject response = copy(entry.getValue());

            assertTrue("fixture for " + entry.getKey()
                    + " must contain an original identifier before filtering",
                    containsAny(response.toString(), ORIGINALS));

            filter.apply(response, entry.getKey(), PrivacyPosture.PSEUDONYMISED,
                    "stage08");
            String out = response.toString();

            for (String original : ORIGINALS) {
                assertFalse(entry.getKey() + " leaked " + original + " in " + out,
                        out.contains(original));
            }
            assertTrue(entry.getKey() + " missing governance block",
                    response.has("_governance"));
            assertTrue(entry.getKey() + " was not redaction-positive",
                    response.getAsJsonObject("_governance")
                            .getAsJsonArray("fields_pseudonymised").size() > 0);
        }
    }

    @Test
    public void everyKnownCommandFixturePassesThroughInStandardMode()
            throws Exception {
        LinkedHashMap<String, JsonObject> fixtures = fixtures();
        assertEquals(TCPCommandServer.knownCommands(),
                new ArrayList<String>(fixtures.keySet()));

        int salt = 100;
        for (Map.Entry<String, JsonObject> entry : fixtures.entrySet()) {
            PseudonymisationFilter filter = filter(new PathTokenMap(bytes(salt++)));
            JsonObject response = copy(entry.getValue());
            String before = response.toString();

            filter.apply(response, entry.getKey(), PrivacyPosture.STANDARD,
                    "stage08");

            assertEquals(entry.getKey() + " changed in Standard mode",
                    before, response.toString());
            assertFalse(entry.getKey() + " gained governance in Standard mode",
                    response.has("_governance"));
        }
    }

    private static LinkedHashMap<String, JsonObject> fixtures() throws Exception {
        LinkedHashMap<String, JsonObject> out =
                new LinkedHashMap<String, JsonObject>();
        put(out, "hello");
        put(out, "ping");
        put(out, "emit_methods_table");
        put(out, "execute_macro");
        put(out, "get_state");
        put(out, "get_image_info");
        put(out, "get_results_table");
        put(out, "capture_image");
        put(out, "request_visual");
        put(out, "open_image");
        put(out, "open_image_by_token");
        put(out, "run_pipeline");
        put(out, "explore_thresholds");
        put(out, "get_state_context");
        put(out, "get_log");
        put(out, "get_histogram");
        put(out, "get_open_windows");
        put(out, "get_metadata");
        put(out, "batch");
        put(out, "run");
        put(out, "get_pixels");
        put(out, "3d_viewer");
        put(out, "get_dialogs");
        put(out, "close_dialogs");
        put(out, "close_windows");
        put(out, "probe_command");
        put(out, "list_commands");
        put(out, "run_script");
        put(out, "interact_dialog");
        put(out, "get_progress");
        put(out, "get_friction_log");
        put(out, "get_friction_patterns");
        put(out, "clear_friction_log");
        put(out, "intent");
        put(out, "intent_teach");
        put(out, "intent_list");
        put(out, "intent_forget");
        put(out, "gui_action");
        put(out, "execute_macro_async");
        put(out, "job_status");
        put(out, "job_cancel");
        put(out, "job_list");
        put(out, "list_reactive_rules");
        put(out, "reactive_stats");
        put(out, "reactive_enable");
        put(out, "reactive_disable");
        put(out, "reactive_reload");
        put(out, "get_roi_state");
        put(out, "get_display_state");
        put(out, "get_console");
        put(out, "get_image_graph");
        put(out, "ledger_lookup");
        put(out, "ledger_confirm");
        put(out, "rewind");
        put(out, "branch");
        put(out, "branch_list");
        put(out, "branch_switch");
        put(out, "branch_delete");
        return out;
    }

    private static void put(Map<String, JsonObject> out, String command)
            throws Exception {
        out.put(command, responseFixture(command));
    }

    private static JsonObject responseFixture(String command) throws Exception {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        JsonObject result = new JsonObject();
        result.addProperty("path", RAW_PATH);
        result.addProperty("filename", RAW_FILE);
        result.addProperty("title", RAW_FILE);
        result.addProperty("info",
                "<OME><Experimenter>" + RAW_EXPERIMENTER + "</Experimenter>"
                        + "<Description>MOAB2 subject_017 visit3</Description>"
                        + "<Pixels SizeX=\"32\" SizeY=\"32\"/></OME>");
        result.addProperty("log",
                "Processed " + RAW_PATH + " for " + RAW_LABEL);
        JsonArray rows = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("Label", RAW_LABEL);
        row.addProperty("Slice", RAW_FILE);
        row.addProperty("Area", "44");
        rows.add(row);
        result.add("results", rows);
        if ("capture_image".equals(command)) {
            result.addProperty("source", "ACTIVE_IMAGE_CONTENT");
            result.addProperty("base64", pngBase64());
            result.addProperty("width", 640);
            result.addProperty("height", 480);
        }
        response.add("result", result);
        return response;
    }

    private static void registerKnownSensitiveStrings(PathTokenMap map) {
        map.tokenForPathString(RAW_PATH);
        map.tokenForPathString(RAW_FILE);
        map.tokenForSensitiveText(RAW_LABEL, "label");
        map.tokenForSensitiveText(RAW_EXPERIMENTER, "ome");
    }

    private static PseudonymisationFilter filter(PathTokenMap map) {
        return new PseudonymisationFilter(map, new OmeXmlScrubber(),
                new CaptureHandler(new BurnInDetector(), new VisualOverrideRegistry()));
    }

    private static JsonObject copy(JsonObject object) {
        return new JsonParser().parse(object.toString()).getAsJsonObject();
    }

    private static boolean containsAny(String value, String[] needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String pngBase64() throws Exception {
        BufferedImage image = new BufferedImage(640, 480,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(80, 80, 80));
        g.fillRect(0, 0, 640, 480);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static byte[] bytes(int value) {
        byte[] salt = new byte[32];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) value;
        }
        return salt;
    }
}
