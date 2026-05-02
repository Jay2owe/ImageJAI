# Stage 07 — Built-in intents B: preprocessing, segmentation, measurement, dialogs

## Why this stage exists

Stage 06 covered the boring-but-frequent surface (inspection,
control, I/O, display). This stage covers the analysis surface —
the actual image science a biologist does in Fiji: filtering,
thresholding, counting, measuring, profiling, and opening the
common analysis dialogs. After this, Local Assistant's intent
library is feature-complete for v1.

## Prerequisites

- Stage 06 (`FijiBridge` helpers and intent registration patterns
  established).

## Read first

- `docs/local_assistant/00_overview.md` (House rules — especially
  masks-not-overlays and Enhance-Contrast guardrails)
- `docs/local_assistant/PLAN.md` §5.3, §5.4, §5.5, §5.8, §8
- `agent/references/macro-reference.md` §2.2 (filters), §2.3
  (binary ops, threshold methods list)
- `agent/recipes/background_subtraction.yaml`,
  `denoise_gaussian.yaml`, `cell_counting.yaml`,
  `particle_analysis.yaml`, `ctcf.yaml`, `line_profile.yaml`,
  `colocalization.yaml` — concrete macro patterns to copy
- `src/main/java/imagejai/engine/TCPCommandServer.java`
  `dispatchCore()` — the `explore_thresholds` handler, since
  "compare thresholds" routes through it locally
- `src/main/java/imagejai/engine/CommandEngine.java`

## Scope

Implement `Intent` classes for:

- **§5.3 Preprocessing:** subtract background (slot: rolling-ball
  radius, default 50), gaussian blur (slot: sigma), median filter
  (slot: radius), mean filter, variance, unsharp mask, bandpass
  filter.
- **§5.4 Segmentation and counting:** auto-threshold (slot: method
  ∈ {Otsu, Li, Triangle, Huang, MaxEntropy, Default}; optional
  "dark background" modifier), compare thresholds (delegates to
  `CommandEngine` calling the same code path that the
  `explore_thresholds` TCP handler uses), convert to mask (always
  with `BlackBackground=true`), count cells / count particles /
  count nuclei (one intent each, all route to `Analyze Particles`
  with `show=Masks`), fill holes, watershed, skeletonize, distance
  map, Voronoi, find maxima (slot: prominence).
- **§5.5 Measurement and quantification:** measure intensity,
  measure CTCF (Corrected Total Cell Fluorescence — formula in
  `agent/recipes/ctcf.yaml`), measure ROIs, summarise, clear
  results, set measurements (slot: comma-separated keys), line
  profile, histogram, nearest-neighbour distance.
- **§5.8 Common dialogs (open and stop):** threshold, analyze
  particles, find maxima, gaussian blur, subtract background. These
  intents simply `IJ.run("...")` the menu command with no args so
  the dialog opens. Per house rule, do **not** guess parameters.

Extend `FijiBridge` with helpers used by multiple intents in this
stage:

```java
public void runAnalyzeParticles(String sizeRange, String circRange,
                                boolean showMasks);  // §5.4
public ResultsTable measureCurrentRoiSet();          // §5.5
public double computeCtcf(Roi roi, ImagePlus imp,
                          double background);        // §5.5
public ThresholdComparison runExploreThresholds(
    String[] methods);                                // §5.4
```

## Out of scope

- IntentRouter fallback and slash commands — stage 08.
- Installer panel — stage 09.
- Menu mining — stage 10.
- Multi-step chained intents (e.g. "threshold and measure") —
  PLAN §14.2 explicitly defers this past v1.
- Parameter-guessing for any plugin dialog — house rule.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/intents/analysis/*.java` | NEW (~25 files) | One handler per intent |
| `src/main/java/imagejai/local/FijiBridge.java` | MODIFY | Add `runAnalyzeParticles`, `measureCurrentRoiSet`, `computeCtcf`, `runExploreThresholds` |
| `src/main/java/imagejai/local/IntentLibrary.java` | MODIFY | Register all new handlers |
| `tests/benchmark/biologist_phrasings.jsonl` | MODIFY | Add ~25 phrasings covering this stage's intents |

## Implementation sketch

```java
// SubtractBackgroundIntent.java — slot: rolling-ball radius
public AssistantReply execute(Map<String,String> slots, FijiBridge fiji) {
  ImagePlus imp = fiji.requireOpenImage();
  if (imp == null) return AssistantReply.text("No image is open.");
  String radius = slots.getOrDefault("radius", "50");
  String macro = String.format(
    "run(\"Subtract Background...\", \"rolling=%s\");", radius);
  fiji.runMacro(macro);
  return AssistantReply.withMacro(
    "Subtracted background (rolling ball " + radius + " px).", macro);
}
```

```java
// AutoThresholdIntent.java — slot: method, optional dark-bg flag
public AssistantReply execute(Map<String,String> slots, FijiBridge fiji) {
  ImagePlus imp = fiji.requireOpenImage();
  if (imp == null) return AssistantReply.text("No image is open.");
  String method = slots.getOrDefault("method", "Otsu");
  boolean dark = "true".equalsIgnoreCase(slots.getOrDefault("dark", "false"));
  String macro = String.format(
    "setAutoThreshold(\"%s%s\");\nrun(\"Convert to Mask\");",
    method, dark ? " dark" : "");
  fiji.runMacro("setOption(\"BlackBackground\", true);\n" + macro);
  return AssistantReply.withMacro(
    "Thresholded with " + method + (dark ? " (dark background)." : "."),
    macro);
}
```

```java
// CountCellsIntent.java — masks-not-overlays guardrail
public AssistantReply execute(Map<String,String> slots, FijiBridge fiji) {
  ImagePlus imp = fiji.requireOpenImage();
  if (imp == null) return AssistantReply.text("No image is open.");
  if (!imp.getProcessor().isBinary()) {
    return AssistantReply.text(
      "Threshold the image first (try 'auto threshold otsu').");
  }
  fiji.runAnalyzeParticles("0-Infinity", "0.00-1.00", true);   // show=Masks
  ResultsTable rt = fiji.currentResults();
  int n = rt == null ? 0 : rt.size();
  return AssistantReply.text("Counted " + n + " object" + (n == 1 ? "" : "s") + ".");
}
```

## Exit gate

1. Every intent in §5.3, §5.4, §5.5, §5.8 has a working handler.
2. Benchmark top-1 accuracy ≥ 80 % on phrasings covered by this
   stage.
3. Manual smoke test against an open Fiji session:
   - "subtract background" with default radius runs without error.
   - "threshold otsu" produces a mask (binary 8-bit), not an
     overlay.
   - "count cells" on a thresholded image returns a sane integer.
   - "compare thresholds" runs `explore_thresholds` and surfaces
     the result.
   - "analyze particles" opens the dialog (no auto-execute).
4. **No** intent calls `Enhance Contrast` with `normalize=true`.
   Grep the new code for `normalize` and verify.
5. **All** mask-producing intents pass `show=Masks` (or its
   equivalent) — never `Overlay`.
6. `mvn -q test` passes.

## Known risks

- **Threshold method casing.** ImageJ accepts "Otsu", "Li" etc.
  but is case-sensitive in some calls. Normalise the slot value
  to title case before substituting into the macro.
- **`compare thresholds` cost.** `explore_thresholds` duplicates
  the active image and thresholds it N times. On a large stack
  this is slow; add a "may take a minute" heads-up reply.
- **CTCF computation.** Requires a background ROI as well as the
  cell ROI(s). Document in the intent description that the user
  should select a background ROI first; otherwise reply asking
  for it.
- **Slot extraction.** Stage 05's matcher does not extract slots
  yet. For v1, slots come from default values — "subtract
  background" uses radius 50, "threshold" uses Otsu. A proper
  slot parser is PLAN §14 future work; this stage just reads from
  the `slots` map and falls back to defaults.
- **Stage 06 / 07 ordering.** Some §5.5 intents (measure ROIs,
  show results) overlap with §5.6. Deduplicate by ID — if stage
  06 already implemented `roi.measure_all`, stage 07 must not
  re-implement it under a different ID.
