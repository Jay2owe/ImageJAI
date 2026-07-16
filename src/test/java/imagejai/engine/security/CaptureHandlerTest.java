package imagejai.engine.security;

import com.google.gson.JsonObject;
import imagejai.config.PrivacyPosture;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CaptureHandlerTest {
    @Test
    public void activeImageContentIsDownsampledAndBurnInMasked() throws Exception {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        CaptureHandler handler = new CaptureHandler(new BurnInDetector(), registry);
        JsonObject response = captureResponse(png(1000, 700, true),
                "ACTIVE_IMAGE_CONTENT");
        RedactionReport.Builder report = RedactionReport.builder()
                .posture(PrivacyPosture.PSEUDONYMISED);

        handler.apply(response, PrivacyPosture.PSEUDONYMISED, "s", report);

        BufferedImage out = decode(response.getAsJsonObject("result")
                .get("base64").getAsString());
        assertTrue(out.getWidth() <= 512);
        assertTrue(out.getHeight() <= 512);
        assertTrue("top burn-in strip should be masked black",
                (out.getRGB(8, 8) & 0x00ffffff) == 0);
    }

    @Test
    public void dialogScreenshotsAreRefusedWithoutBase64() throws Exception {
        CaptureHandler handler = new CaptureHandler(
                new BurnInDetector(), new VisualOverrideRegistry());
        JsonObject response = captureResponse(png(300, 200, false),
                "DIALOG_SCREENSHOT");
        RedactionReport.Builder report = RedactionReport.builder()
                .posture(PrivacyPosture.PSEUDONYMISED);

        handler.apply(response, PrivacyPosture.PSEUDONYMISED, "s", report);

        assertFalse(response.get("ok").getAsBoolean());
        JsonObject result = response.getAsJsonObject("result");
        assertFalse(result.has("base64"));
        assertTrue(result.has("placeholder"));
    }

    @Test
    public void windowAndDesktopScreenshotsAreRefusedWithoutBase64() throws Exception {
        CaptureHandler handler = new CaptureHandler(
                new BurnInDetector(), new VisualOverrideRegistry());
        String[] sources = new String[] { "WINDOW_SCREENSHOT", "DESKTOP_SCREENSHOT" };

        for (String source : sources) {
            JsonObject response = captureResponse(png(300, 200, false), source);
            RedactionReport.Builder report = RedactionReport.builder()
                    .posture(PrivacyPosture.PSEUDONYMISED);

            handler.apply(response, PrivacyPosture.PSEUDONYMISED, "s", report);

            assertFalse(response.get("ok").getAsBoolean());
            JsonObject result = response.getAsJsonObject("result");
            assertEquals(source, result.get("source").getAsString());
            assertFalse(result.has("base64"));
            assertTrue(result.has("placeholder"));
        }
    }

    @Test
    public void activeImageWithOverlayIsAllowedWhenClean() throws Exception {
        CaptureHandler handler = new CaptureHandler(
                new BurnInDetector(), new VisualOverrideRegistry());
        JsonObject response = captureResponse(png(700, 500, false),
                "ACTIVE_IMAGE_WITH_OVERLAY");

        handler.apply(response, PrivacyPosture.PSEUDONYMISED, "s",
                RedactionReport.builder().posture(PrivacyPosture.PSEUDONYMISED));

        assertTrue(response.get("ok").getAsBoolean());
        JsonObject result = response.getAsJsonObject("result");
        assertEquals("ACTIVE_IMAGE_WITH_OVERLAY", result.get("source").getAsString());
        assertTrue(result.has("base64"));
        BufferedImage out = decode(result.get("base64").getAsString());
        assertTrue(out.getWidth() <= 512);
        assertTrue(out.getHeight() <= 512);
    }

    @Test
    public void activeImageWithOverlayIsRefusedWhenBurnInIsDetected() throws Exception {
        CaptureHandler handler = new CaptureHandler(
                new BurnInDetector(), new VisualOverrideRegistry());
        JsonObject response = captureResponse(png(700, 500, true),
                "ACTIVE_IMAGE_WITH_OVERLAY");

        handler.apply(response, PrivacyPosture.PSEUDONYMISED, "s",
                RedactionReport.builder().posture(PrivacyPosture.PSEUDONYMISED));

        assertFalse(response.get("ok").getAsBoolean());
        JsonObject result = response.getAsJsonObject("result");
        assertEquals("ACTIVE_IMAGE_WITH_OVERLAY", result.get("source").getAsString());
        assertFalse(result.has("base64"));
        assertEquals("overlay_burn_in_detected_by_privacy_posture",
                result.get("reason").getAsString());
    }

    @Test
    public void visualOverrideIsConsumedOnceAndThenDownsamplesAgain() throws Exception {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        VisualOverrideRegistry.PendingRequest pending =
                registry.request("session-a", "test", "no-active-image");
        assertTrue(registry.grant("session-a", pending.requestId, "test"));
        CaptureHandler handler = new CaptureHandler(new BurnInDetector(), registry);

        JsonObject first = captureResponse(png(900, 700, false), "ACTIVE_IMAGE_CONTENT");
        handler.apply(first, PrivacyPosture.PSEUDONYMISED, "session-a",
                RedactionReport.builder().posture(PrivacyPosture.PSEUDONYMISED));
        JsonObject firstResult = first.getAsJsonObject("result");
        BufferedImage full = decode(firstResult.get("base64").getAsString());

        JsonObject second = captureResponse(png(900, 700, false), "ACTIVE_IMAGE_CONTENT");
        handler.apply(second, PrivacyPosture.PSEUDONYMISED, "session-a",
                RedactionReport.builder().posture(PrivacyPosture.PSEUDONYMISED));
        JsonObject secondResult = second.getAsJsonObject("result");
        BufferedImage downsampled = decode(secondResult.get("base64").getAsString());

        assertTrue(full.getWidth() == 900 && full.getHeight() == 700);
        assertEquals("consumed", firstResult.get("visual_override").getAsString());
        assertTrue(downsampled.getWidth() <= 512);
        assertEquals(512, secondResult.get("downsampled_to").getAsInt());
        assertFalse(registry.hasGrant("session-a"));
    }

    @Test
    public void internalImageTokenIsStrippedAndConsumedAtomically() throws Exception {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        VisualOverrideRegistry.PendingRequest pending =
                registry.request("session-a", "test", "image-token-a");
        assertTrue(registry.grant("session-a", pending.requestId, "test"));
        CaptureHandler handler = new CaptureHandler(new BurnInDetector(), registry);
        JsonObject response = captureResponse(
                png(900, 700, false), "ACTIVE_IMAGE_CONTENT");
        response.getAsJsonObject("result").addProperty(
                "_visual_image_token", "image-token-a");

        handler.apply(response, PrivacyPosture.PSEUDONYMISED, "session-a",
                RedactionReport.builder().posture(PrivacyPosture.PSEUDONYMISED));

        JsonObject result = response.getAsJsonObject("result");
        assertFalse(result.has("_visual_image_token"));
        assertEquals("consumed", result.get("visual_override").getAsString());
        assertFalse(registry.hasGrant("session-a", "image-token-a"));
    }

    @Test
    public void mismatchedInternalImageTokenDownsamplesWithoutConsumingGrant()
            throws Exception {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        VisualOverrideRegistry.PendingRequest pending =
                registry.request("session-a", "test", "image-token-a");
        assertTrue(registry.grant("session-a", pending.requestId, "test"));
        CaptureHandler handler = new CaptureHandler(new BurnInDetector(), registry);
        JsonObject response = captureResponse(
                png(900, 700, false), "ACTIVE_IMAGE_CONTENT");
        response.getAsJsonObject("result").addProperty(
                "_visual_image_token", "image-token-b");

        handler.apply(response, PrivacyPosture.PSEUDONYMISED, "session-a",
                RedactionReport.builder().posture(PrivacyPosture.PSEUDONYMISED));

        JsonObject result = response.getAsJsonObject("result");
        assertFalse(result.has("_visual_image_token"));
        assertEquals(512, result.get("downsampled_to").getAsInt());
        assertTrue(registry.hasGrant("session-a", "image-token-a"));
    }

    private static JsonObject captureResponse(byte[] png, String source) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        JsonObject result = new JsonObject();
        result.addProperty("base64", Base64.getEncoder().encodeToString(png));
        result.addProperty("width", 1000);
        result.addProperty("height", 700);
        result.addProperty("source", source);
        response.add("result", result);
        return response;
    }

    private static byte[] png(int width, int height, boolean burnIn) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(90, 90, 90));
        g.fillRect(0, 0, width, height);
        if (burnIn) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, 90);
            for (int x = 0; x < width; x += 18) {
                g.setColor((x / 18) % 2 == 0 ? Color.BLACK : Color.WHITE);
                g.fillRect(x, 0, 9, 90);
            }
            g.setColor(Color.BLACK);
            g.drawString("MOAB2 SUBJECT 017 2026-05-01", 25, 45);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage decode(String b64) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
    }
}
