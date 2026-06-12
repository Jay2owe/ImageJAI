package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless unit tests for step 09 — {@link HistogramDelta}. The pure halves
 * (rebin, entropy, meanFromFull, peakCount, summarise, compute) are exercised
 * with injected int[] arrays so the heuristics are verifiable without a live
 * {@code ImagePlus}. Per plan: docs/tcp_upgrade/09_histogram_delta.md.
 */
public class HistogramDeltaTest {

    // ------------------------------------------------------------------
    // rebin
    // ------------------------------------------------------------------

    @Test
    public void rebinCondenses256To32PreservingTotal() {
        int[] full = new int[256];
        for (int i = 0; i < 256; i++) full[i] = 1; // flat
        int[] out = HistogramDelta.rebin(full, 32);
        assertEquals(32, out.length);
        int total = 0;
        for (int v : out) total += v;
        assertEquals("rebin must preserve the total pixel count", 256, total);
        for (int v : out) assertEquals("flat input rebins to flat output", 8, v);
    }

    @Test
    public void rebinPutsSpikeIntoFirstBin() {
        int[] full = new int[256];
        full[0] = 5000; // all black
        int[] out = HistogramDelta.rebin(full, 32);
        assertEquals(5000, out[0]);
        for (int i = 1; i < 32; i++) assertEquals(0, out[i]);
    }

    @Test
    public void rebinPutsSpikeAtTopIntoLastBin() {
        int[] full = new int[256];
        full[255] = 5000;
        int[] out = HistogramDelta.rebin(full, 32);
        assertEquals(5000, out[31]);
        for (int i = 0; i < 31; i++) assertEquals(0, out[i]);
    }

    @Test
    public void rebinHandlesNullAndEmpty() {
        assertEquals(32, HistogramDelta.rebin(null, 32).length);
        assertEquals(32, HistogramDelta.rebin(new int[0], 32).length);
    }

    // ------------------------------------------------------------------
    // entropy
    // ------------------------------------------------------------------

    @Test
    public void entropyZeroForSingleBin() {
        int[] bins = new int[32];
        bins[5] = 1000;
        assertEquals(0.0, HistogramDelta.entropy(bins), 1e-9);
    }

    @Test
    public void entropyMaxForUniform32Bins() {
        int[] bins = new int[32];
        for (int i = 0; i < 32; i++) bins[i] = 100;
        // log2(32) = 5.0 exactly.
        assertEquals(5.0, HistogramDelta.entropy(bins), 1e-9);
    }

    @Test
    public void entropyOneBitForBinarised() {
        int[] bins = new int[32];
        bins[0] = 500;
        bins[31] = 500;
        // Two equal peaks → 1 bit of information.
        assertEquals(1.0, HistogramDelta.entropy(bins), 1e-9);
    }

    @Test
    public void entropyZeroForEmptyOrNull() {
        assertEquals(0.0, HistogramDelta.entropy(null), 1e-9);
        assertEquals(0.0, HistogramDelta.entropy(new int[32]), 1e-9);
    }

    // ------------------------------------------------------------------
    // meanFromFull
    // ------------------------------------------------------------------

    @Test
    public void meanFromFullWeightsByIndex() {
        int[] full = new int[256];
        full[128] = 100; // all pixels at value 128
        assertEquals(128.0, HistogramDelta.meanFromFull(full), 1e-9);
    }

    @Test
    public void meanFromFullHandlesEmpty() {
        assertEquals(0.0, HistogramDelta.meanFromFull(null), 1e-9);
        assertEquals(0.0, HistogramDelta.meanFromFull(new int[0]), 1e-9);
        assertEquals(0.0, HistogramDelta.meanFromFull(new int[256]), 1e-9);
    }

    @Test
    public void meanFromFullAveragesTwoPeaks() {
        int[] full = new int[256];
        full[0] = 100;
        full[200] = 100;
        // (0*100 + 200*100) / 200 = 100
        assertEquals(100.0, HistogramDelta.meanFromFull(full), 1e-9);
    }

    // ------------------------------------------------------------------
    // peakCount
    // ------------------------------------------------------------------

    @Test
    public void peakCountIsZeroOnEmpty() {
        assertEquals(0, HistogramDelta.peakCount(null));
        assertEquals(0, HistogramDelta.peakCount(new int[0]));
        assertEquals(0, HistogramDelta.peakCount(new int[32]));
    }

    @Test
    public void peakCountTwoForBinarised() {
        int[] bins = new int[32];
        bins[0] = 500;
        bins[31] = 500;
        assertEquals(2, HistogramDelta.peakCount(bins));
    }

    @Test
    public void peakCountOneForUnimodalBell() {
        int[] bins = new int[]{1, 2, 5, 10, 20, 30, 20, 10, 5, 2, 1};
        assertEquals(1, HistogramDelta.peakCount(bins));
    }

    @Test
    public void peakCountIgnoresSubThresholdNoise() {
        // Main peak at bin 5, plus a tiny bump at bin 20 below the 10% cut.
        int[] bins = new int[32];
        bins[5] = 1000;
        bins[20] = 50; // under 100 threshold (max/10)
        bins[21] = 30;
        assertEquals(1, HistogramDelta.peakCount(bins));
    }

    // ------------------------------------------------------------------
    // summarise — heuristic order matters
    // ------------------------------------------------------------------

    @Test
    public void summariseSaysUnchangedWhenBinsIdentical() {
        int[] bins = new int[32];
        bins[10] = 100;
        assertEquals("unchanged",
                HistogramDelta.summarise(bins.clone(), bins.clone(),
                        10.0, 10.0, 3.0, 3.0));
    }

    @Test
    public void summariseSaysEntropyCollapsedForBinarisation() {
        // A "natural image" has entropy comfortably above the 5.0 cut (the
        // branch is strict). A Convert-to-Mask output collapses to two
        // spikes — entropy ≈ 1 bit.
        int[] before = new int[32];
        for (int i = 0; i < 32; i++) before[i] = 100;
        int[] after = new int[32];
        after[0] = 800;
        after[31] = 800;
        String s = HistogramDelta.summarise(before, after, 127.0, 127.0, 7.2, 1.0);
        assertTrue("entropy-collapse branch fires: " + s,
                s.contains("entropy collapsed"));
    }

    @Test
    public void summariseSaysShiftedDarkerWhenMeanDropsBy30() {
        int[] before = new int[32];
        int[] after = new int[32];
        before[20] = 100;
        after[10] = 100;
        String s = HistogramDelta.summarise(before, after, 160.0, 130.0, 3.0, 3.0);
        assertTrue("shifted darker branch fires: " + s, s.startsWith("shifted darker"));
    }

    @Test
    public void summariseSaysShiftedBrighterWhenMeanRisesBy30() {
        int[] before = new int[32];
        int[] after = new int[32];
        before[10] = 100;
        after[20] = 100;
        String s = HistogramDelta.summarise(before, after, 100.0, 140.0, 3.0, 3.0);
        assertTrue("shifted brighter branch fires: " + s, s.startsWith("shifted brighter"));
    }

    @Test
    public void summariseSaysBimodalWhenPeakCountDropsToTwo() {
        // Before has 4 peaks (multi-modal), after has 2 (bimodal).
        int[] before = new int[32];
        before[2] = 100; before[10] = 120; before[20] = 110; before[28] = 100;
        int[] after = new int[32];
        after[5] = 300; after[25] = 320;
        // Equal means so the shift branch does not fire first.
        String s = HistogramDelta.summarise(before, after, 120.0, 120.0, 3.0, 3.0);
        assertEquals("bimodal", s);
    }

    @Test
    public void summariseSaysUnimodalWhenPeakCountDropsToOne() {
        int[] before = new int[32];
        before[5] = 300; before[25] = 320;
        int[] after = new int[32];
        after[15] = 800;
        String s = HistogramDelta.summarise(before, after, 120.0, 120.0, 2.0, 1.8);
        assertEquals("unimodal", s);
    }

    @Test
    public void summariseFallsBackToDistributionChanged() {
        int[] before = new int[32];
        before[10] = 100;
        int[] after = new int[32];
        after[11] = 100; // tiny shift, same peak count
        String s = HistogramDelta.summarise(before, after, 80.0, 85.0, 3.0, 3.0);
        assertEquals("distribution changed", s);
    }

    // ------------------------------------------------------------------
    // compute — the public envelope composer
    // ------------------------------------------------------------------

    @Test
    public void computeReturnsNullWhenBothSnapshotsMissing() {
        assertNull(HistogramDelta.compute(null, null));
    }

    @Test
    public void computeReturnsNullWhenOnlyOneSideValid() {
        HistogramDelta.Snapshot after = new HistogramDelta.Snapshot(
                new int[HistogramDelta.BINS], 0.0, 0.0, null);
        assertNull("one-sided snapshot drops the field",
                HistogramDelta.compute(null, after));
        assertNull("one-sided snapshot drops the field",
                HistogramDelta.compute(after, null));
    }

    @Test
    public void computeReturnsSkippedEnvelopeForTooLargeImage() {
        HistogramDelta.Snapshot valid = new HistogramDelta.Snapshot(
                new int[HistogramDelta.BINS], 0.0, 0.0, null);
        JsonObject env = HistogramDelta.compute(
                HistogramDelta.Snapshot.IMAGE_TOO_LARGE, valid);
        assertNotNull(env);
        assertEquals("image_too_large", env.get("skipped").getAsString());

        JsonObject env2 = HistogramDelta.compute(
                valid, HistogramDelta.Snapshot.IMAGE_TOO_LARGE);
        assertEquals("image_too_large", env2.get("skipped").getAsString());
    }

    @Test
    public void computeFullEnvelopeCarriesAllPlanFields() {
        int[] before32 = new int[HistogramDelta.BINS];
        before32[15] = 1000;
        int[] after32 = new int[HistogramDelta.BINS];
        after32[0] = 500;
        after32[31] = 500;

        HistogramDelta.Snapshot b = new HistogramDelta.Snapshot(before32, 127.0, 7.2, null);
        HistogramDelta.Snapshot a = new HistogramDelta.Snapshot(after32, 127.5, 1.0, null);

        JsonObject env = HistogramDelta.compute(b, a);
        assertNotNull(env);
        assertEquals(32, env.get("bins").getAsInt());
        assertEquals(HistogramDelta.BINS, env.getAsJsonArray("before").size());
        assertEquals(HistogramDelta.BINS, env.getAsJsonArray("after").size());
        assertEquals(127.0, env.get("meanBefore").getAsDouble(), 1e-9);
        assertEquals(127.5, env.get("meanAfter").getAsDouble(), 1e-9);
        assertEquals(7.2, env.get("entropyBefore").getAsDouble(), 1e-9);
        assertEquals(1.0, env.get("entropyAfter").getAsDouble(), 1e-9);
        assertTrue("changed flag set when bins differ",
                env.get("changed").getAsBoolean());
        assertTrue("shape summary picks the entropy-collapse branch",
                env.get("shapeSummary").getAsString().contains("entropy collapsed"));
    }

    @Test
    public void computeChangedIsFalseForIdenticalSnapshots() {
        int[] bins = new int[HistogramDelta.BINS];
        bins[10] = 100;
        HistogramDelta.Snapshot s = new HistogramDelta.Snapshot(bins.clone(), 80.0, 3.0, null);
        HistogramDelta.Snapshot s2 = new HistogramDelta.Snapshot(bins.clone(), 80.0, 3.0, null);
        JsonObject env = HistogramDelta.compute(s, s2);
        assertFalse(env.get("changed").getAsBoolean());
        assertEquals("unchanged", env.get("shapeSummary").getAsString());
    }

    // ------------------------------------------------------------------
    // hello handshake: caps.histogram gate and enabled[] advertisement
    // ------------------------------------------------------------------

    @Test
    public void helloDefaultsEnableHistogramDelta() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        JsonObject req = new JsonParser()
                .parse("{\"command\":\"hello\",\"agent\":\"tester\"}")
                .getAsJsonObject();
        JsonObject resp = server.handleHello(req, null);
        assertTrue(resp.get("ok").getAsBoolean());
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertTrue("histogram_delta on by default", enabledContains(enabled, "histogram_delta"));
    }

    @Test
    public void helloCanOptOutOfHistogram() {
        TCPCommandServer server = new TCPCommandServer(0, null, null, null, null);
        JsonObject req = new JsonParser().parse(
                "{\"command\":\"hello\",\"agent\":\"claude-code\","
              + "\"capabilities\":{\"histogram\":false}}")
                .getAsJsonObject();
        JsonObject resp = server.handleHello(req, null);
        JsonArray enabled = resp.getAsJsonObject("result").getAsJsonArray("enabled");
        assertFalse("histogram_delta dropped when caps.histogram=false",
                enabledContains(enabled, "histogram_delta"));
    }

    private static boolean enabledContains(JsonArray arr, String name) {
        for (int i = 0; i < arr.size(); i++) {
            if (name.equals(arr.get(i).getAsString())) return true;
        }
        return false;
    }
}
