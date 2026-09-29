package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.security.PseudonymisationFilter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@code pseudonymise_paths}.
 *
 * <p>The command exists because a token minted outside the JVM cannot be
 * reversed inside it: {@code PathTokenMap} salts every token with a
 * process-local random value. The tests therefore care about three things —
 * the posture decides whether a token is minted at all, the same path always
 * yields the same token, and a token this command returns is one the inbound
 * filter can turn back into the real path.
 */
public class TCPCommandServerPseudonymisePathsTest {

    private Path folder;
    private Path imageFile;

    private static TCPCommandServer newServer() {
        return new TCPCommandServer(0, null, null, null, null);
    }

    @Before
    public void createSampleImage() throws IOException {
        folder = Files.createTempDirectory("pseudonymise-paths-test");
        imageFile = folder.resolve("sentinel-" + System.nanoTime() + ".tif");
        Files.write(imageFile, new byte[] {1, 2, 3, 4});
    }

    @After
    public void cleanUp() throws IOException {
        Files.deleteIfExists(imageFile);
        Files.deleteIfExists(folder);
        resetPosture();
    }

    private static void usePosture(PrivacyPosture posture) {
        Settings settings = new Settings();
        settings.setPrivacyPosture(posture);
        PostureController.getInstance().configure(settings);
    }

    private static void resetPosture() {
        usePosture(PrivacyPosture.defaultPosture());
    }

    private static JsonObject requestFor(String... paths) {
        JsonObject request = new JsonObject();
        request.addProperty("command", "pseudonymise_paths");
        JsonArray array = new JsonArray();
        for (String path : paths) array.add(path);
        request.add("paths", array);
        return request;
    }

    private static JsonObject firstMapping(JsonObject response) {
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        return response.getAsJsonObject("result")
                .getAsJsonArray("mappings").get(0).getAsJsonObject();
    }

    private static String errorCode(JsonObject response) {
        assertFalse(response.toString(), response.get("ok").getAsBoolean());
        return response.getAsJsonObject("error").get("code").getAsString();
    }

    // -----------------------------------------------------------------------
    // Posture behaviour
    // -----------------------------------------------------------------------

    @Test
    public void standardPostureReturnsTheRealPathUnchangedAndSaysSo() {
        usePosture(PrivacyPosture.STANDARD);
        JsonObject response = newServer().handlePseudonymisePaths(
                requestFor(imageFile.toString()));

        JsonObject result = response.getAsJsonObject("result");
        assertEquals("STANDARD", result.get("posture").getAsString());
        assertEquals("Standard", result.get("posture_label").getAsString());
        assertFalse(result.get("pseudonymised").getAsBoolean());
        assertEquals(1, result.get("count").getAsInt());
        assertTrue(result.get("note").getAsString().contains("unchanged"));

        JsonObject mapping = firstMapping(response);
        assertEquals(imageFile.toString(), mapping.get("token").getAsString());
        assertEquals(imageFile.toString(), mapping.get("path").getAsString());
        assertEquals(0, mapping.get("index").getAsInt());
    }

    @Test
    public void pseudonymisedPostureMintsATokenInsteadOfTheRealPath() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        JsonObject response = newServer().handlePseudonymisePaths(
                requestFor(imageFile.toString()));

        JsonObject result = response.getAsJsonObject("result");
        assertEquals("PSEUDONYMISED", result.get("posture").getAsString());
        assertTrue(result.get("pseudonymised").getAsBoolean());

        String token = firstMapping(response).get("token").getAsString();
        assertNotEquals(imageFile.toString(), token);
        assertTrue(token, token.startsWith("image-"));
        assertTrue(token, token.endsWith(".tif"));
        // The echoed 'path' must be the token too: a real path in a reply is
        // exactly what this command exists to prevent.
        assertEquals(token, firstMapping(response).get("path").getAsString());
    }

    @Test
    public void onPremisesPostureAlsoMintsAToken() {
        usePosture(PrivacyPosture.ON_PREMISES);
        JsonObject response = newServer().handlePseudonymisePaths(
                requestFor(imageFile.toString()));

        JsonObject result = response.getAsJsonObject("result");
        assertEquals("ON_PREMISES", result.get("posture").getAsString());
        assertTrue(result.get("pseudonymised").getAsBoolean());
        assertTrue(firstMapping(response).get("token").getAsString()
                .startsWith("image-"));
    }

    @Test
    public void theFullDispatchReplyNeverCarriesTheRealFileName() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        JsonObject response = newServer().dispatch(
                requestFor(imageFile.toString()), TCPCommandServer.DEFAULT_CAPS);

        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        String fileName = imageFile.getFileName().toString();
        // Compared on the file name, not the whole path: the path would be
        // JSON-escaped on Windows and a naive contains() could never fail.
        assertFalse(response.toString(), response.toString().contains(fileName));
        assertTrue(response.has("_governance"));
    }

    // -----------------------------------------------------------------------
    // Stability and round trip
    // -----------------------------------------------------------------------

    @Test
    public void thesamePathAlwaysReturnsTheSameTokenWithinASession() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        String first = firstMapping(newServer().handlePseudonymisePaths(
                requestFor(imageFile.toString()))).get("token").getAsString();
        // A second server shares the process-local map, exactly as two TCP
        // connections in one Fiji session would.
        String second = firstMapping(newServer().handlePseudonymisePaths(
                requestFor(imageFile.toString()))).get("token").getAsString();
        assertEquals(first, second);

        // A different spelling of the same file normalises to one token.
        String viaDotSegment = folder.toString() + java.io.File.separator + "."
                + java.io.File.separator + imageFile.getFileName().toString();
        String third = firstMapping(newServer().handlePseudonymisePaths(
                requestFor(viaDotSegment))).get("token").getAsString();
        assertEquals(first, third);
    }

    @Test
    public void aTokenMintedHereIsReversedByTheInboundFilter() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        String token = firstMapping(newServer().handlePseudonymisePaths(
                requestFor(imageFile.toString()))).get("token").getAsString();

        // This is the whole point of minting plugin-side: the model echoes the
        // token back inside a macro and the server must resolve it.
        JsonObject macro = new JsonObject();
        macro.addProperty("command", "execute_macro");
        macro.addProperty("code", "open(\"" + token + "\");");
        PseudonymisationFilter.getInstance().deTokeniseRequest(
                macro, "execute_macro", PrivacyPosture.PSEUDONYMISED);

        assertEquals("open(\"" + imageFile.toString() + "\");",
                macro.get("code").getAsString());
    }

    @Test
    public void mappingsFollowRequestOrder() throws IOException {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        Path second = folder.resolve("second-" + System.nanoTime() + ".png");
        Files.write(second, new byte[] {9});
        try {
            JsonObject response = newServer().handlePseudonymisePaths(
                    requestFor(imageFile.toString(), second.toString()));
            JsonArray mappings = response.getAsJsonObject("result")
                    .getAsJsonArray("mappings");
            assertEquals(2, mappings.size());
            assertEquals(0, mappings.get(0).getAsJsonObject().get("index").getAsInt());
            assertEquals(1, mappings.get(1).getAsJsonObject().get("index").getAsInt());
            assertTrue(mappings.get(0).getAsJsonObject().get("token")
                    .getAsString().endsWith(".tif"));
            assertTrue(mappings.get(1).getAsJsonObject().get("token")
                    .getAsString().endsWith(".png"));
        } finally {
            Files.deleteIfExists(second);
        }
    }

    // -----------------------------------------------------------------------
    // Refusals — every one a structured error, never an exception
    // -----------------------------------------------------------------------

    @Test
    public void refusesARequestWithNoPathsArray() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        JsonObject request = new JsonObject();
        request.addProperty("command", "pseudonymise_paths");
        assertEquals("invalid_request",
                errorCode(newServer().handlePseudonymisePaths(request)));

        JsonObject wrongType = new JsonObject();
        wrongType.addProperty("command", "pseudonymise_paths");
        wrongType.addProperty("paths", "C:/data/one.tif");
        assertEquals("invalid_request",
                errorCode(newServer().handlePseudonymisePaths(wrongType)));
    }

    @Test
    public void refusesAnEmptyPathsArray() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        assertEquals("invalid_request",
                errorCode(newServer().handlePseudonymisePaths(requestFor())));
    }

    @Test
    public void refusesABlankPathEntry() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        assertEquals("invalid_path",
                errorCode(newServer().handlePseudonymisePaths(requestFor("   "))));
    }

    @Test
    public void refusesMorePathsThanTheBatchCeiling() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        List<String> paths = new ArrayList<String>();
        for (int i = 0; i <= TCPCommandServer.MAX_PSEUDONYMISE_PATHS; i++) {
            paths.add(imageFile.toString());
        }
        JsonObject response = newServer().handlePseudonymisePaths(
                requestFor(paths.toArray(new String[0])));
        assertEquals("too_many_paths", errorCode(response));
        // The ceiling is checked before any minting, so nothing half-landed.
        assertTrue(response.getAsJsonObject("error").get("message").getAsString()
                .contains(String.valueOf(TCPCommandServer.MAX_PSEUDONYMISE_PATHS)));
    }

    @Test
    public void refusesAnOverLongPath() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        StringBuilder overLong = new StringBuilder(
                folder.toString() + java.io.File.separator);
        while (overLong.length() <= TCPCommandServer.MAX_PSEUDONYMISE_PATH_CHARS) {
            overLong.append('x');
        }
        assertEquals("path_too_long", errorCode(newServer()
                .handlePseudonymisePaths(requestFor(overLong.toString()))));
    }

    @Test
    public void refusesAPathThatDoesNotExist() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        Path missing = folder.resolve("never-written-" + System.nanoTime() + ".tif");
        assertEquals("path_not_found", errorCode(newServer()
                .handlePseudonymisePaths(requestFor(missing.toString()))));
    }

    @Test
    public void refusesARelativePath() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        assertEquals("path_not_absolute", errorCode(newServer()
                .handlePseudonymisePaths(requestFor("data/one.tif"))));
    }

    @Test
    public void refusesAPathOutsideAnyReadableLocation() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        TCPCommandServer server = newServer();
        server.pathAccessProbeForTest = new TCPCommandServer.PathAccessProbe() {
            @Override public boolean exists(Path path) {
                return true;
            }

            @Override public boolean readable(Path path) {
                return false;
            }
        };
        JsonObject response = server.handlePseudonymisePaths(
                requestFor(imageFile.toString()));
        assertEquals("path_not_readable", errorCode(response));
        String message = response.getAsJsonObject("error").get("message").getAsString();
        // The refusal names the slot, never the path it refused.
        assertTrue(message, message.contains("paths[0]"));
        assertFalse(message, message.contains(imageFile.getFileName().toString()));
    }

    @Test
    public void refusalsAreStructuredAndNotRetrySafe() {
        usePosture(PrivacyPosture.PSEUDONYMISED);
        JsonObject response = newServer().handlePseudonymisePaths(
                requestFor(Paths.get(folder.toString(), "absent.tif").toString()));
        JsonObject error = response.getAsJsonObject("error");
        assertEquals("validation", error.get("category").getAsString());
        assertFalse(error.get("retry_safe").getAsBoolean());
        assertTrue(error.has("message"));
    }
}
