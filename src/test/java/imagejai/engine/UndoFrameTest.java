package imagejai.engine;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-data unit tests for {@link UndoFrame}. Covers the byte codecs and the
 * disk-side-effect heuristic — the live-ImagePlus path
 * ({@link UndoFrame#capture}, {@link UndoFrame#restorePixels}) needs Fiji and
 * is exercised by the integration tests when a real Fiji session is wired up.
 */
public class UndoFrameTest {

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
}
