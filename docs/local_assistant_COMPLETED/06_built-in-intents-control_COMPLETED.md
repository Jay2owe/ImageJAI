# Stage 06 — Built-in intents A: inspection, control, ROIs, display

## Why this stage exists

Stages 01–05 built the plumbing. This stage fills it with the boring-
but-frequent intents biologists hit dozens of times per session —
inspecting an open image, closing/saving/switching images, managing
the ROI manager, results table I/O, display tweaks, and meta
commands. Coverage of these unblocks daily use even before the
analysis intents in stage 07.

## Prerequisites

- Stage 04 (full phrasebook so chips and Tier 2 work for these
  intents).
- Stage 05 (Tier 2 + autocomplete so users can discover the new
  intents as they type).

## Read first

- `docs/local_assistant/00_overview.md` (especially House rules)
- `docs/local_assistant/PLAN.md` §5.1, §5.2, §5.6, §5.7, §5.9, §5.10
  and §8 ("Guardrails")
- `agent/references/macro-reference.md` §2.1, §2.6, §2.8 — image
  duplication, projection, type conversion, channel/stack ops
- ImageJ APIs: `ij.WindowManager`, `ij.ImagePlus`, `ij.IJ`,
  `ij.measure.Calibration`, `ij.measure.ResultsTable`,
  `ij.plugin.frame.RoiManager`, `ij.io.FileSaver`
- `src/main/java/imagejai/engine/CommandEngine.java` —
  `executeMacro(String)` for intents that just run a macro string

## Scope

Implement an `Intent` class for each phrasebook entry in:

- **§5.1 Image inspection (read-only):** pixel size (already done in
  stage 03; leave alone), image dimensions, bit depth, channel
  count, slice count, frame count, active channel, active slice,
  active frame, image title, file path, min/max/mean intensity of
  active channel, list open images, is anything saturated.
- **§5.2 Image control:** close all, close active, close all but
  active, duplicate active, revert, save as TIFF/PNG/JPEG (one
  intent per format; prompt for filename via `JFileChooser`
  rooted at `AI_Exports/`), next slice, previous slice, switch to
  channel N, jump to slice N, jump to frame N, merge channels,
  split channels, z-project max/mean/sum/SD (one intent per
  projection), make substack (slot-aware: channels/slices/frames),
  crop to selection, scale by N, invert image, invert LUT, convert
  to 8-bit, 16-bit, 32-bit, RGB, composite, set scale (slot-aware:
  N px = M unit).
- **§5.6 ROIs and results I/O:** list ROIs, count ROIs, clear ROI
  manager, save ROIs to AI_Exports, show results table, save
  results to CSV in AI_Exports.
- **§5.7 Display:** auto contrast (display only — `setMinAndMax`),
  reset display, fit window to image, set zoom to N % (slot).
- **§5.9 State and diagnostics:** what plugins do I have (defer to
  shelling out `agent/scan_plugins.py` is over-engineered; instead
  read `ij.Menus.getCommands().keySet().size()` and respond with a
  count + a one-line "use /macros to see saved macros, /info for
  open images"), open the macro recorder / ROI manager / channels
  tool, show log, show console, any open dialogs, memory used,
  garbage collect.
- **§5.10 Help and meta:** help (replaces stage 02 stub), what can
  you do, commands, which agent am I using, version.

Add `FijiBridge` helpers for repeated tasks:

```java
public ImagePlus requireOpenImage();   // returns null if none
public Path resolveAiExportsDir();      // throws if image has no path
public void runMacro(String code);      // wraps CommandEngine
public ResultsTable currentResults();
public RoiManager currentRoiManager();
```

Apply guardrails strictly: state check before mutation, AI_Exports
path resolution, never close the Log window, masks not overlays,
display-only contrast via `setMinAndMax`.

## Out of scope

- Preprocessing, segmentation, measurement intents — stage 07.
- Slash commands (`/help`, `/clear`, `/macros`, `/info`, `/close`,
  `/teach`, `/intents`, `/forget`) — stage 08.
- IntentRouter fallback — stage 08.
- Installer panel — stage 09.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/local/intents/control/*.java` | NEW (~30 files) | One handler per intent |
| `src/main/java/imagejai/local/FijiBridge.java` | MODIFY | Add `requireOpenImage`, `resolveAiExportsDir`, `runMacro`, `currentResults`, `currentRoiManager` helpers |
| `src/main/java/imagejai/local/IntentLibrary.java` | MODIFY | Register all new handlers in the load step |
| `tests/benchmark/biologist_phrasings.jsonl` | MODIFY | Add ~30 phrasings covering this stage's intents |

## Implementation sketch

```java
// CloseAllIntent.java
public class CloseAllIntent implements Intent {
  public String id() { return "image.close_all"; }
  public String description() { return "Close all open images"; }
  public AssistantReply execute(Map<String,String> slots, FijiBridge fiji) {
    int n = WindowManager.getImageCount();
    fiji.runMacro("run(\"Close All\");");
    return AssistantReply.withMacro(
      "Closed " + n + " image" + (n == 1 ? "" : "s") + ".",
      "run(\"Close All\");");
  }
}
```

```java
// ImageDimensionsIntent.java
public AssistantReply execute(Map<String,String> slots, FijiBridge fiji) {
  ImagePlus imp = fiji.requireOpenImage();
  if (imp == null) return AssistantReply.text("No image is open.");
  int[] d = imp.getDimensions();   // {w, h, c, z, t}
  return AssistantReply.text(String.format(
    "%d × %d × %d channel%s × %d slice%s × %d frame%s",
    d[0], d[1], d[2], d[2] == 1 ? "" : "s",
    d[3], d[3] == 1 ? "" : "s",
    d[4], d[4] == 1 ? "" : "s"));
}
```

```java
// SaveAsTiffIntent.java
public AssistantReply execute(Map<String,String> slots, FijiBridge fiji) {
  ImagePlus imp = fiji.requireOpenImage();
  if (imp == null) return AssistantReply.text("No image is open.");
  Path dir;
  try { dir = fiji.resolveAiExportsDir(); }
  catch (IllegalStateException e) {
    return AssistantReply.text(
      "Save the image first so I know where AI_Exports/ should live.");
  }
  // JFileChooser rooted at dir, default extension .tif
  ...
}
```

## Exit gate

1. Every intent in §5.1, §5.2, §5.6, §5.7, §5.9, §5.10 has a
   working handler that respects the relevant guardrail.
2. Benchmark top-1 accuracy ≥ 80 % on the phrasings covered by
   this stage.
3. Manual smoke test against an open Fiji session:
   - "what's the bit depth" → correct answer.
   - "close all" → all images close, Log window stays open.
   - "save as TIFF" → file dialog rooted at AI_Exports/.
   - "auto contrast" → display range changes; pixel values do
     **not** change (verify by re-saving and comparing histograms).
   - "list rois" → table of ROI Manager content.
4. `mvn -q test` passes.

## Known risks

- **`AI_Exports` resolution.** Many users open images from
  Bio-Formats virtual stacks where `getOriginalFileInfo().directory`
  is null. Reply asking the user to save the image first; do not
  fall back to a temp directory.
- **`Enhance Contrast normalize=true` smell.** Easy to type
  `IJ.run(imp, "Enhance Contrast", "saturated=0.35")` thinking it
  is display-only. Without `normalize`, that flavour *is* display-
  only and acceptable for the auto-contrast intent — but verify
  pixel values are unchanged before shipping.
- **JFileChooser on EDT.** All Swing dialogs must run on the EDT.
  Wrap in `SwingUtilities.invokeLater` if the intent's `execute`
  is called off-EDT.
- **Stage scope creep.** Resist implementing analysis intents
  (background subtraction, threshold, etc.) here — they go in
  stage 07.
- **Benchmark drift.** Adding ~30 phrasings to the benchmark
  changes the denominator; re-baseline the top-1 accuracy
  reporting line.
