package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for snapshot-bound active-image scientific reads. */
public class TCPCommandServerScientificSnapshotTest {

    @Test
    public void imageInfoPublishesStableRevisionAndExactPlane() {
        TCPCommandServer server = newServer();
        ImagePlus image = hyperstack("dataset-a");
        image.setPosition(2, 2, 2);
        server.currentImageForTest = () -> image;
        try {
            JsonObject first = result(server, "{\"command\":\"get_image_info\"}");
            JsonObject second = result(server, "{\"command\":\"get_image_info\"}");

            assertTrue(first.get("image_id").getAsString().startsWith("img-"));
            assertTrue(first.get("image_revision").getAsLong() > 0L);
            assertEquals(first.get("image_id").getAsString(),
                    second.get("image_id").getAsString());
            assertEquals(first.get("image_revision").getAsLong(),
                    second.get("image_revision").getAsLong());
            assertPlane(first, 2, 2, 2, 2, 2, 2);
        } finally {
            server.stop();
        }
    }

    @Test
    public void sameShapedActiveImageSwitchFailsClosed() {
        TCPCommandServer server = newServer();
        ImagePlus firstImage = new ImagePlus("same", new ByteProcessor(2, 2));
        ImagePlus secondImage = new ImagePlus("same", new ByteProcessor(2, 2));
        AtomicReference<ImagePlus> active = new AtomicReference<ImagePlus>(firstImage);
        server.currentImageForTest = active::get;
        try {
            JsonObject info = result(server, "{\"command\":\"get_image_info\"}");
            active.set(secondImage);

            JsonObject request = parse("{\"command\":\"get_pixels\"}");
            request.addProperty("image_id", info.get("image_id").getAsString());
            request.addProperty("image_revision", info.get("image_revision").getAsLong());
            JsonObject response = server.dispatch(request, new TCPCommandServer.AgentCaps());

            assertErrorCode(response, "image_snapshot_mismatch");
        } finally {
            server.stop();
        }
    }

    @Test
    public void explicitDirtyMarkAdvancesRevisionAfterSilentRawPixelEdit() {
        TCPCommandServer server = newServer();
        ByteProcessor pixels = new ByteProcessor(2, 2);
        ImagePlus image = new ImagePlus("edited", pixels);
        server.currentImageForTest = () -> image;
        try {
            JsonObject info = result(server, "{\"command\":\"get_image_info\"}");
            pixels.set(0, 0, 17);

            JsonObject request = parse("{\"command\":\"get_histogram\"}");
            request.addProperty("image_id", info.get("image_id").getAsString());
            request.addProperty("image_revision", info.get("image_revision").getAsLong());
            // ImageJ emits no event for a retained raw-array write. Such code
            // must call updateAndDraw() or explicitly mark the dataset dirty.
            JsonObject silent = server.dispatch(request, new TCPCommandServer.AgentCaps());
            assertTrue(silent.toString(), silent.get("ok").getAsBoolean());

            ImageRevisionTracker.getInstance().markContentChanged(image);
            JsonObject response = server.dispatch(request, new TCPCommandServer.AgentCaps());

            assertErrorCode(response, "image_snapshot_mismatch");
            JsonObject fresh = result(server, "{\"command\":\"get_image_info\"}");
            assertTrue(fresh.get("image_revision").getAsLong()
                    > info.get("image_revision").getAsLong());
        } finally {
            server.stop();
        }
    }

    @Test
    public void histogramReadsRequestedPlaneAndReportsItsIdentity() {
        TCPCommandServer server = newServer();
        ImagePlus image = hyperstack("histogram");
        image.setPosition(1, 1, 1);
        server.currentImageForTest = () -> image;
        try {
            JsonObject info = result(server, "{\"command\":\"get_image_info\"}");
            JsonObject request = parse("{\"command\":\"get_histogram\","
                    + "\"channel\":2,\"slice\":2,\"frame\":2}");
            request.addProperty("image_id", info.get("image_id").getAsString());
            request.addProperty("image_revision", info.get("image_revision").getAsLong());
            JsonObject response = server.dispatch(request, new TCPCommandServer.AgentCaps());

            assertTrue(response.toString(), response.get("ok").getAsBoolean());
            JsonObject histogram = response.getAsJsonObject("result");
            assertEquals(222.0, histogram.get("mean").getAsDouble(), 0.0);
            assertPlane(histogram, 2, 2, 2, 2, 2, 2);
            assertEquals(1, image.getC());
            assertEquals(1, image.getZ());
            assertEquals(1, image.getT());
        } finally {
            server.stop();
        }
    }

    @Test
    public void fullPlaneHistogramIgnoresActiveRoiAndCountsAcquisitionLimits() {
        TCPCommandServer server = newServer();
        ByteProcessor processor = new ByteProcessor(3, 2,
                new byte[] {0, 0, (byte) 255, 7, 8, 9}, null);
        ImagePlus image = new ImagePlus("limits", processor);
        image.setRoi(new Roi(0, 0, 2, 1));
        server.currentImageForTest = () -> image;
        try {
            JsonObject roi = result(server,
                    "{\"command\":\"get_histogram\"}");
            JsonObject full = result(server,
                    "{\"command\":\"get_histogram\",\"scope\":\"full_plane\"}");

            assertEquals("active_roi", roi.get("scope").getAsString());
            assertEquals(2L, roi.get("nPixels").getAsLong());
            assertEquals("full_plane", full.get("scope").getAsString());
            assertEquals(6L, full.get("nPixels").getAsLong());
            assertEquals(46.5, full.get("mean").getAsDouble(), 0.0);
            assertEquals(2L, full.get("acquisition_min_count").getAsLong());
            assertEquals(1L, full.get("acquisition_max_count").getAsLong());
            assertTrue(full.get("acquisition_limit_counts_exact").getAsBoolean());
            JsonObject domain = full.getAsJsonObject("value_domain");
            assertEquals("raw", domain.get("representation").getAsString());
            assertEquals("uint8", domain.get("pixel_type").getAsString());
            assertFalse(domain.get("signed").getAsBoolean());
            assertEquals(0.0, domain.get("acquisition_min_raw").getAsDouble(), 0.0);
            assertEquals(255.0, domain.get("acquisition_max_raw").getAsDouble(), 0.0);
        } finally {
            server.stop();
        }
    }

    @Test
    public void rgbHistogramPublishesScalarDomainAndExactPrimaryColorWeights() {
        TCPCommandServer server = newServer();
        ColorProcessor processor = new ColorProcessor(3, 1, new int[] {
                0x00ff0000, 0x0000ff00, 0x000000ff
        });
        ImagePlus image = new ImagePlus("rgb-primaries", processor);
        server.currentImageForTest = () -> image;
        double[] previousWeights = ColorProcessor.getWeightingFactors();
        ColorProcessor.setWeightingFactors(0.2, 0.3, 0.5);
        try {
            JsonObject pixels = result(server, "{\"command\":\"get_pixels\"}");
            assertEquals("rgb24", pixels.getAsJsonObject("value_domain")
                    .get("pixel_type").getAsString());

            JsonObject histogram = result(server,
                    "{\"command\":\"get_histogram\",\"scope\":\"full_plane\"}");
            JsonObject domain = histogram.getAsJsonObject("value_domain");
            assertEquals("raw", domain.get("representation").getAsString());
            assertEquals("uint8", domain.get("pixel_type").getAsString());
            assertFalse(domain.get("signed").getAsBoolean());
            assertEquals(0.0, domain.get("acquisition_min_raw").getAsDouble(), 0.0);
            assertEquals(255.0, domain.get("acquisition_max_raw").getAsDouble(), 0.0);

            JsonObject scalarization = domain.getAsJsonObject("scalarization");
            assertEquals("imagej_weighted_rgb_intensity",
                    scalarization.get("method").getAsString());
            assertEquals("rgb24",
                    scalarization.get("source_pixel_type").getAsString());
            assertEquals("nearest_integer_half_up",
                    scalarization.get("rounding").getAsString());
            JsonObject weights = scalarization.getAsJsonObject("weights");
            assertEquals(0.2, weights.get("red").getAsDouble(), 0.0);
            assertEquals(0.3, weights.get("green").getAsDouble(), 0.0);
            assertEquals(0.5, weights.get("blue").getAsDouble(), 0.0);

            JsonArray bins = histogram.getAsJsonArray("bins");
            assertEquals(256, bins.size());
            assertEquals("pure red must map to round(255 * 0.2)",
                    1, bins.get(51).getAsInt());
            assertEquals("pure green must map to round(255 * 0.3)",
                    1, bins.get(77).getAsInt());
            assertEquals("pure blue must map to round(255 * 0.5)",
                    1, bins.get(128).getAsInt());
            assertEquals(51.0, histogram.get("min").getAsDouble(), 0.0);
            assertEquals(128.0, histogram.get("max").getAsDouble(), 0.0);
            assertEquals((51.0 + 77.0 + 128.0) / 3.0,
                    histogram.get("mean").getAsDouble(), 1e-12);
        } finally {
            ColorProcessor.setWeightingFactors(
                    previousWeights[0], previousWeights[1], previousWeights[2]);
            server.stop();
        }
    }

    @Test
    public void rgbPixelsMaskSignedHighByteBeforeFloatTransport() {
        assertExactRgb24Pixel(0x80ffffff, 0x00ffffff);
    }

    @Test
    public void rgbPixelsMaskPositiveHighByteBeforeFloatTransport() {
        assertExactRgb24Pixel(0x7f123456, 0x00123456);
    }

    @Test
    public void rgbHistogramPreservesSafeProcessorLocalWeights() {
        TCPCommandServer server = newServer();
        ColorProcessor processor = new ColorProcessor(3, 1, new int[] {
                0x00ff0000, 0x0000ff00, 0x000000ff
        });
        ImagePlus image = new ImagePlus("rgb-local-weights", processor);
        // ImageJ consumes only the first three entries of a local array.
        ((ColorProcessor) image.getProcessor()).setRGBWeights(
                new double[] {0.6, 0.3, 0.1, Double.NEGATIVE_INFINITY});
        server.currentImageForTest = () -> image;
        double[] previousWeights = ColorProcessor.getWeightingFactors();
        // A safe processor-local override must win even if the unused global
        // configuration would be rejected.
        ColorProcessor.setWeightingFactors(2.0, 2.0, 2.0);
        try {
            JsonObject histogram = result(server,
                    "{\"command\":\"get_histogram\",\"scope\":\"full_plane\"}");
            JsonObject scalarization = histogram.getAsJsonObject("value_domain")
                    .getAsJsonObject("scalarization");
            JsonObject weights = scalarization.getAsJsonObject("weights");
            assertEquals(0.6, weights.get("red").getAsDouble(), 0.0);
            assertEquals(0.3, weights.get("green").getAsDouble(), 0.0);
            assertEquals(0.1, weights.get("blue").getAsDouble(), 0.0);
            JsonArray bins = histogram.getAsJsonArray("bins");
            assertEquals(1, bins.get(153).getAsInt());
            assertEquals(1, bins.get(77).getAsInt());
            assertEquals(1, bins.get(26).getAsInt());
        } finally {
            ColorProcessor.setWeightingFactors(
                    previousWeights[0], previousWeights[1], previousWeights[2]);
            server.stop();
        }
    }

    @Test
    public void rgbHistogramUsesLocalWeightsOnlyForLiveCurrentPlane() {
        TCPCommandServer server = newServer();
        ImageStack stack = new ImageStack(3, 1);
        stack.addSlice(new ColorProcessor(3, 1, new int[] {
                0x00ff0000, 0x0000ff00, 0x000000ff
        }));
        stack.addSlice(new ColorProcessor(3, 1, new int[] {
                0x00ff0000, 0x0000ff00, 0x000000ff
        }));
        ImagePlus image = new ImagePlus("rgb-plane-weights", stack);
        image.setDimensions(1, 2, 1);
        image.setPosition(1, 1, 1);
        ((ColorProcessor) image.getProcessor()).setRGBWeights(
                new double[] {0.6, 0.3, 0.1, Double.NEGATIVE_INFINITY});
        server.currentImageForTest = () -> image;
        double[] previousWeights = ColorProcessor.getWeightingFactors();
        ColorProcessor.setWeightingFactors(0.2, 0.3, 0.5);
        try {
            JsonObject nonCurrent = result(server,
                    "{\"command\":\"get_histogram\",\"slice\":2,"
                            + "\"scope\":\"full_plane\"}");
            assertRgbWeights(nonCurrent, 0.2, 0.3, 0.5);
            JsonArray nonCurrentBins = nonCurrent.getAsJsonArray("bins");
            assertEquals(1, nonCurrentBins.get(51).getAsInt());
            assertEquals(1, nonCurrentBins.get(77).getAsInt());
            assertEquals(1, nonCurrentBins.get(128).getAsInt());

            JsonObject current = result(server,
                    "{\"command\":\"get_histogram\",\"slice\":1,"
                            + "\"scope\":\"full_plane\"}");
            assertRgbWeights(current, 0.6, 0.3, 0.1);
            JsonArray currentBins = current.getAsJsonArray("bins");
            assertEquals(1, currentBins.get(153).getAsInt());
            assertEquals(1, currentBins.get(77).getAsInt());
            assertEquals(1, currentBins.get(26).getAsInt());

            assertEquals(1, image.getC());
            assertEquals(1, image.getZ());
            assertEquals(1, image.getT());
        } finally {
            ColorProcessor.setWeightingFactors(
                    previousWeights[0], previousWeights[1], previousWeights[2]);
            server.stop();
        }
    }

    @Test
    public void rgbHistogramEnforcesWeightNormalizationTolerance() {
        TCPCommandServer server = newServer();
        ImagePlus image = new ImagePlus("rgb-weight-tolerance",
                new ColorProcessor(1, 1, new int[] {0x00ffffff}));
        server.currentImageForTest = () -> image;
        try {
            ((ColorProcessor) image.getProcessor()).setRGBWeights(
                    new double[] {0.2, 0.3, 0.5000000005});
            JsonObject accepted = result(server,
                    "{\"command\":\"get_histogram\",\"scope\":\"full_plane\"}");
            assertRgbWeights(accepted, 0.2, 0.3, 0.5000000005);

            ((ColorProcessor) image.getProcessor()).setRGBWeights(
                    new double[] {0.2, 0.3, 0.500000002});
            assertUnsupportedRgbWeights(server.dispatch(
                    parse("{\"command\":\"get_histogram\"}"),
                    new TCPCommandServer.AgentCaps()));
        } finally {
            server.stop();
        }
    }

    @Test
    public void rgbHistogramRejectsUnsafeProcessorLocalWeightsStably() {
        TCPCommandServer server = newServer();
        ColorProcessor processor = new ColorProcessor(1, 1,
                new int[] {0x00ffffff});
        ImagePlus image = new ImagePlus("rgb-unsafe-local", processor);
        server.currentImageForTest = () -> image;
        double[][] unsafeWeights = new double[][] {
                {0.5, 0.5},
                {0.2, 0.3, 0.4},
                {-0.1, 0.6, 0.5},
                {Double.NaN, 0.5, 0.5},
                {Double.POSITIVE_INFINITY, 0.0, 0.0}
        };
        try {
            for (double[] weights : unsafeWeights) {
                ((ColorProcessor) image.getProcessor()).setRGBWeights(weights);
                assertUnsupportedRgbWeights(server.dispatch(
                        parse("{\"command\":\"get_histogram\"}"),
                        new TCPCommandServer.AgentCaps()));
            }
        } finally {
            server.stop();
        }
    }

    @Test
    public void rgbHistogramRejectsUnsafeGlobalWeightsStably() {
        TCPCommandServer server = newServer();
        ColorProcessor processor = new ColorProcessor(1, 1,
                new int[] {0x00ffffff});
        ImagePlus image = new ImagePlus("rgb-unsafe-global", processor);
        server.currentImageForTest = () -> image;
        double[] previousWeights = ColorProcessor.getWeightingFactors();
        try {
            ColorProcessor.setWeightingFactors(0.6, 0.3, 0.3);
            assertUnsupportedRgbWeights(server.dispatch(
                    parse("{\"command\":\"get_histogram\"}"),
                    new TCPCommandServer.AgentCaps()));
            ColorProcessor.setWeightingFactors(Double.NEGATIVE_INFINITY, 1.0, 0.0);
            assertUnsupportedRgbWeights(server.dispatch(
                    parse("{\"command\":\"get_histogram\"}"),
                    new TCPCommandServer.AgentCaps()));
        } finally {
            ColorProcessor.setWeightingFactors(
                    previousWeights[0], previousWeights[1], previousWeights[2]);
            server.stop();
        }
    }

    @Test
    public void calibratedUint16ReportsRawRepresentationAndCalibratedLimits() {
        TCPCommandServer server = newServer();
        ImagePlus image = new ImagePlus("calibrated",
                new ShortProcessor(2, 1, new short[] {0, (short) 65535}, null));
        Calibration calibration = image.getCalibration();
        calibration.setFunction(Calibration.STRAIGHT_LINE,
                new double[] {10.0, 2.0}, "intensity");
        server.currentImageForTest = () -> image;
        try {
            JsonObject histogram = result(server,
                    "{\"command\":\"get_histogram\",\"scope\":\"full_plane\"}");
            JsonObject domain = histogram.getAsJsonObject("value_domain");
            assertEquals("raw", domain.get("representation").getAsString());
            assertEquals("uint16", domain.get("pixel_type").getAsString());
            assertTrue(domain.get("density_calibrated").getAsBoolean());
            assertEquals(0.0, domain.get("acquisition_min_raw").getAsDouble(), 0.0);
            assertEquals(65535.0, domain.get("acquisition_max_raw").getAsDouble(), 0.0);
            assertEquals(10.0,
                    domain.get("acquisition_min_calibrated").getAsDouble(), 0.0);
            assertEquals(131080.0,
                    domain.get("acquisition_max_calibrated").getAsDouble(), 0.0);
            assertEquals(1L, histogram.get("acquisition_min_count").getAsLong());
            assertEquals(1L, histogram.get("acquisition_max_count").getAsLong());

            JsonObject pixels = result(server, "{\"command\":\"get_pixels\"}");
            assertEquals(1L, pixels.get("acquisition_min_count").getAsLong());
            assertEquals(1L, pixels.get("acquisition_max_count").getAsLong());
            assertTrue(pixels.get("acquisition_limit_counts_exact").getAsBoolean());
        } finally {
            server.stop();
        }
    }

    @Test
    public void nonlinearCalibrationReportsExactInteriorAcquisitionExtremum() {
        TCPCommandServer server = newServer();
        ImagePlus image = new ImagePlus("nonlinear-calibration",
                new ByteProcessor(1, 1));
        Calibration calibration = image.getCalibration();
        // (raw - 127.5)^2: both raw endpoints map to 16256.25, while the
        // exact minimum over integer acquisition codes is 0.25 at 127/128.
        calibration.setFunction(Calibration.POLY2,
                new double[] {16256.25, -255.0, 1.0}, "intensity");
        server.currentImageForTest = () -> image;
        try {
            JsonObject histogram = result(server,
                    "{\"command\":\"get_histogram\",\"scope\":\"full_plane\"}");
            JsonObject domain = histogram.getAsJsonObject("value_domain");

            assertEquals(0.0, domain.get("acquisition_min_raw").getAsDouble(), 0.0);
            assertEquals(255.0, domain.get("acquisition_max_raw").getAsDouble(), 0.0);
            assertEquals(0.25,
                    domain.get("acquisition_min_calibrated").getAsDouble(), 0.0);
            assertEquals(16256.25,
                    domain.get("acquisition_max_calibrated").getAsDouble(), 0.0);
        } finally {
            server.stop();
        }
    }

    @Test
    public void nonfiniteCalibrationMakesBothAcquisitionBoundsUnavailable() {
        TCPCommandServer server = newServer();
        ImagePlus image = new ImagePlus("nonfinite-calibration",
                new ByteProcessor(1, 1));
        Calibration calibration = image.getCalibration();
        // raw=0 is finite, but raw=1 overflows. A finite subset must not be
        // advertised as bounds for the complete calibrated acquisition domain.
        calibration.setFunction(Calibration.POLY2,
                new double[] {0.0, Double.MAX_VALUE, Double.MAX_VALUE},
                "intensity");
        server.currentImageForTest = () -> image;
        try {
            JsonObject histogram = result(server,
                    "{\"command\":\"get_histogram\",\"scope\":\"full_plane\"}");
            JsonObject domain = histogram.getAsJsonObject("value_domain");

            assertEquals(0.0, domain.get("acquisition_min_raw").getAsDouble(), 0.0);
            assertEquals(255.0, domain.get("acquisition_max_raw").getAsDouble(), 0.0);
            assertTrue(domain.get("acquisition_min_calibrated").isJsonNull());
            assertTrue(domain.get("acquisition_max_calibrated").isJsonNull());
        } finally {
            server.stop();
        }
    }

    @Test
    public void strictBindingsRejectPartialBooleanAndFractionalNumbers() {
        TCPCommandServer server = newServer();
        ImagePlus image = new ImagePlus("strict", new ByteProcessor(2, 2));
        server.currentImageForTest = () -> image;
        try {
            JsonObject info = result(server, "{\"command\":\"get_image_info\"}");
            String id = info.get("image_id").getAsString();

            assertErrorCode(server.dispatch(parse("{\"command\":\"get_pixels\","
                    + "\"image_id\":\"" + id + "\"}"),
                    new TCPCommandServer.AgentCaps()), "invalid_image_snapshot");
            assertErrorCode(server.dispatch(parse("{\"command\":\"get_pixels\","
                    + "\"image_id\":\"" + id + "\",\"image_revision\":true}"),
                    new TCPCommandServer.AgentCaps()), "invalid_image_snapshot");
            assertErrorCode(server.dispatch(parse("{\"command\":\"get_pixels\","
                    + "\"image_id\":\"" + id + "\",\"image_revision\":1.0}"),
                    new TCPCommandServer.AgentCaps()), "invalid_image_snapshot");
            assertErrorCode(server.dispatch(parse(
                    "{\"command\":\"get_pixels\",\"x\":true}"),
                    new TCPCommandServer.AgentCaps()), "invalid_image_plane");
            assertErrorCode(server.dispatch(parse(
                    "{\"command\":\"get_histogram\",\"slice\":1.5}"),
                    new TCPCommandServer.AgentCaps()), "invalid_image_plane");
        } finally {
            server.stop();
        }
    }

    @Test
    public void datasetRevisionRecheckFailsReadChangedDuringExtraction() {
        TCPCommandServer server = newServer();
        ByteProcessor processor = new ByteProcessor(2, 2);
        ImagePlus image = new ImagePlus("concurrent-edit", processor);
        server.currentImageForTest = () -> image;
        server.pixelExtractionStartedForTest = () ->
                ImageRevisionTracker.getInstance().markContentChanged(image);
        try {
            JsonObject response = server.dispatch(parse(
                    "{\"command\":\"get_pixels\"}"),
                    new TCPCommandServer.AgentCaps());
            assertErrorCode(response, "image_snapshot_mismatch");
        } finally {
            server.stop();
        }
    }

    @Test
    public void displayStateBindsRoiAndOverlayToSameSnapshot() {
        TCPCommandServer server = newServer();
        ImagePlus image = new ImagePlus("annotated", new ByteProcessor(4, 4));
        image.setRoi(new Roi(0, 0, 2, 3));
        Overlay overlay = new Overlay();
        overlay.add(new Roi(1, 1, 1, 1));
        image.setOverlay(overlay);
        server.currentImageForTest = () -> image;
        try {
            JsonObject info = result(server, "{\"command\":\"get_image_info\"}");
            JsonObject request = parse("{\"command\":\"get_display_state\"}");
            request.addProperty("image_id", info.get("image_id").getAsString());
            request.addProperty("image_revision", info.get("image_revision").getAsLong());
            request.addProperty("display_revision", info.get("display_revision").getAsLong());
            JsonObject response = server.dispatch(request, new TCPCommandServer.AgentCaps());

            assertTrue(response.toString(), response.get("ok").getAsBoolean());
            JsonObject display = response.getAsJsonObject("result");
            assertEquals(info.get("image_id").getAsString(),
                    display.get("image_id").getAsString());
            assertEquals(info.get("image_revision").getAsLong(),
                    display.get("image_revision").getAsLong());
            assertTrue(display.get("hasRoi").getAsBoolean());
            assertEquals(2, display.get("roiWidth").getAsInt());
            assertEquals(3, display.get("roiHeight").getAsInt());
            assertTrue(display.get("hasOverlay").getAsBoolean());
            assertEquals(1, display.get("overlaySize").getAsInt());
        } finally {
            server.stop();
        }
    }

    @Test
    public void displayStateRejectsChangedAnnotationsAndExpectedPlane() {
        TCPCommandServer server = newServer();
        ImagePlus image = hyperstack("display-binding");
        image.setPosition(1, 1, 1);
        server.currentImageForTest = () -> image;
        try {
            JsonObject info = result(server, "{\"command\":\"get_image_info\"}");
            JsonObject wrongPlane = parse("{\"command\":\"get_display_state\","
                    + "\"channel\":2}");
            wrongPlane.addProperty("image_id", info.get("image_id").getAsString());
            wrongPlane.addProperty("image_revision", info.get("image_revision").getAsLong());
            wrongPlane.addProperty("display_revision", info.get("display_revision").getAsLong());
            assertErrorCode(server.dispatch(wrongPlane,
                    new TCPCommandServer.AgentCaps()), "image_snapshot_mismatch");

            image.setRoi(new Roi(0, 0, 1, 1));
            JsonObject staleDisplay = parse("{\"command\":\"get_display_state\"}");
            staleDisplay.addProperty("image_id", info.get("image_id").getAsString());
            staleDisplay.addProperty("image_revision", info.get("image_revision").getAsLong());
            staleDisplay.addProperty("display_revision", info.get("display_revision").getAsLong());
            assertErrorCode(server.dispatch(staleDisplay,
                    new TCPCommandServer.AgentCaps()), "image_snapshot_mismatch");
        } finally {
            server.stop();
        }
    }

    private static TCPCommandServer newServer() {
        return new TCPCommandServer(0, null, null, null, null);
    }

    private static void assertExactRgb24Pixel(int storedPixel, int expectedRgb24) {
        TCPCommandServer server = newServer();
        ImagePlus image = new ImagePlus("rgb24-transport",
                new ColorProcessor(1, 1, new int[] {storedPixel}));
        server.currentImageForTest = () -> image;
        try {
            JsonObject pixels = result(server, "{\"command\":\"get_pixels\"}");
            JsonObject domain = pixels.getAsJsonObject("value_domain");
            assertEquals("raw", domain.get("representation").getAsString());
            assertEquals("rgb24", domain.get("pixel_type").getAsString());
            assertTrue(domain.get("signed").isJsonNull());

            byte[] bytes = Base64.getDecoder().decode(pixels.get("data").getAsString());
            assertEquals(4, bytes.length);
            float transported = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    .getFloat();
            assertEquals(expectedRgb24, (int) transported);
            assertEquals((float) expectedRgb24, transported, 0.0f);
        } finally {
            server.stop();
        }
    }

    private static JsonObject result(TCPCommandServer server, String json) {
        JsonObject response = server.dispatch(parse(json),
                new TCPCommandServer.AgentCaps());
        assertTrue(response.toString(), response.get("ok").getAsBoolean());
        return response.getAsJsonObject("result");
    }

    private static void assertErrorCode(JsonObject response, String expected) {
        assertFalse(response.toString(), response.get("ok").getAsBoolean());
        assertEquals(expected, response.getAsJsonObject("error")
                .get("code").getAsString());
    }

    private static void assertUnsupportedRgbWeights(JsonObject response) {
        assertErrorCode(response, "unsupported_rgb_weights");
        JsonObject error = response.getAsJsonObject("error");
        assertEquals("RGB histogram weights must be finite, non-negative, and sum to 1.",
                error.get("message").getAsString());
        assertEquals("state", error.get("category").getAsString());
        assertFalse(error.get("retry_safe").getAsBoolean());
        assertEquals("Restore the first three ImageJ RGB weights to finite, non-negative "
                        + "values that sum to 1, then retry.",
                error.get("recovery_hint").getAsString());
    }

    private static void assertRgbWeights(JsonObject histogram,
                                         double red, double green, double blue) {
        JsonObject weights = histogram.getAsJsonObject("value_domain")
                .getAsJsonObject("scalarization").getAsJsonObject("weights");
        assertEquals(red, weights.get("red").getAsDouble(), 0.0);
        assertEquals(green, weights.get("green").getAsDouble(), 0.0);
        assertEquals(blue, weights.get("blue").getAsDouble(), 0.0);
    }

    private static void assertPlane(JsonObject result, int channel,
                                    int sliceStart, int sliceEnd, int frame,
                                    int channels, int frames) {
        assertEquals(channel, result.get("channel").getAsInt());
        assertEquals(sliceStart, result.get("sliceStart").getAsInt());
        assertEquals(sliceEnd, result.get("sliceEnd").getAsInt());
        assertEquals("Z", result.get("sliceAxis").getAsString());
        assertEquals(frame, result.get("frame").getAsInt());
        assertEquals(channels, result.get("channels").getAsInt());
        assertEquals(2, result.get("slices").getAsInt());
        assertEquals(frames, result.get("frames").getAsInt());
    }

    private static ImagePlus hyperstack(String title) {
        ImageStack stack = new ImageStack(1, 1);
        for (int t = 1; t <= 2; t++) {
            for (int z = 1; z <= 2; z++) {
                for (int c = 1; c <= 2; c++) {
                    stack.addSlice(new FloatProcessor(1, 1,
                            new float[] {100 * t + 10 * z + c}, null));
                }
            }
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(2, 2, 2);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static JsonObject parse(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }
}
