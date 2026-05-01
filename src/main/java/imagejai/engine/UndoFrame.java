package imagejai.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Step 15 (docs/tcp_upgrade/15_undo_stack_api.md): one captured snapshot of an
 * image plus its side-state (ROI Manager contents and Results table CSV).
 * Pixels are deflate-compressed at level 3 — a stand-in for the plan's zstd
 * recommendation, since the JDK ships zlib but not zstd, and the ratio /
 * latency are close enough at this image size class that a hard dep on a
 * native zstd binding is not worth it for v1.
 *
 * <p>Frames are immutable once captured. {@link #restorePixels(ImagePlus)}
 * writes the decompressed bytes back into a target ImagePlus whose geometry
 * matches the frame; mismatches throw IllegalArgumentException so callers
 * never silently corrupt an unrelated image.
 *
 * <p>This class is split into a pure-data layer (constructor, byte-array
 * codecs, sizeBytes accounting) that is fully unit-testable headless, and a
 * Fiji-touching layer ({@link #capture}, {@link #restorePixels}) that needs a
 * live ImagePlus.
 */
public final class UndoFrame {

    /** Captured ROI: name, bounding box, and a stable type token. v1 stores
     *  enough to label the ROI and reapply rectangular bounds; non-rectangular
     *  geometry restores conservatively (the bounding box). Pixel-precise Roi
     *  serialization via {@code ij.io.RoiEncoder} is left as a future
     *  refinement once transcripts show the bounding-box fidelity is the
     *  bottleneck. */
    public static final class RoiSnapshot {
        public final String name;
        public final String roiClass;
        public final int x, y, w, h;
        RoiSnapshot(String name, String roiClass, int x, int y, int w, int h) {
            this.name = name;
            this.roiClass = roiClass;
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    public final String callId;
    public final String imageTitle;
    public final int width;
    public final int height;
    public final int slices;
    public final int channels;
    public final int frames;
    public final int bitDepth;
    /** Deflate-compressed pixel block: concatenation of all slice arrays. */
    public final byte[] compressedPixels;
    public final long uncompressedSize;
    public final List<RoiSnapshot> rois;
    public final String resultsCsv;
    public final long timestampMs;
    public final long sizeBytes;
    /** True when the producing macro contains a disk-write call (saveAs etc.).
     *  Surfaced on rewind so the agent knows the file write isn't reversed. */
    public final boolean diskSideEffect;
    /** Sentinel marker pushed by run_script / run_pipeline. The plan
     *  (§Out-of-scope) treats script execution as a branch boundary —
     *  rewinding past one is disallowed because Groovy/Jython can do
     *  arbitrary side effects we cannot reverse. The rewind handler refuses
     *  any walk that would cross a frame whose scriptBoundary == true. */
    public final boolean scriptBoundary;

    public UndoFrame(String callId, String imageTitle,
                    int width, int height, int slices, int channels, int frames,
                    int bitDepth, byte[] compressedPixels, long uncompressedSize,
                    List<RoiSnapshot> rois, String resultsCsv,
                    long timestampMs, boolean diskSideEffect) {
        this(callId, imageTitle, width, height, slices, channels, frames,
                bitDepth, compressedPixels, uncompressedSize, rois, resultsCsv,
                timestampMs, diskSideEffect, false);
    }

    public UndoFrame(String callId, String imageTitle,
                    int width, int height, int slices, int channels, int frames,
                    int bitDepth, byte[] compressedPixels, long uncompressedSize,
                    List<RoiSnapshot> rois, String resultsCsv,
                    long timestampMs, boolean diskSideEffect,
                    boolean scriptBoundary) {
        this.callId = callId;
        this.imageTitle = imageTitle;
        this.width = width;
        this.height = height;
        this.slices = slices;
        this.channels = channels;
        this.frames = frames;
        this.bitDepth = bitDepth;
        this.compressedPixels = compressedPixels != null ? compressedPixels : new byte[0];
        this.uncompressedSize = uncompressedSize;
        this.rois = (rois != null)
                ? Collections.unmodifiableList(new ArrayList<RoiSnapshot>(rois))
                : Collections.<RoiSnapshot>emptyList();
        this.resultsCsv = resultsCsv != null ? resultsCsv : "";
        this.timestampMs = timestampMs;
        this.diskSideEffect = diskSideEffect;
        this.scriptBoundary = scriptBoundary;
        long bytes = this.compressedPixels.length;
        bytes += this.resultsCsv.length();
        for (RoiSnapshot r : this.rois) {
            if (r.name != null) bytes += r.name.length();
            if (r.roiClass != null) bytes += r.roiClass.length();
            bytes += 16; // bbox ints
        }
        this.sizeBytes = bytes;
    }

    /** Build a zero-data sentinel for run_script / run_pipeline. The frame
     *  carries no pixels and no side state — its sole job is to sit on the
     *  per-image stack as a "do not cross" marker. The rewind handler scans
     *  the popping range for one of these and refuses with
     *  UNDO_SCRIPT_BOUNDARY rather than restoring across an uninvertible
     *  script execution. */
    public static UndoFrame boundary(String callId, String imageTitle) {
        return new UndoFrame(callId, imageTitle,
                0, 0, 0, 0, 0, 0,
                new byte[0], 0L,
                Collections.<RoiSnapshot>emptyList(),
                "",
                System.currentTimeMillis(), false, true);
    }

    /** Capture a frame from the live ImageJ state. Returns null when imp is
     *  null. {@code rm} and {@code resultsCsv} may be null — both are
     *  optional side-state that the rewind path will simply not restore if
     *  absent. */
    public static UndoFrame capture(String callId, ImagePlus imp,
                                    RoiManager rm, String resultsCsv,
                                    boolean diskSideEffect) {
        if (imp == null) return null;
        ImageStack stk = imp.getStack();
        int n = (stk != null && stk.getSize() > 0) ? stk.getSize() : 1;
        byte[] raw = serialiseStackPixels(imp, stk, n);
        byte[] compressed = deflate(raw);
        List<RoiSnapshot> rois = snapshotRois(rm);
        return new UndoFrame(
                callId,
                imp.getTitle(),
                imp.getWidth(), imp.getHeight(), n,
                imp.getNChannels(), imp.getNFrames(),
                imp.getBitDepth(),
                compressed, raw.length,
                rois,
                resultsCsv == null ? "" : resultsCsv,
                System.currentTimeMillis(),
                diskSideEffect);
    }

    private static byte[] serialiseStackPixels(ImagePlus imp, ImageStack stk, int n) {
        int bd = imp.getBitDepth();
        // RGB images store 4 bytes/px (int[]); other depths follow the natural
        // ceiling of (bitDepth + 7) / 8. 32-bit float also uses 4 bytes.
        int bytesPerPx = (bd == 24) ? 4 : (bd + 7) / 8;
        if (bd == 32) bytesPerPx = 4;
        int sliceBytes = imp.getWidth() * imp.getHeight() * bytesPerPx;
        byte[] buf = new byte[sliceBytes * Math.max(1, n)];
        for (int s = 0; s < n; s++) {
            Object pix = (stk != null && stk.getSize() > 0)
                    ? stk.getPixels(s + 1)
                    : imp.getProcessor().getPixels();
            writePixels(pix, buf, s * sliceBytes);
        }
        return buf;
    }

    private static void writePixels(Object pix, byte[] dest, int offset) {
        if (pix instanceof byte[]) {
            byte[] s = (byte[]) pix;
            System.arraycopy(s, 0, dest, offset, s.length);
        } else if (pix instanceof short[]) {
            short[] s = (short[]) pix;
            for (int i = 0; i < s.length; i++) {
                dest[offset + i * 2]     = (byte) (s[i] & 0xff);
                dest[offset + i * 2 + 1] = (byte) ((s[i] >> 8) & 0xff);
            }
        } else if (pix instanceof int[]) {
            int[] r = (int[]) pix;
            for (int i = 0; i < r.length; i++) {
                int v = r[i];
                dest[offset + i * 4]     = (byte) (v & 0xff);
                dest[offset + i * 4 + 1] = (byte) ((v >> 8) & 0xff);
                dest[offset + i * 4 + 2] = (byte) ((v >> 16) & 0xff);
                dest[offset + i * 4 + 3] = (byte) ((v >> 24) & 0xff);
            }
        } else if (pix instanceof float[]) {
            float[] f = (float[]) pix;
            for (int i = 0; i < f.length; i++) {
                int bits = Float.floatToRawIntBits(f[i]);
                dest[offset + i * 4]     = (byte) (bits & 0xff);
                dest[offset + i * 4 + 1] = (byte) ((bits >> 8) & 0xff);
                dest[offset + i * 4 + 2] = (byte) ((bits >> 16) & 0xff);
                dest[offset + i * 4 + 3] = (byte) ((bits >> 24) & 0xff);
            }
        }
    }

    private static List<RoiSnapshot> snapshotRois(RoiManager rm) {
        List<RoiSnapshot> out = new ArrayList<RoiSnapshot>();
        if (rm == null) return out;
        try {
            Roi[] all = rm.getRoisAsArray();
            String[] names = (rm.getList() != null) ? rm.getList().getItems() : null;
            int count = (all != null) ? all.length : 0;
            for (int i = 0; i < count; i++) {
                Roi r = all[i];
                if (r == null) continue;
                String name = (names != null && i < names.length && names[i] != null)
                        ? names[i] : ("roi-" + i);
                java.awt.Rectangle b = r.getBounds();
                if (b == null) b = new java.awt.Rectangle(0, 0, 0, 0);
                out.add(new RoiSnapshot(name, r.getClass().getName(),
                        b.x, b.y, b.width, b.height));
            }
        } catch (Throwable ignore) {
            // RoiManager APIs vary across IJ versions — best-effort capture.
        }
        return out;
    }

    /** Deflate a byte buffer at level 3. Public so unit tests can round-trip
     *  without going through {@link #capture}. */
    public static byte[] deflate(byte[] raw) {
        if (raw == null || raw.length == 0) return new byte[0];
        Deflater def = new Deflater(3);
        try {
            def.setInput(raw);
            def.finish();
            byte[] buf = new byte[Math.max(64, raw.length / 4)];
            int written = 0;
            while (!def.finished()) {
                if (written >= buf.length - 64) {
                    byte[] grown = new byte[Math.max(buf.length * 2, written + 1024)];
                    System.arraycopy(buf, 0, grown, 0, written);
                    buf = grown;
                }
                written += def.deflate(buf, written, buf.length - written);
            }
            byte[] out = new byte[written];
            System.arraycopy(buf, 0, out, 0, written);
            return out;
        } finally {
            def.end();
        }
    }

    /** Inverse of {@link #deflate}; expectedSize is the original
     *  uncompressed length so we can pre-size the output buffer. */
    public static byte[] inflate(byte[] compressed, long expectedSize) {
        if (compressed == null || compressed.length == 0) return new byte[0];
        if (expectedSize <= 0 || expectedSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "UndoFrame inflate: invalid expectedSize " + expectedSize);
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(compressed);
            byte[] out = new byte[(int) expectedSize];
            int written = 0;
            while (written < out.length && !inf.finished()) {
                int n = inf.inflate(out, written, out.length - written);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                    break;
                }
                written += n;
            }
            return out;
        } catch (DataFormatException e) {
            throw new RuntimeException("UndoFrame inflate failed: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
    }

    /** Restore this frame's pixels into the supplied target. Throws
     *  IllegalArgumentException when geometry doesn't match — callers must
     *  resolve the right ImagePlus first (typically via title lookup against
     *  WindowManager). Returns the number of slices written. */
    public int restorePixels(ImagePlus target) {
        if (target == null) return 0;
        if (target.getWidth() != width || target.getHeight() != height) {
            throw new IllegalArgumentException(
                    "UndoFrame restore: geometry mismatch (frame "
                  + width + "x" + height + ", target "
                  + target.getWidth() + "x" + target.getHeight() + ")");
        }
        byte[] raw = inflate(compressedPixels, uncompressedSize);
        ImageStack stk = target.getStack();
        int targetN = (stk != null && stk.getSize() > 0) ? stk.getSize() : 1;
        int sliceLen = (slices > 0) ? (raw.length / slices) : raw.length;
        int restored = 0;
        for (int s = 0; s < Math.min(slices, targetN); s++) {
            Object dst = (stk != null && stk.getSize() > 0)
                    ? stk.getPixels(s + 1)
                    : target.getProcessor().getPixels();
            readPixelsInto(raw, s * sliceLen, dst);
            restored++;
        }
        try {
            target.updateAndDraw();
        } catch (Throwable ignore) {
            // updateAndDraw can NPE in headless tests; the byte-write above
            // is the actual restore — drawing is just a UI refresh.
        }
        return restored;
    }

    private static void readPixelsInto(byte[] src, int offset, Object dest) {
        if (dest instanceof byte[]) {
            byte[] d = (byte[]) dest;
            System.arraycopy(src, offset, d, 0, Math.min(d.length, src.length - offset));
        } else if (dest instanceof short[]) {
            short[] d = (short[]) dest;
            for (int i = 0; i < d.length; i++) {
                int lo = src[offset + i * 2] & 0xff;
                int hi = src[offset + i * 2 + 1] & 0xff;
                d[i] = (short) ((hi << 8) | lo);
            }
        } else if (dest instanceof int[]) {
            int[] d = (int[]) dest;
            for (int i = 0; i < d.length; i++) {
                d[i] = (src[offset + i * 4] & 0xff)
                     | ((src[offset + i * 4 + 1] & 0xff) << 8)
                     | ((src[offset + i * 4 + 2] & 0xff) << 16)
                     | ((src[offset + i * 4 + 3] & 0xff) << 24);
            }
        } else if (dest instanceof float[]) {
            float[] d = (float[]) dest;
            for (int i = 0; i < d.length; i++) {
                int bits = (src[offset + i * 4] & 0xff)
                         | ((src[offset + i * 4 + 1] & 0xff) << 8)
                         | ((src[offset + i * 4 + 2] & 0xff) << 16)
                         | ((src[offset + i * 4 + 3] & 0xff) << 24);
                d[i] = Float.intBitsToFloat(bits);
            }
        }
    }

    /** Heuristic: does the macro source contain a disk-write call? Used to
     *  attach a {@code diskSideEffectWarning} to rewind responses so the agent
     *  knows the file write was not reversed. */
    public static boolean macroHasDiskWrites(String macro) {
        if (macro == null) return false;
        String m = macro.toLowerCase();
        return m.contains("saveas(")
            || m.contains("ij.save")
            || m.contains("file.save")
            || m.contains("file.copy")
            || m.contains("file.append")
            || m.contains("file.write")
            || m.contains("savetable(")
            || m.contains("run(\"save\"")
            || m.contains("run(\"tiff");
    }

    /** Encode a UTF-8 string for inclusion in a diff payload (kept here so
     *  callers don't have to reach for the charset constant). */
    static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
