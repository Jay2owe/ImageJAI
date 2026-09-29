package imagejai.engine.automation;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * Bounded in-process rendering. This is the deterministic visual evidence; it
 * must be honest about what offscreen rendering cannot show, and must never
 * reach beyond the component it was handed.
 */
public class UiCaptureServiceTest {

    private UiAutomationService service;
    private UiCaptureService capture;
    private JFrame frame;
    private JPanel panel;

    @Before
    public void setUp() throws Exception {
        assumeFalse("real AWT windows unavailable", GraphicsEnvironment.isHeadless());
        service = new UiAutomationService(new UiIdentityRegistry(), null);
        capture = new UiCaptureService();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                frame = new JFrame("Capture Fixture");
                panel = new JPanel();
                panel.setPreferredSize(new Dimension(320, 200));
                panel.add(new JButton("Run"));
                frame.getContentPane().add(panel);
                frame.pack();
                frame.setLocation(-3000, -3000);
                frame.setVisible(true);
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() { frame.dispose(); }
            });
        }
    }

    @Test
    public void aComponentRendersToADecodablePngWithMatchingMetadata()
            throws Exception {
        JsonObject response = onEdt(() -> capture.capture(panel, "n-1", "w-1", 5L,
                0, true));
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        JsonObject result = response.getAsJsonObject("result");

        assertEquals("component", result.get("mode").getAsString());
        assertEquals(UiCaptureService.SOURCE, result.get("source").getAsString());
        assertEquals("png", result.get("format").getAsString());
        assertEquals("n-1", result.get("node_id").getAsString());
        assertEquals("w-1", result.get("window_id").getAsString());
        assertEquals(5L, result.get("generation").getAsLong());
        assertEquals(1.0d, result.get("scale").getAsDouble(), 0.0001d);
        // Offscreen rendering cannot show native decorations or OS-composited
        // heavyweight content; a report must not be able to claim otherwise.
        assertFalse(result.get("includes_native_decorations").getAsBoolean());
        assertFalse(result.get("includes_heavyweight_content").getAsBoolean());

        byte[] png = Base64.getDecoder().decode(result.get("base64").getAsString());
        assertEquals(png.length, result.get("byte_length").getAsInt());
        assertEquals(UiCaptureService.sha256(png), result.get("sha256").getAsString());

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(decoded);
        assertEquals(result.get("width").getAsInt(), decoded.getWidth());
        assertEquals(result.get("height").getAsInt(), decoded.getHeight());
        assertEquals(panel.getWidth(), decoded.getWidth());
    }

    @Test
    public void aWindowCaptureIsLabelledAsSuch() throws Exception {
        JsonObject result = onEdt(() -> capture.capture(frame, "w-1", "w-1", 5L,
                0, false)).getAsJsonObject("result");
        assertEquals("window", result.get("mode").getAsString());
        assertFalse("metadata-only captures carry no bytes", result.has("base64"));
        assertFalse(result.get("bytes_included").getAsBoolean());
        // The digest is still present, so a harness holding a baseline can
        // compare without moving pixels across the socket.
        assertEquals(64, result.get("sha256").getAsString().length());
    }

    @Test
    public void oversizedComponentsAreScaledDownAndReportTheScale()
            throws Exception {
        JsonObject result = onEdt(() -> capture.capture(panel, "n-1", "w-1", 5L,
                80, true)).getAsJsonObject("result");
        assertTrue(result.get("width").getAsInt() <= 80);
        assertTrue(result.get("height").getAsInt() <= 80);
        assertEquals(panel.getWidth(), result.get("source_width").getAsInt());
        assertTrue(result.get("scale").getAsDouble() < 1.0d);
    }

    @Test
    public void aRequestedCeilingCannotExceedThePolicyCeiling() throws Exception {
        JsonObject result = onEdt(() -> capture.capture(panel, "n-1", "w-1", 5L,
                Integer.MAX_VALUE, false)).getAsJsonObject("result");
        assertTrue(result.get("width").getAsInt()
                <= AutomationPolicy.MAX_CAPTURE_DIMENSION);
    }

    @Test
    public void aPayloadOverTheByteCeilingIsRefusedRatherThanTruncated()
            throws Exception {
        UiCaptureService tiny = new UiCaptureService(
                AutomationPolicy.MAX_CAPTURE_DIMENSION, 1024);
        JsonObject response = onEdt(() -> tiny.capture(panel, "n-1", "w-1", 5L, 0, true));
        assertFalse(response.get("ok").getAsBoolean());
        assertEquals(UiCaptureService.ERR_CAPTURE_TOO_LARGE,
                response.getAsJsonObject("error").get("code").getAsString());
        assertTrue(response.getAsJsonObject("error").get("message").getAsString()
                .contains("max_dimension"));
    }

    @Test
    public void unlaidOutOrDisposedTargetsAreRefused() throws Exception {
        JPanel unrealised = new JPanel();
        JsonObject noSize = onEdt(() -> capture.capture(unrealised, "n-2", "w-1", 5L,
                0, true));
        assertFalse(noSize.get("ok").getAsBoolean());
        assertEquals(UiCaptureService.ERR_NOT_RENDERABLE,
                noSize.getAsJsonObject("error").get("code").getAsString());

        final JPanel captured = panel;
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() { frame.dispose(); }
        });
        JsonObject disposed = onEdt(() -> capture.capture(captured, "n-1", "w-1", 5L,
                0, true));
        assertFalse(disposed.get("ok").getAsBoolean());
        assertEquals(UiCaptureService.ERR_NOT_RENDERABLE,
                disposed.getAsJsonObject("error").get("code").getAsString());

        JsonObject missing = capture.capture(null, "n-3", "w-1", 5L, 0, true);
        assertFalse(missing.get("ok").getAsBoolean());
    }

    @Test
    public void renderingOffTheEventThreadIsRefused() {
        JsonObject response = capture.capture(panel, "n-1", "w-1", 5L, 0, true);
        assertFalse(response.get("ok").getAsBoolean());
        assertEquals(UiCaptureService.ERR_CAPTURE_FAILED,
                response.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    public void theDigestIsDerivedFromTheBytesAndNothingElse() {
        // Identity of the reported digest matters more than pixel determinism:
        // live Swing rendering is not guaranteed to be byte-identical between
        // two paints, which is exactly why stage-17 pixel baselines are keyed by
        // environment and reviewed rather than asserted here.
        byte[] payload = new byte[] {1, 2, 3, 4, 5};
        String digest = UiCaptureService.sha256(payload);
        assertEquals(64, digest.length());
        assertEquals(digest, UiCaptureService.sha256(new byte[] {1, 2, 3, 4, 5}));
        assertFalse(digest.equals(UiCaptureService.sha256(new byte[] {1, 2, 3, 4, 6})));
    }

    private JsonObject onEdt(Supplier<JsonObject> work) throws Exception {
        return service.callOnEdt(work, 10_000L);
    }
}
