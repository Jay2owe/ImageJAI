package imagejai.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import org.junit.After;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure-data unit tests for {@link UndoFrame}. Covers the byte codecs and the
 * disk-side-effect heuristic — the live-ImagePlus path
 * ({@link UndoFrame#capture}, {@link UndoFrame#restorePixels}) needs Fiji and
 * is exercised by the integration tests when a real Fiji session is wired up.
 */
public class UndoFrameTest {

    @After
    public void clearImageJGlobals() {
        WindowState.clear();
    }

    @Test
    public void deflateThenInflateRoundTripsBytes() {
        byte[] raw = new byte[1024];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) (i & 0xff);
        byte[] compressed = UndoFrame.deflate(raw);
        assertTrue("compressed shorter than raw", compressed.length < raw.length);
        byte[] back = UndoFrame.inflate(compressed, raw.length);
        assertArrayEquals(raw, back);
    }

    @Test
    public void deflateEmptyReturnsEmpty() {
        assertEquals(0, UndoFrame.deflate(null).length);
        assertEquals(0, UndoFrame.deflate(new byte[0]).length);
    }

    @Test
    public void macroHasDiskWritesDetectsCommonForms() {
        assertTrue(UndoFrame.macroHasDiskWrites("saveAs(\"Tiff\", \"/tmp/x.tif\");"));
        assertTrue(UndoFrame.macroHasDiskWrites("IJ.save(imp, \"/tmp/x.tif\");"));
        assertTrue(UndoFrame.macroHasDiskWrites("File.copy(a, b);"));
        assertTrue(UndoFrame.macroHasDiskWrites("saveTable(\"results.csv\");"));
        assertTrue(UndoFrame.macroHasDiskWrites("run(\"Save\")"));
    }

    @Test
    public void macroHasDiskWritesReturnsFalseForReadOnlyMacros() {
        assertFalse(UndoFrame.macroHasDiskWrites(null));
        assertFalse(UndoFrame.macroHasDiskWrites(""));
        assertFalse(UndoFrame.macroHasDiskWrites(
                "run(\"Gaussian Blur...\", \"sigma=2\");"));
        assertFalse(UndoFrame.macroHasDiskWrites(
                "setAutoThreshold(\"Otsu\");\nrun(\"Convert to Mask\");"));
    }

    @Test
    public void macroHasDiskWritesDetectsFileWrite() {
        assertTrue(UndoFrame.macroHasDiskWrites(
                "f = File.open(\"out.txt\");\nFile.write(\"hi\", f);"));
    }

    @Test
    public void boundaryFactoryProducesScriptBoundaryFrame() {
        UndoFrame f = UndoFrame.boundary("c-x", "img.tif");
        assertTrue(f.scriptBoundary);
        assertEquals("c-x", f.callId);
        assertEquals("img.tif", f.imageTitle);
        // Boundary carries no pixels.
        assertEquals(0, f.compressedPixels.length);
    }

    @Test
    public void sizeBytesIncludesPixelsAndCsvLengths() {
        byte[] compressed = new byte[100];
        String csv = "header\n1\n2\n";
        UndoFrame f = new UndoFrame(
                "c-1", "img.tif",
                10, 10, 1, 1, 1, 8,
                compressed, 100,
                Collections.<UndoFrame.RoiSnapshot>emptyList(),
                csv,
                System.currentTimeMillis(),
                false);
        // sizeBytes must equal compressed pixel length + csv length when
        // there are no ROIs. RoIs add their own overhead beyond this.
        assertEquals(compressed.length + csv.length(), f.sizeBytes);
    }

    @Test
    public void restoresEveryHyperstackPlaneCalibrationExactRoisAndResults() {
        ImagePlus image = hyperstack("hyper", 2, 3, 1);
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.42;
        calibration.pixelHeight = 0.43;
        calibration.pixelDepth = 1.7;
        calibration.setUnit("micron");
        image.setCalibration(calibration);

        RoiManager manager = new RoiManager(true);
        PolygonRoi polygon = new PolygonRoi(
                new int[]{0, 1, 1}, new int[]{0, 0, 1}, 3, Roi.POLYGON);
        polygon.setName("triangle");
        manager.addRoi(polygon);
        ResultsTable results = new ResultsTable();
        results.incrementCounter();
        results.addLabel("source-row");
        results.addValue("Area", 12.5);
        Analyzer.setResultsTable(results);

        byte[][] expected = planes(image);
        UndoFrame frame = UndoFrame.capture("c-hyper", image, manager,
                results.toString(), false);

        for (int plane = 1; plane <= image.getStackSize(); plane++) {
            byte[] pixels = (byte[]) image.getStack().getPixels(plane);
            java.util.Arrays.fill(pixels, (byte) 99);
        }
        image.getCalibration().pixelWidth = 9.0;
        manager.reset();
        manager.addRoi(new Roi(0, 0, 1, 1));
        ResultsTable changed = new ResultsTable();
        changed.incrementCounter();
        changed.addValue("Area", 999.0);
        Analyzer.setResultsTable(changed);

        UndoFrame.RestorePlan plan = frame.prepareRestore(image);
        assertEquals(6, plan.applyPixelsAndCalibration());
        assertEquals(1, plan.applySideState());

        for (int plane = 1; plane <= image.getStackSize(); plane++) {
            assertArrayEquals(expected[plane - 1],
                    (byte[]) image.getStack().getPixels(plane));
        }
        assertEquals(2, image.getNChannels());
        assertEquals(3, image.getNSlices());
        assertEquals(1, image.getNFrames());
        assertEquals(0.42, image.getCalibration().pixelWidth, 0.0);
        assertEquals(0.43, image.getCalibration().pixelHeight, 0.0);
        assertEquals(1.7, image.getCalibration().pixelDepth, 0.0);
        assertEquals("micron", image.getCalibration().getUnit());
        assertEquals(1, manager.getCount());
        assertTrue(manager.getRoi(0) instanceof PolygonRoi);
        assertEquals("triangle", manager.getName(0));
        assertEquals(1, Analyzer.getResultsTable().getCounter());
        assertEquals("source-row", Analyzer.getResultsTable().getLabel(0));
        assertEquals(12.5, Analyzer.getResultsTable().getValue("Area", 0), 0.0);
    }

    @Test
    public void replacementSameTitleAndShapeFailsIdentityValidation() {
        ImagePlus captured = hyperstack("same", 1, 1, 1);
        UndoFrame frame = UndoFrame.capture("c", captured, null, "", false);
        ImagePlus replacement = hyperstack("same", 1, 1, 1);
        expectRestoreFailure(frame, replacement, "identity");
    }

    @Test
    public void sameSizeDifferentTypeFailsBeforeWriting() {
        ImagePlus captured = hyperstack("typed", 1, 1, 1);
        UndoFrame frame = UndoFrame.capture("c", captured, null, "", false);
        ImageStack shorts = new ImageStack(2, 2);
        shorts.addSlice(new ShortProcessor(2, 2, new short[]{7, 7, 7, 7}, null));
        ImagePlus different = new ImagePlus("typed", shorts);
        // Use a legacy synthetic frame to isolate type validation from ID validation.
        UndoFrame legacy = new UndoFrame("c", "typed", 2, 2, 1, 1, 1, 8,
                frame.compressedPixels, frame.uncompressedSize,
                Collections.<UndoFrame.RoiSnapshot>emptyList(), "",
                System.currentTimeMillis(), false);
        expectRestoreFailure(legacy, different, "type");
        assertArrayEquals(new short[]{7, 7, 7, 7},
                (short[]) different.getStack().getPixels(1));
    }

    @Test
    public void hyperstackDimensionMismatchFailsBeforeWriting() {
        ImagePlus image = hyperstack("dims", 2, 3, 1);
        UndoFrame frame = UndoFrame.capture("c", image, null, "", false);
        image.setDimensions(1, 6, 1);
        byte first = ((byte[]) image.getStack().getPixels(1))[0];
        expectRestoreFailure(frame, image, "dimensions");
        assertEquals(first, ((byte[]) image.getStack().getPixels(1))[0]);
    }

    private static ImagePlus hyperstack(String title, int channels, int slices, int frames) {
        ImageStack stack = new ImageStack(2, 2);
        int planes = channels * slices * frames;
        for (int plane = 0; plane < planes; plane++) {
            byte base = (byte) (plane * 10);
            stack.addSlice(new ByteProcessor(2, 2,
                    new byte[]{base, (byte) (base + 1), (byte) (base + 2),
                            (byte) (base + 3)}, null));
        }
        ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(channels, slices, frames);
        image.setOpenAsHyperStack(planes > 1);
        return image;
    }

    private static byte[][] planes(ImagePlus image) {
        byte[][] copy = new byte[image.getStackSize()][];
        for (int plane = 1; plane <= image.getStackSize(); plane++) {
            copy[plane - 1] = ((byte[]) image.getStack().getPixels(plane)).clone();
        }
        return copy;
    }

    private static void expectRestoreFailure(UndoFrame frame, ImagePlus target,
                                             String messagePart) {
        try {
            frame.prepareRestore(target);
            fail("Expected restore validation failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains(messagePart));
        }
    }

    private static final class WindowState {
        static void clear() {
            ij.WindowManager.setTempCurrentImage(null);
            Analyzer.setResultsTable(null);
            RoiManager manager = RoiManager.getRawInstance();
            if (manager != null) {
                manager.reset();
                manager.close();
            }
        }
    }
}
