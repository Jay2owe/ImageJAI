package imagejai.engine;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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

    // The disk-write heuristic moved to {@link DestructiveScanner#hasDiskWrites}
    // in Stage 05 (docs/safe_mode_v2/05_destructive-scanner-expansion.md);
    // its tests live in {@code DestructiveScannerTest} now.

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
