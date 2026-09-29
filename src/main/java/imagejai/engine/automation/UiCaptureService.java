package imagejai.engine.automation;

import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Bounded, test-only rendering of one owned Swing/AWT component or window.
 *
 * <p>This is the deterministic half of visual evidence. It renders the selected
 * component's own graphics into an offscreen image, so it can never contain
 * another application's pixels, the desktop, or a window the harness does not
 * own. It is not a substitute for the harness's real screenshot: offscreen
 * rendering does not include native window decorations, and heavyweight or
 * OpenGL content composited by the OS will not appear. Both facts are reported
 * on every reply so a report cannot silently claim more than it proves.</p>
 *
 * <p>Production capture policy is untouched. {@code capture_image} still
 * refuses {@code DIALOG_SCREENSHOT}, {@code WINDOW_SCREENSHOT} and
 * {@code DESKTOP_SCREENSHOT} exactly as before; this command exists only under
 * the startup automation policy and the negotiated test capability.</p>
 */
public final class UiCaptureService {

    public static final String SOURCE = "IN_PROCESS_RENDER";
    public static final String ERR_CAPTURE_FAILED = "ui_capture_failed";
    public static final String ERR_CAPTURE_TOO_LARGE = "ui_capture_too_large";
    public static final String ERR_NOT_RENDERABLE = "ui_target_not_renderable";

    private final int maxDimension;
    private final int maxBytes;

    public UiCaptureService() {
        this(AutomationPolicy.MAX_CAPTURE_DIMENSION, AutomationPolicy.MAX_CAPTURE_BYTES);
    }

    UiCaptureService(int maxDimension, int maxBytes) {
        this.maxDimension = Math.max(1, maxDimension);
        this.maxBytes = Math.max(1024, maxBytes);
    }

    /**
     * Render {@code component} into a PNG. <strong>Must run on the event
     * thread</strong>: Swing painting off the EDT produces torn output.
     *
     * @param requestedMaxDimension caller ceiling for the longest edge, clamped
     *                              to the policy maximum
     * @param includeBytes          when false the reply carries only metadata
     *                              and the digest, which is enough for a
     *                              baseline comparison the harness already holds
     * @return a full protocol response ({@code ok}/{@code result} or
     *         {@code ok}/{@code error})
     */
    public JsonObject capture(Component component, String nodeId, String windowId,
                              long generation, int requestedMaxDimension,
                              boolean includeBytes) {
        if (component == null) {
            return UiAutomationService.error(ERR_NOT_RENDERABLE,
                    "No component to capture.");
        }
        if (!UiAutomationService.isEventThread()) {
            return UiAutomationService.error(ERR_CAPTURE_FAILED,
                    "Capture must execute on the Swing event thread.");
        }
        int sourceWidth = component.getWidth();
        int sourceHeight = component.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return UiAutomationService.error(ERR_NOT_RENDERABLE,
                    "The target has no laid-out size to render.");
        }
        if (!component.isDisplayable()) {
            return UiAutomationService.error(ERR_NOT_RENDERABLE,
                    "The target is not displayable; its window has been disposed.");
        }

        int limit = requestedMaxDimension <= 0 ? maxDimension
                : Math.min(maxDimension, requestedMaxDimension);
        double scale = Math.min(1.0d,
                Math.min((double) limit / sourceWidth, (double) limit / sourceHeight));
        int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));

        byte[] png;
        try {
            BufferedImage full = new BufferedImage(sourceWidth, sourceHeight,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = full.createGraphics();
            try {
                // printAll walks the whole hierarchy without the double-buffer
                // shortcuts paint() takes, which is what makes an offscreen
                // render match what the component would show.
                component.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            BufferedImage rendered = full;
            if (scale < 1.0d) {
                rendered = new BufferedImage(targetWidth, targetHeight,
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D scaler = rendered.createGraphics();
                try {
                    scaler.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    scaler.drawImage(full, 0, 0, targetWidth, targetHeight, null);
                } finally {
                    scaler.dispose();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(rendered, "png", out)) {
                return UiAutomationService.error(ERR_CAPTURE_FAILED,
                        "No PNG writer is available in this JVM.");
            }
            png = out.toByteArray();
        } catch (Throwable failure) {
            return UiAutomationService.error(ERR_CAPTURE_FAILED,
                    "Rendering failed: " + failure.getClass().getSimpleName());
        }

        if (png.length > maxBytes) {
            return UiAutomationService.error(ERR_CAPTURE_TOO_LARGE,
                    "The rendered PNG is " + png.length + " bytes; the ceiling is "
                            + maxBytes + ". Lower max_dimension or capture a child node.");
        }

        JsonObject result = new JsonObject();
        result.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
        result.addProperty("source", SOURCE);
        result.addProperty("mode", component instanceof Window ? "window" : "component");
        result.addProperty("node_id", nodeId);
        result.addProperty("window_id", windowId);
        result.addProperty("generation", generation);
        result.addProperty("format", "png");
        result.addProperty("width", targetWidth);
        result.addProperty("height", targetHeight);
        result.addProperty("source_width", sourceWidth);
        result.addProperty("source_height", sourceHeight);
        result.addProperty("scale", Math.round(scale * 10_000.0d) / 10_000.0d);
        result.addProperty("byte_length", png.length);
        result.addProperty("sha256", sha256(png));
        result.addProperty("captured_at_epoch_ms", System.currentTimeMillis());
        result.addProperty("includes_native_decorations", false);
        result.addProperty("includes_heavyweight_content", false);
        result.addProperty("showing", component.isShowing());
        if (includeBytes) {
            result.addProperty("base64", Base64.getEncoder().encodeToString(png));
        }
        result.addProperty("bytes_included", includeBytes);
        return UiAutomationService.success(result);
    }

    static String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) {
                int unsigned = value & 0xff;
                if (unsigned < 16) out.append('0');
                out.append(Integer.toHexString(unsigned));
            }
            return out.toString();
        } catch (Exception impossible) {
            return "unavailable";
        }
    }
}
