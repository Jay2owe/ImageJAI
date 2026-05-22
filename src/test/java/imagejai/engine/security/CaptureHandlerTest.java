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
    public void visualOverrideIsConsumedOnceAndThenDownsamplesAgain() throws Exception {
        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        registry.grant("session-a", "test");
        CaptureHandler handler = new CaptureHandler(new BurnInDetector(), registry);

        JsonObject first = captureResponse(png(900, 700, false), "ACTIVE_IMAGE_CONTENT");
        handler.apply(first, PrivacyPosture.PSEUDONYMISED, "session-a",
                RedactionReport.builder().posture(PrivacyPosture.PSEUDONYMISED));
        BufferedImage full = decode(first.getAsJsonObject("result").get("base64").getAsString());

        JsonObject second = captureResponse(png(900, 700, false), "ACTIVE_IMAGE_CONTENT");
        handler.apply(second, PrivacyPosture.PSEUDONYMISED, "session-a",
                RedactionReport.builder().posture(PrivacyPosture.PSEUDONYMISED));
        BufferedImage downsampled = decode(second.getAsJsonObject("result")
                .get("base64").getAsString());

        assertTrue(full.getWidth() == 900 && full.getHeight() == 700);
        assertTrue(downsampled.getWidth() <= 512);
        assertFalse(registry.hasGrant("session-a"));
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
