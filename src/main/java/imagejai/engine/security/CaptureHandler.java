package imagejai.engine.security;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import imagejai.config.PrivacyPosture;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Applies the visual side of pseudonymisation to capture_image responses.
 */
public final class CaptureHandler {
    private static final int MAX_PSEUDONYMISED_DIMENSION = 512;

    private final BurnInDetector burnInDetector;
    private final VisualOverrideRegistry visualOverrideRegistry;

    public CaptureHandler(BurnInDetector burnInDetector,
                          VisualOverrideRegistry visualOverrideRegistry) {
        this.burnInDetector = burnInDetector == null ? new BurnInDetector() : burnInDetector;
        this.visualOverrideRegistry = visualOverrideRegistry == null
                ? VisualOverrideRegistry.getInstance()
                : visualOverrideRegistry;
    }

    public void apply(JsonObject response, PrivacyPosture posture, String sessionId,
                      RedactionReport.Builder report) {
        JsonObject result = resultObject(response);
        if (result == null) {
            return;
        }
        JsonElement internalImageToken = result.remove("_visual_image_token");
        boolean hasInternalImageToken = internalImageToken != null
                && internalImageToken.isJsonPrimitive()
                && internalImageToken.getAsJsonPrimitive().isString();

        CaptureSource source = CaptureSource.from(optString(result, "source",
                optString(response, "source", "ACTIVE_IMAGE_CONTENT")));
        result.addProperty("source", source.name());

        if (source.isRefusedScreenshot()) {
            refuse(response, result, source, report);
            return;
        }

        JsonElement b64Element = result.get("base64");
        if (b64Element == null || !b64Element.isJsonPrimitive()) {
            return;
        }

        byte[] png = Base64.getDecoder().decode(b64Element.getAsString());
        if (source == CaptureSource.ACTIVE_IMAGE_WITH_OVERLAY
                && burnInDetector.hasDetectedBurnIn(png)) {
            refuse(response, result, source, report);
            result.addProperty("reason", "overlay_burn_in_detected_by_privacy_posture");
            return;
        }
        boolean consumeOverride = posture == PrivacyPosture.PSEUDONYMISED
                && source == CaptureSource.ACTIVE_IMAGE_CONTENT
                && (hasInternalImageToken
                ? visualOverrideRegistry.consumeIfPresent(
                        sessionId, internalImageToken.getAsString())
                : visualOverrideRegistry.consumeIfPresent(sessionId));
        byte[] processed = consumeOverride
                ? burnInDetector.mask(png)
                : burnInDetector.mask(downsampleTo(png, MAX_PSEUDONYMISED_DIMENSION));

        result.addProperty("base64", Base64.getEncoder().encodeToString(processed));
        int[] dims = dimensions(processed);
        if (dims != null) {
            if (result.has("width")) result.addProperty("original_width", result.get("width").getAsInt());
            if (result.has("height")) result.addProperty("original_height", result.get("height").getAsInt());
            result.addProperty("width", dims[0]);
            result.addProperty("height", dims[1]);
        }
        result.addProperty("burn_in_mask", "heuristic_border");
        if (consumeOverride) {
            result.addProperty("visual_override", "consumed");
        } else {
            result.addProperty("downsampled_to", MAX_PSEUDONYMISED_DIMENSION);
        }
        report.fieldPseudonymised("image");
    }

    private void refuse(JsonObject response, JsonObject result, CaptureSource source,
                        RedactionReport.Builder report) {
        result.remove("base64");
        result.addProperty("placeholder", placeholder(source));
        result.addProperty("refused", true);
        result.addProperty("reason", "capture_source_refused_by_privacy_posture");
        response.addProperty("ok", false);
        response.addProperty("error", "capture_refused");
        report.fieldPseudonymised("image");
    }

    private byte[] downsampleTo(byte[] png, int maxDimension) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                return png;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            if (w <= maxDimension && h <= maxDimension) {
                return png;
            }
            double scale = Math.min((double) maxDimension / w, (double) maxDimension / h);
            int newW = Math.max(1, (int) Math.round(w * scale));
            int newH = Math.max(1, (int) Math.round(h * scale));
            BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, newW, newH, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("capture downsample failed", e);
        }
    }

    private int[] dimensions(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                return null;
            }
            return new int[] { image.getWidth(), image.getHeight() };
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject resultObject(JsonObject response) {
        if (response == null) {
            return null;
        }
        JsonElement result = response.get("result");
        if (result != null && result.isJsonObject()) {
            return result.getAsJsonObject();
        }
        return response.has("base64") ? response : null;
    }

    private static String optString(JsonObject object, String key, String fallback) {
        if (object == null) {
            return fallback;
        }
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static String placeholder(CaptureSource source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((source.name() + ":" + System.nanoTime())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (int i = 0; i < 8 && i < digest.length; i++) {
                String h = Integer.toHexString(digest[i] & 0xff);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }
}
