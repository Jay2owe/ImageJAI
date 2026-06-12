package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.util.Arrays;

/**
 * Step 09 (docs/tcp_upgrade/09_histogram_delta.md): a vision-free proxy for
 * "did this macro change the pixels, and if so, how". Before/after snapshots
 * of the active image's intensity distribution are rebinned to 32 bins and
 * summarised by a small set of ordered heuristics, so a text-only agent
 * (Gemma, etc.) can see a one-line diagnosis like
 * {@code "entropy collapsed (image was binarised)"} alongside the raw bin
 * counts.
 * <p>
 * The class is intentionally split into an ImageJ-touching snapshot step and
 * a pure JSON composition step. Headless unit tests exercise the pure halves
 * ({@link #rebin}, {@link #entropy}, {@link #meanFromFull}, {@link #summarise},
 * {@link #peakCount}) with injected int[] arrays so the heuristic logic is
 * verifiable without a live {@link ImagePlus}.
 */
public final class HistogramDelta {

    /** Every reply ships this bin count regardless of bit depth. */
    public static final int BINS = 32;

    /**
     * Pixel threshold above which the snapshot is skipped. {@code
     * ip.getHistogram()} is O(pixels); beyond this the double-snapshot cost
     * becomes user-visible lag on every macro.
     */
    static final long MAX_PIXELS = 50_000_000L;

    private HistogramDelta() { }

    /**
     * Immutable per-snapshot record. {@link #skipReason} is non-null exactly
     * when the snapshot was skipped (currently only {@code "image_too_large"})
     * so callers can surface a typed {@code "skipped": "..."} envelope.
     */
    public static final class Snapshot {
        final int[] bins;
        final double mean;
        final double entropy;
        final String skipReason;

        Snapshot(int[] bins, double mean, double entropy, String skipReason) {
            this.bins = bins;
            this.mean = mean;
            this.entropy = entropy;
            this.skipReason = skipReason;
        }

        static final Snapshot IMAGE_TOO_LARGE =
                new Snapshot(null, 0.0, 0.0, "image_too_large");
    }

    /**
     * Capture the active image's intensity distribution. Returns {@code null}
     * when {@code imp} is null (caller should omit the {@code histogramDelta}
     * field entirely) or a sentinel with a {@code skipReason} when the image
     * exceeds {@link #MAX_PIXELS}.
     * <p>
     * Uses {@link ImageProcessor#getHistogram()} which returns 256 bins for
     * 8/16/32-bit images. We rebin to {@link #BINS} for the reply and compute
     * mean / entropy from the full 256-bin version for more precision.
     */
    public static Snapshot snapshot(ImagePlus imp) {
        if (imp == null) return null;
        long pixels = (long) imp.getWidth() * (long) imp.getHeight();
        if (pixels > MAX_PIXELS) return Snapshot.IMAGE_TOO_LARGE;
        try {
            ImageProcessor ip = imp.getProcessor();
            if (ip == null) return null;
            int[] full = ip.getHistogram();
            if (full == null || full.length == 0) return null;
            return new Snapshot(rebin(full, BINS), meanFromFull(full), entropy(full), null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Compose the reply envelope from two snapshots. Pure — unit-testable
     * without any ImageJ state. Returns {@code null} when either side is
     * missing (caller omits the field), or a {@code {"skipped": reason}}
     * envelope when either snapshot carried a skip reason.
     */
    public static JsonObject compute(Snapshot before, Snapshot after) {
        if (before == null && after == null) return null;
        String skipReason = null;
        if (before != null && before.skipReason != null) skipReason = before.skipReason;
        else if (after != null && after.skipReason != null) skipReason = after.skipReason;
        if (skipReason != null) {
            JsonObject skip = new JsonObject();
            skip.addProperty("skipped", skipReason);
            return skip;
        }
        if (before == null || after == null) return null;

        JsonObject out = new JsonObject();
        out.addProperty("bins", BINS);
        out.add("before", toJsonArray(before.bins));
        out.add("after", toJsonArray(after.bins));
        out.addProperty("meanBefore", before.mean);
        out.addProperty("meanAfter", after.mean);
        out.addProperty("entropyBefore", before.entropy);
        out.addProperty("entropyAfter", after.entropy);
        boolean changed = !Arrays.equals(before.bins, after.bins)
                || before.mean != after.mean
                || before.entropy != after.entropy;
        out.addProperty("changed", changed);
        out.addProperty("shapeSummary", summarise(
                before.bins, after.bins,
                before.mean, after.mean,
                before.entropy, after.entropy));
        return out;
    }

    // ------------------------------------------------------------------
    // Pure helpers — package-private for the headless unit tests.
    // ------------------------------------------------------------------

    /**
     * Proportionally accumulate {@code full}'s bins into {@code target} bins.
     * Empty / malformed input yields an all-zero target array.
     */
    static int[] rebin(int[] full, int target) {
        if (target <= 0) return new int[0];
        int[] out = new int[target];
        if (full == null || full.length == 0) return out;
        int n = full.length;
        for (int i = 0; i < n; i++) {
            int t = (int) ((long) i * target / n);
            if (t >= target) t = target - 1;
            out[t] += full[i];
        }
        return out;
    }

    /**
     * Mean "bin-index" of a {@code full}-length histogram. For 8-bit this
     * equals the image mean in pixel units (0..255); for 16/32-bit it's the
     * normalized position inside the displayed min..max range, which is what
     * the shift heuristic needs regardless of bit depth.
     */
    static double meanFromFull(int[] full) {
        if (full == null || full.length == 0) return 0.0;
        long total = 0;
        double weighted = 0.0;
        for (int i = 0; i < full.length; i++) {
            total += full[i];
            weighted += (double) i * full[i];
        }
        if (total == 0) return 0.0;
        return weighted / total;
    }

    /** Shannon entropy of a histogram in bits ({@code -Σ p log2 p}). */
    static double entropy(int[] bins) {
        if (bins == null) return 0.0;
        long total = 0;
        for (int c : bins) total += c;
        if (total == 0) return 0.0;
        double log2 = Math.log(2.0);
        double H = 0.0;
        for (int c : bins) {
            if (c <= 0) continue;
            double p = (double) c / total;
            H -= p * (Math.log(p) / log2);
        }
        return H;
    }

    /**
     * First-match-wins summary diagnosis. Order follows the plan
     * (docs/tcp_upgrade/09_histogram_delta.md):
     * <ol>
     *   <li>{@code unchanged} — bins identical</li>
     *   <li>{@code entropy collapsed (image was binarised)} — high → low entropy</li>
     *   <li>{@code shifted darker by N} / {@code shifted brighter by N} — mean shift > 20</li>
     *   <li>{@code bimodal} — peak count dropped to 2</li>
     *   <li>{@code unimodal} — peak count dropped to 1</li>
     *   <li>{@code distribution changed} — fallback</li>
     * </ol>
     */
    static String summarise(int[] before, int[] after,
                            double mb, double ma, double eb, double ea) {
        if (Arrays.equals(before, after)) return "unchanged";
        if (ea < 1.5 && eb > 5.0) return "entropy collapsed (image was binarised)";
        double shift = ma - mb;
        if (shift > 20.0) return "shifted brighter by " + (int) Math.round(shift);
        if (shift < -20.0) return "shifted darker by " + (int) Math.round(-shift);
        int peaksBefore = peakCount(before);
        int peaksAfter = peakCount(after);
        if (peaksAfter == 2 && peaksBefore > 2) return "bimodal";
        if (peaksAfter == 1 && peaksBefore > 1) return "unimodal";
        return "distribution changed";
    }

    /**
     * Count local maxima exceeding 10% of the tallest bin. Flat plateaus
     * count as a single peak (attributed to the left edge of descent).
     */
    static int peakCount(int[] bins) {
        if (bins == null || bins.length == 0) return 0;
        int max = 0;
        for (int v : bins) if (v > max) max = v;
        if (max == 0) return 0;
        int threshold = Math.max(1, max / 10);
        int count = 0;
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] < threshold) continue;
            boolean leftOk = (i == 0) || bins[i] > bins[i - 1];
            boolean rightOk = (i == bins.length - 1) || bins[i] >= bins[i + 1];
            if (leftOk && rightOk) count++;
        }
        return count;
    }

    private static JsonArray toJsonArray(int[] arr) {
        JsonArray a = new JsonArray();
        if (arr != null) for (int v : arr) a.add(new JsonPrimitive(v));
        return a;
    }
}
