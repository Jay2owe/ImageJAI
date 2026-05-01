# Step 09 — Histogram Delta

## Motivation

Gemma can't see images. `capture_image` returns base64 PNGs she
can't decode, so her only signal for "did this macro change the
pixels" is either writing a macro that samples them or guessing
from the log. She guesses.

A 32-bin intensity histogram before and after every
pixel-mutating macro is a cheap, vision-free proxy for "did
anything change, and if so, how". ~80 tokens per reply. Tells the
agent "the image got darker", "the distribution collapsed to
two peaks" (it was binarised), or "nothing changed" (the macro
didn't affect pixels).

## End goal

Replies to pixel-mutating macros gain:

```json
{"histogramDelta": {
  "bins": 32,
  "before": [0, 2, 5, 18, 42, 118, ...],
  "after":  [5213, 0, 0, 0, 0, 0, ..., 5213],
  "meanBefore": 128.3,
  "meanAfter":  127.6,
  "entropyBefore": 7.2,
  "entropyAfter":  1.0,
  "changed": true,
  "shapeSummary": "bimodal; mean unchanged; entropy collapsed (image was binarised)"
}}
```

`shapeSummary` is a short human-readable diagnosis produced by
simple heuristics — "bimodal", "entropy collapsed", "shifted
darker by 30", "unchanged". Gives Gemma something concrete to
reason about.

## Scope

In:
- `HistogramDelta` utility that produces the 32-bin histogram,
  mean, entropy, and the shape summary.
- Integration in `handleExecuteMacro`, `handleRunPipeline`,
  `handleRunScript`.
- Capture "before" histogram at macro start, "after" at macro end.
- Capability gate `caps.histogram` — default `true`.

Out:
- Per-channel histograms for composite images. Ship grayscale
  composite (or active channel only) in Phase 2; per-channel is
  a future refinement if it matters.
- Multi-slice aggregation. Use the active slice only; stack-wide
  delta is expensive and rarely useful.

## Read first

- `src/main/java/imagejai/engine/TCPCommandServer.java`
  — `handleGetHistogram` already exists; reuse its binning logic.
- `docs/tcp_upgrade/05_state_delta_and_pulse.md` — where in the
  reply envelope this new field sits.

## Mechanics

```java
public final class HistogramDelta {
    public static JsonObject compute(int[] before, int[] after, ImagePlus imp) {
        JsonObject out = new JsonObject();
        out.addProperty("bins", 32);
        out.add("before", toJsonArray(before));
        out.add("after",  toJsonArray(after));
        double mb = mean(before, imp), ma = mean(after, imp);
        double eb = entropy(before),  ea = entropy(after);
        out.addProperty("meanBefore", mb);
        out.addProperty("meanAfter",  ma);
        out.addProperty("entropyBefore", eb);
        out.addProperty("entropyAfter",  ea);
        boolean changed = !Arrays.equals(before, after);
        out.addProperty("changed", changed);
        out.addProperty("shapeSummary", summarise(before, after, mb, ma, eb, ea));
        return out;
    }

    static int[] histogram32(ImagePlus imp) {
        ImageProcessor ip = imp.getProcessor();
        int[] full = ip.getHistogram();  // 256 for 8-bit, varies for 16/32
        return rebin(full, 32, imp);
    }
}
```

`summarise` heuristics — order matters, first match wins:
- `!changed` → `"unchanged"`
- `ea < 1.5 && eb > 5.0` → `"entropy collapsed (image was binarised)"`
- `Math.abs(ma - mb) > 20` → `"shifted darker by N"` or `"shifted brighter by N"`
- `peakCount(after) == 2 && peakCount(before) > 2` → `"bimodal"`
- `peakCount(after) == 1 && peakCount(before) > 1` → `"unimodal"`
- fallback → `"distribution changed"`

Cheap heuristics are fine — the goal is a one-line pointer for a
model that can't see pixels, not a rigorous analysis.

## When to skip

Don't compute the histogram when:
- The active image is huge (> 50 Mpixel). The `getHistogram` pass
  is linear in pixel count; at ~200M pixels this becomes user-
  visible lag. Return `"histogramDelta": {"skipped": "image_too_large"}`.
- No active image. Omit the field entirely.
- `caps.histogram == false` set by the client.

## Interaction with `stateDelta`

The two are complementary. `stateDelta` names what *structurally*
changed (new images, result rows, dialogs). `histogramDelta`
describes what happened to the pixels. An agent reading both
gets a complete picture:

> Active image same (no new images in stateDelta), but pixels
> changed: entropy collapsed. The macro binarised the existing
> image in place.

## Tests

- Macro that opens a new image from a file → `changed: true`,
  heuristic names it a distribution change (new content).
- `run("Convert to Mask")` on 8-bit → `"entropy collapsed"`.
- `run("Gaussian Blur...")` → `"distribution changed"`, mean
  near-identical, entropy slightly down.
- `run("Invert")` → mean flips around 127.5 on 8-bit; summary
  names the shift.
- Macro with no pixel effect (opens a dialog and cancels) →
  `changed: false`.
- Large image → `skipped: image_too_large`.

## Failure modes

- 32-bit images with huge ranges: `getHistogram()` returns 256
  bins spanning the range, rebinned to 32. Outliers on one end
  can collapse detail. Document in the field's docstring that
  `bins` is fixed at 32 regardless of bit depth.
- Floating-point imprecision in entropy calc. Use a well-known
  form (`-Σ p log2 p`) with a small-probability guard.
- Cost of `getHistogram` called twice per macro on
  medium-large images. Measure on a 4K slice; if > 100 ms
  consistently, pre-cache the "after" from a previous macro's
  "before" when they're back-to-back calls on the same image.
